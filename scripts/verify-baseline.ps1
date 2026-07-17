$ErrorActionPreference = "Stop"

$projectRoot = Split-Path -Parent $PSScriptRoot
Push-Location $projectRoot

try {
    mvn validate
    pnpm install --lockfile-only --offline

    $env:UV_CACHE_DIR = Join-Path $projectRoot ".uv-cache"
    uv lock --offline --python 3.12
    python3.12 -m compileall -q "workers/file-worker/src"
}
finally {
    Pop-Location
}
