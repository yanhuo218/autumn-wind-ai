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
- CSRF 使用独立的 Cookie Token Repository，不与会话表混用；V2 迁移已删除早期设计中的会话 CSRF Hash 字段。
- Gateway 通过受 Service JWT 保护的 introspection 接口同步校验会话，账户禁用后旧会话立即失效。
- 会话 Cookie 必须使用 `HttpOnly`、`Secure` 和适当的 `SameSite`，所有浏览器状态变更请求必须校验 CSRF。
- 邮箱域策略显式选择 `ALLOWLIST` 或 `BLOCKLIST`，两种模式不能同时生效。
- 邮箱域名先做 IDNA ASCII、大小写和末尾点规范化，再执行完整域名精确匹配；V1 不支持通配符。
- 关闭公开注册不影响已有用户登录和管理员创建账户。

浏览器首次进入认证页面时，应先请求 `GET /api/v1/auth/csrf`。服务返回 HttpOnly 的 `AW_CSRF` Cookie，并在响应正文和 `X-CSRF-TOKEN` Header 中返回对应的掩码 Token。后续注册、登录和注销请求必须由浏览器自动携带 `AW_CSRF` Cookie，同时把掩码 Token 放入 `X-CSRF-TOKEN` Header；前端不得尝试读取 Cookie，也不得记录 Token。

登录成功后服务设置 host-only 的 `AW_SESSION` Cookie，属性固定为 `HttpOnly`、`Secure`、`SameSite=Lax` 和 `Path=/`，不设置 `Domain`。注销使用相同属性和 `Max-Age=0` 清除 Cookie。重复同名会话 Cookie 按无效会话处理；无效 Cookie 不阻断公开注册或登录，但受保护接口统一返回 `401`。

浏览器安全链采用无状态 SecurityContext，对健康检查、CSRF、注册选项、注册和登录显式放行，对当前会话要求认证，对管理路径要求 `ADMIN`，其他路径默认拒绝。内部 Service JWT 使用独立且优先级更高的安全链，不能复用浏览器 Cookie 认证。

内部 `POST /internal/v1/auth/session-introspections` 使用优先级更高的独立资源服务器链，不使用浏览器 Cookie，也不校验 CSRF。Service JWT 只接受 RS256，必须通过 JWK Set 验签，并同时满足配置的 issuer、`identity-service` audience、`sub` 调用方白名单和 `identity.session.introspect` scope。Token 必须同时包含签发与过期时间，默认最大有效跨度为 5 分钟。Identity Service 只验证 Token，不持有或签发内部服务私钥。

部署时必须提供 `IDENTITY_SERVICE_JWT_ISSUER` 和 HTTPS 的 `IDENTITY_SERVICE_JWT_JWK_SET_URI`。可选变量包括 `IDENTITY_SERVICE_JWT_AUDIENCE`、逗号分隔的 `IDENTITY_SERVICE_JWT_ALLOWED_CALLERS`、`IDENTITY_SERVICE_JWT_REQUIRED_SCOPE` 和 ISO-8601 Duration 格式的 `IDENTITY_SERVICE_JWT_MAXIMUM_LIFETIME`。最大有效期必须大于零且不超过 1 小时。默认只允许 `gateway-service` 调用 introspection；生产环境应按实际服务清单显式配置，不能把网络位置视为身份。

## 应用服务行为

- 公开注册先执行注册开关、邮箱域、密码和输入校验，再进行 Argon2id Hash。已存在邮箱与数据库并发唯一冲突均返回统一受理结果，不修改原账户，也不暴露邮箱是否存在。
- 邮箱验证 Token、邮件请求 Outbox 和条款接受审计尚未形成原子持久化闭环。只要认证策略要求邮箱验证、条款接受或隐私接受，公开注册就失败关闭，不能创建无法激活或缺少审计证据的账户。
- 登录对未知邮箱执行 dummy Argon2id 校验；邮箱不存在、密码错误、账户临时锁定和非 `ACTIVE` 状态使用同一种认证失败语义。
- 同一账户的并发登录依靠 `users.version` 乐观锁与最多三次有界重试处理，不在 Argon2id 计算期间持有数据库悲观锁。
- 会话默认有效期为 7 天，可通过 `IDENTITY_SESSION_TTL` 使用 ISO-8601 Duration 调整，但必须大于零且不超过 365 天。
- 当前会话查询和内部 introspection 会同时校验 Session 与账户状态。Session 实体尚无版本字段，因此 V1 不在读取时更新 `last_seen_at`，避免覆盖并发撤销结果。
- 注销使用带活动条件的原子更新写入 `revoked_at`；重复注销按无效会话处理。

## 当前验证

当前自动化测试覆盖账户状态迁移、邮箱规范化、白名单与黑名单语义、Token 签发、Argon2id 随机盐、注册统一受理、登录锁定、乐观锁重试、会话查询、注销、introspection、真实 Cookie CSRF 协作和浏览器安全链。迁移已在临时 PostgreSQL 17 容器中执行，确认 7 张表只创建在 `identity` schema；后续结构调整通过新版本迁移完成，不改写已发布迁移。
