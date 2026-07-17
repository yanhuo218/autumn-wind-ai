$ErrorActionPreference = "Stop"

$checks = @(
    "verify-baseline.ps1",
    "verify-infrastructure.ps1",
    "verify-contracts.ps1",
    "verify-java.ps1"
)

foreach ($check in $checks) {
    $checkPath = Join-Path $PSScriptRoot $check
    & $checkPath
}

Write-Host "项目当前阶段全部校验通过。"
