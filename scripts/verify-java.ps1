$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$localRepository = Join-Path $projectRoot ".m2/repository"
Push-Location $projectRoot

try {
    & mvn "-Dmaven.repo.local=$localRepository" test
    if ($LASTEXITCODE -ne 0) {
        throw "Java 模块验证失败。"
    }
}
finally {
    Pop-Location
}
