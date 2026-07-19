# Inference Gateway 内部推理入口

## 调用链与内部路径

内部文本推理链固定为：

```text
Conversation Service -> Inference Gateway -> Model Registry -> Provider
```

Conversation Service 仅调用 `POST /internal/v1/inference/chat-completions`。请求使用 `application/json`，响应使用 `application/x-ndjson`；调用方只能提供平台标准的用户、模型、生成、调用尝试和消息字段，不能提交服务商模型标识、端点地址、API Key 或 Authorization Header。Inference Gateway 使用 `actor_user_id` 与模型标识调用 `POST /internal/v1/model-registry/inference-target-resolutions` 解析固定推理目标，再在本次调用内临时解密凭据并访问 Provider。

成功时每一行都是一个独立的标准推理事件：`start`、`reasoning`、`text_delta`、`usage`、`done`。Provider 是否流式由 Registry 快照确定；两种模式都向上游输出相同的 NDJSON 序列。`GET /internal/v1/security/jwks` 仅公开 Inference Gateway 的 JWK，供 Model Registry 验证其出站 Token。最终反向代理必须拒绝所有外部 `/internal/**` 请求。

## JWT Claims

Conversation Service 到 Inference Gateway 的入站 Token 固定使用 RS256，且必须满足：

| Claim | 要求 |
| --- | --- |
| `iss` | 与配置的 Conversation Service Issuer 完全一致 |
| `sub` | 固定为 `conversation-service` |
| `aud` | 唯一值 `inference-gateway` |
| `scope` | 包含 `inference.chat.invoke` |
| `actor_user_id` | 规范 UUID，且等于请求中的用户标识 |
| `iat`、`exp`、`jti` | 必填，最长寿命为 60 秒 |

算法、签名、Issuer、Audience、Subject 或时间校验失败返回 `401`；scope 或 actor 不符合返回 `403`。内部网络位置不能替代 JWT 校验。

Inference Gateway 到 Model Registry 的出站 Token 也固定为 RS256，`sub` 为 `inference-gateway-service`，`aud` 为 `model-registry-service`，最大寿命为 60 秒。推理目标解析使用 `model-registry.inference.resolve` scope 且带规范 `actor_user_id`；连接测试 Worker 使用 `model-registry.connection-test.execute` scope。Model Registry 的内部路由只信任这套独立配置，不能与公共业务路由共用宽泛 Decoder。

## 环境变量

以下是 Inference Gateway 内部入口使用的全部环境变量。未列出默认值的变量必须显式提供；私钥和主密钥只通过只读文件挂载，不能进入环境变量内容、镜像、仓库或日志。

| 环境变量 | 用途与约束 |
| --- | --- |
| `INFERENCE_GATEWAY_SERVER_PORT` | 服务端口，默认 `8083`。 |
| `INFERENCE_GATEWAY_HTTP_REQUEST_MAX_BYTES` | 内部 JSON 请求体上限，默认 `1048576`，硬上限 1 MiB。 |
| `INFERENCE_GATEWAY_SECRET_STORE_MASTER_KEY_FILE` | AES 主密钥 Base64 文件的只读路径，必填。 |
| `INFERENCE_GATEWAY_SECRET_STORE_KEY_ID` | AES 主密钥版本标识，必填。 |
| `INFERENCE_GATEWAY_SERVICE_JWT_ISSUER` | 出站 JWT Issuer，必填 HTTPS URI。 |
| `INFERENCE_GATEWAY_SERVICE_JWT_PRIVATE_KEY_PATH` | 出站 JWT 的 PKCS#8 私钥只读路径，必填。 |
| `INFERENCE_GATEWAY_SERVICE_JWT_PUBLIC_KEY_PATH` | 出站 JWT 的 X.509 公钥只读路径，必填。 |
| `INFERENCE_GATEWAY_SERVICE_JWT_KEY_ID` | 出站 JWT Key ID，必填且不是秘密值。 |
| `INFERENCE_GATEWAY_SERVICE_JWT_LIFETIME` | 出站 JWT 寿命，默认 `PT30S`，最大 `PT60S`。 |
| `INFERENCE_GATEWAY_MODEL_REGISTRY_BASE_URL` | Registry 内部基础地址，必填绝对 URI；生产必须 HTTPS。 |
| `INFERENCE_GATEWAY_MODEL_REGISTRY_TIMEOUT` | Registry 总超时，默认 `PT5S`，范围 `PT1S..PT30S`。 |
| `INFERENCE_GATEWAY_MODEL_REGISTRY_ALLOW_LOOPBACK_HTTP_FOR_TEST` | 仅测试可设为 `true`，且只允许回环 HTTP；生产保持 `false`。 |
| `INFERENCE_GATEWAY_CONVERSATION_JWT_ISSUER` | 入站 Conversation Service Issuer，必填 HTTPS URI。 |
| `INFERENCE_GATEWAY_CONVERSATION_JWT_JWK_SET_URI` | 入站 JWT 的 JWKS 地址，必填 HTTPS URI。 |
| `INFERENCE_GATEWAY_CONVERSATION_JWT_MAXIMUM_LIFETIME` | 入站 JWT 最大寿命，默认 `PT60S`，不得超过该值。 |

与该入口配套的 Model Registry 内部信任变量：

| 环境变量 | 用途与约束 |
| --- | --- |
| `MODEL_REGISTRY_INFERENCE_JWT_ISSUER` | 验证 Inference Gateway 出站 Token 的 Issuer，必填 HTTPS URI。 |
| `MODEL_REGISTRY_INFERENCE_JWT_AUDIENCE` | 内部 Audience，默认 `model-registry-service`。 |
| `MODEL_REGISTRY_INFERENCE_JWT_JWK_SET_URI` | Inference Gateway 公共 JWKS 地址，必填 HTTPS URI。 |
| `MODEL_REGISTRY_INFERENCE_JWT_ALLOWED_CALLERS` | 内部 Subject，默认且只能为 `inference-gateway-service`。 |
| `MODEL_REGISTRY_INFERENCE_JWT_MAXIMUM_LIFETIME` | 内部 Token 最大寿命，默认 `PT60S`。 |
| `MODEL_REGISTRY_SECRET_STORE_MASTER_KEY_FILE` | 与 Inference Gateway 共享的 AES 主密钥只读路径。 |
| `MODEL_REGISTRY_SECRET_STORE_KEY_ID` | 必须与 Inference Gateway 的 AES Key ID 完全一致。 |

部署示例只使用以下占位值：

```text
YOUR_PRIVATE_KEY_PATH_HERE
YOUR_PUBLIC_KEY_PATH_HERE
YOUR_MASTER_KEY_FILE_HERE
https://inference.example.invalid
```

## AES 共享与 RSA 隔离

Model Registry 与 Inference Gateway 必须共享同一份 32 字节 AES 主密钥材料和完全相同的 Key ID，才能打开 Registry 保存的加密凭据信封。该材料只能挂载给这两个服务；Gateway、Conversation Service、前端和反向代理不得读取。

RSA 私钥按服务隔离：Gateway、Conversation Service 和 Inference Gateway 各自使用独立私钥、Issuer、Subject 和权限范围。Inference Gateway 私钥仅用于签发给 Model Registry 的短时 Token；启动时校验 RSA 公私钥匹配，JWKS 只公开字段。API Key 仅在单次 Provider attempt 中临时解密，成功、错误和取消均关闭临时凭据并清零明文字节。

## HTTP、NDJSON、取消与限制

事件流建立前，JSON 或字段错误返回 `400`，Token 缺失或无效返回 `401`，scope 或 actor 不符合返回 `403`，不接受 NDJSON 返回 `406`，Content-Type 错误返回 `415`，正文超过 1 MiB 返回 `413`，未处理错误返回 `500`。这些响应不得包含 Provider 原始错误详情。

事件流开始后，Registry、凭据、网络和 Provider 错误使用稳定的 `InferenceEvent.Error` NDJSON 行，不再切换 HTTP 状态。错误事件只包含错误码、关联标识与 `retryable`，随后结束，不再输出文本或 `done`。

调用方断开内部流时，取消必须传递到 Provider，并释放连接、DNS Resolver 与临时凭据。NDJSON 遵循 Reactor 背压，不能无界聚合 Provider 流。请求正文上限为 1 MiB；Registry 快照中的 Provider 请求超时范围为 1 到 120 秒，该值构成从凭据解析、DNS 校验到全部 Provider attempt 的单次调用绝对截止时间。Provider 首帧前的 `429`、`502`、`503`、`504` 与连接失败最多重试两次，每次都重新解密凭据并重新校验 DNS，但不会延长绝对截止时间。截止时间耗尽、Provider 首帧后的错误、认证错误、参数错误和目标拒绝不重试。

日志、指标、Trace、异常、HTTP 响应和测试快照不得记录 API Key、Token、Authorization Header、完整端点 URL、密钥路径、密钥内容或服务商原始错误正文。

## 验证命令

从仓库根目录运行：

```powershell
mvn -pl services/inference-gateway -am "-Dtest=InferenceSensitiveDataTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
mvn -pl services/inference-gateway -am test
mvn -pl services/model-registry-service -am test
pwsh -NoProfile -File scripts/verify-contracts.ps1
mvn test
git diff --check
```

Model Registry 的既有 PostgreSQL 环境测试可按其现有条件跳过；其他验证不应因环境跳过。

## Compose 待完成项

本批固定服务间路径、JWT、密钥材料和资源边界，但最终 Docker Compose 尚待完成。后续需要统一确定内部 TLS 终结位置、服务 DNS、证书 SAN、truststore 类型、只读密钥挂载、就绪依赖与外部端口暴露策略。Inference Gateway 的内部端口不得直接发布到外部网络。
