# Notification Service 开发说明

## 职责边界

Notification Service 独占 SMTP 连接配置、加密凭据引用、邮件模板、投递任务、重试记录和投递状态。它不决定是否允许注册、邮箱验证、密码重置或管理操作，也不读取 Identity schema。

管理接口契约位于 `contracts/openapi/notification.openapi.json`，异步邮件请求使用 `contracts/events/email-requested.v1.schema.json`。事件不携带验证或重置凭据，Notification 必须通过受 Service JWT 保护的内部接口读取受保护内容。

## 技术与数据边界

- Java 21、Spring Boot 4.1.0、Spring MVC、Spring Data JPA、Flyway、PostgreSQL 和 RabbitMQ。
- 服务只使用独立 `notification` schema，不跨 schema 读取业务数据。
- `smtp_credentials` 保存 SecretStore 信封密文字段，`smtp_config` 只引用当前凭据记录。
- SMTP 密码使用固定 SecretContext：`platform`、`smtp-password`、`smtp-config`。
- 配置读取和响应永远不返回密码、密文、Nonce 或密钥标识，只返回 `passwordConfigured`。
- V1 只允许 `STARTTLS` 或隐式 `TLS`，不提供携带凭据的明文 SMTP 模式。
- SMTP Host 只接受 ASCII DNS 名称或 IPv4 地址，不接受协议、端口或路径；发件地址只接受常规 ASCII 互联网邮箱格式。
- 管理写接口的 Service JWT 必须携带 `actor_user_id` UUID 声明，`sub` 仍表示调用服务，不能替代操作者标识。

## 测试邮件

管理端测试邮件请求只创建 `SMTP_TEST` 邮件任务并返回 `202 Accepted`。实际 DNS、连接、TLS、认证和发送由后台投递 Worker 执行，并通过 `delivery_attempts` 记录稳定错误码和结果。日志、Trace 和审计不得记录密码、Authorization Header、完整收件地址或邮件正文。

Worker 领取任务时必须在同一原子更新中写入 `SENDING` 和租约截止时间。Worker 崩溃后只能重新领取已过期租约，不能并发发送仍持有有效租约的任务。`consumed_events` 的清理周期必须长于消息系统允许的最大重投周期。

## 本地配置

服务默认监听 `8082`，数据库配置使用：

- `NOTIFICATION_DATABASE_URL`
- `NOTIFICATION_DATABASE_USERNAME`
- `NOTIFICATION_DATABASE_PASSWORD`
- `NOTIFICATION_SERVER_PORT`

仓库不提供真实数据库密码、SMTP 密码或 SecretStore 主密钥。后续接入本地 SecretStore 时，主密钥必须通过只读 Secret 文件挂载。
