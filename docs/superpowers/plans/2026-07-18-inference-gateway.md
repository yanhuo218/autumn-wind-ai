# Inference Gateway 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**目标：** 交付可安全调用用户 OpenAI-compatible Chat Completions 端点的 Inference Gateway，并复用同一网络出口执行连接测试。

**架构：** Model Registry 通过内部 Service JWT 接口提供租户绑定的模型、端点和加密凭据快照；Inference Gateway 使用 SecretStore 临时解密。Gateway 通过独立 URI/IP 策略、逐次 DNS 校验和固定地址解析器阻止 SSRF，再由 OpenAI-compatible 适配器输出统一流事件。

**技术栈：** Java 21、Spring Boot 4.1.0、Spring WebFlux、Spring Security Resource Server、Reactor Netty、JUnit 5、Mockito、PostgreSQL 17。

## 全局约束

- 所有人工文档和代码注释使用简体中文。
- Inference Gateway 是唯一允许访问用户模型端点的业务服务。
- API Key 明文不得经过 Registry HTTP 响应，不得进入日志、指标、Trace、异常或测试快照。
- 每个网络尝试和每次重定向都重新校验全部 DNS 结果，并固定实际连接地址。
- V1 只支持 HTTPS、OpenAI-compatible Chat Completions、同源 307/308 和最多 3 次重定向。
- 不修改已发布的 Flyway 迁移；数据库变化只新增迁移。
- 所有行为变更按 RED、GREEN、REFACTOR 顺序执行。

---

### Task 1：Model Registry 内部推理目标解析

**文件：**

- 新建：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/application/inference/EncryptedCredentialEnvelope.java`
- 新建：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/application/inference/InferenceTargetResolutionService.java`
- 新建：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/application/inference/InferenceTargetView.java`
- 新建：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/InferenceTargetResolutionController.java`
- 新建：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/InferenceTargetResolutionRequest.java`
- 修改：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/infrastructure/configuration/ModelRegistrySecurityConfiguration.java`
- 修改：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/application/endpoint/ModelRegistryErrorCode.java`
- 新建：`contracts/openapi/model-registry-internal.openapi.json`
- 修改：`scripts/verify-contracts.ps1`
- 测试：`services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/application/inference/InferenceTargetResolutionServiceTest.java`
- 测试：`services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/InferenceTargetResolutionControllerTest.java`
- 测试：`services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/ModelRegistryInternalSecurityTest.java`

**接口：**

- 输入：`InferenceTargetResolutionService.resolve(UUID ownerUserId, UUID modelId)`。
- 输出：`InferenceTargetView`，包含模型、端点、能力、固定版本和 `EncryptedCredentialEnvelope`。
- HTTP：`POST /internal/v1/model-registry/inference-target-resolutions`，scope 为 `model-registry.inference.resolve`。

- [ ] **Step 1：编写应用层红灯测试**

测试必须证明启用的聊天模型返回固定快照，并分别拒绝未知租户模型、禁用模型、禁用端点、图片生成模型和缺少当前凭据。成功断言至少包含：

```java
InferenceTargetView view = service.resolve(OWNER_ID, MODEL_ID);

assertEquals(MODEL_ID, view.modelId());
assertEquals("provider-chat", view.providerModelId());
assertEquals(ENDPOINT_ID, view.endpointId());
assertEquals(0, view.modelVersion());
assertEquals(0, view.endpointVersion());
assertEquals(CREDENTIAL_ID, view.credentialId());
assertEquals("local-v1", view.credential().keyId());
```

- [ ] **Step 2：运行红灯**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/model-registry-service -am "-Dtest=InferenceTargetResolutionServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：因 `InferenceTargetResolutionService` 等类型不存在而编译失败。

- [ ] **Step 3：实现最小解析服务和脱敏密文信封**

密文 DTO 使用 Base64 字符串承载二进制字段，并覆盖字符串表示：

```java
public record EncryptedCredentialEnvelope(
        int version,
        String keyId,
        String wrappedDataKeyNonce,
        String wrappedDataKey,
        String payloadNonce,
        String ciphertext
) {
    public static EncryptedCredentialEnvelope from(EncryptedSecret secret) {
        Base64.Encoder encoder = Base64.getEncoder();
        return new EncryptedCredentialEnvelope(
                secret.version(),
                secret.keyId(),
                encoder.encodeToString(secret.wrappedDataKeyNonce()),
                encoder.encodeToString(secret.wrappedDataKey()),
                encoder.encodeToString(secret.payloadNonce()),
                encoder.encodeToString(secret.ciphertext())
        );
    }

    public EncryptedSecret toEncryptedSecret() {
        Base64.Decoder decoder = Base64.getDecoder();
        return new EncryptedSecret(
                version,
                keyId,
                decoder.decode(wrappedDataKeyNonce),
                decoder.decode(wrappedDataKey),
                decoder.decode(payloadNonce),
                decoder.decode(ciphertext)
        );
    }

    @Override
    public String toString() {
        return "EncryptedCredentialEnvelope[version=" + version + ", keyId=<REDACTED>]";
    }
}
```

解析服务在 `@Transactional(readOnly = true)` 中按租户读取模型和端点，完成启用状态、接口类型和凭据存在性校验后构造不可变视图。

- [ ] **Step 4：运行应用层绿灯**

执行 Step 2 命令，预期全部通过。

- [ ] **Step 5：编写 HTTP 与安全红灯测试**

测试 `200`、`Cache-Control: no-store`、未知字段 `400`、租户声明不一致 `403`、缺少专用 scope `403`，以及缺少 `actor_user_id` 时先于畸形正文解析返回 `403`。

- [ ] **Step 6：实现内部 Controller、安全匹配器和契约**

请求体固定为：

```java
public record InferenceTargetResolutionRequest(UUID ownerUserId, UUID modelId) {
    public InferenceTargetResolutionRequest {
        Objects.requireNonNull(ownerUserId, "用户标识不能为空。");
        Objects.requireNonNull(modelId, "模型标识不能为空。");
    }
}
```

Controller 必须确认 JWT 的规范 `actor_user_id` 与 `ownerUserId` 相同。OpenAPI 将密文字段标为 `writeOnly: false`、说明为“仅内部传输的加密信封”，并禁止未声明字段；响应声明 `Cache-Control`、`X-Correlation-ID` 和统一错误。

- [ ] **Step 7：运行 Task 1 验证并提交**

```powershell
./scripts/verify-contracts.ps1
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/model-registry-service -am "-Dtest=InferenceTargetResolutionServiceTest,InferenceTargetResolutionControllerTest,ModelRegistryInternalSecurityTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
git diff --check
```

```powershell
git add contracts/openapi/model-registry-internal.openapi.json scripts/verify-contracts.ps1 services/model-registry-service/src
git commit -m "feat: 提供推理目标内部解析契约"
```

---

### Task 2：Inference Gateway 骨架与 SSRF 领域策略

**文件：**

- 新建：`services/inference-gateway/pom.xml`
- 修改：`pom.xml`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/InferenceGatewayApplication.java`
- 新建：`services/inference-gateway/src/main/resources/application.yaml`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/HostResolver.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/JdkHostResolver.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/PublicAddressPolicy.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/OutboundTargetPolicy.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/TargetPolicyException.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/ValidatedTarget.java`
- 测试：`services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/security/PublicAddressPolicyTest.java`
- 测试：`services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/security/OutboundTargetPolicyTest.java`

**接口：**

- `HostResolver.resolve(String host): List<InetAddress>`。
- `PublicAddressPolicy.requirePublic(InetAddress address): void`。
- `OutboundTargetPolicy.validate(URI uri): ValidatedTarget`。

- [ ] **Step 1：建立模块并编写地址策略红灯测试**

参数化测试必须覆盖公网 IPv4/IPv6，以及设计文档列出的全部禁止范围、IPv4-mapped IPv6 和混合 DNS 结果。

```java
@ParameterizedTest
@ValueSource(strings = {"127.0.0.1", "10.0.0.1", "100.64.0.1", "169.254.169.254", "::1", "fc00::1"})
void 禁止非公网地址(String value) throws Exception {
    assertThrows(TargetPolicyException.class,
            () -> policy.requirePublic(InetAddress.getByName(value)));
}
```

- [ ] **Step 2：运行红灯**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/inference-gateway -am "-Dtest=PublicAddressPolicyTest,OutboundTargetPolicyTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

预期：模块或策略类型不存在。

- [ ] **Step 3：实现模块和最小策略**

`ValidatedTarget` 必须保存规范 URI 和不可变地址副本：

```java
public record ValidatedTarget(URI uri, List<InetAddress> addresses) {
    public ValidatedTarget {
        Objects.requireNonNull(uri, "目标 URI 不能为空。");
        addresses = List.copyOf(addresses);
        if (addresses.isEmpty()) {
            throw new IllegalArgumentException("目标地址不能为空。");
        }
    }
}
```

CIDR 匹配使用地址字节和前缀长度，不通过字符串前缀判断。域名解析结果只要有一个不公开地址即拒绝整个目标。

- [ ] **Step 4：运行绿灯并提交**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/inference-gateway -am test
git diff --check
git add pom.xml services/inference-gateway
git commit -m "feat: 建立推理网关与SSRF策略"
```

---

### Task 3：凭据解析、Registry 客户端与固定地址传输

**文件：**

- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/registry/InferenceTargetClient.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/registry/InferenceTarget.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/registry/ModelRegistryWebClient.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/credentials/EndpointCredentialResolver.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/credentials/ResolvedCredential.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/transport/PinnedAddressResolverGroup.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/transport/ProviderRequest.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/transport/ProviderFrame.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/transport/ProviderExchangeClient.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/transport/ReactorNettyProviderExchangeClient.java`
- 测试：`services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/credentials/EndpointCredentialResolverTest.java`
- 测试：`services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/transport/PinnedAddressResolverGroupTest.java`
- 测试：`services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/transport/RedirectPolicyTest.java`

**接口：**

- `InferenceTargetClient.resolve(UUID ownerUserId, UUID modelId, String correlationId): Mono<InferenceTarget>`。
- `EndpointCredentialResolver.withCredential(InferenceTarget target, Function<ResolvedCredential, Mono<T>> action): Mono<T>`。
- `ProviderExchangeClient.exchange(ValidatedTarget target, ProviderRequest request): Flux<ProviderFrame>`。

- [ ] **Step 1：编写凭据生命周期红灯测试**

分别证明成功、异常和取消时明文字节被清零。测试 SecretStore 返回已知字节后，保留该数组引用并断言：

```java
assertArrayEquals(new byte[plaintext.length], plaintext);
```

- [ ] **Step 2：实现 `usingWhen` 凭据作用域**

`ResolvedCredential` 实现 `AutoCloseable`，`close()` 使用 `Arrays.fill(apiKey, (byte) 0)`；`withCredential` 使用 `Mono.usingWhen`，成功、错误和取消清理函数都调用 `close()`。

- [ ] **Step 3：编写固定解析和重定向红灯测试**

测试连接解析器只返回 `ValidatedTarget.addresses()`，同源 307/308 重新调用 `OutboundTargetPolicy`，301/302/303、跨源、第四次重定向和 HTTPS 降级均失败。

- [ ] **Step 4：实现 Reactor Netty 传输**

每次 attempt 创建使用本次 `PinnedAddressResolverGroup` 的 `HttpClient`。TLS peer host 始终是 URI 原始主机；禁止自动重定向；Authorization Header 只在目标校验完成后写入。

- [ ] **Step 5：运行 Task 3 验证并提交**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/inference-gateway -am test
git diff --check
git add services/inference-gateway
git commit -m "feat: 接入加密凭据与固定地址传输"
```

---

### Task 4：OpenAI-compatible 文本适配器与标准事件

**文件：**

- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/chat/ChatInferenceCommand.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/chat/InferenceEvent.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/chat/OpenAiChatCompletionsAdapter.java`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/chat/OpenAiStreamDecoder.java`
- 新建：`contracts/events/inference-event.v1.schema.json`
- 修改：`scripts/verify-contracts.ps1`
- 测试：`services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/chat/OpenAiChatCompletionsAdapterTest.java`
- 测试：`services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/chat/OpenAiStreamDecoderTest.java`

**接口：**

- `OpenAiChatCompletionsAdapter.infer(ChatInferenceCommand command, InferenceTarget target): Flux<InferenceEvent>`。
- 事件类型固定为 `start`、`reasoning`、`text_delta`、`usage`、`error`、`done`。

- [ ] **Step 1：编写请求映射和 SSE 红灯测试**

覆盖 System Prompt、模型 ID 不可由调用方覆盖、`stream_options.include_usage=true`、跨数据块 SSE、`[DONE]`、reasoning 字段、usage 和畸形 JSON。

- [ ] **Step 2：实现最小请求映射与流解码**

使用 Jackson 结构化对象构建请求，不拼接 JSON 字符串。SSE 解码器只读取已知字段；未知字段忽略，原始响应正文不进入异常。

- [ ] **Step 3：补充非流响应、错误和重试边界测试**

认证/参数/SSRF 不重试；429、502、503、504 仅在没有发出事件时最多重试 2 次；流已开始后只产生一个 `error`，不重新调用。

- [ ] **Step 4：实现标准错误和重试策略**

错误事件只包含稳定错误码、关联 ID 和 `retryable`，不包含 Base URL、IP、Header 或服务商正文。

- [ ] **Step 5：验证契约并提交**

```powershell
./scripts/verify-contracts.ps1
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/inference-gateway -am test
git diff --check
git add contracts/events/inference-event.v1.schema.json scripts/verify-contracts.ps1 services/inference-gateway
git commit -m "feat: 实现OpenAI文本流适配器"
```

---

### Task 5：连接测试任务租约与结果回写

**文件：**

- 新建：`services/model-registry-service/src/main/resources/db/migration/V3__add_connection_test_job_leases.sql`
- 修改：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/infrastructure/persistence/EndpointConnectionTestJobEntity.java`
- 修改：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/infrastructure/persistence/EndpointConnectionTestJobRepository.java`
- 新建：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/application/inference/ConnectionTestWorkerService.java`
- 新建：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/ConnectionTestWorkerController.java`
- 修改：`contracts/openapi/model-registry-internal.openapi.json`
- 新建：`services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/connectiontest/ConnectionTestWorker.java`
- 测试：`services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/application/inference/ConnectionTestWorkerServiceTest.java`
- 测试：`services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/connectiontest/ConnectionTestWorkerTest.java`

**接口：**

- Registry：领取、续租、成功和失败四个内部操作，均要求 `model-registry.connection-test.execute` scope。
- Gateway：`ConnectionTestWorker.runOnce(): Mono<Boolean>`，返回本次是否领取到任务。

- [ ] **Step 1：编写 Registry 租约红灯测试**

覆盖只领取 `QUEUED` 或租约已过期任务、有效租约不可重复领取、租约 ID/版本不匹配不能回写、完成状态不可再次修改。

- [ ] **Step 2：新增 V3 和 Registry 最小实现**

V3 只增加 `lease_id`、`lease_expires_at`、`attempt_count` 和领取索引；状态、租约和时间约束必须保持一致。仓库领取使用 PostgreSQL `FOR UPDATE SKIP LOCKED`，单事务内更新为 `RUNNING`。

- [ ] **Step 3：编写 Gateway Worker 红灯测试**

证明 Worker 复用 `InferenceTargetClient`、`EndpointCredentialResolver`、`OutboundTargetPolicy` 和适配器；结果只回写稳定错误码，不包含服务商正文。

- [ ] **Step 4：实现 Worker 与内部 HTTP**

连接测试使用固定任务版本和凭据 ID。如果当前解析快照不匹配任务固定值，回写 `CONFIGURATION_CHANGED`，不测试新配置。

- [ ] **Step 5：PostgreSQL 迁移验证并提交**

在 PostgreSQL 17 临时容器执行 V1→V2→V3、Hibernate `ddl-auto=validate`、并发领取和过期租约恢复验证，随后删除临时容器。

```powershell
./scripts/verify-contracts.ps1
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/model-registry-service,services/inference-gateway -am test
git diff --check
git add contracts/openapi/model-registry-internal.openapi.json services/model-registry-service/src services/inference-gateway/src
git commit -m "feat: 执行端点连接测试任务"
```

---

### Task 6：Fake Provider 集成、安全回归与文档

**文件：**

- 新建：`services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/integration/FakeOpenAiProvider.java`
- 新建：`services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/integration/InferenceGatewayIntegrationTest.java`
- 新建：`docs/development/inference-gateway.md`
- 修改：`README.md`
- 修改：`contracts/README.md`
- 修改：`task_plan.md`（本地忽略文件）
- 修改：`progress.md`（本地忽略文件）

- [ ] **Step 1：建立受控 Fake Provider**

Fake Provider 支持正常 JSON、SSE、reasoning、usage、429、5xx、慢响应、畸形 SSE、307/308 和跨源 Location。测试 API Key 使用固定占位值，日志断言不得出现该值。

- [ ] **Step 2：编写端到端服务测试**

覆盖 Registry 快照、Gateway 解密、SSRF、固定解析、请求映射、标准事件和任务回写。安全负向用例必须证明私网、混合 DNS、DNS 变化和私网重定向在发起 HTTP 前被拒绝。

- [ ] **Step 3：更新中文文档**

记录环境变量、内部 scope、超时、重试、SSRF 范围、连接测试租约、凭据清零和本地验证命令，不写入任何真实密钥。

- [ ] **Step 4：运行完整验证**

```powershell
./scripts/verify-all.ps1
git diff --check
git status --short --branch
```

预期：全部模块测试、契约、工具链和 Compose 校验通过；工作区只包含本任务预期文件。

- [ ] **Step 5：只读代码审查、提交并推送**

审查重点为 DNS TOCTOU、TLS 主机名、重定向凭据泄露、明文生命周期、租户绑定和租约并发。修复全部 Critical/Important 后重新运行 Step 4。

```powershell
git add README.md contracts docs/development/inference-gateway.md services/inference-gateway services/model-registry-service/src scripts/verify-contracts.ps1 pom.xml
git commit -m "feat: 完成安全文本推理网关"
git push origin main
```
