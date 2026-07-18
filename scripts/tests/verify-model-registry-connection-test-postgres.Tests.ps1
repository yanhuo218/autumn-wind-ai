[CmdletBinding()]
param()

$ErrorActionPreference = "Stop"
$workspace = (Resolve-Path (Join-Path $PSScriptRoot "../..")).Path
$verificationScript = Join-Path $workspace "scripts/verify-model-registry-connection-test-postgres.ps1"
$testRoot = Join-Path ([IO.Path]::GetTempPath()) (
    "autumn-wind-task5a-cleanup-test-" + [Guid]::NewGuid().ToString("N")
)
$shimDirectory = Join-Path $testRoot "bin"
$previousEnvironment = @{}
$environmentNames = @(
    "PATH",
    "TEMP",
    "TMP",
    "TASK5_CLEANUP_TEST_SCENARIO",
    "TASK5_CLEANUP_TEST_STATE"
)
foreach ($name in $environmentNames) {
    $previousEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

function Invoke-CleanupScenario {
    param([Parameter(Mandatory = $true)][string]$Scenario)

    $scenarioRoot = Join-Path $testRoot $Scenario
    $stateDirectory = Join-Path $scenarioRoot "state"
    $outputFile = Join-Path $scenarioRoot "verification-output.txt"
    New-Item -ItemType Directory -Path $stateDirectory | Out-Null

    [Environment]::SetEnvironmentVariable("TEMP", $stateDirectory, "Process")
    [Environment]::SetEnvironmentVariable("TMP", $stateDirectory, "Process")
    [Environment]::SetEnvironmentVariable(
        "TASK5_CLEANUP_TEST_SCENARIO",
        $Scenario,
        "Process"
    )
    [Environment]::SetEnvironmentVariable(
        "TASK5_CLEANUP_TEST_STATE",
        $stateDirectory,
        "Process"
    )

    & ([Environment]::ProcessPath) -NoProfile -NonInteractive -File $verificationScript *> $outputFile
    $verificationExitCode = $LASTEXITCODE

    [PSCustomObject]@{
        ExitCode = $verificationExitCode
        Output = Get-Content -Raw -LiteralPath $outputFile
        StateDirectory = $stateDirectory
    }
}

function Assert-MasterKeyRemoved {
    param([Parameter(Mandatory = $true)][string]$StateDirectory)

    $remainingKeys = @(Get-ChildItem -LiteralPath $StateDirectory -Filter (
        "autumn-wind-task5a-key-*.txt"
    ))
    if ($remainingKeys.Count -ne 0) {
        throw "验证脚本退出后临时主密钥仍然存在。"
    }
}

function Assert-SanitizedOutput {
    param([Parameter(Mandatory = $true)][string]$Output)

    if ($Output -match "POSTGRES_PASSWORD=|TASK5_MASTER_KEY_FILE=|Authorization:") {
        throw "验证脚本输出包含敏感参数。"
    }
}

try {
    New-Item -ItemType Directory -Path $shimDirectory | Out-Null

    @'
@echo off
if /I "%~1|%TASK5_CLEANUP_TEST_SCENARIO%"=="run|start-failure" (
    echo attempted>"%TASK5_CLEANUP_TEST_STATE%\docker-create-attempted.txt"
    echo attempted>"%TASK5_CLEANUP_TEST_STATE%\docker-start-attempted.txt"
    exit /b 42
)
if /I "%~1"=="run" (
    exit /b 0
)
if /I "%~1"=="create" (
    echo attempted>"%TASK5_CLEANUP_TEST_STATE%\docker-create-attempted.txt"
    exit /b 0
)
if /I "%~1|%TASK5_CLEANUP_TEST_SCENARIO%"=="start|start-failure" (
    echo attempted>"%TASK5_CLEANUP_TEST_STATE%\docker-start-attempted.txt"
    exit /b 42
)
if /I "%~1"=="start" (
    echo attempted>"%TASK5_CLEANUP_TEST_STATE%\docker-start-attempted.txt"
    exit /b 0
)
if /I "%~1"=="port" (
    echo 127.0.0.1:55432
    exit /b 0
)
if /I "%~1|%TASK5_CLEANUP_TEST_SCENARIO%"=="rm|rm-failure" (
    echo attempted>"%TASK5_CLEANUP_TEST_STATE%\docker-rm-attempted.txt"
    exit /b 41
)
if /I "%~1"=="rm" (
    echo attempted>"%TASK5_CLEANUP_TEST_STATE%\docker-rm-attempted.txt"
    exit /b 0
)
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $shimDirectory "docker.cmd") -Encoding ASCII

    @'
@echo off
echo attempted>"%TASK5_CLEANUP_TEST_STATE%\maven-attempted.txt"
exit /b 0
'@ | Set-Content -LiteralPath (Join-Path $shimDirectory "mvn.cmd") -Encoding ASCII

    [Environment]::SetEnvironmentVariable(
        "PATH",
        "$shimDirectory$([IO.Path]::PathSeparator)$($previousEnvironment.PATH)",
        "Process"
    )

    $rmFailure = Invoke-CleanupScenario -Scenario "rm-failure"
    if (-not (Test-Path -LiteralPath (
        Join-Path $rmFailure.StateDirectory "maven-attempted.txt"
    ))) {
        throw "正常主体场景未执行模拟 Maven 命令。"
    }
    Assert-MasterKeyRemoved -StateDirectory $rmFailure.StateDirectory
    if (-not (Test-Path -LiteralPath (
        Join-Path $rmFailure.StateDirectory "docker-rm-attempted.txt"
    ))) {
        throw "验证脚本未尝试删除临时容器。"
    }
    if ($rmFailure.ExitCode -eq 0) {
        throw "容器清理失败后验证脚本仍返回零退出码。"
    }
    if ($rmFailure.Output -notmatch [Regex]::Escape(
        "PostgreSQL 17 临时容器清理失败。"
    )) {
        throw "验证脚本未抛出固定、脱敏的容器清理异常。"
    }
    Assert-SanitizedOutput -Output $rmFailure.Output

    $startFailure = Invoke-CleanupScenario -Scenario "start-failure"
    if (-not (Test-Path -LiteralPath (
        Join-Path $startFailure.StateDirectory "docker-create-attempted.txt"
    ))) {
        throw "验证脚本未创建临时容器。"
    }
    if (-not (Test-Path -LiteralPath (
        Join-Path $startFailure.StateDirectory "docker-start-attempted.txt"
    ))) {
        throw "验证脚本未尝试启动临时容器。"
    }
    Assert-MasterKeyRemoved -StateDirectory $startFailure.StateDirectory
    if (-not (Test-Path -LiteralPath (
        Join-Path $startFailure.StateDirectory "docker-rm-attempted.txt"
    ))) {
        throw "容器创建成功但启动失败后，验证脚本未尝试删除容器。"
    }
    if ($startFailure.ExitCode -eq 0) {
        throw "容器启动失败后验证脚本仍返回零退出码。"
    }
    Assert-SanitizedOutput -Output $startFailure.Output

    Write-Output "容器清理失败与启动失败行为验证通过。"
} finally {
    foreach ($name in $environmentNames) {
        [Environment]::SetEnvironmentVariable(
            $name,
            $previousEnvironment[$name],
            "Process"
        )
    }
    if (Test-Path -LiteralPath $testRoot) {
        Remove-Item -LiteralPath $testRoot -Recurse -Force
    }
}
