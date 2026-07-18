# Inference Gateway 设计

## 1. 目标与范围

本设计实现阶段 4 的 Inference Gateway：它是唯一允许访问用户自定义模型端点的业务服务网络出口，首批支持 OpenAI-compatible Chat Completions、连接测试和标准化文本流。

本阶段包含：

- 从 Model Registry 获取租户绑定的模型、端点和加密凭据快照。
- 在 Gateway 进程内临时解密 API Key，并在调用结束后清零明文字节。
- 对初始目标、每次 DNS 解析结果和每次允许的重定向执行 SSRF 校验。
- 将 OpenAI-compatible 请求映射为服务商请求，并把响应标准化为内部流事件。
- 执行连接测试任务，但不在 Gateway 保存业务任务状态。

本阶段不包含会话历史、消息持久化、附件投影、图片生成和视频生成。这些能力继续由后续 Conversation Service、File Service 和多模态批次接入。

## 2. 凭据交付方案

采用“加密信封跨服务、Gateway 临时解密”方案。

Model Registry 的内部解析接口返回模型和端点快照，以及 `EncryptedSecret` 所需的版本、密钥标识、Nonce、包装数据密钥和密文。接口不返回 API Key 明文，响应设置 `Cache-Control: no-store`，DTO 的字符串表示不得包含密文字节。

Inference Gateway 使用与 Registry 相同的 SecretStore 实现和主密钥，按以下固定上下文解密：

- `tenantId`：用户 UUID。
- `purpose`：`model-endpoint-api-key`。
- `ownerId`：端点 UUID。

解密结果只存在于单次调用作用域。调用完成、失败、取消或流断开时都清零明文字节，不写入日志、指标、Trace、异常或响应。

未采用的方案：

- Registry 解密后返回明文：实现较少，但让 API Key 经过服务间网络和序列化层，不符合最小明文暴露原则。
- 立即建设独立凭据服务：长期可替换本地 SecretStore，但超出 V1 当前切片，待 Vault/KMS 适配器出现时再引入。

## 3. 服务边界

### 3.1 Model Registry 内部解析

新增 `POST /internal/v1/model-registry/inference-target-resolutions`。请求包含 `ownerUserId` 和 `modelId`，只允许具有 `model-registry.inference.resolve` scope 的 `inference-gateway-service` 调用。

Registry 在单个只读事务中确认：

- 模型属于请求用户。
- 模型和端点均已启用。
- 模型接口类型为 `CHAT_COMPLETIONS`。
- 端点存在当前凭据。

响应固定模型版本、端点版本和凭据 ID，避免一次调用在配置更新期间混用新旧状态。内部接口与用户管理接口使用独立安全匹配器和 scope。

### 3.2 Inference Gateway

Gateway 使用 Spring WebFlux，内部拆分为以下组件：

- `InferenceTargetClient`：调用 Registry 内部解析接口，不解释密文。
- `EndpointCredentialResolver`：重建 `EncryptedSecret`、调用 SecretStore、管理明文字节生命周期。
- `OutboundTargetPolicy`：校验 URI、DNS 名称和全部解析地址。
- `PinnedAddressResolver`：只把本次校验通过的地址交给 Reactor Netty，消除“校验一次、连接时再次解析”的时间窗口。
- `OpenAiChatCompletionsAdapter`：映射请求、处理 SSE、标准化错误和流事件。
- `ConnectionTestWorker`：领取 Registry 任务并复用同一解析、凭据和网络路径。

## 4. SSRF 与网络规则

只允许无用户信息、无片段的 `https` URI。端口必须在 `1..65535`；未指定时使用 443。禁止以 URL、Header 或请求体覆盖最终 `Host`、`Authorization`、`Cookie`、转发类 Header和逐跳 Header。

每次网络尝试都解析主机的全部 A/AAAA 地址。只要其中一个地址落入禁止范围，整个目标即拒绝，不能只挑选公开地址继续调用。

禁止范围至少包括：

- IPv4/IPv6 unspecified、loopback、link-local、multicast 和私网。
- IPv4 `0.0.0.0/8`、`100.64.0.0/10`、`192.0.0.0/24`、`192.0.2.0/24`、`198.18.0.0/15`、`198.51.100.0/24`、`203.0.113.0/24`、`224.0.0.0/4` 和 `240.0.0.0/4`。
- IPv6 `::/128`、`::1/128`、`fc00::/7`、`fe80::/10`、`ff00::/8` 和文档地址 `2001:db8::/32`。
- IPv4-mapped IPv6 在转换为 IPv4 后命中任一禁止范围的地址。

Reactor Netty 禁用自动重定向。V1 只允许同源的 `307` 和 `308`，最多 3 次；每次都重新规范化 URI、重新解析 DNS、重新执行地址策略并固定连接地址。`301`、`302`、`303`、跨源重定向、协议降级和缺少合法 `Location` 均拒绝。

TLS 必须继续使用原始主机名执行 SNI 和证书主机名校验，不能因为固定目标 IP 而改用 IP 作为 TLS 身份。

## 5. 请求与流事件

首批内部请求只接受文本消息、可选 System Prompt、温度、最大输出长度和是否流式。Gateway 使用 Registry 中固定的 `providerModelId`，调用方不能提交另一个服务商模型 ID。

OpenAI-compatible 流响应标准化为：

- `start`：调用已建立，包含内部 attempt ID，不包含端点地址或凭据。
- `reasoning`：服务商显式提供的推理增量；模型未声明该能力时忽略该字段。
- `text_delta`：文本增量。
- `usage`：提示、生成和总 Token；服务商不提供时字段为空，不伪造数值。
- `error`：稳定错误码、可重试标识和关联 ID。
- `done`：完成原因。

不把未知服务商字段直接透传给 Conversation Service。非流响应转换为同一事件序列，保证上游只依赖一种内部协议。

## 6. 超时、重试与错误

连接、响应头和整体空闲超时均受端点配置限制。认证失败、参数错误、能力冲突、SSRF 拒绝和不受支持响应不重试。

只在尚未向上游发出任何内容时，对连接失败、`429`、`502`、`503` 和 `504` 最多重试 2 次，使用有界指数退避和 Jitter。流已经产生后发生错误时立即发送 `error` 并结束，不能重新调用造成重复文本。

错误按稳定类别标准化：目标被策略拒绝、DNS 失败、连接失败、超时、服务商认证失败、限流、服务商响应不合法、服务商错误和内部依赖错误。用户可见消息不包含完整 Base URL、解析 IP、响应正文、API Key 或 Authorization Header。

## 7. 连接测试任务

Registry 仍拥有任务状态。Gateway 使用带租约的内部领取接口获得固定的端点版本和凭据 ID，再通过 Registry 的任务解析接口取得对应快照。测试请求使用最小 Chat Completions 输入，并复用正式推理的凭据解析、SSRF、TLS、超时和错误标准化组件。

Gateway 只回写稳定结果码、开始/完成时间和被测试版本，不保存或回传服务商响应正文。租约过期后任务可以重新领取；通过任务版本和租约标识防止旧 Worker 覆盖新结果。

## 8. 测试策略

单元测试覆盖 URI 规范化、全部禁止地址范围、混合 DNS 结果、IPv4-mapped IPv6、重定向规则、密文 DTO 脱敏和明文字节清零。

契约测试覆盖 Registry 内部解析接口、Gateway 内部推理接口、标准化事件 Schema 和稳定错误码。

集成测试使用本地 Fake Provider 和可控 DNS 解析器，覆盖：

- 正常非流和 SSE 文本流。
- reasoning、usage、done 和畸形 SSE。
- 慢连接、响应头超时、流空闲超时、429 和 5xx。
- 初始私网目标、混合公网/私网 DNS、DNS 变化、同源 307/308、跨源和私网重定向。
- API Key 只出现在发往已验证服务商的 Authorization Header，日志和错误中没有凭据。

## 9. 交付顺序

1. Registry 内部推理目标解析契约与租户安全边界。
2. Inference Gateway 工程骨架、加密目标客户端和凭据生命周期。
3. SSRF URI/IP 策略与固定地址解析器。
4. OpenAI-compatible Chat Completions 适配器和标准化事件。
5. 连接测试任务租约、领取和结果回写。
6. Fake Provider 集成、安全和全量回归验证。
