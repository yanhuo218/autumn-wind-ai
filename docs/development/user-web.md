# 用户端聊天工作区开发说明

## 当前边界

`apps/user-web` 是 Autumn Wind Ai 的文本对话用户端。当前批次覆盖会话列表、模型选择、文本发送、流式响应、停止、断线重连、`replay.reset`、失败恢复和重新生成。

附件上传、图片或视频生成、真实用户认证、管理端和生产模型端点不属于当前用户端 Mock 的能力。页面只依赖 `ConversationClient` 和 `ModelCatalog` 接口，后续接入真实 BFF 时不应修改页面状态机。

## 依赖与目录

- Node.js：满足根目录 `package.json` 中的 `>=24 <27`。
- pnpm：满足 `>=11 <12`，当前锁定版本为 `11.12.0`。
- `apps/user-web/src/features/conversation/`：会话 HTTP Client、SSE 解析、生成状态机和聊天组件。
- `apps/user-web/src/features/models/`：模型目录接口、Mock 目录和 HTTP 目录适配器。
- `apps/user-web/e2e/`：Playwright 关键流程和响应式测试。
- `packages/api-contracts/`：由公共 OpenAPI/SSE 契约生成的类型和运行时校验入口。

## 启动本地工作区

先安装依赖：

```powershell
pnpm install
```

终端一启动 Conversation Mock：

```powershell
pnpm mock:conversation
```

终端二启动 Vite 用户端：

```powershell
pnpm dev:user-web
```

浏览器访问 `http://127.0.0.1:4173/chat`。Vite 只通过同源 `/api/v1` 代理访问 Mock，页面代码不直接写入 `4174` 端口。

Playwright 会自动启动独立的 Mock 和 Vite 实例：

```powershell
pnpm --filter @autumn-wind/user-web test:e2e
```

如果本地已经启动上述两个服务，可显式复用它们：

```powershell
$env:PLAYWRIGHT_REUSE_EXISTING_SERVER = "1"
pnpm --filter @autumn-wind/user-web test:e2e
```

未设置该变量时，Playwright 默认使用 `reuseExistingServer: false`，此时 `4173` 和 `4174` 必须空闲。复用模式不会重新初始化常驻服务的数据，只适合本地验证；持续集成和可重复回归应使用 Playwright 自动启动的独立进程。

## Mock 场景

开发环境可以在初始地址附加以下场景参数：

```text
success / slow / failed / interrupted / replay-reset / disconnect-once
```

示例：`http://127.0.0.1:4173/chat?scenario=disconnect-once`。场景只在开发构建读取，并只追加到创建生成和重新生成请求；生产构建不会读取或发送该参数。Mock 使用进程内数据和 Fake SSE，不提供持久化、认证授权、真实模型调用或生产级重放保证。

## 模型目录切换

开发默认使用无凭据的 `MockModelCatalog`：

```powershell
pnpm dev:user-web
```

需要联调本地 Model Registry 时，显式设置：

```powershell
$env:VITE_MODEL_CATALOG_MODE = "http"
pnpm dev:user-web
```

HTTP 模式通过同源 `/api/v1/model-registry` 代理到本地 Model Registry。模型目录响应仍需经过公共契约校验，页面不会显示端点地址、凭据或 Authorization Header。

## 验证命令

```powershell
pwsh -NoProfile -File scripts/verify-contracts.ps1
pnpm contracts:frontend
pnpm --filter @autumn-wind/user-web test
pnpm --filter @autumn-wind/user-web check
pnpm --filter @autumn-wind/user-web build
pnpm --filter @autumn-wind/user-web test:e2e
pnpm test:conversation-mock
```

Playwright 截图和本地评审产物保存在 `.superpowers/`，不会提交到仓库。任何测试、示例或文档都不得写入真实 API Key、Token、Cookie、密码、用户对话或内部端点凭据。
