# 公共契约

该目录保存跨服务、跨语言消费的机器可读契约。业务服务不能复制后自行修改公共定义，变更必须先在这里完成兼容性评审。

## 目录

- `openapi/`：同步 HTTP API 的 OpenAPI 3.1 公共组件。
- `events/`：异步事件 Envelope 和业务事件 JSON Schema。

具体规则参见[契约约定](../docs/development/contract-conventions.md)。

## 验证

```powershell
pwsh -NoProfile -File scripts/verify-contracts.ps1
```

当前验证覆盖 JSON 语法、必要公共组件、错误码格式、关联 ID 和事件 Envelope 的版本字段。业务服务接入后，应增加服务 OpenAPI 与事件 Schema 的兼容性检查。
