[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$containerName = "autumn-wind-task5a-pg17-" + [Guid]::NewGuid().ToString("N")
$databaseUser = "task5_worker"
$databaseName = "task5_registry"
$databasePassword = [Convert]::ToHexString(
    [Security.Cryptography.RandomNumberGenerator]::GetBytes(24)
).ToLowerInvariant()
$masterKeyFile = Join-Path ([IO.Path]::GetTempPath()) (
    "autumn-wind-task5a-key-" + [Guid]::NewGuid().ToString("N") + ".txt"
)
$containerCreated = $false

function Invoke-Docker {
    param([Parameter(Mandatory = $true)][string[]]$Arguments)

    & docker @Arguments
    if ($LASTEXITCODE -ne 0) {
        throw "Docker 命令执行失败。"
    }
}

try {
    [IO.File]::WriteAllText(
        $masterKeyFile,
        [Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32)),
        [Text.Encoding]::ASCII
    )

    Invoke-Docker @("create", "--pull=never", "--name", $containerName,
        "--publish", "127.0.0.1::5432",
        "--env", "POSTGRES_USER=$databaseUser",
        "--env", "POSTGRES_PASSWORD=$databasePassword",
        "--env", "POSTGRES_DB=$databaseName",
        "postgres:17")
    $containerCreated = $true
    Invoke-Docker @("start", $containerName)

    $ready = $false
    $consecutiveReadyChecks = 0
    for ($attempt = 0; $attempt -lt 120; $attempt++) {
        & docker exec $containerName psql -U $databaseUser -d $databaseName `
            --tuples-only --no-align --command "SELECT 1" *> $null
        if ($LASTEXITCODE -eq 0) {
            $consecutiveReadyChecks++
            if ($consecutiveReadyChecks -ge 3) {
                $ready = $true
                break
            }
        } else {
            $consecutiveReadyChecks = 0
        }
        Start-Sleep -Milliseconds 500
    }
    if (-not $ready) {
        throw "PostgreSQL 17 临时容器未在预期时间内就绪。"
    }

    $portOutput = & docker port $containerName "5432/tcp"
    if ($LASTEXITCODE -ne 0 -or $portOutput -notmatch ":(?<port>[0-9]+)$") {
        throw "无法解析 PostgreSQL 17 临时容器端口。"
    }
    $hostPort = $Matches.port

    $sqlFiles = @(
        "services/model-registry-service/src/main/resources/db/migration/V1__create_model_registry_schema.sql",
        "services/model-registry-service/src/main/resources/db/migration/V2__create_endpoint_connection_test_jobs.sql",
        "services/model-registry-service/src/test/resources/db/task5/V2__connection_test_job_fixture.sql",
        "services/model-registry-service/src/main/resources/db/migration/V3__add_connection_test_job_leases.sql"
    )
    foreach ($relativePath in $sqlFiles) {
        $source = Join-Path $workspace $relativePath
        $target = "/tmp/" + [IO.Path]::GetFileName($source)
        Invoke-Docker @("cp", $source, "${containerName}:$target")
        Invoke-Docker @("exec", $containerName, "psql", "-v", "ON_ERROR_STOP=1",
            "-U", $databaseUser, "-d", $databaseName, "-f", $target)
    }

    $jdbcUrl = "jdbc:postgresql://127.0.0.1:$hostPort/$databaseName"
    $integrationEnvironment = @{
        "TASK5_POSTGRES_URL" = $jdbcUrl
        "TASK5_POSTGRES_USERNAME" = $databaseUser
        "TASK5_POSTGRES_PASSWORD" = $databasePassword
        "TASK5_MASTER_KEY_FILE" = $masterKeyFile
    }
    $previousEnvironment = @{}
    foreach ($name in $integrationEnvironment.Keys) {
        $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
        [Environment]::SetEnvironmentVariable($name, $integrationEnvironment[$name], "Process")
    }
    Push-Location $workspace
    try {
        & mvn -o "-Dmaven.repo.local=$workspace\.m2\repository" `
            -pl services/model-registry-service -am `
            "-Dtest=ConnectionTestWorkerPostgresIntegrationTest" `
            "-Dsurefire.failIfNoSpecifiedTests=false" test
        if ($LASTEXITCODE -ne 0) {
            throw "PostgreSQL 17 集成测试失败。"
        }
    } finally {
        Pop-Location
        foreach ($name in $integrationEnvironment.Keys) {
            [Environment]::SetEnvironmentVariable($name, $previousEnvironment[$name], "Process")
        }
    }
} finally {
    $containerCleanupError = $null
    try {
        if ($containerCreated) {
            & docker rm --force $containerName *> $null
            if ($LASTEXITCODE -ne 0) {
                throw "Docker 临时容器清理命令返回非零退出码。"
            }
            Write-Output "已删除 PostgreSQL 17 临时容器：$containerName"
        }
    } catch {
        $containerCleanupError = $_
        Write-Warning "PostgreSQL 17 临时容器清理失败：$containerName"
    } finally {
        if (Test-Path -LiteralPath $masterKeyFile) {
            Remove-Item -LiteralPath $masterKeyFile -Force
        }
    }
    if ($null -ne $containerCleanupError) {
        throw "PostgreSQL 17 临时容器清理失败。"
    }
}
