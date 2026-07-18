$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$openApiFile = Join-Path $projectRoot "contracts/openapi/common.openapi.json"
$eventSchemaFile = Join-Path $projectRoot "contracts/events/event-envelope.v1.schema.json"
$identityOpenApiFile = Join-Path $projectRoot "contracts/openapi/identity.openapi.json"
$notificationOpenApiFile = Join-Path $projectRoot "contracts/openapi/notification.openapi.json"
$identityEventFiles = @(
    "user-disabled.v1.schema.json",
    "account-deletion-requested.v1.schema.json",
    "email-requested.v1.schema.json"
)

$openApi = Get-Content -Raw $openApiFile | ConvertFrom-Json
$eventSchema = Get-Content -Raw $eventSchemaFile | ConvertFrom-Json
$identityOpenApi = Get-Content -Raw $identityOpenApiFile | ConvertFrom-Json
$notificationOpenApi = Get-Content -Raw $notificationOpenApiFile | ConvertFrom-Json

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

Write-Host "公共契约和 Identity 契约校验通过。"
