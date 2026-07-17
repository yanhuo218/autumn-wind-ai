# 本地开发环境基线

更新日期：2026-07-18

## 目标

本文记录 Autumn Wind Ai 开始实施时已经验证的本地工具链。版本基线用于保证工程骨架可复现，不代表所有生产依赖的最终版本。

## 已验证工具

| 工具 | 本机版本 | 项目基线 | 状态 |
| --- | --- | --- | --- |
| Java | OpenJDK 21.0.2 | Java 21 LTS | 可用 |
| Maven | 3.9.16 | Maven 3.9.x | 可用 |
| Node.js | 26.5.0 | Node.js 24 LTS | 本机兼容构建，仓库与 CI 固定 24 LTS |
| npm | 11.17.0 | 不作为项目主要包管理器 | 可用 |
| pnpm | 11.12.0 | pnpm 11 | 可用 |
| Python | 系统 3.14.6 | 不用于 File Worker | 保留 |
| Python | uv 管理的 3.12.10 | Python 3.12 | 可用 |
| uv | 0.11.29 | uv 0.11.x | 可用 |
| Docker | 29.6.1 | Docker 29.x | 可用 |
| Docker Compose | 5.2.0 | Compose 5.x | 可用 |
| FFmpeg / FFprobe | 8.1.2 | FFmpeg 8.x | 可用 |
| Git | 2.55.0 | Git 2.x | 可用 |
| gh | 2.96.0 | gh 2.x | 可用 |
| PowerShell | 7.6.3 | PowerShell 7.x | 可用 |

## 环境结论

- Docker Desktop Linux Engine 已验证正常，服务端为 Linux amd64。
- PostgreSQL、RabbitMQ、Redis、MinIO、前端和 Java 常用开发端口当前没有监听进程。
- D 盘剩余空间约 1253.7 GB，可满足依赖缓存、容器镜像和本地数据卷需要。
- 本机未安装 fnm、nvm 或 Volta，因此不替换现有 Node.js 26；仓库通过 `.node-version` 和 `engines` 声明 Node.js 24 LTS 基线。
- Python Worker 使用 uv 管理的 Python 3.12.10，不依赖系统 Python 3.14 或 Windows Python Launcher。
- 受限执行环境无法写 Scoop 的全局 uv 缓存，项目将缓存定向到被忽略的 `.uv-cache/`。
- pnpm Store 位于项目 `.pnpm-store/`，该目录不得提交。

## 版本锁定原则

- Java 使用 Maven Compiler Release 锁定为 21。
- Node.js 使用 `.node-version` 声明 24.18.0；`package.json` 同时限制支持范围。
- pnpm 使用 `packageManager` 字段锁定 11.12.0。
- Python 使用 `.python-version` 锁定 3.12.10，并由 uv 生成锁文件。
- Spring Boot、React、Vite 等第三方框架只有在读取正式版本元数据并完成兼容验证后才锁定，不凭记忆选择版本。

## 本地权限说明

在受限自动化环境中访问 Docker Desktop 命名管道可能需要额外权限。这是执行环境限制，不代表 Docker Desktop 故障。项目脚本不得通过关闭安全检查或放宽系统权限规避该限制。
