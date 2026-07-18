# Gateway 浏览器认证基础设计

## 1. 背景与结论

Autumn Wind Ai 已经具备以下基础能力：

- Identity Service 提供注册、登录、当前会话、注销、CSRF 和内部 Session Introspection。
- Model Registry 提供按 `actor_user_id` 隔离的端点与模型接口。
- 用户端聊天工作区可以在 Conversation Mock 上完成文本发送、停止、重连和失败恢复。
- Conversation Service 当前只有领域基线和公共契约，尚未实现真实 HTTP、持久化和下游推理调用。
- 仓库尚无 API Gateway/BFF，也没有 Service JWT 签发器或 JWKS 发布端点。

本设计选择先交付一个可独立验收的 Gateway 认证纵切片：

```text
Browser
  -> Gateway Service
      -> Identity Service：公共认证代理、Session Introspection
      -> Model Registry：只读模型目录
```

本批不接入真实 Conversation Service，不代理生成接口，也不实现管理端、附件或多模态。现有 Conversation Mock 继续服务于用户端开发和回归测试，不能作为生产实现。

## 2. 方案比较

### 2.1 前端直接访问 Identity Service

该方案改动最少，但会让浏览器依赖 Identity 的部署地址和跨域策略。后续引入 Gateway 时仍需迁移 Cookie、CSRF、错误和路由边界，不符合既定的单一公网入口设计，因此不采用。

### 2.2 一次完成真实文本聊天链路

该方案最终调用方向正确，但当前 Conversation Service 和 Inference Gateway 的 HTTP 层均未完成。把 Gateway、Conversation 持久化、内部推理流和前端认证放进同一批，会失去独立测试和回滚边界，因此不采用。

### 2.3 Gateway 认证与只读模型目录纵切片

该方案先证明以下关键边界：

- 浏览器只访问 Gateway。
- Gateway 不解析或保存用户密码。
- Gateway 使用 `AW_SESSION` 向 Identity 同步确认会话。
- 原始 Session Cookie 不进入业务服务。
- Gateway 为每个下游和用途签发独立、短时、最小 scope 的 Service JWT。
- Model Registry 继续在自身服务内校验用户所有权。

该方案与现有设计一致，并且可以在 Conversation 后端完成前独立验收，因此采用。

## 3. 范围

### 3.1 本批交付

- 新建 `services/gateway-service` Java 21、Spring Boot 4.1.0、Spring WebFlux 模块。
- Gateway 默认监听 `8080`，只向浏览器暴露同源 `/api/v1`。
- 透明代理 Identity 的六个现有公共认证操作。
- 加载部署挂载的 RSA 私钥与公钥，签发 RS256 Service JWT。
- 发布不包含私钥材料的内部 JWKS。
- 从浏览器请求提取唯一 `AW_SESSION`，调用 Identity Session Introspection。
- 只代理 `GET /api/v1/model-registry/models`，不开放端点、凭据或模型写操作。
- 为 Model Registry 增加 `model-registry.model.read` scope；现有 `model-registry.model.manage` 保持兼容。
- 建立 Gateway 错误、关联 ID、请求大小、超时和安全 Header 基线。
- 编写中文开发说明、模块测试和跨服务契约验证。

### 3.2 明确不交付

- Conversation Service 的 HTTP、JPA、Redis、Outbox 和真实推理流。
- `/api/v1/conversations/**` 与 `/api/v1/generations/**` 的生产代理。
- Model Registry 的端点、凭据和模型写操作代理。
- 管理端 API 聚合和 `admin-web`。
- 邮箱验证、密码找回、附件、图片和视频能力。
- 通用浏览器业务写操作的 Gateway CSRF 校验。本批除 Identity 自有认证写操作外，只开放只读业务接口；Identity 继续校验注册、登录和注销的 CSRF。
- Service JWT 私钥在线生成、远程托管或自动轮换。V1 只加载部署提供的密钥文件。

## 4. 模块与技术边界

### 4.1 Gateway Service

模块路径固定为：

```text
services/gateway-service
```

包根固定为：

```text
io.github.yanhuo218.autumnwind.gateway
```

依赖仅包括当前交付所需组件：

- `spring-boot-starter-webflux`
- `spring-boot-starter-security`
- `spring-security-oauth2-jose`
- `spring-boot-starter-validation`
- `spring-boot-starter-actuator`
- Spring Boot Test、WebFlux Test 与 Spring Security Test

不引入 Spring Cloud Gateway。认证路由数量固定且较少，使用明确的 WebFlux Handler 与 WebClient 更容易限制路径、Header、正文大小和错误映射，也避免新增 Spring Cloud 与 Spring Boot 版本兼容矩阵。

Gateway 不连接 PostgreSQL、Redis、RabbitMQ 或对象存储，不保存业务事实和浏览器会话。

### 4.2 下游地址

下游地址只从服务端配置读取，不进入浏览器构建产物：

- `GATEWAY_IDENTITY_BASE_URL`
- `GATEWAY_MODEL_REGISTRY_BASE_URL`

两个地址必须是绝对 `http` 或 `https` URI，禁止用户信息、Query 和 Fragment。生产部署必须使用内部 HTTPS；允许本地测试使用回环 HTTP。Gateway 不接受浏览器提交下游地址。

### 4.3 超时与正文限制

- Identity 普通认证请求总超时：5 秒。
- Session Introspection 总超时：2 秒。
- Model Registry 只读请求总超时：5 秒。
- 登录与注册请求正文最大 16 KiB。
- 下游 JSON 响应最大 1 MiB。
- 超时、连接失败、非法媒体类型和超限响应均失败关闭。

密码、Session Cookie、CSRF Token、Service JWT 和下游 Authorization Header 不得进入日志、异常消息、指标标签或 Trace 属性。

## 5. 路由设计

### 5.1 Identity 公共认证代理

Gateway 只允许以下精确方法与路径：

| 方法 | 路径 | 认证要求 |
| --- | --- | --- |
| `GET` | `/api/v1/auth/csrf` | 匿名 |
| `GET` | `/api/v1/auth/registration-options` | 匿名 |
| `POST` | `/api/v1/auth/registrations` | Identity 校验 CSRF |
| `POST` | `/api/v1/auth/sessions` | Identity 校验 CSRF |
| `GET` | `/api/v1/auth/session` | Identity 校验 `AW_SESSION` |
| `DELETE` | `/api/v1/auth/session` | Identity 校验 Session 与 CSRF |

Gateway 保持路径、方法、状态码和 JSON 响应语义，不复制 Identity DTO，也不解释密码字段。

允许向 Identity 转发的请求 Header 只有：

- `Accept`
- `Content-Type`
- `Cookie`
- `X-CSRF-TOKEN`
- `X-Correlation-ID`
- W3C Trace Header

Gateway 必须删除浏览器提交的 `Authorization`、`Forwarded`、`X-Forwarded-*`、`X-Actor-User-Id`、`X-User-*` 和其他内部身份 Header。可信代理 Header 只能由部署入口和 Gateway 自己生成。

允许返回浏览器的响应 Header 只有：

- `Content-Type`
- `Set-Cookie`
- `X-CSRF-TOKEN`
- `X-Correlation-ID`
- `Retry-After`
- 安全缓存 Header

`Set-Cookie` 必须原样保留 Identity 设置的 host-only `AW_CSRF` 与 `AW_SESSION` 属性。Gateway 不读取 CSRF Cookie 内容，也不重写 Session Cookie。

### 5.2 只读模型目录代理

本批只开放：

```text
GET /api/v1/model-registry/models
```

Gateway 执行以下顺序：

1. 校验请求中只有一个非空 `AW_SESSION` Cookie。
2. 生成或接受合法 `X-Correlation-ID`。
3. 使用面向 `identity-service` 的短时 Service JWT 调用 Session Introspection。
4. 要求结果为 `active=true`、`accountStatus=ACTIVE` 且 `expiresAt` 晚于当前时间。
5. 使用面向 `model-registry-service` 的新 Service JWT 调用 Model Registry。
6. Token 仅包含 `model-registry.model.read` scope 与来自 introspection 的 `actor_user_id`。
7. 不向 Model Registry 转发浏览器 Cookie、CSRF Header、浏览器 Authorization 或用户自报身份 Header。
8. 原样返回符合契约的状态码、JSON 正文、`Content-Type` 与关联 ID。

Model Registry 仍负责按 `actor_user_id` 查询当前用户的模型。Gateway 不读取 Registry 数据库，也不在内存中按用户二次过滤响应。

## 6. Session Introspection

Gateway 调用现有接口：

```text
POST /internal/v1/auth/session-introspections
```

请求体只包含：

```json
{
  "sessionValue": "<REDACTED>"
}
```

原始 Session 值只允许存在于浏览器到 Gateway、Gateway 到 Identity 的本次调用内存中。不得缓存、持久化或加入 `toString()`。

第一版不缓存 active 结果，保证账户禁用与会话撤销即时生效。后续只有在测量证明 Identity 成为瓶颈后，才允许引入不超过 15 秒的有界缓存，并且必须设计主动撤销失效机制。

结果处理规则：

- 缺失、空白或重复 `AW_SESSION`：返回 `401`，不调用 Identity。
- `active=false`：返回 `401`。
- `active=true` 但缺少用户、角色、账户状态或过期时间：按协议错误返回 `502`。
- 账户状态不是 `ACTIVE` 或会话已过期：返回 `401`。
- Identity 超时、不可达或返回非法媒体类型/JSON：返回 `503`。

Gateway 不把 Identity 的内部响应正文、Service JWT 或 Session 值放入公共错误。

## 7. Service JWT 与 JWKS

### 7.1 密钥来源

Gateway 从部署挂载的 PEM 文件加载密钥：

- `GATEWAY_SERVICE_JWT_PRIVATE_KEY_PATH`：PKCS#8 RSA 私钥。
- `GATEWAY_SERVICE_JWT_PUBLIC_KEY_PATH`：X.509 SubjectPublicKeyInfo RSA 公钥。
- `GATEWAY_SERVICE_JWT_KEY_ID`：1 至 128 个无空白字符的稳定 Key ID。

仓库不生成、保存或提交任何真实密钥。启动时必须验证公私钥匹配、RSA 至少 2048 位且 Key ID 合法；验证失败时拒绝启动。

### 7.2 Token 规则

所有 Gateway Service JWT 固定：

- 算法：`RS256`。
- `iss`：`GATEWAY_SERVICE_JWT_ISSUER`。
- `sub`：`gateway-service`。
- `aud`：单个目标服务 Audience。
- `iat`：当前时间。
- `exp`：`iat + 60 秒`。
- `jti`：每次签发的新 UUID。
- `scope`：以空格分隔的最小 scope 集合。

调用 Identity Introspection 时：

```text
aud = identity-service
scope = identity.session.introspect
```

调用 Model Registry 读取模型时：

```text
aud = model-registry-service
scope = model-registry.model.read
actor_user_id = <introspection userId>
```

Introspection Token 在身份确认前不得携带 `actor_user_id`。不同 Audience、scope 或 actor 的 Token 不复用，也不缓存到请求范围之外。

### 7.3 JWKS

Gateway 发布：

```text
GET /internal/v1/security/jwks
```

响应只包含当前 RSA 公钥的 `kty`、`kid`、`use=sig`、`alg=RS256`、`n` 和 `e`，不得包含 `d`、`p`、`q` 或其他私钥字段。响应使用 `application/json` 和 `Cache-Control: public, max-age=300`。

JWKS 是公钥材料，不要求浏览器会话或 Service JWT。部署入口不得把 `/internal/**` 暴露为产品 API；下游服务通过内部 HTTPS 地址读取。

V1 每次只发布一个活动 Key。轮换时先部署能够同时信任新 Key 的下游配置，再切换 Gateway Key，最后等待旧 Token 最大寿命结束；自动多 Key 轮换不属于本批。

## 8. Model Registry 最小权限调整

新增稳定 scope：

```text
model-registry.model.read
```

授权规则调整为：

- `GET /api/v1/model-registry/models`
- `GET /api/v1/model-registry/models/{modelId}`

接受 `model-registry.model.read` 或既有 `model-registry.model.manage`，并继续要求合法 `actor_user_id`。

以下操作仍只接受 `model-registry.model.manage`：

- `POST /api/v1/model-registry/models`
- `PUT /api/v1/model-registry/models/{modelId}`

端点、凭据、连接测试和内部推理解析 scope 不变。Gateway 第一版只代理模型列表，不代理按 ID 读取和任何写操作。

## 9. 关联 ID 与公共错误

Gateway 接受满足公共契约的 `X-Correlation-ID`：长度 16 至 64，字符仅为字母、数字、点、下划线和短横线。缺失或非法时生成新的不透明标识。

Gateway 自身错误使用：

| 语义 | 状态 | 错误码 |
| --- | --- | --- |
| 浏览器会话无效 | `401` | `AW-GATEWAY-AUTH-0001` |
| 请求方法或路径不允许 | `404/405` | `AW-GATEWAY-ROUTING-0001` |
| 请求正文过大 | `413` | `AW-GATEWAY-VALIDATION-0001` |
| Identity 不可用 | `503` | `AW-GATEWAY-DEPENDENCY-0001` |
| Model Registry 不可用 | `503` | `AW-GATEWAY-DEPENDENCY-0002` |
| 下游响应协议无效 | `502` | `AW-GATEWAY-DEPENDENCY-0003` |
| Gateway 内部错误 | `500` | `AW-GATEWAY-INTERNAL-0001` |

公共错误只返回 `code`、稳定中文 `message` 和 `correlationId`。Gateway 不返回下游地址、内部路径、堆栈、Token、Cookie 或下游原始错误正文。

Identity 和 Model Registry 返回的合法公共业务错误可以保持原状态与错误码；非法错误结构统一转换为 Gateway 协议错误。

## 10. Web 安全基线

- Gateway 默认拒绝未声明路由。
- 不启用浏览器 Basic Auth、Form Login 或服务端 HttpSession。
- 认证代理的 CSRF 由 Identity 现有安全链验证，Gateway 不关闭或伪造该验证。
- Model Registry 代理只有 GET，不接受请求正文，不需要 CSRF。
- CORS 默认关闭；生产使用同源部署。若未来开放跨域，必须新增独立规格。
- Gateway 给浏览器响应设置 `X-Content-Type-Options: nosniff`、`Referrer-Policy: no-referrer`、合理的 `Content-Security-Policy` 和 `Permissions-Policy`。
- `Cache-Control: no-store` 用于 Session、登录和包含用户数据的响应。
- Gateway 不信任浏览器提交的 `Forwarded` 或身份 Header。
- Actuator 只暴露 `health` 和 `info`；详细健康信息不向匿名公网返回。

## 11. 前端与开发环境边界

本设计不修改用户端页面，但为下一批前端认证接入固定以下边界：

- 浏览器继续只请求同源 `/api/v1` 并使用 `credentials: include`。
- Identity 类型与运行时 Schema 必须从 `identity.openapi.json` 生成，不能在前端手写第二份 DTO。
- 登录、注册和注销在每次提交前重新请求 `/api/v1/auth/csrf`，只在当前内存调用中使用返回值。
- 密码、CSRF 值和 Session 值不得进入 URL、Query Key、Web Storage、日志、截图或错误文本。
- 未认证访问聊天路由时，必须在发出会话、模型或生成请求前跳转登录。
- 现有 Conversation Mock 测试保持独立；Gateway 认证 E2E 不得声称已验证真实 Conversation 链路。

开发模式允许 Vite 按精确前缀组合代理：认证与真实模型目录到 Gateway，Conversation 路径继续到 Mock。该混合模式只能用于开发和测试，生产部署必须让整个 `/api/v1` 由 Gateway 接收。

浏览器认证联调必须使用 HTTPS 页面，确保 Chromium 实际接受 `Secure` 的 `AW_CSRF` 和 `AW_SESSION`。下一批前端认证计划应让 Vite 从本地证书文件路径读取证书，或把 Vite 放在本地 HTTPS 反向代理后；证书和私钥不得提交仓库。禁止通过开发配置移除 Cookie 的 `Secure`、`HttpOnly` 或 `SameSite` 属性。Gateway 的 WebTestClient 测试可以使用进程内 HTTP，因为它不用于证明浏览器 Cookie 行为。

## 12. 测试策略

### 12.1 单元测试

- RSA PEM 加载、公私钥匹配、最小位数和 Key ID 校验。
- JWT 的算法、Issuer、Subject、Audience、scope、actor、`iat`、`exp` 和 `jti`。
- Introspection Token 不包含 actor；Registry Token 必须包含 actor。
- JWKS 不含任何私钥字段。
- 关联 ID 接受、拒绝和生成规则。
- 唯一 Session Cookie 提取规则。
- 公共错误不会包含下游正文或敏感字段。

### 12.2 Gateway HTTP 测试

使用 WebTestClient 与本地假下游服务器覆盖：

- 六条认证路径的方法与路径白名单。
- Cookie、CSRF、关联 ID 和 `Set-Cookie` 的正确透传。
- 浏览器 Authorization、伪造 actor 和转发 Header 被删除。
- 登录与注册正文超限返回 `413`，且请求不发给 Identity。
- 无 Session、重复 Session、inactive、过期和非 ACTIVE 状态均返回 `401`。
- Identity 超时、非法 JSON 和错误媒体类型分别返回稳定 Gateway 错误。
- 模型列表请求携带最小 Registry JWT，并且不携带浏览器 Cookie。
- Model Registry 合法公共错误保持语义，非法错误转换为 `502`。

测试中的 RSA 密钥只在测试进程内生成或使用固定无生产价值夹具，不得复制到文档或生产配置。

### 12.3 Model Registry 回归

- 只读 Token 可以列出和读取自己的模型。
- 只读 Token 不能创建或更新模型。
- 既有管理 Token 仍可读写模型。
- 缺失、非法或其他用户 actor 仍被拒绝。

### 12.4 项目验证

完成实现后至少运行：

```powershell
pwsh -NoProfile -File scripts/verify-contracts.ps1
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/gateway-service -am test
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/model-registry-service -am test
mvn "-Dmaven.repo.local=$PWD\.m2\repository" test
pnpm check
pnpm test
git diff --check
```

## 13. 提交边界

实现建议拆为以下独立提交：

1. `docs: 固化Gateway认证基础设计`
2. `build: 建立Gateway服务与密钥边界`
3. `feat: 实现Identity认证透明代理`
4. `feat: 接入浏览器会话校验`
5. `feat: 提供只读模型目录代理`
6. `test: 验证Gateway认证安全边界`

每次提交前检查暂存文件，不包含 `.env`、PEM、JWK 私钥字段、Token、Cookie、密码、代理配置或本地测试缓存。

## 14. 完成标准

本批只有同时满足以下条件才算完成：

1. Gateway 可以透明代理六个 Identity 公共认证操作。
2. `AW_SESSION` 只发送给 Identity 的认证或 Introspection 接口，不进入 Model Registry。
3. Gateway 可以从部署密钥签发 RS256、60 秒、限定 Audience 与 scope 的 Service JWT。
4. JWKS 只发布公钥字段。
5. 有效浏览器会话可以通过 Gateway 读取该用户的模型列表。
6. inactive、过期、撤销、重复 Cookie 和下游故障全部失败关闭。
7. `model-registry.model.read` 不能执行任何模型写操作。
8. Gateway 与 Registry 定向测试、根 Maven 测试、契约校验和差异检查全部通过。
9. 日志、错误、文档和测试产物不包含真实敏感信息。
10. 文档明确说明真实 Conversation 链路仍未完成，不把 Mock 结果作为生产验收证据。
