$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$composeFile = Join-Path $projectRoot "infra/compose.yaml"
$envFile = Join-Path $projectRoot "infra/.env.example"
$requiredServices = @("postgres", "rabbitmq", "redis", "minio")

$configJson = & docker compose --env-file $envFile -f $composeFile config --format json
if ($LASTEXITCODE -ne 0) {
    throw "Docker Compose 配置校验失败。"
}

$config = $configJson | ConvertFrom-Json
$actualServices = @($config.services.PSObject.Properties.Name)

foreach ($serviceName in $requiredServices) {
    if ($serviceName -notin $actualServices) {
        throw "Docker Compose 缺少服务：$serviceName"
    }

    $service = $config.services.$serviceName
    if ([string]::IsNullOrWhiteSpace($service.image) -or $service.image -match ":latest$") {
        throw "服务 $serviceName 必须配置非 latest 镜像标签。"
    }

    foreach ($port in @($service.ports)) {
        if ($port.host_ip -ne "127.0.0.1") {
            throw "服务 $serviceName 的端口必须只绑定到 127.0.0.1。"
        }
    }
}

Write-Host "基础设施 Compose 配置校验通过。"
