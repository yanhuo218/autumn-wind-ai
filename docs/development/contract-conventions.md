# 公共契约约定

## 目标

公共契约用于约束服务之间可观察的行为，不共享数据库实体或业务实现。同步接口使用 OpenAPI 3.1，异步事件使用 JSON Schema Draft 2020-12。

机器可读文件集中在 `contracts/`。每个服务仍然拥有自己的接口和事件，但必须引用或遵守公共组件。

## HTTP API

- 公共路径使用 `/api/v{major}`，内部路径使用 `/internal/v{major}`。
- API 的破坏性变化提升路径中的主版本；兼容新增只提升文档版本。
- 新增可选字段属于兼容变化；删除字段、收紧取值或改变语义属于破坏性变化。
- 消费方必须忽略响应中未知的新增字段。
- 错误响应统一使用 `ErrorResponse`，不得向用户返回堆栈、内部地址、SQL、密钥或上游原始响应。

公共 OpenAPI 组件位于 `contracts/openapi/common.openapi.json`。服务 OpenAPI 可以通过构建流程复制到发布产物，但源定义只能在 `contracts/` 中维护。

## 错误码

稳定错误码格式为：

```text
AW-{BOUNDARY}-{CATEGORY}-{NUMBER}
```

- `BOUNDARY`：产生错误的限界上下文，例如 `IDENTITY`、`REGISTRY`、`INFERENCE`、`FILE`、`ADMIN`。
- `CATEGORY`：稳定类别，例如 `VALIDATION`、`AUTH`、`FORBIDDEN`、`NOT_FOUND`、`CONFLICT`、`RATE_LIMIT`、`DEPENDENCY`、`INTERNAL`。
- `NUMBER`：四位数字，在同一限界上下文和类别内永久保留，不能重用。

示例：`AW-COMMON-VALIDATION-0001`。

HTTP 状态码表达通用协议语义，错误码表达稳定业务语义。客户端不能依赖错误消息文本做流程判断。

## 关联 ID 与 Trace

- HTTP 请求头和响应头统一使用 `X-Correlation-ID`。
- Gateway 接受符合公共格式的调用方值；缺失或非法时生成新值。
- 关联 ID 必须传入同步调用、事件 Envelope、结构化日志和审计记录。
- W3C `traceparent` 由 OpenTelemetry 管理，不能用关联 ID 替代 Trace ID。
- 关联 ID 是诊断标识，不是认证凭据，不能用于授权或数据隔离。

用户可见错误必须返回关联 ID，便于管理员在不暴露敏感上下文的前提下定位日志。

## 异步事件

- 所有事件使用 `event-envelope.v1.schema.json` 的字段。
- `eventType` 使用过去式业务事实名称，例如 `UserDisabled`、`FileUploaded`。
- `eventVersion` 从 `1` 开始；Payload 的破坏性变化必须提升版本。
- RabbitMQ 路由键格式为 `{boundary}.{event-name}.v{version}`，全部使用小写短横线形式。
- 发布方负责生成全局唯一 `eventId`；消费方使用它实现幂等处理。
- `causationId` 指向直接导致当前事件的命令或事件，没有明确因果来源时可以省略。
- 消费方必须忽略 Envelope 和 Payload 中未知的新增字段。
- 事件中不得包含密码、API Key、Token、Cookie、Authorization Header 或可直接使用的凭据。

队列由消费方拥有。发布方不能依赖具体队列名称，也不能通过读取消费方数据库确认处理结果。

## 演进流程

1. 在 `contracts/` 中修改机器可读定义和中文说明。
2. 运行 `scripts/verify-contracts.ps1`。
3. 对已有消费者执行兼容性检查。
4. 先发布能够同时理解旧、新结构的消费者。
5. 再发布产生新字段或新版本的生产者。
6. 只有所有消费者停止使用旧版本后，才允许移除旧契约。
