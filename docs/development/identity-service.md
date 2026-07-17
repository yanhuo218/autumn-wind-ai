# Identity Service 开发说明

## 职责边界

Identity Service 独占用户、密码哈希、浏览器会话、邮箱验证、密码重置、账户状态和认证策略。它不保存 SMTP 配置，不投递邮件，也不代理 Admin Service 或 Notification Worker 的接口。

公共、管理和内部接口以 `contracts/openapi/identity.openapi.json` 为准。业务事件位于 `contracts/events/`，验证和重置凭据不得进入 RabbitMQ。

## 技术基线

- Java 21。
- Spring Boot 4.1.0 与 Spring MVC。
- Spring Security、Spring Data JPA、Flyway、PostgreSQL 和 RabbitMQ。
- 密码使用 Argon2id；Bouncy Castle 版本在根 POM 统一锁定。

模块路径为 `services/identity-service`。执行模块测试：

```powershell
mvn "-Dmaven.repo.local=$((Resolve-Path .).Path)\.m2\repository" -pl services/identity-service -am test
```

## 数据库

服务只使用 `identity` schema。首个迁移创建：

- `users`
- `auth_sessions`
- `email_verifications`
- `password_resets`
- `auth_policies`
- `auth_policy_email_domains`
- `outbox_events`

本地启动前必须提供 `IDENTITY_DATABASE_PASSWORD`。可选变量包括 `IDENTITY_DATABASE_URL`、`IDENTITY_DATABASE_USERNAME` 和 `IDENTITY_SERVER_PORT`。仓库不提供真实密码或 `.env`。

当前 `users.email` 在软删除后仍保持唯一，避免旧身份被静默接管。若后续产品决定允许邮箱重用，应通过新的 Flyway 迁移增加保留期、历史标识和审计约束，不能直接修改已经发布的迁移文件。

数据模型允许后续调整，但必须遵循 Expand、Migrate、Contract：先增加兼容结构，再完成可恢复的数据迁移和校验，最后在所有运行版本停止使用旧结构后收缩。

## 会话与策略

- 浏览器只持有高熵不透明会话 Token，数据库只保存 SHA-256 Hash。
- Gateway 通过受 Service JWT 保护的 introspection 接口同步校验会话，账户禁用后旧会话立即失效。
- 会话 Cookie 必须使用 `HttpOnly`、`Secure` 和适当的 `SameSite`，所有浏览器状态变更请求必须校验 CSRF。
- 邮箱域策略显式选择 `ALLOWLIST` 或 `BLOCKLIST`，两种模式不能同时生效。
- 邮箱域名先做 IDNA ASCII、大小写和末尾点规范化，再执行完整域名精确匹配；V1 不支持通配符。
- 关闭公开注册不影响已有用户登录和管理员创建账户。

## 当前验证

单元测试覆盖账户状态迁移、邮箱规范化、白名单与黑名单语义，以及 Argon2id 随机盐和密码校验。首个迁移已在临时 PostgreSQL 17 容器中执行，确认 7 张表只创建在 `identity` schema。
