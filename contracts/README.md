# 公共契约

该目录保存跨服务、跨语言消费的机器可读契约。业务服务不能复制后自行修改公共定义，变更必须先在这里完成兼容性评审。

## 目录

- `openapi/`：同步 HTTP API 的 OpenAPI 3.1 公共组件。
- `events/`：异步事件 Envelope 和业务事件 JSON Schema。

当前业务契约包括：

- `openapi/identity.openapi.json`：Identity Service 公共、管理和内部接口。
- `openapi/model-registry.openapi.json`：Model Registry 端点、只写凭据、连接测试任务和模型能力接口。
- `openapi/model-registry-internal.openapi.json`：Model Registry 推理目标解析和连接测试任务租约内部接口。
- `openapi/conversation.openapi.json`：Conversation 会话、当前活动分支消息投影、生成快照、停止、重新生成和 SSE 订阅接口。
- `events/conversation-stream-event.v1.schema.json`：Conversation SSE `data` 字段使用的 V1 事件信封。
- `events/inference-event.v1.schema.json`：Inference Gateway 标准推理事件。
- `openapi/inference-internal.openapi.json`：Conversation 调用 Inference Gateway 的内部 NDJSON 推理接口。
- `events/user-disabled.v1.schema.json`：用户禁用事实。
- `events/account-deletion-requested.v1.schema.json`：账户删除请求事实。
- `events/email-requested.v1.schema.json`：不携带验证或重置凭据的邮件投递请求。

具体规则参见[契约约定](../docs/development/contract-conventions.md)。

## 验证

```powershell
pwsh -NoProfile -File scripts/verify-contracts.ps1
```

当前验证覆盖 JSON 语法、必要公共组件、错误码格式、关联 ID 和事件 Envelope 的版本字段。业务服务接入后，应增加服务 OpenAPI 与事件 Schema 的兼容性检查。

## Conversation 本地 Mock

本地开发可启动只监听 `127.0.0.1:4174` 的 Conversation Mock：

```powershell
pnpm mock:conversation
```

运行 Mock 自动化测试：

```powershell
pnpm test:conversation-mock
```

Mock 使用进程内数据和 Fake SSE，仅用于前端联调与契约验证，不是生产服务，不提供持久化、认证授权、真实模型调用或生产级重放保证。
