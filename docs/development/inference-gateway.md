# Inference Gateway 开发说明

## 当前职责

Inference Gateway 是唯一允许访问用户模型端点的业务服务。当前文本推理链从 Model Registry 返回的固定 `InferenceTarget` 快照开始，依次完成命令与快照一致性校验、API Key 临时解密、目标地址安全校验、OpenAI-compatible Chat Completions 请求映射、服务商交换和标准推理事件解码。

当前仅支持文本 `CHAT_COMPLETIONS`。附件、多模态、会话存储和后台调度不在本模块当前实现范围内。仓库也没有可直接启动完整推理 HTTP 入口所需的 Registry、JWT 和 SecretStore Spring 装配。

Model Registry 已提供连接测试任务的领取、续租和结果回写能力，但任务尚未固定 `modelId` 和 `providerModelId` 来源。Gateway Worker 因此暂未实现；不得隐式选择最新、默认或任意模型。

## Registry 内部接口

- 推理目标解析：`POST /internal/v1/model-registry/inference-target-resolutions`，Service JWT 必须包含 `model-registry.inference.resolve` scope 和规范的 `actor_user_id`。
- 连接测试租约：`/internal/v1/model-registry/connection-test-jobs/` 下的领取、续租、成功和失败接口，Service JWT 必须包含 `model-registry.connection-test.execute` scope。该能力属于 Model Registry 已完成边界，不表示 Gateway Worker 已实现。

推理链必须使用 Registry 快照中的所有者、模型、端点、凭据引用、能力和 `providerModelId`。调用方不能覆盖服务商模型标识，快照与命令的 owner、model 或 capability 不匹配时，在解密和 Provider 调用前拒绝。

## 协议与出站安全

当前只允许 HTTPS 的 OpenAI-compatible Chat Completions。初始请求和每次 HTTP attempt 都重新解析目标 host，并校验 DNS 返回的全部地址；任一地址落入禁止范围即拒绝，不允许从多个结果中挑选一个公网地址绕过校验。

禁止范围包括未指定、私网、回环、链路本地、站点本地、共享地址、组播、保留和文档示例地址，以及对应的 IPv6 和 IPv4-mapped IPv6 范围。DNS 解析失败或没有结果同样拒绝。

传输层只连接本次校验得到的固定地址，TLS SNI 和证书主机名校验仍使用原始 host，避免连接阶段再次解析 DNS。只允许同源的 `307` 和 `308` 重定向，且每一跳都重新解析并校验全部 DNS；最多跟随 3 次重定向。`301`、`302`、`303`、跨源、HTTPS 降级、非法 `Location` 和第 4 次重定向均拒绝。Authorization 只在目标校验后发送，不会跨源转发。

端点请求超时取自 Registry 快照，合法范围为 1 到 120 秒。`429`、`502`、`503` 和 `504` 仅在尚未产生标准事件时最多重试 2 次，即最多 3 次 attempt；每次重试都重新解密临时凭据并重新校验 DNS。认证错误、参数错误、SSRF 拒绝和流开始后的解析或连接错误不重试。

## 凭据边界

API Key 以 `EncryptedSecret` 加密信封保存，并绑定用户、用途和端点上下文。Inference Gateway 只在单次 attempt 的资源作用域内通过 `SecretStore` 解密；成功、失败和取消都会关闭临时凭据并清零明文字节。

日志、指标、Trace、异常、HTTP 响应和测试快照不得记录 API Key、Authorization Header、完整端点 URL 或服务商原始错误正文。测试只能使用明显占位值，且不得访问真实凭据、真实模型端点或外部 DNS。

## 本地配置

当前 Inference Gateway 只存在 `INFERENCE_GATEWAY_SERVER_PORT`，默认值为 `8083`。该默认值与 Model Registry 的本地默认端口相同；并行启动时必须显式修改其中一个服务的端口。本批不修改默认端口，也不得编造 Registry、JWT 或 SecretStore 环境变量。

## 验证命令

专项集成测试从仓库根目录运行：

```powershell
mvn -o "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/inference-gateway -am "-Dtest=InferenceGatewayIntegrationTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

完整模块、公共契约和 Model Registry PostgreSQL 连接测试租约验证：

```powershell
mvn -o "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/inference-gateway -am test
./scripts/verify-contracts.ps1
./scripts/verify-model-registry-connection-test-postgres.ps1
```
