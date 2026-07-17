$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$openApiFile = Join-Path $projectRoot "contracts/openapi/common.openapi.json"
$eventSchemaFile = Join-Path $projectRoot "contracts/events/event-envelope.v1.schema.json"

$openApi = Get-Content -Raw $openApiFile | ConvertFrom-Json
$eventSchema = Get-Content -Raw $eventSchemaFile | ConvertFrom-Json

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

Write-Host "公共契约校验通过。"
