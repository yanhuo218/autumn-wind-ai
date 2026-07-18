$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$openApiFile = Join-Path $projectRoot "contracts/openapi/common.openapi.json"
$eventSchemaFile = Join-Path $projectRoot "contracts/events/event-envelope.v1.schema.json"
$inferenceEventSchemaFile = Join-Path $projectRoot "contracts/events/inference-event.v1.schema.json"
$identityOpenApiFile = Join-Path $projectRoot "contracts/openapi/identity.openapi.json"
$notificationOpenApiFile = Join-Path $projectRoot "contracts/openapi/notification.openapi.json"
$modelRegistryOpenApiFile = Join-Path $projectRoot "contracts/openapi/model-registry.openapi.json"
$modelRegistryInternalOpenApiFile = Join-Path $projectRoot "contracts/openapi/model-registry-internal.openapi.json"
$identityEventFiles = @(
    "user-disabled.v1.schema.json",
    "account-deletion-requested.v1.schema.json",
    "email-requested.v1.schema.json"
)

$openApi = Get-Content -Raw $openApiFile | ConvertFrom-Json
$eventSchema = Get-Content -Raw $eventSchemaFile | ConvertFrom-Json
$identityOpenApi = Get-Content -Raw $identityOpenApiFile | ConvertFrom-Json
$notificationOpenApi = Get-Content -Raw $notificationOpenApiFile | ConvertFrom-Json
$modelRegistryOpenApi = Get-Content -Raw $modelRegistryOpenApiFile | ConvertFrom-Json
$modelRegistryInternalOpenApi = Get-Content -Raw $modelRegistryInternalOpenApiFile | ConvertFrom-Json

if (-not (Test-Path $inferenceEventSchemaFile)) {
    throw "Inference 标准事件 Schema 不存在。"
}
$inferenceEventSchema = Get-Content -Raw $inferenceEventSchemaFile | ConvertFrom-Json

if ($openApi.openapi -notmatch "^3\.1\.") {
    throw "公共 OpenAPI 必须使用 3.1.x。"
}

if ($null -eq $openApi.paths -or $null -eq $openApi.components.headers.CorrelationId) {
    throw "公共 OpenAPI 缺少 paths 或 CorrelationId Header。"
}

$errorCode = $openApi.components.schemas.ErrorCode
$errorResponse = $openApi.components.schemas.ErrorResponse
if ($null -eq $errorCode -or $errorCode.pattern -ne "^AW-[A-Z][A-Z0-9_]{1,31}-[A-Z][A-Z0-9_]{1,31}-[0-9]{4}$") {
    throw "公共 OpenAPI 缺少稳定错误码格式。"
}

if ($errorResponse.additionalProperties -ne $true) {
    throw "ErrorResponse 必须允许兼容新增字段。"
}

$requiredErrorFields = @($errorResponse.required)
foreach ($field in @("code", "message", "correlationId")) {
    if ($field -notin $requiredErrorFields) {
        throw "ErrorResponse 缺少必填字段：$field"
    }
}

if ($eventSchema.'$schema' -ne "https://json-schema.org/draft/2020-12/schema") {
    throw "事件 Schema 必须使用 JSON Schema Draft 2020-12。"
}

if ($eventSchema.additionalProperties -ne $true) {
    throw "事件 Envelope 必须允许兼容新增字段。"
}

if ($inferenceEventSchema.'$schema' -ne "https://json-schema.org/draft/2020-12/schema") {
    throw "Inference 标准事件必须使用 JSON Schema Draft 2020-12。"
}
$inferenceEventBranches = @($inferenceEventSchema.oneOf)
if ($inferenceEventBranches.Count -ne 6) {
    throw "Inference 标准事件必须精确定义六种事件。"
}
$expectedInferenceEventTypes = @("done", "error", "reasoning", "start", "text_delta", "usage")
$actualInferenceEventTypes = @($inferenceEventBranches | ForEach-Object { $_.properties.type.const } | Sort-Object)
if (Compare-Object $expectedInferenceEventTypes $actualInferenceEventTypes) {
    throw "Inference 标准事件 type 集合发生漂移。"
}
foreach ($branch in $inferenceEventBranches) {
    if ($branch.additionalProperties -ne $false) {
        throw "Inference 标准事件各分支必须禁止未声明字段。"
    }
}
$errorBranch = $inferenceEventBranches | Where-Object { $_.properties.type.const -eq "error" }
$expectedInferenceErrorCodes = @(
    "CONNECTION_FAILED",
    "INTERNAL_DEPENDENCY_ERROR",
    "PROVIDER_AUTHENTICATION_FAILED",
    "PROVIDER_ERROR",
    "PROVIDER_RATE_LIMITED",
    "PROVIDER_RESPONSE_INVALID",
    "PROVIDER_UNAVAILABLE",
    "TARGET_REJECTED"
)
$actualInferenceErrorCodes = @($errorBranch.properties.code.enum | Sort-Object)
if (Compare-Object $expectedInferenceErrorCodes $actualInferenceErrorCodes) {
    throw "Inference 标准事件错误码集合发生漂移。"
}

$requiredEventFields = @($eventSchema.required)
foreach ($field in @("eventId", "eventType", "eventVersion", "occurredAt", "producer", "correlationId", "payload")) {
    if ($field -notin $requiredEventFields) {
        throw "事件 Envelope 缺少必填字段：$field"
    }
}

if ($identityOpenApi.openapi -notmatch "^3\.1\.") {
    throw "Identity OpenAPI 必须使用 3.1.x。"
}

$requiredIdentityPaths = @(
    "/api/v1/auth/registration-options",
    "/api/v1/auth/registrations",
    "/api/v1/auth/sessions",
    "/api/v1/auth/session",
    "/api/v1/admin/users",
    "/api/v1/admin/auth-policy",
    "/internal/v1/auth/session-introspections"
)
foreach ($path in $requiredIdentityPaths) {
    if ($null -eq $identityOpenApi.paths.$path) {
        throw "Identity OpenAPI 缺少必要路径：$path"
    }
}

$implementedIdentityOperations = @(
    $identityOpenApi.paths."/api/v1/auth/csrf".get,
    $identityOpenApi.paths."/api/v1/auth/registration-options".get,
    $identityOpenApi.paths."/api/v1/auth/registrations".post,
    $identityOpenApi.paths."/api/v1/auth/sessions".post,
    $identityOpenApi.paths."/api/v1/auth/session".get,
    $identityOpenApi.paths."/api/v1/auth/session".delete,
    $identityOpenApi.paths."/internal/v1/auth/session-introspections".post
)
foreach ($operation in $implementedIdentityOperations) {
    if ("500" -notin @($operation.responses.PSObject.Properties.Name)) {
        throw "Identity 已实现接口必须声明统一 500 响应。"
    }
}

$csrfHeaders = $identityOpenApi.paths."/api/v1/auth/csrf".get.responses."200".headers
if ($null -eq $csrfHeaders."X-CSRF-TOKEN" -or $null -eq $csrfHeaders."Set-Cookie") {
    throw "Identity CSRF 接口必须同时声明 Header Token 和安全 Cookie。"
}

if ($notificationOpenApi.openapi -notmatch "^3\.1\.") {
    throw "Notification OpenAPI 必须使用 3.1.x。"
}
$requiredNotificationPaths = @(
    "/api/v1/admin/notification/smtp-config",
    "/api/v1/admin/notification/test-emails"
)
foreach ($path in $requiredNotificationPaths) {
    if ($null -eq $notificationOpenApi.paths.$path) {
        throw "Notification OpenAPI 缺少必要路径：$path"
    }
}
$notificationGet = $notificationOpenApi.paths."/api/v1/admin/notification/smtp-config".get
$notificationUpdate = $notificationOpenApi.paths."/api/v1/admin/notification/smtp-config".put
$notificationTestEmail = $notificationOpenApi.paths."/api/v1/admin/notification/test-emails".post
foreach ($operation in @($notificationGet, $notificationUpdate, $notificationTestEmail)) {
    if ("406" -notin @($operation.responses.PSObject.Properties.Name)) {
        throw "Notification 已实现接口必须声明统一 406 响应。"
    }
}
foreach ($operation in @($notificationUpdate, $notificationTestEmail)) {
    if ("415" -notin @($operation.responses.PSObject.Properties.Name)) {
        throw "Notification 带请求体接口必须声明统一 415 响应。"
    }
}
$notificationErrorResponses = @(
    $notificationOpenApi.components.responses.BadRequest,
    $notificationOpenApi.components.responses.Unauthorized,
    $notificationOpenApi.components.responses.Forbidden,
    $notificationOpenApi.components.responses.NotFound,
    $notificationOpenApi.components.responses.Conflict,
    $notificationOpenApi.components.responses.InternalServerError,
    $notificationOpenApi.components.responses.NotAcceptable,
    $notificationOpenApi.components.responses.UnsupportedMediaType
)
foreach ($response in $notificationErrorResponses) {
    if ($null -eq $response.headers."X-Correlation-ID") {
        throw "Notification 错误响应必须声明 X-Correlation-ID。"
    }
}
$smtpUpdate = $notificationOpenApi.components.schemas.SmtpConfigUpdateRequest
$smtpPasswordIsNotWriteOnly = $smtpUpdate.properties.password.writeOnly -ne $true
$smtpPasswordAppearsInView = $null -ne $notificationOpenApi.components.schemas.SmtpConfigView.properties.password
$smtpViewAllowsUnknownFields = $notificationOpenApi.components.schemas.SmtpConfigView.additionalProperties -ne $false
if ($smtpPasswordIsNotWriteOnly -or $smtpPasswordAppearsInView -or $smtpViewAllowsUnknownFields) {
    throw "Notification SMTP 密码必须只写且不能出现在读取视图。"
}
$clearPasswordRule = $smtpUpdate.allOf[0].not
$clearPasswordRequiredFields = @($clearPasswordRule.required)
$clearPasswordRuleMissesPassword = "password" -notin $clearPasswordRequiredFields
$clearPasswordRuleMissesFlag = "clearPassword" -notin $clearPasswordRequiredFields
$clearPasswordRuleAllowsTrue = $clearPasswordRule.properties.clearPassword.const -ne $true
if ($clearPasswordRuleMissesPassword -or $clearPasswordRuleMissesFlag -or $clearPasswordRuleAllowsTrue) {
    throw "Notification SMTP 密码与 clearPassword=true 必须互斥。"
}
$notificationSecurityDescription = $notificationOpenApi.components.securitySchemes.ServiceJwt.description
if ($notificationSecurityDescription -notmatch "notification\.smtp\.manage") {
    throw "Notification Service JWT 必须声明稳定的 SMTP 管理 scope。"
}
$testEmailJobView = $notificationOpenApi.components.schemas.TestEmailJobView
if ($testEmailJobView.additionalProperties -ne $false) {
    throw "Notification 测试邮件任务响应不能允许未声明字段。"
}

if ($modelRegistryOpenApi.openapi -notmatch "^3\.1\.") {
    throw "Model Registry OpenAPI 必须使用 3.1.x。"
}
$requiredModelRegistryPaths = @(
    "/api/v1/model-registry/endpoints",
    "/api/v1/model-registry/endpoints/{endpointId}",
    "/api/v1/model-registry/endpoints/{endpointId}/credential",
    "/api/v1/model-registry/endpoints/{endpointId}/connection-tests",
    "/api/v1/model-registry/models",
    "/api/v1/model-registry/models/{modelId}"
)
foreach ($path in $requiredModelRegistryPaths) {
    if ($null -eq $modelRegistryOpenApi.paths.$path) {
        throw "Model Registry OpenAPI 缺少必要路径：$path"
    }
}
$endpointCreate = $modelRegistryOpenApi.components.schemas.EndpointCreateRequest
$endpointCredentialReplace = $modelRegistryOpenApi.components.schemas.EndpointCredentialReplaceRequest
$endpointView = $modelRegistryOpenApi.components.schemas.EndpointView
$endpointCreateKeyIsNotWriteOnly = $endpointCreate.properties.apiKey.writeOnly -ne $true
$endpointReplaceKeyIsNotWriteOnly = $endpointCredentialReplace.properties.apiKey.writeOnly -ne $true
$endpointViewContainsApiKey = $null -ne $endpointView.properties.apiKey
$endpointViewAllowsUnknownFields = $endpointView.additionalProperties -ne $false
$endpointSecretBoundaryInvalid = $endpointCreateKeyIsNotWriteOnly -or $endpointReplaceKeyIsNotWriteOnly -or $endpointViewContainsApiKey -or $endpointViewAllowsUnknownFields
if ($endpointSecretBoundaryInvalid) {
    throw "Model Registry API Key 必须只写且不能出现在端点读取视图。"
}
$modelRegistrySecurityDescription = $modelRegistryOpenApi.components.securitySchemes.ServiceJwt.description
$missingEndpointScope = $modelRegistrySecurityDescription -notmatch "model-registry\.endpoint\.manage"
$missingModelScope = $modelRegistrySecurityDescription -notmatch "model-registry\.model\.manage"
$missingActorClaim = $modelRegistrySecurityDescription -notmatch "actor_user_id"
if ($missingEndpointScope -or $missingModelScope -or $missingActorClaim) {
    throw "Model Registry Service JWT 必须声明端点、模型 scope 和操作者声明。"
}
$connectionTestOperation = $modelRegistryOpenApi.paths."/api/v1/model-registry/endpoints/{endpointId}/connection-tests".post
$connectionTestMissingAccepted = $null -eq $connectionTestOperation.responses."202"
$connectionTestMissingGatewayBoundary = $connectionTestOperation.description -notmatch "Inference Gateway"
if ($connectionTestMissingAccepted -or $connectionTestMissingGatewayBoundary) {
    throw "Model Registry 连接测试必须声明异步 202 和 Inference Gateway 网络边界。"
}
$modelRegistryOperations = @(
    $modelRegistryOpenApi.paths."/api/v1/model-registry/endpoints".get,
    $modelRegistryOpenApi.paths."/api/v1/model-registry/endpoints".post,
    $modelRegistryOpenApi.paths."/api/v1/model-registry/endpoints/{endpointId}".get,
    $modelRegistryOpenApi.paths."/api/v1/model-registry/endpoints/{endpointId}/credential".put,
    $connectionTestOperation,
    $modelRegistryOpenApi.paths."/api/v1/model-registry/models".get,
    $modelRegistryOpenApi.paths."/api/v1/model-registry/models".post,
    $modelRegistryOpenApi.paths."/api/v1/model-registry/models/{modelId}".get,
    $modelRegistryOpenApi.paths."/api/v1/model-registry/models/{modelId}".put
)
foreach ($operation in $modelRegistryOperations) {
    if ("406" -notin @($operation.responses.PSObject.Properties.Name)) {
        throw "Model Registry 接口必须声明统一 406 响应。"
    }
    if ($null -ne $operation.requestBody -and "415" -notin @($operation.responses.PSObject.Properties.Name)) {
        throw "Model Registry 带请求体接口必须声明统一 415 响应。"
    }
}
$modelRegistryErrorResponses = @(
    $modelRegistryOpenApi.components.responses.Error400,
    $modelRegistryOpenApi.components.responses.Error401,
    $modelRegistryOpenApi.components.responses.Error403,
    $modelRegistryOpenApi.components.responses.Error404,
    $modelRegistryOpenApi.components.responses.Error409,
    $modelRegistryOpenApi.components.responses.Error500,
    $modelRegistryOpenApi.components.responses.Error406,
    $modelRegistryOpenApi.components.responses.Error415
)
foreach ($response in $modelRegistryErrorResponses) {
    if ($null -eq $response.headers."X-Correlation-ID") {
        throw "Model Registry 错误响应必须声明 X-Correlation-ID。"
    }
}
$modelRegistryErrorPattern = $modelRegistryOpenApi.components.schemas.ErrorResponse.properties.code.pattern
if ($modelRegistryErrorPattern -ne "^AW-MODEL_REGISTRY-[A-Z][A-Z0-9_]{1,31}-[0-9]{4}$") {
    throw "Model Registry 错误码格式不符合公共约定。"
}

if ($modelRegistryInternalOpenApi.openapi -notmatch "^3\.1\.") {
    throw "Model Registry 内部 OpenAPI 必须使用 3.1.x。"
}
$inferenceTargetResolutionPath = "/internal/v1/model-registry/inference-target-resolutions"
$inferenceTargetResolution = $modelRegistryInternalOpenApi.paths.$inferenceTargetResolutionPath.post
if ($null -eq $inferenceTargetResolution) {
    throw "Model Registry 内部 OpenAPI 缺少推理目标解析路径。"
}
$internalRequest = $modelRegistryInternalOpenApi.components.schemas.InferenceTargetResolutionRequest
$internalView = $modelRegistryInternalOpenApi.components.schemas.InferenceTargetView
$encryptedEnvelope = $modelRegistryInternalOpenApi.components.schemas.EncryptedCredentialEnvelope
if ($internalRequest.additionalProperties -ne $false -or $internalView.additionalProperties -ne $false -or $encryptedEnvelope.additionalProperties -ne $false) {
    throw "Model Registry 内部推理接口请求和响应不得允许未声明字段。"
}
if ($null -ne $internalView.properties.apiKey -or $null -ne $encryptedEnvelope.properties.apiKey) {
    throw "Model Registry 内部推理接口不得声明 API Key 明文字段。"
}
$internalSuccessHeaders = $inferenceTargetResolution.responses."200".headers
if ($null -eq $internalSuccessHeaders."Cache-Control" -or $null -eq $internalSuccessHeaders."X-Correlation-ID") {
    throw "Model Registry 内部推理接口必须声明 no-store 和 X-Correlation-ID。"
}
$cacheControlHeaderReference = $internalSuccessHeaders."Cache-Control".'$ref'
if ($cacheControlHeaderReference -ne "#/components/headers/NoStoreCacheControl") {
    throw "Model Registry 内部推理接口 Cache-Control 必须引用 no-store Header 定义。"
}
$cacheControlHeader = $modelRegistryInternalOpenApi.components.headers.NoStoreCacheControl
if ($cacheControlHeader.schema.const -ne "no-store") {
    throw "Model Registry 内部推理接口 Cache-Control 必须固定为 no-store。"
}
$internalSecurityDescription = $modelRegistryInternalOpenApi.components.securitySchemes.ServiceJwt.description
if ($internalSecurityDescription -notmatch "model-registry\.inference\.resolve" -or $internalSecurityDescription -notmatch "actor_user_id") {
    throw "Model Registry 内部推理接口必须声明专用 scope 和操作者声明。"
}

$policyRequest = $identityOpenApi.components.schemas.AuthPolicyUpdateRequest
$policyRequired = @($policyRequest.required)
if ("emailDomainPolicyMode" -notin $policyRequired -or "emailDomains" -notin $policyRequired) {
    throw "Identity 认证策略必须显式声明互斥的邮箱域策略模式和域名集合。"
}
if ($null -ne $policyRequest.properties.allowedEmailDomains -or $null -ne $policyRequest.properties.blockedEmailDomains) {
    throw "Identity 认证策略不能同时暴露白名单和黑名单字段。"
}

foreach ($eventFileName in $identityEventFiles) {
    $identityEventPath = Join-Path $projectRoot "contracts/events/$eventFileName"
    $identityEvent = Get-Content -Raw $identityEventPath | ConvertFrom-Json
    if ($identityEvent.'$schema' -ne "https://json-schema.org/draft/2020-12/schema") {
        throw "Identity 事件必须使用 JSON Schema Draft 2020-12：$eventFileName"
    }
    if ($identityEvent.'$defs'.payload.additionalProperties -ne $true) {
        throw "Identity 事件 Payload 必须允许兼容新增字段：$eventFileName"
    }
}

Write-Host "公共契约及各服务契约校验通过。"
