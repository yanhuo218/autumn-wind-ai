$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
$localRepository = Join-Path $projectRoot ".m2/repository"
Push-Location $projectRoot

try {
    & mvn "-Dmaven.repo.local=$localRepository" validate
    if ($LASTEXITCODE -ne 0) {
        throw "Maven 基线校验失败。"
    }

    pnpm install --lockfile-only --offline
    if ($LASTEXITCODE -ne 0) {
        throw "pnpm 锁文件校验失败。"
    }

    $env:UV_CACHE_DIR = Join-Path $projectRoot ".uv-cache"
    uv lock --offline --python 3.12
    if ($LASTEXITCODE -ne 0) {
        throw "uv 锁文件校验失败。"
    }

    python3.12 -m compileall -q "workers/file-worker/src"
    if ($LASTEXITCODE -ne 0) {
        throw "Python 源码编译失败。"
    }
}
finally {
    Pop-Location
}
