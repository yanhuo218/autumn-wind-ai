# Inference Gateway 内部入口与服务信任设计

## 1. 背景与目标

当前 Inference Gateway 已具备 OpenAI-compatible 文本适配器、加密凭据临时解密、SSRF 防护、固定地址连接和标准事件模型，但这些能力仍只能由进程内测试直接调用。服务缺少可部署的内部 HTTP 入口、生产 Bean 装配、入站 Service JWT 校验，以及调用 Model Registry 所需的出站 Service JWT。

本设计交付真实文本对话链路的第一段可部署依赖：

```text
Conversation Service -> Inference Gateway -> Model Registry -> Provider
```

本批完成后，受信任的 Conversation Service 可以通过内部流式接口发起一次文本推理；Inference Gateway 可以在不接触明文持久凭据的前提下解析用户模型、调用 Provider，并输出既有标准事件。

## 2. 范围

本批包含：

- Inference Gateway 内部文本推理 OpenAPI。
- JSON 请求和 NDJSON 标准事件流。
- Conversation Service 到 Inference Gateway 的入站 Service JWT 边界。
- Inference Gateway 到 Model Registry 的出站 Service JWT 和 JWKS。
- Model Registry 对 Gateway 与 Inference Gateway 的分离信任链。
- Inference Gateway 现有核心组件的生产 Bean 装配、健康检查和稳定错误。
- 请求限制、严格 JSON、取消传播、凭据清零和安全回归。

本批不包含：

- Conversation Service 的数据库迁移、公共 HTTP、SSE 重放或生成状态持久化。
- Gateway 的 Conversation 公共代理。
- 浏览器登录、CSRF 或真实聊天页面联调。
- 文件、图片、视频、RabbitMQ、Redis、MinIO 或管理端。
- 最终 Docker Compose。Compose 在真实文本链路完成后统一组装，本批只固定所需配置边界。

## 3. 方案比较与选择

### 3.1 依赖优先

先让 Inference Gateway 成为可部署内部服务，再实现 Conversation Service 和浏览器链路。每一层都可以用契约和受控 Fake Provider 独立验证，故障定位和回滚边界清晰。

该方案作为实施方案。

### 3.2 一次完成完整文本纵向链路

同时修改 Inference Gateway、Conversation Service、Gateway、Identity 和用户端，能够更早看到真实页面结果，但一次跨越多个安全边界、两种流协议和数据库状态机，评审与回滚范围过大，因此不采用。

### 3.3 Compose 优先并保留 Conversation Mock

可以较快得到可启动环境，但只能证明容器启动和 Mock 协议兼容，不能证明真实 Provider 调用、身份传递或持久化正确，后续还会重复调整网络与密钥配置，因此不采用。

## 4. 组件职责

### 4.1 Conversation Service

本批只作为未来调用方固定信任契约，不实现调用代码。它最终使用自己的 RSA 私钥签发短时 Service JWT，并把当前用户作为 `actor_user_id` 传入。它不能向 Inference Gateway 发送 Provider 模型 ID、端点 URL、API Key 或 Authorization Header。

### 4.2 Inference Gateway

Inference Gateway 负责：

- 校验调用方 Service JWT、scope、Audience、Subject 和 actor。
- 校验并规范化内部推理请求。
- 使用 actor 和 Registry 模型 ID 获取固定推理目标。
- 临时解密端点凭据，调用结束、失败或取消时清零明文字节。
- 执行现有 SSRF、DNS、TLS、重定向和 Provider 协议策略。
- 以 NDJSON 输出既有 `InferenceEvent`，不透传 Provider 私有字段。

Inference Gateway 不保存会话、消息、生成记录、文件或 Provider 响应正文。

### 4.3 Model Registry

Model Registry 继续拥有模型、端点、能力和加密凭据事实。它必须按路由使用不同的 Service JWT 信任配置：

- `/api/v1/model-registry/**` 保持现有外部调用方信任配置，主要服务于 Gateway 的用户管理和模型目录代理。
- `/internal/v1/model-registry/**` 信任 Inference Gateway，服务于推理目标解析和连接测试 Worker。

两类路由不能共用一个允许多个调用方的宽泛 Decoder。错误 Issuer、JWKS、Subject、Audience 或 scope 必须在进入 Controller 前失败。

## 5. 内部推理契约

### 5.1 路径与媒体类型

固定接口：

```text
POST /internal/v1/inference/chat-completions
Content-Type: application/json
Accept: application/x-ndjson
```

成功响应：

```text
200 OK
Content-Type: application/x-ndjson
Cache-Control: no-store
X-Content-Type-Options: nosniff
```

每一行是一个完整、独立的 `InferenceEvent` JSON 对象，以换行符结束。选择 NDJSON 而不是内部 SSE，是因为事件本身已经包含 `type`，服务间链路不需要浏览器 SSE 的 `id/event/data` 信封、自动重连或注释语义。公共浏览器 SSE 仍由后续 Conversation Service 负责事件 ID、重放和 `replay.reset`。

### 5.2 请求

请求只包含平台标准字段：

```json
{
  "ownerUserId": "00000000-0000-0000-0000-000000000000",
  "modelId": "00000000-0000-0000-0000-000000000000",
  "generationId": "00000000-0000-0000-0000-000000000000",
  "invocationAttemptId": "00000000-0000-0000-0000-000000000000",
  "messages": [
    {
      "role": "user",
      "content": "占位文本"
    }
  ],
  "systemPrompt": null,
  "temperature": null,
  "maxOutputTokens": null
}
```

约束如下：

- `ownerUserId`、`modelId`、`generationId` 和 `invocationAttemptId` 必须是规范 UUID。
- `ownerUserId` 必须与 Token 中规范的 `actor_user_id` 完全一致。
- `generationId` 关联 Conversation 业务事实；`invocationAttemptId` 标识本次内部调用和取消传播。Inference Gateway 不根据这两个字段读取其他服务数据库，也不把它们当作用户身份。
- `messages` 必须有 1 至 256 项。
- `role` 只允许 `user`、`assistant`；System Prompt 只使用独立的 `systemPrompt` 字段。
- 每条 `content` 必须非空；整个请求正文最大 1 MiB。
- `temperature` 可空；存在时必须在 `0..2`。
- `maxOutputTokens` 可空；存在时必须大于零且不超过服务端硬上限 131072。
- 该端点固定输出 NDJSON 标准事件，不接受调用方提交 `stream` 字段。Provider 是否使用 SSE 由 Registry 目标快照的 `streaming` 能力决定，调用方不能覆盖。
- 不允许未知字段、重复 JSON Key、尾随 JSON Token、Provider 模型 ID、端点、凭据或 Header。
- 关联 ID 只从 `X-Correlation-ID` 获取；格式固定为 `[A-Za-z0-9._-]{16,64}`，不接受正文覆盖。缺失或非法时生成新的 UUID 字符串。
- 服务端不截断或修复不合法输入，直接返回稳定验证错误。

应用服务先解析 Registry 目标快照，再把请求映射为现有 `ChatInferenceCommand`：`tenantId=ownerUserId`、`modelId` 和消息保持不变、`systemPrompt` 使用独立字段、`stream=target.capabilities.streaming`、`correlationId` 来自 Header。目标支持流式时 Adapter 消费 Provider SSE；目标不支持流式时 Adapter 消费普通 JSON，并转换为相同的 `start/text_delta/usage/done` 事件序列。两种模式对上游都输出 NDJSON。`generationId` 和 `invocationAttemptId` 进入内部调用上下文，用于 Trace、取消和晚到事件隔离，不进入 Provider 请求。Inference Gateway 不根据文本内容推断角色或 System Prompt。

### 5.3 事件

响应逐行输出既有 `contracts/events/inference-event.v1.schema.json` 定义的事件：

- `start`
- `reasoning`
- `text_delta`
- `usage`
- `error`
- `done`

合法成功流必须以一个 `start` 开始，并以一个 `done` 结束。失败流可以在首个事件位置输出一个 `error`，或在已经输出内容后输出一个 `error` 并结束。`error` 后不得继续输出 `done` 或文本。

Provider 未提供用量时保持 `null`，不能按文本长度估算 Token。`reasoning` 和 `text_delta` 的字符串表示必须脱敏，不能进入普通日志或异常。

## 6. HTTP 与流错误

以下错误在建立事件流前使用 HTTP 和公共内部错误响应：

| 场景 | 状态 |
| --- | --- |
| JSON、字段或参数不合法 | `400` |
| Service JWT 缺失、无效或 Subject 不符合 | `401` |
| scope 或 actor 不符合 | `403` |
| 不接受 NDJSON | `406` |
| Content-Type 错误 | `415` |
| 正文超过 1 MiB | `413` |
| 内部未处理错误 | `500` |

请求通过安全和结构校验后，目标解析、网络、Provider 或凭据错误统一通过既有 `InferenceEvent.Error` 表达，避免已提交 `200` 后切换 HTTP 语义。错误事件只能包含稳定错误码、关联 ID 和 `retryable`，不能包含 URL、IP、模型输入、Provider 正文、凭据、Header 或堆栈。

## 7. Service JWT 信任模型

### 7.1 Conversation Service 到 Inference Gateway

Token 固定要求：

- 算法：`RS256`。
- Issuer：部署配置的 Conversation Service Issuer。
- Subject：`conversation-service`。
- Audience：唯一值 `inference-gateway`。
- scope：包含 `inference.chat.invoke`。
- `actor_user_id`：规范用户 UUID。
- `iat`、`exp`、`jti`：必填。
- 最大寿命：60 秒。

Inference Gateway 只信任配置的 Conversation Service HTTPS JWKS。算法、签名、Issuer、Audience、Subject、时间和最大寿命在 `JwtDecoder` 中验证，失败统一返回 `401`；scope 和 actor 在授权层验证，失败返回 `403`。不能因为请求来自内部网络而放宽校验。

### 7.2 Inference Gateway 到 Model Registry

Token 固定要求：

- 算法：`RS256`。
- Issuer：部署配置的 Inference Gateway Issuer。
- Subject：`inference-gateway-service`。
- Audience：唯一值 `model-registry-service`。
- 推理目标解析 scope：`model-registry.inference.resolve`。
- 连接测试 scope：`model-registry.connection-test.execute`。
- 推理目标解析必须带规范 `actor_user_id`。
- `iat`、`exp`、`jti`：必填。
- 最大寿命：60 秒。

Inference Gateway 从只读挂载的 PKCS#8 私钥和 X.509 公钥加载独立 RSA 密钥，RSA 至少 2048 位；启动时验证公私钥匹配。它在 `/internal/v1/security/jwks` 只输出公开 JWK 字段，并设置短期公共缓存。最终反向代理必须拒绝所有外部 `/internal/**` 请求。

### 7.3 私钥隔离

Gateway、Conversation Service 和 Inference Gateway 各自拥有独立私钥。禁止为了简化 Compose 而共享私钥、把私钥写入环境变量、镜像、仓库或日志。代码可以复用签发与加载组件，但密钥、Issuer、Subject 和权限不能复用。

## 8. Model Registry 分离安全链

Model Registry 保留现有 Gateway 信任配置，并新增独立的 Inference Gateway 信任配置。安全过滤链按更精确路径排序：

1. `/internal/v1/model-registry/**` 使用 Inference Gateway Decoder。
2. `/api/v1/model-registry/**` 使用 Gateway Decoder。
3. 其他路径不由这两条链放行。

每个 Decoder 分别验证固定 Issuer、Audience、允许 Subject、最大寿命、RS256 和 HTTPS JWKS。Token 完整性或 Subject 失败返回 `401`，scope 或 actor 授权失败返回 `403`。不能使用一个 Decoder 接受多个 Issuer 后再只靠 scope 区分来源。

## 9. 生产 Bean 与运行配置

Inference Gateway 必须提供显式生产 Bean：

- 严格 Jackson `ObjectMapper`。
- `HostResolver`、`PublicAddressPolicy` 和 `OutboundTargetPolicy`。
- `SecretStore` 和 `EndpointCredentialResolver`。
- Provider `HttpClient`、`ProviderExchangeClient` 和 `OpenAiChatCompletionsAdapter`。
- Model Registry `WebClient` 和 `InferenceTargetClient`。
- 入站 `JwtDecoder`、出站 `ServiceJwtIssuer` 和公开 JWKS。
- 关联 ID、统一错误写入器和安全响应 Header。
- Actuator health/info。

生产配置只使用环境变量名和文件路径，不提供真实默认凭据。下游 Base URL 必须是无用户信息、Query 和 Fragment 的绝对 HTTPS URI，或仅在明确本地测试配置中允许回环 HTTP；不能接受任意内网 HTTP 地址。

### 9.1 Inference Gateway 稳定配置

| 环境变量 | 用途 | 默认或约束 |
| --- | --- | --- |
| `INFERENCE_GATEWAY_SERVER_PORT` | 服务端口 | 默认 `8083` |
| `INFERENCE_GATEWAY_CONVERSATION_JWT_ISSUER` | 入站 Conversation Issuer | 必填 HTTPS URI |
| `INFERENCE_GATEWAY_CONVERSATION_JWT_AUDIENCE` | 入站 Audience | 默认 `inference-gateway` |
| `INFERENCE_GATEWAY_CONVERSATION_JWT_JWK_SET_URI` | Conversation JWKS | 必填 HTTPS URI |
| `INFERENCE_GATEWAY_CONVERSATION_JWT_ALLOWED_CALLERS` | 允许的 Subject | 默认且只能包含 `conversation-service` |
| `INFERENCE_GATEWAY_CONVERSATION_JWT_MAXIMUM_LIFETIME` | 入站 Token 最大寿命 | 默认且不得超过 `PT60S` |
| `INFERENCE_GATEWAY_SERVICE_JWT_ISSUER` | 出站 Token Issuer | 必填 HTTPS URI |
| `INFERENCE_GATEWAY_SERVICE_JWT_PRIVATE_KEY_PATH` | 出站 PKCS#8 私钥 | 必填只读文件路径 |
| `INFERENCE_GATEWAY_SERVICE_JWT_PUBLIC_KEY_PATH` | 出站 X.509 公钥 | 必填只读文件路径 |
| `INFERENCE_GATEWAY_SERVICE_JWT_KEY_ID` | 出站 Key ID | 必填稳定非秘密标识 |
| `INFERENCE_GATEWAY_SERVICE_JWT_LIFETIME` | 出站 Token 寿命 | 默认且固定上限 `PT60S` |
| `INFERENCE_GATEWAY_MODEL_REGISTRY_BASE_URL` | Registry 内部地址 | 必填绝对 URI；生产 HTTPS，本地只允许回环 HTTP |
| `INFERENCE_GATEWAY_MODEL_REGISTRY_TIMEOUT` | Registry 总超时 | 默认 `PT5S`，范围 `PT1S..PT30S` |
| `INFERENCE_GATEWAY_SECRET_STORE_MASTER_KEY_FILE` | AES 主密钥 | 必填只读 Base64 文件路径 |
| `INFERENCE_GATEWAY_SECRET_STORE_KEY_ID` | 主密钥版本 | 默认 `local-v1` |
| `INFERENCE_GATEWAY_REQUEST_MAX_BYTES` | 内部请求正文上限 | 默认且硬上限 `1048576` |

Subject 固定为 `inference-gateway-service`，不由环境变量覆盖。所有配置 DTO 必须拒绝空白、危险 URI、超范围时长和不合法文件路径；字符串表示不得包含密钥文件内容或 Token。

`INFERENCE_GATEWAY_SECRET_STORE_MASTER_KEY_FILE` 和现有 `MODEL_REGISTRY_SECRET_STORE_MASTER_KEY_FILE` 必须指向同一份 32 字节 AES 主密钥材料，两个服务的 `SECRET_STORE_KEY_ID` 也必须完全一致。原因是当前 `AesGcmSecretStore` 只支持单活动 Key，并要求加密信封的 Key ID 与本地 Key ID 匹配。该 AES 材料只允许 Model Registry 和 Inference Gateway 读取，Gateway、Conversation Service、前端和反向代理不得挂载。它与各服务独立的 RSA 私钥不是同一种信任拓扑。

### 9.2 Model Registry 新增信任配置

现有 `MODEL_REGISTRY_SERVICE_JWT_*` 继续代表公共业务路由的外部调用方信任配置，不改变变量名、允许调用方或既有行为。内部路由新增：

| 环境变量 | 用途 | 默认或约束 |
| --- | --- | --- |
| `MODEL_REGISTRY_INFERENCE_JWT_ISSUER` | Inference Issuer | 必填 HTTPS URI |
| `MODEL_REGISTRY_INFERENCE_JWT_AUDIENCE` | 内部 Audience | 默认 `model-registry-service` |
| `MODEL_REGISTRY_INFERENCE_JWT_JWK_SET_URI` | Inference JWKS | 必填 HTTPS URI |
| `MODEL_REGISTRY_INFERENCE_JWT_ALLOWED_CALLERS` | 内部 Subject | 默认且只能包含 `inference-gateway-service` |
| `MODEL_REGISTRY_INFERENCE_JWT_MAXIMUM_LIFETIME` | 内部 Token 最大寿命 | 默认且不得超过 `PT60S` |

### 9.3 TLS 与本地测试

生产和最终 Compose 的 JWKS URI 保持 HTTPS。内部测试 CA 与 truststore 通过只读文件挂载，并使用 JVM 标准 truststore 参数配置；密码来自被忽略的 secret 文件或 Compose secret，不写入通用 YAML。单元和进程内 HTTP 测试使用注入的 `JwtDecoder`、`WebClient` 或回环地址，不新增通用的“允许任意 HTTP”开关。

Conversation Service 的签名私钥、Issuer、Key ID 和 JWKS 地址由下一批 Conversation 生产设计固定；本批只固定 Inference Gateway 的入站验证契约，不复用 Gateway 或 Inference Gateway 私钥。最终 Compose 设计再确定内部 TLS 终结者、服务 DNS、证书 SAN、truststore 类型和就绪依赖，不能由本批实现自行发明一套临时拓扑。

Inference Gateway 默认端口继续保持现有 `8083`，避免在本批破坏既有配置。最终 Compose 不向宿主机发布 Inference Gateway 端口，因此它与 Model Registry 的容器内部端口相同不会冲突；本地同时运行时通过 `INFERENCE_GATEWAY_SERVER_PORT` 显式改为其他端口。

## 10. 取消、背压与资源生命周期

- Conversation Service 断开内部流时，取消信号必须传播到 Provider 请求。
- 成功、错误和取消都必须关闭 `ResolvedCredential` 并清零明文字节。
- HTTP Client、DNS Resolver 和连接资源必须在取消后释放。
- 服务不能缓存原始 API Key、Authorization Header、消息正文或 Provider 响应正文。
- NDJSON 输出遵守 Reactor 背压，不把无限 Provider 流一次性聚合到内存。
- Registry 目标快照的 `endpointRequestTimeoutSeconds` 是一次 Provider 调用的总超时，合法范围保持 `1..120` 秒。
- Provider TCP 连接超时固定上限 10 秒；响应头超时为目标总超时与 30 秒的较小值；流空闲超时为目标总超时与 60 秒的较小值。
- 单个 Provider 数据帧最大 1 MiB；单次 Provider 响应累计最大 16 MiB。流式响应只累计计数，不聚合正文；超过上限后取消上游并输出稳定错误。
- 收到终态后丢弃晚到事件，不能向同一流输出第二个终态。

## 11. Docker Compose 边界

本批不创建最终 Compose，但固定以下部署前提：

- Inference Gateway 只连接内部 Model Registry 和外部 Provider。
- Inference Gateway、Model Registry 和未来 Conversation Service 只加入内部应用网络，不发布宿主机端口。
- 私钥、公钥和 AES 主密钥从被忽略的 `infra/.secrets/` 只读挂载。
- Model Registry 与 Inference Gateway 以不同容器路径只读挂载同一份 AES 主密钥，并配置相同 Key ID；其他服务不得挂载该文件。
- HTTPS JWKS 由内部 TLS 入口或服务自身 TLS 提供，Java truststore 显式挂载；不得在通用生产配置中放宽为 HTTP。
- Compose 健康检查使用 `/actuator/health`。
- 反向代理只公开用户 Web 和 Gateway 公共路由，拒绝 `/internal/**`。

最终 Compose 必须等 PostgreSQL 和内部 TLS/JWKS 入口健康后再启动依赖服务，并以 Model Registry、Inference Gateway、Conversation Service 和 Gateway 的健康检查作为后续依赖条件；容器启动状态本身不等于服务就绪。

V1 不声称支持 AES 主密钥在线轮换。轮换必须在后续独立设计中同时处理旧密钥读取、全部凭据重新加密、双服务切换和失败回滚；不能只替换其中一个服务的文件或 Key ID。

## 12. 测试策略

### 12.1 契约

- OpenAPI 声明唯一内部路径、严格请求、NDJSON 响应、所有 HTTP 错误和安全方案。
- 验证脚本同时校验 OpenAPI 与既有 Inference Event Schema。
- Schema 与 Java 事件序列化保持一致。

### 12.2 安全

- 缺失、过期、超长寿命、错误算法、Issuer、Audience、Subject、scope、actor 和 JWKS 的 Token 全部失败关闭。
- body actor 与 Token actor 不一致返回 `403`，且不调用 Registry。
- Model Registry 外部和内部路由分别拒绝来自错误发行方的合法 Token。
- JWKS 不含 `d/p/q/dp/dq/qi`。
- DTO、日志、错误和测试报告不含消息正文、Token、凭据或端点地址。

### 12.3 HTTP 与流

- 严格 JSON、重复 Key、尾随 Token、未知字段、错误媒体类型、正文超限和不接受 NDJSON。
- 正常 `start -> text_delta -> usage -> done`。
- reasoning、空用量、首事件前错误、中途错误、非法事件和晚到事件。
- 客户端取消后 Provider 取消、凭据清零和资源释放。
- 慢客户端不会造成无限缓冲。

### 12.4 集成

使用受控 Fake Model Registry 和 Fake HTTPS Provider 覆盖：

- 入站 Token 到 actor，再到 Registry 出站 Token 的身份连续性。
- 加密凭据解析、SSRF、防 DNS 变化、TLS 主机名和同源重定向。
- Provider 正常流、限流、5xx、慢流、畸形流和断开。
- 普通日志、错误和事件不包含测试凭据或响应正文。

## 13. 完成标准

1. Inference Gateway 可以作为 Spring Boot 服务启动并通过健康检查。
2. 符合配置的 Conversation Service Token 可以调用唯一内部文本流接口。
3. 不合法 Token、actor、请求和媒体类型在调用 Registry 或 Provider 前失败关闭。
4. Inference Gateway 使用自己的短时 Service JWT 调用 Model Registry。
5. Model Registry 对 Gateway 与 Inference Gateway 使用分离信任链。
6. 一次合法请求通过 Fake Registry 和 Fake Provider 输出符合 Schema 的 NDJSON 标准事件流。
7. 取消、错误和完成路径都证明凭据清零与资源释放。
8. 契约、定向测试、根 Maven、差异和敏感信息检查全部通过。
9. 文档明确本批尚未完成 Conversation 持久化、浏览器真实聊天或最终 Docker Compose。
