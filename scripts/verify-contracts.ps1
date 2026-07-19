$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$openApiFile = Join-Path $projectRoot "contracts/openapi/common.openapi.json"
$eventSchemaFile = Join-Path $projectRoot "contracts/events/event-envelope.v1.schema.json"
$inferenceEventSchemaFile = Join-Path $projectRoot "contracts/events/inference-event.v1.schema.json"
$identityOpenApiFile = Join-Path $projectRoot "contracts/openapi/identity.openapi.json"
$notificationOpenApiFile = Join-Path $projectRoot "contracts/openapi/notification.openapi.json"
$modelRegistryOpenApiFile = Join-Path $projectRoot "contracts/openapi/model-registry.openapi.json"
$modelRegistryInternalOpenApiFile = Join-Path $projectRoot "contracts/openapi/model-registry-internal.openapi.json"
$inferenceInternalOpenApiFile = Join-Path $projectRoot "contracts/openapi/inference-internal.openapi.json"
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
$conversationOpenApi = Get-Content -Raw (Join-Path $projectRoot "contracts/openapi/conversation.openapi.json") | ConvertFrom-Json
$conversationStreamSchema = Get-Content -Raw (Join-Path $projectRoot "contracts/events/conversation-stream-event.v1.schema.json") | ConvertFrom-Json

if (-not (Test-Path $inferenceInternalOpenApiFile)) {
    throw "缺少 Inference Gateway 内部 OpenAPI。"
}
$inferenceInternalOpenApi = Get-Content -Raw $inferenceInternalOpenApiFile | ConvertFrom-Json

if (-not (Test-Path $inferenceEventSchemaFile)) {
    throw "Inference 标准事件 Schema 不存在。"
}
$inferenceEventSchema = Get-Content -Raw $inferenceEventSchemaFile | ConvertFrom-Json

if ($openApi.openapi -notmatch "^3\.1\.") {
    throw "公共 OpenAPI 必须使用 3.1.x。"
}

if ($conversationOpenApi.openapi -notmatch "^3\.1\.") {
    throw "Conversation OpenAPI 必须使用 3.1.x。"
}

$requiredConversationPaths = @(
    "/api/v1/conversations",
    "/api/v1/conversations/{conversationId}",
    "/api/v1/conversations/{conversationId}/generations",
    "/api/v1/generations/{generationId}",
    "/api/v1/generations/{generationId}/events",
    "/api/v1/generations/{generationId}/stop",
    "/api/v1/generations/{generationId}/regenerate"
)
foreach ($path in $requiredConversationPaths) {
    if ($null -eq $conversationOpenApi.paths.$path) {
        throw "Conversation OpenAPI 缺少必要路径：$path"
    }
}

if ($conversationStreamSchema.'$schema' -ne "https://json-schema.org/draft/2020-12/schema") {
    throw "Conversation SSE Schema 必须使用 JSON Schema Draft 2020-12。"
}

$conversationOperations = @(
    @{ Name = "POST /api/v1/conversations"; Operation = $conversationOpenApi.paths."/api/v1/conversations".post; Success = "201" },
    @{ Name = "GET /api/v1/conversations"; Operation = $conversationOpenApi.paths."/api/v1/conversations".get; Success = "200" },
    @{ Name = "GET /api/v1/conversations/{conversationId}"; Operation = $conversationOpenApi.paths."/api/v1/conversations/{conversationId}".get; Success = "200" },
    @{ Name = "DELETE /api/v1/conversations/{conversationId}"; Operation = $conversationOpenApi.paths."/api/v1/conversations/{conversationId}".delete; Success = "204" },
    @{ Name = "POST /api/v1/conversations/{conversationId}/generations"; Operation = $conversationOpenApi.paths."/api/v1/conversations/{conversationId}/generations".post; Success = "202" },
    @{ Name = "GET /api/v1/generations/{generationId}"; Operation = $conversationOpenApi.paths."/api/v1/generations/{generationId}".get; Success = "200" },
    @{ Name = "GET /api/v1/generations/{generationId}/events"; Operation = $conversationOpenApi.paths."/api/v1/generations/{generationId}/events".get; Success = "200" },
    @{ Name = "POST /api/v1/generations/{generationId}/stop"; Operation = $conversationOpenApi.paths."/api/v1/generations/{generationId}/stop".post; Success = "200" },
    @{ Name = "POST /api/v1/generations/{generationId}/regenerate"; Operation = $conversationOpenApi.paths."/api/v1/generations/{generationId}/regenerate".post; Success = "202" }
)
$validatedConversationErrorResponseRefs = @()
foreach ($responseProperty in $conversationOpenApi.components.responses.PSObject.Properties) {
    $response = $responseProperty.Value
    if ($null -eq $response.headers."X-Correlation-ID") {
        throw "Conversation OpenAPI 错误响应缺少 X-Correlation-ID：$($responseProperty.Name)"
    }
    if ($response.content."application/json".schema.'$ref' -ne "#/components/schemas/ErrorResponse") {
        throw "Conversation OpenAPI 错误响应未引用 ErrorResponse：$($responseProperty.Name)"
    }
    $validatedConversationErrorResponseRefs += "#/components/responses/$($responseProperty.Name)"
}

foreach ($entry in $conversationOperations) {
    if ($null -eq $entry.Operation) {
        throw "Conversation OpenAPI 缺少操作：$($entry.Name)"
    }
    $responseStatuses = @($entry.Operation.responses.PSObject.Properties.Name)
    if ($entry.Success -notin $responseStatuses) {
        throw "Conversation OpenAPI 操作缺少成功状态 $($entry.Success)：$($entry.Name)"
    }
    foreach ($status in @("406", "500")) {
        if ($status -notin $responseStatuses) {
            throw "Conversation OpenAPI 操作缺少统一 $status 响应：$($entry.Name)"
        }
        $errorResponseRef = $entry.Operation.responses.PSObject.Properties[$status].Value.'$ref'
        if ($errorResponseRef -notin $validatedConversationErrorResponseRefs) {
            throw "Conversation OpenAPI 操作 $status 必须引用已验证的公共错误响应：$($entry.Name)"
        }
    }
    if ($null -ne $entry.Operation.requestBody) {
        if ("415" -notin $responseStatuses) {
            throw "Conversation OpenAPI 带请求体操作缺少 415：$($entry.Name)"
        }
        $unsupportedMediaTypeRef = $entry.Operation.responses.PSObject.Properties["415"].Value.'$ref'
        if ($unsupportedMediaTypeRef -notin $validatedConversationErrorResponseRefs) {
            throw "Conversation OpenAPI 操作 415 必须引用已验证的公共错误响应：$($entry.Name)"
        }
    }
    foreach ($operationResponse in $entry.Operation.responses.PSObject.Properties) {
        if ($operationResponse.Name -eq $entry.Success) {
            continue
        }
        $operationErrorRef = $operationResponse.Value.'$ref'
        if ($operationErrorRef -notin $validatedConversationErrorResponseRefs) {
            throw "Conversation OpenAPI 非成功响应必须引用已验证的公共错误响应：$($entry.Name) $($operationResponse.Name)"
        }
    }
    $successResponse = $entry.Operation.responses.PSObject.Properties[$entry.Success].Value
    if ($null -eq $successResponse.headers."X-Correlation-ID") {
        throw "Conversation OpenAPI 成功响应缺少 X-Correlation-ID：$($entry.Name)"
    }
}

$generationCreateRequest = $conversationOpenApi.components.schemas.GenerationCreateRequest
$expectedGenerationRequestFields = @("clientRequestId", "content", "modelId")
$actualGenerationRequestFields = @($generationCreateRequest.required | Sort-Object)
if (Compare-Object $expectedGenerationRequestFields $actualGenerationRequestFields) {
    throw "Conversation GenerationCreateRequest 必填字段发生漂移。"
}
foreach ($uuidField in @("clientRequestId", "modelId")) {
    if ($generationCreateRequest.properties.$uuidField.format -ne "uuid") {
        throw "Conversation GenerationCreateRequest 字段必须使用 UUID：$uuidField"
    }
}
foreach ($schemaName in @("ConversationCreateRequest", "GenerationCreateRequest", "RegenerateRequest", "MessageContent")) {
    if ($conversationOpenApi.components.schemas.$schemaName.additionalProperties -ne $false) {
        throw "Conversation 请求 Schema 必须禁止未声明字段：$schemaName"
    }
}
foreach ($blockSchemaName in @("TextContentBlock", "ImageReferenceContentBlock", "FileReferenceContentBlock")) {
    if ($conversationOpenApi.components.schemas.$blockSchemaName.additionalProperties -ne $false) {
        throw "Conversation 内容块必须禁止未声明字段：$blockSchemaName"
    }
}

$messageContent = $conversationOpenApi.components.schemas.MessageContent
$expectedMessageContentFields = @("blocks", "schemaVersion")
if (Compare-Object $expectedMessageContentFields @($messageContent.required | Sort-Object)) {
    throw "Conversation MessageContent 必填字段必须精确为 schemaVersion 和 blocks。"
}
if ($messageContent.properties.schemaVersion.const -ne 1) {
    throw "Conversation MessageContent schemaVersion 必须固定为 1。"
}
$contentBlocks = $messageContent.properties.blocks
if ($contentBlocks.minItems -ne 1 -or $contentBlocks.maxItems -ne 100 -or $contentBlocks.items.'$ref' -ne "#/components/schemas/ContentBlock") {
    throw "Conversation MessageContent blocks 必须包含 1 至 100 个 ContentBlock。"
}

$contentBlock = $conversationOpenApi.components.schemas.ContentBlock
$expectedContentBlockRefs = @(
    "#/components/schemas/FileReferenceContentBlock",
    "#/components/schemas/ImageReferenceContentBlock",
    "#/components/schemas/TextContentBlock"
)
$actualContentBlockRefs = @($contentBlock.oneOf | ForEach-Object { $_.'$ref' } | Sort-Object)
if ($contentBlock.oneOf.Count -ne 3 -or (Compare-Object $expectedContentBlockRefs $actualContentBlockRefs)) {
    throw "Conversation ContentBlock 必须精确引用 text、image_ref 和 file_ref 三个分支。"
}

$contentBlockExpectations = @(
    @{ Name = "TextContentBlock"; Required = @("text", "type"); Type = "text"; Resource = $false },
    @{ Name = "ImageReferenceContentBlock"; Required = @("resourceId", "type"); Type = "image_ref"; Resource = $true },
    @{ Name = "FileReferenceContentBlock"; Required = @("resourceId", "type"); Type = "file_ref"; Resource = $true }
)
foreach ($expectation in $contentBlockExpectations) {
    $blockSchema = $conversationOpenApi.components.schemas.($expectation.Name)
    if (Compare-Object $expectation.Required @($blockSchema.required | Sort-Object)) {
        throw "Conversation 内容块必填字段发生漂移：$($expectation.Name)"
    }
    if ($blockSchema.properties.type.const -ne $expectation.Type) {
        throw "Conversation 内容块 type 发生漂移：$($expectation.Name)"
    }
    if ($expectation.Resource -and $blockSchema.properties.resourceId.format -ne "uuid") {
        throw "Conversation 引用内容块 resourceId 必须使用 UUID：$($expectation.Name)"
    }
}

$generationAcceptedView = $conversationOpenApi.components.schemas.GenerationAcceptedView
$expectedGenerationAcceptedFields = @("eventsUrl", "generationId", "statusUrl", "userMessageId")
if (Compare-Object $expectedGenerationAcceptedFields @($generationAcceptedView.required | Sort-Object)) {
    throw "Conversation GenerationAcceptedView 必填字段发生漂移。"
}

$conversationDetailView = $conversationOpenApi.components.schemas.ConversationDetailView
if ("messages" -notin @($conversationDetailView.required)) {
    throw "Conversation ConversationDetailView 必须要求 messages。"
}
if ($conversationDetailView.properties.messages.type -ne "array" -or $conversationDetailView.properties.messages.items.'$ref' -ne "#/components/schemas/MessageView") {
    throw "Conversation ConversationDetailView messages 必须是 MessageView 数组。"
}

$expectedMessageRoles = @("ASSISTANT", "USER")
if (Compare-Object $expectedMessageRoles @($conversationOpenApi.components.schemas.MessageRole.enum | Sort-Object)) {
    throw "Conversation MessageRole 必须只暴露 USER 和 ASSISTANT。"
}
$expectedMessageCompleteness = @("COMPLETE", "PARTIAL")
if (Compare-Object $expectedMessageCompleteness @($conversationOpenApi.components.schemas.MessageCompleteness.enum | Sort-Object)) {
    throw "Conversation MessageCompleteness 集合发生漂移。"
}

$messageView = $conversationOpenApi.components.schemas.MessageView
$expectedMessageViewFields = @("completeness", "content", "createdAt", "generationId", "messageId", "role")
if (Compare-Object $expectedMessageViewFields @($messageView.required | Sort-Object)) {
    throw "Conversation MessageView 必填字段发生漂移。"
}
$messageGenerationIdBranches = @($messageView.properties.generationId.oneOf)
$messageGenerationIdUuidBranch = $messageGenerationIdBranches | Where-Object { $_.type -eq "string" -and $_.format -eq "uuid" }
$messageGenerationIdNullBranch = $messageGenerationIdBranches | Where-Object { $_.type -eq "null" }
if ($messageGenerationIdBranches.Count -ne 2 -or $null -eq $messageGenerationIdUuidBranch -or $null -eq $messageGenerationIdNullBranch) {
    throw "Conversation MessageView generationId 必须接受 UUID 或 null。"
}
$messageRoleBranches = @($messageView.allOf[0].oneOf)
$userMessageBranch = $messageRoleBranches | Where-Object { $_.properties.role.const -eq "USER" }
$assistantMessageBranch = $messageRoleBranches | Where-Object { $_.properties.role.const -eq "ASSISTANT" }
if (@($messageView.allOf).Count -ne 1 -or $messageRoleBranches.Count -ne 2 -or $null -eq $userMessageBranch -or $null -eq $assistantMessageBranch -or $userMessageBranch.properties.generationId.type -ne "null" -or $assistantMessageBranch.properties.generationId.type -ne "string" -or $assistantMessageBranch.properties.generationId.format -ne "uuid") {
    throw "Conversation MessageView 必须约束 USER 为 null 且 ASSISTANT 为 UUID。"
}

foreach ($responseSchemaName in @("ConversationView", "ConversationListView", "ConversationDetailView", "GenerationAcceptedView", "GenerationView", "MessageView", "ErrorResponse")) {
    if ($conversationOpenApi.components.schemas.$responseSchemaName.additionalProperties -eq $false) {
        throw "Conversation 响应 Schema 必须允许兼容新增字段：$responseSchemaName"
    }
}

$expectedGenerationStatuses = @("FAILED", "INTERRUPTED", "PENDING", "STOPPED", "STREAMING", "SUCCEEDED")
$actualGenerationStatuses = @($conversationOpenApi.components.schemas.GenerationStatus.enum | Sort-Object)
if (Compare-Object $expectedGenerationStatuses $actualGenerationStatuses) {
    throw "Conversation GenerationStatus 集合发生漂移。"
}

$conversationErrorPattern = "^AW-CONVERSATION-(NOT_FOUND|CONFLICT|VALIDATION|DEPENDENCY|RATE_LIMIT|INTERNAL)-[0-9]{4}$"
if ($conversationOpenApi.components.schemas.ErrorResponse.properties.code.pattern -ne $conversationErrorPattern) {
    throw "Conversation ErrorResponse 必须限制为批准的稳定错误类别。"
}

$conversationSecurityDescription = $conversationOpenApi.components.securitySchemes.ServiceJwt.description
foreach ($requiredSecurityText in @("conversation\.manage", "conversation\.generate", "actor_user_id")) {
    if ($conversationSecurityDescription -notmatch $requiredSecurityText) {
        throw "Conversation Service JWT 缺少安全要求：$requiredSecurityText"
    }
}

$sseOperation = $conversationOpenApi.paths."/api/v1/generations/{generationId}/events".get
$lastEventIdParameter = @($sseOperation.parameters | Where-Object { $_.name -eq "Last-Event-ID" -and $_.in -eq "header" })
if ($lastEventIdParameter.Count -ne 1 -or $lastEventIdParameter[0].required -ne $false) {
    throw "Conversation SSE 操作必须声明可选 Last-Event-ID Header。"
}
$sseSuccessResponse = $sseOperation.responses."200"
if ($null -eq $sseSuccessResponse.content."text/event-stream") {
    throw "Conversation SSE 200 响应必须声明 text/event-stream。"
}
$expectedStreamHeaders = @{
    "Cache-Control" = "no-cache"
    "X-Accel-Buffering" = "no"
}
foreach ($streamHeader in $expectedStreamHeaders.Keys) {
    if ($null -eq $sseSuccessResponse.headers.$streamHeader) {
        throw "Conversation SSE 200 响应缺少代理缓冲控制 Header：$streamHeader"
    }
    if ($sseSuccessResponse.headers.$streamHeader.schema.const -ne $expectedStreamHeaders[$streamHeader]) {
        throw "Conversation SSE 200 响应缓冲控制值发生漂移：$streamHeader"
    }
}

$conversationEventBranches = @($conversationStreamSchema.oneOf)
$expectedConversationEventTypes = @(
    "content.checkpoint",
    "content.delta",
    "generation.completed",
    "generation.failed",
    "generation.interrupted",
    "generation.started",
    "generation.stopped",
    "reasoning.delta",
    "replay.reset",
    "stream.heartbeat",
    "usage.updated"
)
$actualConversationEventTypes = @($conversationEventBranches | ForEach-Object { $_.properties.eventType.const } | Sort-Object)
if ($conversationEventBranches.Count -ne 11 -or (Compare-Object $expectedConversationEventTypes $actualConversationEventTypes)) {
    throw "Conversation SSE Schema 必须精确定义 11 个事件。"
}

$expectedConversationEventFields = @("eventId", "eventType", "generationId", "occurredAt", "payload", "payloadVersion", "sequence")
foreach ($branch in $conversationEventBranches) {
    $actualRequiredFields = @($branch.required | Sort-Object)
    if (Compare-Object $expectedConversationEventFields $actualRequiredFields) {
        throw "Conversation SSE 事件公共必填字段发生漂移：$($branch.properties.eventType.const)"
    }
    if ($branch.additionalProperties -ne $false) {
        throw "Conversation SSE 事件分支必须禁止未声明字段：$($branch.properties.eventType.const)"
    }
    if ($branch.properties.payloadVersion.'$ref' -ne '#/$defs/payloadVersion') {
        throw "Conversation SSE 事件必须引用固定 payloadVersion：$($branch.properties.eventType.const)"
    }
}
if ($conversationStreamSchema.'$defs'.payloadVersion.const -ne 1) {
    throw "Conversation SSE payloadVersion 必须固定为 1。"
}

$eventByType = @{}
foreach ($branch in $conversationEventBranches) {
    $eventByType[$branch.properties.eventType.const] = $branch
}
foreach ($deltaType in @("content.delta", "reasoning.delta")) {
    $payload = $eventByType[$deltaType].properties.payload
    if ("delta" -notin @($payload.required) -or $payload.properties.delta.minLength -ne 1) {
        throw "Conversation SSE delta 必须为非空必填字段：$deltaType"
    }
}
$checkpointRequired = @($eventByType["content.checkpoint"].properties.payload.required)
if ("content" -notin $checkpointRequired -or "throughSequence" -notin $checkpointRequired) {
    throw "Conversation content.checkpoint 缺少完整内容或覆盖序号。"
}
$usagePayload = $eventByType["usage.updated"].properties.payload
$expectedTokenFields = @("completionTokens", "promptTokens", "totalTokens")
if (Compare-Object $expectedTokenFields @($usagePayload.required | Sort-Object)) {
    throw "Conversation usage.updated 必须显式提供三个 Token 字段。"
}
foreach ($tokenField in $expectedTokenFields) {
    if ($usagePayload.properties.$tokenField.'$ref' -ne '#/$defs/nullableTokenCount') {
        throw "Conversation Token 字段必须引用可空非负整数：$tokenField"
    }
}
$nullableTokenBranches = @($conversationStreamSchema.'$defs'.nullableTokenCount.oneOf)
$integerTokenBranch = $nullableTokenBranches | Where-Object { $_.type -eq "integer" }
$nullTokenBranch = $nullableTokenBranches | Where-Object { $_.type -eq "null" }
if ($nullableTokenBranches.Count -ne 2 -or $integerTokenBranch.minimum -ne 0 -or $null -eq $nullTokenBranch) {
    throw "Conversation Token 计数必须接受非负整数或 null。"
}

$terminalStatuses = @{
    "generation.completed" = "SUCCEEDED"
    "generation.failed" = "FAILED"
    "generation.stopped" = "STOPPED"
    "generation.interrupted" = "INTERRUPTED"
}
foreach ($terminalType in $terminalStatuses.Keys) {
    $payload = $eventByType[$terminalType].properties.payload
    if ("status" -notin @($payload.required) -or $payload.properties.status.const -ne $terminalStatuses[$terminalType]) {
        throw "Conversation 终态事件 status 发生漂移：$terminalType"
    }
}
foreach ($errorTerminalType in @("generation.failed", "generation.interrupted")) {
    $requiredPayloadFields = @($eventByType[$errorTerminalType].properties.payload.required)
    if ("code" -notin $requiredPayloadFields -or "correlationId" -notin $requiredPayloadFields) {
        throw "Conversation 错误终态缺少错误码或关联 ID：$errorTerminalType"
    }
}
if ("snapshotUrl" -notin @($eventByType["replay.reset"].properties.payload.required)) {
    throw "Conversation replay.reset 缺少 snapshotUrl。"
}
if ($conversationStreamSchema.'$defs'.errorCode.pattern -ne $conversationErrorPattern) {
    throw "Conversation SSE 错误码必须限制为批准的稳定错误类别。"
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
$missingModelReadScope = $modelRegistrySecurityDescription -notmatch "model-registry\.model\.read"
$missingModelScope = $modelRegistrySecurityDescription -notmatch "model-registry\.model\.manage"
$missingActorClaim = $modelRegistrySecurityDescription -notmatch "actor_user_id"
if ($missingEndpointScope -or $missingModelReadScope -or $missingModelScope -or $missingActorClaim) {
    throw "Model Registry Service JWT 必须声明端点、模型 read/manage scope 和操作者声明。"
}
$modelReadOperations = @(
    $modelRegistryOpenApi.paths."/api/v1/model-registry/models".get,
    $modelRegistryOpenApi.paths."/api/v1/model-registry/models/{modelId}".get
)
$modelWriteOperations = @(
    $modelRegistryOpenApi.paths."/api/v1/model-registry/models".post,
    $modelRegistryOpenApi.paths."/api/v1/model-registry/models/{modelId}".put
)
$expectedModelReadScopes = @("model-registry.model.read", "model-registry.model.manage")
foreach ($operation in $modelReadOperations) {
    $authorization = $operation."x-service-jwt-authorization"
    $actualScopes = @($authorization.requiredScopes.anyOf | Sort-Object)
    if ((Compare-Object $expectedModelReadScopes $actualScopes) -or
            $authorization.actorUserId.claim -ne "actor_user_id" -or
            $authorization.actorUserId.format -ne "uuid" -or
            $authorization.actorUserId.canonical -ne $true) {
        throw "Model Registry 模型 GET 必须结构化声明 read/manage scope 和规范操作者。"
    }
}
foreach ($operation in $modelWriteOperations) {
    $authorization = $operation."x-service-jwt-authorization"
    $actualScopes = @($authorization.requiredScopes.anyOf)
    if ($actualScopes.Count -ne 1 -or $actualScopes[0] -ne "model-registry.model.manage" -or
            $authorization.actorUserId.claim -ne "actor_user_id" -or
            $authorization.actorUserId.format -ne "uuid" -or
            $authorization.actorUserId.canonical -ne $true) {
        throw "Model Registry 模型 POST/PUT 必须结构化声明仅管理 scope 和规范操作者。"
    }
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

if ($inferenceInternalOpenApi.openapi -notmatch "^3\.1\.") {
    throw "Inference 内部 OpenAPI 必须使用 3.1.x。"
}
$inferencePath = "/internal/v1/inference/chat-completions"
$inferencePathItem = $inferenceInternalOpenApi.paths.$inferencePath
$operation = $inferencePathItem.post
if ($null -eq $operation -or $inferenceInternalOpenApi.paths.PSObject.Properties.Count -ne 1 `
        -or $inferencePathItem.PSObject.Properties.Count -ne 1 `
        -or $inferencePathItem.PSObject.Properties.Name -ne "post") {
    throw "Inference 内部 OpenAPI 必须只声明固定推理路径。"
}
$requestSchema = $inferenceInternalOpenApi.components.schemas.ChatCompletionRequest
$messageSchema = $inferenceInternalOpenApi.components.schemas.ChatMessage
if ($requestSchema.additionalProperties -ne $false -or $messageSchema.additionalProperties -ne $false `
        -or $requestSchema.properties.messages.minItems -ne 1 `
        -or $requestSchema.properties.messages.maxItems -ne 256) {
    throw "Inference 内部请求必须严格且消息数量为 1..256。"
}
if ((@($requestSchema.required | Sort-Object) -join ",") -ne "generationId,invocationAttemptId,messages,modelId,ownerUserId") {
    throw "Inference 内部请求必须精确声明五个必填字段。"
}
foreach ($field in @("ownerUserId", "modelId", "generationId", "invocationAttemptId")) {
    if ($requestSchema.properties.$field.format -ne "uuid") {
        throw "Inference 内部请求字段 $field 必须是 UUID。"
    }
}
if ($requestSchema.properties.messages.items.'$ref' -ne "#/components/schemas/ChatMessage" `
        -or (@($messageSchema.properties.role.enum | Sort-Object) -join ",") -ne "assistant,user" `
        -or $messageSchema.properties.content.minLength -ne 1) {
    throw "Inference 内部消息定义不符合严格约定。"
}
if ($requestSchema.properties.systemPrompt.minLength -ne 1 `
        -or $requestSchema.properties.temperature.minimum -ne 0 `
        -or $requestSchema.properties.temperature.maximum -ne 2 `
        -or $requestSchema.properties.maxOutputTokens.minimum -ne 1 `
        -or $requestSchema.properties.maxOutputTokens.maximum -ne 131072) {
    throw "Inference 内部请求字段边界不符合约定。"
}
if ($operation.requestBody.content.PSObject.Properties.Count -ne 1 `
        -or $operation.requestBody.content.PSObject.Properties.Name -ne "application/json" `
        -or $operation.requestBody.content."application/json".schema.'$ref' -ne "#/components/schemas/ChatCompletionRequest") {
    throw "Inference 内部请求必须使用 JSON ChatCompletionRequest。"
}
foreach ($status in @("400", "401", "403", "406", "413", "415", "500")) {
    if ($null -eq $operation.responses.$status) {
        throw "Inference OpenAPI 缺少 $status 响应。"
    }
}
$successResponse = $operation.responses."200"
if ($null -eq $successResponse.content."application/x-ndjson") {
    throw "Inference 成功响应必须是 application/x-ndjson。"
}
if ($successResponse.content."application/x-ndjson".schema.items.'$ref' -ne "../events/inference-event.v1.schema.json") {
    throw "Inference NDJSON 响应必须引用标准推理事件 Schema。"
}
if ($successResponse.headers."Cache-Control".'$ref' -ne "#/components/headers/NoStoreCacheControl" `
        -or $inferenceInternalOpenApi.components.headers.NoStoreCacheControl.schema.const -ne "no-store" `
        -or $successResponse.headers."X-Content-Type-Options".'$ref' -ne "#/components/headers/NoSniffContentTypeOptions" `
        -or $inferenceInternalOpenApi.components.headers.NoSniffContentTypeOptions.schema.const -ne "nosniff") {
    throw "Inference 成功响应必须声明 no-store 和 nosniff。"
}
$inferenceSecurity = $inferenceInternalOpenApi.components.securitySchemes.ServiceJwt
if ($inferenceSecurity.type -ne "http" -or $inferenceSecurity.scheme -ne "bearer" `
        -or $inferenceSecurity.bearerFormat -ne "JWT" `
        -or $inferenceSecurity.description -notmatch "RS256" `
        -or $inferenceSecurity.description -notmatch "固定的 inference\.chat\.invoke scope" `
        -or $inferenceSecurity.description -match "inference\.chat\.execute" `
        -or $inferenceSecurity.description -notmatch "actor_user_id" `
        -or "ServiceJwt" -notin @($operation.security[0].PSObject.Properties.Name)) {
    throw "Inference 内部接口必须声明 Bearer Service JWT、固定 scope 和 actor_user_id。"
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
