# Inference Gateway 内部入口实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把现有进程内 Inference 适配器交付为受 Conversation Service JWT 保护、可调用 Model Registry 与 Provider、始终输出 NDJSON 标准事件的内部服务。

**Architecture:** 先固定 OpenAPI 与强类型配置，再分别建立 Inference 入站和出站信任边界；Model Registry 使用两条有序 `SecurityFilterChain` 隔离 Gateway 与 Inference 调用方。最后由应用服务把严格请求映射到现有 `ChatInferenceCommand`，Controller 只负责 HTTP/NDJSON，现有 Adapter 继续负责 Provider 协议、SSRF 与凭据生命周期。

**Tech Stack:** Java 21、Spring Boot 4.1、Spring WebFlux、Spring Security Resource Server、Nimbus JOSE JWT、Reactor Netty、Jackson、Jakarta Validation、JUnit 5、Reactor Test、MockWebServer、OpenAPI 3.1、PowerShell。

## Global Constraints

- 所有人工文档和代码注释统一使用简体中文；代码标识符、协议名和第三方产品名保留英文。
- 内部接口固定为 `POST /internal/v1/inference/chat-completions`，请求 `application/json`，响应 `application/x-ndjson`。
- `messages.role` 只允许 `user`、`assistant`；System Prompt 只走独立 `systemPrompt` 字段。
- 关联 ID 只读取 `X-Correlation-ID`，格式固定为 `[A-Za-z0-9._-]{16,64}`；缺失或非法时生成规范 UUID。
- 请求正文默认且硬上限均为 `1048576` 字节；消息 1..256 条；`maxOutputTokens` 1..131072；`temperature` 0..2。
- 入站 Service JWT 固定 `RS256`、Subject `conversation-service`、Audience `inference-gateway`、scope `inference.chat.invoke`、最大寿命 60 秒。
- 出站 Service JWT 固定 `RS256`、Subject `inference-gateway-service`、Audience `model-registry-service`、最大寿命 60 秒；解析目标使用 scope `model-registry.inference.resolve` 并携带 `actor_user_id`。
- Token 完整性、算法、Issuer、Audience、Subject 或有效期错误返回 `401`；scope 或 actor 授权错误返回 `403`。
- Model Registry 公共与内部路由必须使用独立 `JwtDecoder` 和独立 `SecurityFilterChain`，不能用多 Issuer Decoder 再靠 scope 区分。
- Gateway、Conversation Service、Inference Gateway 各自使用独立 RSA 私钥；不得在代码、测试、文档、镜像或日志中写入真实密钥、Token、凭据、端点地址或消息正文。
- Model Registry 与 Inference Gateway 必须读取同一 32 字节 AES 主密钥并配置完全相同的 Key ID；本批不实现在线轮换。
- Registry 目标快照 `endpointRequestTimeoutSeconds` 保持 `1..120` 秒；Provider 连接上限 10 秒、响应头上限 30 秒、流空闲上限 60 秒。
- Provider 单帧上限 1 MiB、单次响应累计上限 16 MiB；流式响应只累计字节，不聚合完整正文。
- 成功、错误与取消都必须关闭 `ResolvedCredential` 并清零明文字节；客户端取消必须传播到 Provider。
- 本批不实现 Conversation 公共 HTTP、浏览器链路、文件/图片/视频、管理端或最终 Docker Compose，不得声称这些链路已经完成。
- 不新增 Spring Cloud Gateway，不修改已发布 Flyway migration，不放宽生产 HTTPS、Cookie 或 JWT 安全要求。
- 实施子代理不得执行 `git add`、`git commit` 或 `git push`；主代理在任务复核通过后统一提交。

---

### Task 1: 内部 OpenAPI、严格契约与配置 DTO

**Files:**
- Create: `contracts/openapi/inference-internal.openapi.json`
- Modify: `contracts/README.md`
- Modify: `scripts/verify-contracts.ps1`
- Modify: `services/inference-gateway/pom.xml`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/ConversationJwtProperties.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceServiceJwtProperties.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/ModelRegistryClientProperties.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceSecretStoreProperties.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceHttpProperties.java`
- Create: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/configuration/InferencePropertiesTest.java`

**Interfaces:**
- Consumes: 规格中的环境变量表、`contracts/events/inference-event.v1.schema.json`。
- Produces: `ConversationJwtProperties`、`InferenceServiceJwtProperties`、`ModelRegistryClientProperties`、`InferenceSecretStoreProperties`、`InferenceHttpProperties`，供后续安全与生产装配使用。

- [ ] **Step 1: 写契约验证的失败断言**

在 `scripts/verify-contracts.ps1` 加载新文件，并精确校验唯一路径、媒体类型、`additionalProperties=false`、字段上下限、六类 HTTP 错误、Bearer 安全说明和 NDJSON 响应引用：

```powershell
$inferenceInternalOpenApiFile = Join-Path $projectRoot "contracts/openapi/inference-internal.openapi.json"
if (-not (Test-Path $inferenceInternalOpenApiFile)) {
    throw "缺少 Inference Gateway 内部 OpenAPI。"
}
$inferenceInternalOpenApi = Get-Content -Raw $inferenceInternalOpenApiFile | ConvertFrom-Json
$inferencePath = "/internal/v1/inference/chat-completions"
$operation = $inferenceInternalOpenApi.paths.$inferencePath.post
if ($null -eq $operation -or $inferenceInternalOpenApi.paths.PSObject.Properties.Count -ne 1) {
    throw "Inference 内部 OpenAPI 必须只声明固定推理路径。"
}
$requestSchema = $inferenceInternalOpenApi.components.schemas.ChatCompletionRequest
if ($requestSchema.additionalProperties -ne $false -or $requestSchema.properties.messages.minItems -ne 1 `
        -or $requestSchema.properties.messages.maxItems -ne 256) {
    throw "Inference 内部请求必须严格且消息数量为 1..256。"
}
foreach ($status in @("400", "401", "403", "406", "413", "415", "500")) {
    if ($null -eq $operation.responses.$status) { throw "Inference OpenAPI 缺少 $status 响应。" }
}
if ($null -eq $operation.responses."200".content."application/x-ndjson") {
    throw "Inference 成功响应必须是 application/x-ndjson。"
}
```

- [ ] **Step 2: 运行契约脚本，确认 RED**

Run: `pwsh -NoProfile -File scripts/verify-contracts.ps1`

Expected: FAIL，提示“缺少 Inference Gateway 内部 OpenAPI”。

- [ ] **Step 3: 新增严格 OpenAPI**

创建 OpenAPI 3.1 文档。请求 Schema 的核心定义必须精确为：

```json
{
  "ChatCompletionRequest": {
    "type": "object",
    "additionalProperties": false,
    "required": ["ownerUserId", "modelId", "generationId", "invocationAttemptId", "messages"],
    "properties": {
      "ownerUserId": {"type": "string", "format": "uuid"},
      "modelId": {"type": "string", "format": "uuid"},
      "generationId": {"type": "string", "format": "uuid"},
      "invocationAttemptId": {"type": "string", "format": "uuid"},
      "messages": {"type": "array", "minItems": 1, "maxItems": 256, "items": {"$ref": "#/components/schemas/ChatMessage"}},
      "systemPrompt": {"type": ["string", "null"], "minLength": 1},
      "temperature": {"type": ["number", "null"], "minimum": 0, "maximum": 2},
      "maxOutputTokens": {"type": ["integer", "null"], "minimum": 1, "maximum": 131072}
    }
  },
  "ChatMessage": {
    "type": "object",
    "additionalProperties": false,
    "required": ["role", "content"],
    "properties": {
      "role": {"type": "string", "enum": ["user", "assistant"]},
      "content": {"type": "string", "minLength": 1}
    }
  }
}
```

`200` 响应设置 `Cache-Control: no-store`、`X-Content-Type-Options: nosniff`，NDJSON item 引用 `../events/inference-event.v1.schema.json`；安全说明写明固定 scope 与 `actor_user_id`。

同时在 `services/inference-gateway/pom.xml` 增加生产安全、Actuator、校验和测试依赖：

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-validation</artifactId>
</dependency>
<dependency>
    <groupId>org.springframework.security</groupId>
    <artifactId>spring-security-test</artifactId>
    <scope>test</scope>
</dependency>
```

- [ ] **Step 4: 写配置属性失败测试**

`InferencePropertiesTest` 必须覆盖安全 URI、允许调用方固定值、60 秒上限、Registry 1..30 秒、只允许回环 HTTP 的显式测试构造、密钥路径、Key ID 和 1 MiB 硬上限：

```java
@Test
void Conversation调用方必须固定且寿命不得超过六十秒() {
    assertThatThrownBy(() -> new ConversationJwtProperties(
            "https://conversation.internal", "inference-gateway",
            URI.create("https://conversation.internal/internal/v1/security/jwks"),
            Set.of("other-service"), Duration.ofSeconds(60)))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ConversationJwtProperties(
            "https://conversation.internal", "inference-gateway",
            URI.create("https://conversation.internal/internal/v1/security/jwks"),
            Set.of("conversation-service"), Duration.ofSeconds(61)))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void 生产Registry地址拒绝HTTP与危险URI() {
    assertThatThrownBy(() -> new ModelRegistryClientProperties(
            URI.create("http://registry.internal"), Duration.ofSeconds(5), false))
            .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new ModelRegistryClientProperties(
            URI.create("https://user@registry.internal/path?secret=x"), Duration.ofSeconds(5), false))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 5: 运行属性测试，确认 RED**

Run: `mvn -pl services/inference-gateway -am -Dtest=InferencePropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，配置记录尚不存在。

- [ ] **Step 6: 实现最小强类型配置**

配置记录使用构造器完成 fail-fast 校验，固定签名如下：

```java
@ConfigurationProperties("autumn-wind.inference.conversation-jwt")
public record ConversationJwtProperties(
        String issuer, String audience, URI jwkSetUri,
        Set<String> allowedCallers, Duration maximumLifetime) {
    public static final String REQUIRED_CALLER = "conversation-service";
}

@ConfigurationProperties("autumn-wind.inference.service-jwt")
public record InferenceServiceJwtProperties(
        String issuer, Path privateKeyPath, Path publicKeyPath,
        String keyId, Duration lifetime) {
    public static final String SUBJECT = "inference-gateway-service";
}

@ConfigurationProperties("autumn-wind.inference.model-registry")
public record ModelRegistryClientProperties(
        URI baseUrl, Duration timeout, boolean allowLoopbackHttpForTest) {
}

@ConfigurationProperties("autumn-wind.inference.secret-store")
public record InferenceSecretStoreProperties(Path masterKeyFile, String keyId) {
}

@ConfigurationProperties("autumn-wind.inference.http")
public record InferenceHttpProperties(int requestMaxBytes) {
    public static final int HARD_MAX_BYTES = 1_048_576;
}
```

公共校验方法只接受绝对 HTTPS URI，且拒绝 user info、query、fragment；`allowLoopbackHttpForTest=true` 时只允许 `localhost`、`127.0.0.1` 或 `[::1]`。

- [ ] **Step 7: 运行定向验证，确认 GREEN**

Run: `pwsh -NoProfile -File scripts/verify-contracts.ps1`

Expected: PASS。

Run: `mvn -pl services/inference-gateway -am -Dtest=InferencePropertiesTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 8: 主代理提交**

```bash
git add contracts/openapi/inference-internal.openapi.json contracts/README.md scripts/verify-contracts.ps1 services/inference-gateway/pom.xml services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/configuration/InferencePropertiesTest.java
git commit -m "feat: 定义推理内部入口契约"
```

### Task 2: Inference 入站 JWT、安全错误与关联 ID

**Files:**
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/ConversationServiceJwtValidator.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/InferenceSecurityErrorWriter.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceSecurityConfiguration.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/CorrelationIdWebFilter.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/ApiErrorResponse.java`
- Create: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/security/ConversationServiceJwtValidatorTest.java`
- Create: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/InferenceSecurityTest.java`
- Create: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/CorrelationIdWebFilterTest.java`

**Interfaces:**
- Consumes: `ConversationJwtProperties`。
- Produces: 名为 `conversationServiceJwtDecoder` 的 `JwtDecoder`；请求 attribute `CorrelationIdWebFilter.ATTRIBUTE_NAME`；固定 `401/403` JSON 错误边界。

- [ ] **Step 1: 写 Validator 与 WebFlux 安全失败测试**

测试构造注入的 `JwtDecoder`，覆盖缺失、过期、超长寿命、错误 Issuer/Audience/Subject、缺 scope、actor 缺失或非规范 UUID；安全链授权函数必须只接受：

```java
private static boolean mayInvoke(Authentication authentication) {
    if (!(authentication instanceof JwtAuthenticationToken token)
            || token.getAuthorities().stream().noneMatch(
                    authority -> "SCOPE_inference.chat.invoke".equals(authority.getAuthority()))) {
        return false;
    }
    Object actor = token.getToken().getClaims().get("actor_user_id");
    return actor instanceof String value && isCanonicalUuid(value);
}
```

断言 Token 结构失败为 `401`，scope/actor 失败为 `403`，两者响应均包含安全关联 ID、不包含 Token。

- [ ] **Step 2: 运行安全测试，确认 RED**

Run: `mvn -pl services/inference-gateway -am -Dtest=ConversationServiceJwtValidatorTest,InferenceSecurityTest,CorrelationIdWebFilterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，新类型尚不存在。

- [ ] **Step 3: 实现 Validator、关联 ID 与安全错误写入器**

`ConversationServiceJwtValidator` 使用 `DelegatingOAuth2TokenValidator<Jwt>` 组合时间、Issuer、Audience、固定 Subject、非空 `jti` 与最大寿命校验；`CorrelationIdWebFilter` 使用：

```java
public static final String ATTRIBUTE_NAME = CorrelationIdWebFilter.class.getName() + ".correlationId";
private static final Pattern VALID = Pattern.compile("[A-Za-z0-9._-]{16,64}");

static String normalize(String supplied) {
    return supplied != null && VALID.matcher(supplied).matches()
            ? supplied
            : UUID.randomUUID().toString();
}
```

`ApiErrorResponse` 固定为：

```java
public record ApiErrorResponse(String code, String message, String correlationId) {
}
```

错误写入器设置 JSON、`Cache-Control: no-store`、`X-Content-Type-Options: nosniff`，不写异常 message。

- [ ] **Step 4: 实现 Inference 安全链**

```java
@Bean
@Order(1)
SecurityWebFilterChain inferenceInternalSecurityWebFilterChain(
        ServerHttpSecurity http,
        @Qualifier("conversationServiceJwtDecoder") ReactiveJwtDecoder decoder,
        InferenceSecurityErrorWriter errorWriter) {
    return http.securityMatcher(new PathPatternParserServerWebExchangeMatcher("/internal/v1/inference/**"))
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
            .securityContextRepository(NoOpServerSecurityContextRepository.getInstance())
            .authorizeExchange(authorize -> authorize
                    .pathMatchers(HttpMethod.POST, "/internal/v1/inference/chat-completions")
                    .access((authentication, context) -> authentication
                            .map(InferenceSecurityConfiguration::mayInvoke)
                            .map(AuthorizationDecision::new))
                    .anyExchange().denyAll())
            .oauth2ResourceServer(resource -> resource
                    .jwt(jwt -> jwt.jwtDecoder(decoder))
                    .authenticationEntryPoint(errorWriter::writeUnauthorized)
                    .accessDeniedHandler(errorWriter::writeForbidden))
            .build();
}
```

JWKS 公共读取由后续 Task 3 的独立链放行；其余路由默认拒绝。

- [ ] **Step 5: 运行安全测试，确认 GREEN**

Run: `mvn -pl services/inference-gateway -am -Dtest=ConversationServiceJwtValidatorTest,InferenceSecurityTest,CorrelationIdWebFilterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 6: 主代理提交**

```bash
git add services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceSecurityConfiguration.java services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/interfaces/http services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/security/ConversationServiceJwtValidatorTest.java services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/InferenceSecurityTest.java services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/CorrelationIdWebFilterTest.java
git commit -m "feat: 建立推理入口信任边界"
```

### Task 3: Inference 出站 RSA JWT、JWKS 与 Registry Client

**Files:**
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/RsaKeyMaterial.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/RsaKeyMaterialLoader.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/ServiceJwtRequest.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/ServiceJwtIssuer.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security/NimbusServiceJwtIssuer.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/InferenceJwksController.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceJwtConfiguration.java`
- Modify: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceSecurityConfiguration.java`
- Modify: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/registry/ModelRegistryWebClient.java`
- Create: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/security/NimbusServiceJwtIssuerTest.java`
- Create: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/InferenceJwksControllerTest.java`
- Modify: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/registry/ModelRegistryWebClientTest.java`

**Interfaces:**
- Consumes: `InferenceServiceJwtProperties`、`ModelRegistryClientProperties`、`ModelRegistryWebClient` 的 `Function<UUID, Mono<String>>`。
- Produces: `ServiceJwtIssuer.issue(ServiceJwtRequest)`、`GET /internal/v1/security/jwks`、带固定 scope/actor 的 Registry 请求。

- [ ] **Step 1: 写 RSA、JWT、JWKS 失败测试**

测试必须验证 2048 位下限、公私钥匹配、RS256、`kid`、固定 Subject、唯一 Audience、`iat/exp/jti`、60 秒上限、actor 与 scope；JWKS 断言只能出现 `kty/kid/use/alg/n/e`：

```java
assertThat(jwk).containsOnlyKeys("kty", "kid", "use", "alg", "n", "e");
assertThat(jwk).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
```

Registry Client 测试捕获 Authorization Token 并解码 claims，断言 scope 为 `model-registry.inference.resolve` 且 `actor_user_id` 等于 owner。

- [ ] **Step 2: 运行出站安全测试，确认 RED**

Run: `mvn -pl services/inference-gateway -am -Dtest=NimbusServiceJwtIssuerTest,InferenceJwksControllerTest,ModelRegistryWebClientTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，新 JWT 组件尚不存在。

- [ ] **Step 3: 实现密钥加载与 JWT 签发**

复用 Gateway 已验证算法但保留独立包与配置，接口固定为：

```java
public interface ServiceJwtIssuer {
    String issue(ServiceJwtRequest request);
}

public record ServiceJwtRequest(String audience, Set<String> scopes, UUID actorUserId) {
    public static ServiceJwtRequest actor(String audience, String scope, UUID actorUserId) {
        return new ServiceJwtRequest(audience, Set.of(scope),
                Objects.requireNonNull(actorUserId, "操作者不能为空。"));
    }
}
```

`NimbusServiceJwtIssuer` 固定 `.subject(InferenceServiceJwtProperties.SUBJECT)`，不允许调用方覆盖 Subject；`RsaKeyMaterialLoader` 读取 PKCS#8/X.509 PEM，校验至少 2048 位并签名验签确认配对，临时 DER 与签名字节在 `finally` 清零。

- [ ] **Step 4: 实现 JWKS 与 Registry Token Provider**

`InferenceJwksController` 返回 `Cache-Control: public, max-age=300`。生产装配向 `ModelRegistryWebClient` 传入：

```java
ownerUserId -> Mono.fromSupplier(() -> serviceJwtIssuer.issue(
        ServiceJwtRequest.actor(
                "model-registry-service",
                "model-registry.inference.resolve",
                ownerUserId)))
```

`InferenceSecurityConfiguration` 新增更高优先级公共 JWKS 链，仅匹配 `GET /internal/v1/security/jwks`，其他方法拒绝。

- [ ] **Step 5: 运行出站安全测试，确认 GREEN**

Run: `mvn -pl services/inference-gateway -am -Dtest=NimbusServiceJwtIssuerTest,InferenceJwksControllerTest,ModelRegistryWebClientTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 6: 主代理提交**

```bash
git add services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/security services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/InferenceJwksController.java services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceJwtConfiguration.java services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceSecurityConfiguration.java services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/registry/ModelRegistryWebClient.java services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/security/NimbusServiceJwtIssuerTest.java services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/InferenceJwksControllerTest.java services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/registry/ModelRegistryWebClientTest.java
git commit -m "feat: 签发推理服务JWT"
```

### Task 4: Model Registry 公共与内部双安全链

**Files:**
- Create: `services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/infrastructure/configuration/InferenceJwtProperties.java`
- Modify: `services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/infrastructure/security/ServiceJwtValidator.java`
- Modify: `services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/infrastructure/configuration/ModelRegistrySecurityConfiguration.java`
- Modify: `services/model-registry-service/src/main/resources/application.yaml`
- Create: `services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/infrastructure/configuration/InferenceJwtPropertiesTest.java`
- Modify: `services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/ModelRegistryInternalSecurityTest.java`
- Modify: `services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/ModelRegistrySecurityTest.java`
- Modify: `services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/ModelRegistryModelSecurityTest.java`
- Modify: `services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/ConnectionTestWorkerControllerSecurityTest.java`
- Modify: `services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/application/endpoint/ConnectionTestWorkerPostgresIntegrationTest.java`

**Interfaces:**
- Consumes: 现有 `ServiceJwtProperties` 作为公共 Gateway 信任；新增 Inference 固定调用方配置。
- Produces: `@Order(1)` 内部链与 `@Order(2)` 公共链，分别注入 `inferenceJwtDecoder` 和 `modelRegistryServiceJwtDecoder`。

- [ ] **Step 1: 写交叉 Issuer 与配置失败测试**

测试矩阵必须包含：Inference 合法 Token 可访问内部解析、被公共路由 `401` 拒绝；Gateway 合法 Token 可访问公共模型目录、被内部路由 `401` 拒绝；内部错误 Subject 为 `401`，缺 scope/actor 为 `403`。属性测试固定：

```java
assertThatThrownBy(() -> new InferenceJwtProperties(
        "https://inference.internal", "model-registry-service",
        URI.create("https://inference.internal/internal/v1/security/jwks"),
        Set.of("gateway-service"), Duration.ofSeconds(60)))
        .isInstanceOf(IllegalArgumentException.class);
```

- [ ] **Step 2: 运行 Registry 安全测试，确认 RED**

Run: `mvn -pl services/model-registry-service -am -Dtest=InferenceJwtPropertiesTest,ModelRegistryInternalSecurityTest,ModelRegistrySecurityTest,ModelRegistryModelSecurityTest,ConnectionTestWorkerControllerSecurityTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，内部配置与独立 Decoder 尚不存在。

- [ ] **Step 3: 实现 Inference 信任属性与通用 Validator 输入**

```java
@ConfigurationProperties("autumn-wind.model-registry.inference-jwt")
public record InferenceJwtProperties(
        String issuer, String audience, URI jwkSetUri,
        Set<String> allowedCallers, Duration maximumLifetime) {
    public static final String REQUIRED_CALLER = "inference-gateway-service";
}
```

让 `ServiceJwtValidator` 接受一个最小公共接口，并在两类 Token 上都要求非空 `jti`：

```java
public interface ServiceJwtValidationProperties {
    String issuer();
    String audience();
    Set<String> allowedCallers();
    Duration maximumLifetime();
}
```

`ServiceJwtProperties` 与 `InferenceJwtProperties` 均实现该接口；保留公共配置现有最大一小时行为，Inference 配置严格不超过 60 秒。Validator 增加 `new JwtClaimValidator<>("jti", value -> value instanceof String text && !text.isBlank())`。

- [ ] **Step 4: 拆分两个 Decoder 和两条安全链**

```java
@Bean("inferenceJwtDecoder")
JwtDecoder inferenceJwtDecoder(InferenceJwtProperties properties, Clock clock) {
    return decoder(properties.jwkSetUri(), new ServiceJwtValidator(properties, clock));
}

@Bean
@Order(1)
SecurityFilterChain modelRegistryInternalSecurityFilterChain(
        HttpSecurity http,
        @Qualifier("inferenceJwtDecoder") JwtDecoder decoder,
        ModelRegistrySecurityErrorWriter errorWriter) throws Exception {
    http.securityMatcher("/internal/v1/model-registry/**");
    configureStateless(http, decoder, errorWriter);
    http.authorizeHttpRequests(auth -> auth
            .requestMatchers(HttpMethod.POST, "/internal/v1/model-registry/inference-target-resolutions")
            .access((authentication, context) -> new AuthorizationDecision(
                    mayResolveInferenceTarget(authentication.get())))
            .requestMatchers(HttpMethod.POST, "/internal/v1/model-registry/connection-test-jobs/**")
            .access((authentication, context) -> new AuthorizationDecision(
                    mayExecuteConnectionTest(authentication.get())))
            .anyRequest().denyAll());
    return http.build();
}
```

公共链只匹配 `/api/v1/model-registry/**`，保持既有权限矩阵。不得把两个 Decoder 合并。

- [ ] **Step 5: 写入新增环境变量映射并运行 GREEN**

`application.yaml` 增加 `MODEL_REGISTRY_INFERENCE_JWT_*`，默认调用方只能是 `inference-gateway-service`，默认最大寿命 `PT60S`。

Run: `mvn -pl services/model-registry-service -am -Dtest=InferenceJwtPropertiesTest,ModelRegistryInternalSecurityTest,ModelRegistrySecurityTest,ModelRegistryModelSecurityTest,ConnectionTestWorkerControllerSecurityTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 6: 主代理提交**

```bash
git add services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/infrastructure services/model-registry-service/src/main/resources/application.yaml services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/infrastructure/configuration/InferenceJwtPropertiesTest.java services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/ModelRegistryInternalSecurityTest.java services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/ModelRegistrySecurityTest.java services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/ModelRegistryModelSecurityTest.java services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/ConnectionTestWorkerControllerSecurityTest.java services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/application/endpoint/ConnectionTestWorkerPostgresIntegrationTest.java
git commit -m "feat: 隔离模型注册中心信任链"
```

### Task 5: 推理应用服务与生产 Bean 装配

**Files:**
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/application/ChatInferenceRequest.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/application/InferenceInvocationContext.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/application/ForbiddenActorException.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/application/ChatInferenceService.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceApplicationConfiguration.java`
- Modify: `services/inference-gateway/src/main/resources/application.yaml`
- Create: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/application/ChatInferenceServiceTest.java`
- Create: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceApplicationConfigurationTest.java`

**Interfaces:**
- Consumes: `InferenceTargetClient.resolve(UUID, UUID, String)`、`OpenAiChatCompletionsAdapter.infer(ChatInferenceCommand, InferenceTarget)`、所有 Task 1/3 配置。
- Produces: `Flux<InferenceEvent> ChatInferenceService.infer(ChatInferenceRequest request, UUID authenticatedActor, String correlationId)` 与可启动生产 Bean 图。

- [ ] **Step 1: 写应用服务失败测试**

覆盖 actor 不一致时不调用 Registry；目标解析后 `stream` 只取 `target.capabilities().streaming()`；Generation/Attempt ID 保留在 `InferenceInvocationContext` 且不进入 Provider 请求；Registry/凭据/Provider 建流前错误映射为稳定 `InferenceEvent.Error`：

```java
@Test
void actor不一致时拒绝且不调用Registry() {
    StepVerifier.create(service.infer(requestFor(OWNER), OTHER_USER, CORRELATION_ID))
            .expectError(ForbiddenActorException.class)
            .verify();
    verifyNoInteractions(targetClient);
}

@Test
void stream只由Registry能力决定() {
    when(targetClient.resolve(OWNER, MODEL_ID, CORRELATION_ID)).thenReturn(Mono.just(nonStreamingTarget()));
    when(adapter.infer(any(), any())).thenReturn(Flux.just(new InferenceEvent.Done("stop")));
    StepVerifier.create(service.infer(requestFor(OWNER), OWNER, CORRELATION_ID)).expectNextCount(1).verifyComplete();
    verify(adapter).infer(argThat(command -> !command.stream()));
}
```

- [ ] **Step 2: 运行应用服务测试，确认 RED**

Run: `mvn -pl services/inference-gateway -am -Dtest=ChatInferenceServiceTest,InferenceApplicationConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，应用服务与 Bean 配置尚不存在。

- [ ] **Step 3: 实现请求与应用服务**

```java
public record ChatInferenceRequest(
        UUID ownerUserId,
        UUID modelId,
        UUID generationId,
        UUID invocationAttemptId,
        List<ChatInferenceCommand.Message> messages,
        String systemPrompt,
        Double temperature,
        Integer maxOutputTokens) {
}

public record InferenceInvocationContext(UUID generationId, UUID invocationAttemptId) {
    public static final Class<InferenceInvocationContext> REACTOR_CONTEXT_KEY =
            InferenceInvocationContext.class;
}

public Flux<InferenceEvent> infer(
        ChatInferenceRequest request,
        UUID authenticatedActor,
        String correlationId) {
    if (!request.ownerUserId().equals(authenticatedActor)) {
        return Flux.error(new ForbiddenActorException());
    }
    InferenceInvocationContext invocation = new InferenceInvocationContext(
            request.generationId(), request.invocationAttemptId());
    return targetClient.resolve(request.ownerUserId(), request.modelId(), correlationId)
            .flatMapMany(target -> adapter.infer(new ChatInferenceCommand(
                    request.ownerUserId(), request.modelId(), request.messages(),
                    request.systemPrompt(), request.temperature(), request.maxOutputTokens(),
                    target.capabilities().streaming(), correlationId), target))
            .map(event -> event instanceof InferenceEvent.Start
                    ? new InferenceEvent.Start(invocation.invocationAttemptId().toString())
                    : event)
            .contextWrite(context -> context.put(
                    InferenceInvocationContext.REACTOR_CONTEXT_KEY, invocation));
}
```

异常映射只返回 `INTERNAL_DEPENDENCY_ERROR` 等既有稳定码；异常和 `toString()` 不得包含正文、URL、Token 或凭据。

- [ ] **Step 4: 装配生产 Bean**

`InferenceApplicationConfiguration` 显式声明：严格 `ObjectMapper`（未知字段、重复 Key、尾随 Token、标量强制转换均失败）、`JdkHostResolver`、`PublicAddressPolicy`、`OutboundTargetPolicy`、`AesGcmSecretStore.fromBase64File`、`EndpointCredentialResolver`、Reactor Netty `HttpClient`、`ProviderExchangeClient`、`OpenAiChatCompletionsAdapter`、Registry `WebClient`、`InferenceTargetClient`、`ChatInferenceService`。

Registry WebClient 总超时来自 `ModelRegistryClientProperties.timeout()`；Provider 连接、响应头、空闲和字节上限必须通过现有 transport 的明确构造参数或常量生效，不使用无界默认值。

- [ ] **Step 5: 配置 Actuator 与环境变量**

`application.yaml` 精确映射规格中的 `INFERENCE_GATEWAY_*`，增加：

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info
```

生产 YAML 不提供私钥、公钥、AES 主密钥或下游地址的真实默认值。

- [ ] **Step 6: 运行应用服务与 Context 验证，确认 GREEN**

Run: `mvn -pl services/inference-gateway -am -Dtest=ChatInferenceServiceTest,InferenceApplicationConfigurationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS，测试使用临时占位密钥文件和回环测试配置，不输出内容。

- [ ] **Step 7: 主代理提交**

```bash
git add services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/application services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceApplicationConfiguration.java services/inference-gateway/src/main/resources/application.yaml services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/application/ChatInferenceServiceTest.java services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/configuration/InferenceApplicationConfigurationTest.java
git commit -m "feat: 装配推理网关生产组件"
```

### Task 6: 严格请求、NDJSON Controller 与流资源边界

**Files:**
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/ChatMessageRequest.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/ChatCompletionRequest.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/CanonicalUuidDeserializer.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/InferenceController.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/InferenceExceptionHandler.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/RequestBodyLimitWebFilter.java`
- Modify: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/chat/ChatInferenceCommand.java`
- Modify: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/chat/InferenceEvent.java`
- Create: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/transport/ProviderExchangeLimits.java`
- Modify: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/transport/ProviderExchangeClient.java`
- Modify: `services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/transport/ReactorNettyProviderExchangeClient.java`
- Create: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/InferenceControllerTest.java`
- Modify: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/transport/ReactorNettyHttpAttemptTest.java`
- Modify: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/chat/OpenAiChatCompletionsAdapterTest.java`

**Interfaces:**
- Consumes: `ChatInferenceService.infer(...)`、认证 JWT 的 `actor_user_id`、关联 ID attribute。
- Produces: 严格 HTTP/NDJSON 行流；固定 HTTP 错误；统一 16..64 关联 ID；1 MiB/16 MiB 资源限制和取消传播。

- [ ] **Step 1: 写 Controller 与严格 JSON 失败测试**

使用 `WebTestClient` 覆盖合法流、未知字段、重复 Key、尾随 Token、非法 role、空 content、257 条消息、非法 temperature/max tokens、`406/415/413`、body actor 不一致 `403` 且 Registry 未调用。成功断言：

```java
client.post().uri("/internal/v1/inference/chat-completions")
        .header(HttpHeaders.AUTHORIZATION, bearerToken(OWNER))
        .header("X-Correlation-ID", CORRELATION_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .accept(MediaType.parseMediaType("application/x-ndjson"))
        .bodyValue(validRequest())
        .exchange()
        .expectStatus().isOk()
        .expectHeader().contentType("application/x-ndjson")
        .expectHeader().valueEquals(HttpHeaders.CACHE_CONTROL, "no-store")
        .expectHeader().valueEquals("X-Content-Type-Options", "nosniff")
        .returnResult(String.class);
```

逐行解析后断言 `start -> text_delta -> usage -> done`，每行以 `\n` 终止。

- [ ] **Step 2: 写取消与字节上限失败测试**

Provider transport 测试生成单帧 `1_048_577` 字节和累计 `16_777_217` 字节，断言上游被取消且只输出一个稳定 `error`。取消测试分为两种：只消费同步 `start` 后取消，断言尚未触达 Provider；消费 `start` 和至少一个 Provider 事件后取消，断言 Provider dispose 与 `ResolvedCredential.close()` 都发生。终态后注入晚到帧，断言不会出现第二个终态。

- [ ] **Step 3: 运行 HTTP/流测试，确认 RED**

Run: `mvn -pl services/inference-gateway -am -Dtest=InferenceControllerTest,ReactorNettyHttpAttemptTest,OpenAiChatCompletionsAdapterTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，HTTP 入口与新资源限制尚不存在。

- [ ] **Step 4: 实现严格 DTO 与请求转换**

```java
public record ChatMessageRequest(
        @NotNull Role role,
        @NotBlank String content) {
    public enum Role { user, assistant }
    ChatInferenceCommand.Message toCommandMessage() {
        return new ChatInferenceCommand.Message(role.name(), content);
    }
}

public record ChatCompletionRequest(
        @NotNull @JsonDeserialize(using = CanonicalUuidDeserializer.class) UUID ownerUserId,
        @NotNull @JsonDeserialize(using = CanonicalUuidDeserializer.class) UUID modelId,
        @NotNull @JsonDeserialize(using = CanonicalUuidDeserializer.class) UUID generationId,
        @NotNull @JsonDeserialize(using = CanonicalUuidDeserializer.class) UUID invocationAttemptId,
        @NotEmpty @Size(max = 256) List<@Valid ChatMessageRequest> messages,
        String systemPrompt,
        @DecimalMin("0.0") @DecimalMax("2.0") Double temperature,
        @Min(1) @Max(131072) Integer maxOutputTokens) {
}
```

构造器复制 messages；`systemPrompt` 非空时不可为空字符串。`CanonicalUuidDeserializer` 只接受 JSON 字符串，并用 `UUID.fromString(raw)` 后的 `toString().equalsIgnoreCase(raw)` 拒绝非规范文本；失败统一调用 `context.handleWeirdStringValue(UUID.class, raw, "UUID 必须使用规范格式。")`，不得把原始正文写入日志。

- [ ] **Step 5: 实现 Controller 与异常处理**

Controller 从 `JwtAuthenticationToken` 取规范 actor，从 exchange attribute 取关联 ID：

```java
@PostMapping(
        path = "/internal/v1/inference/chat-completions",
        consumes = MediaType.APPLICATION_JSON_VALUE,
        produces = "application/x-ndjson")
public ResponseEntity<Flux<InferenceEvent>> infer(
        @Valid @RequestBody ChatCompletionRequest request,
        JwtAuthenticationToken authentication,
        ServerWebExchange exchange) {
    UUID actor = UUID.fromString(authentication.getToken().getClaimAsString("actor_user_id"));
    String correlationId = exchange.getAttributeOrDefault(
            CorrelationIdWebFilter.ATTRIBUTE_NAME, UUID.randomUUID().toString());
    if (!request.ownerUserId().equals(actor)) {
        throw new ForbiddenActorException();
    }
    Flux<InferenceEvent> events = service.infer(request.toApplicationRequest(), actor, correlationId);
    return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType("application/x-ndjson"))
            .cacheControl(CacheControl.noStore())
            .header("X-Content-Type-Options", "nosniff")
            .body(events);
}
```

使用 Jackson NDJSON encoder 保证每个对象一行且不聚合 Flux。`InferenceExceptionHandler` 只处理建流前错误并返回稳定 `ApiErrorResponse`；`RequestBodyLimitWebFilter` 在读取 JSON 前按实际 `DataBuffer` 字节计数，超过 1 MiB 释放 buffer 并返回 `413`。

- [ ] **Step 6: 收紧关联 ID、帧与累计响应上限**

把 `ChatInferenceCommand` 与 `InferenceEvent.Error` 的关联 ID Pattern 统一为 `[A-Za-z0-9._-]{16,64}`。把 transport 接口改为显式接收限制快照：

```java
public interface ProviderExchangeClient {
    Flux<ProviderFrame> exchange(
            ValidatedTarget target,
            ProviderRequest request,
            ProviderExchangeLimits limits);
}

public record ProviderExchangeLimits(
        Duration responseHeaderTimeout,
        Duration streamIdleTimeout,
        int maxFrameBytes,
        long maxResponseBytes) {
    public static ProviderExchangeLimits forTargetTimeoutSeconds(int seconds) {
        Duration total = Duration.ofSeconds(seconds);
        return new ProviderExchangeLimits(
                min(total, Duration.ofSeconds(30)),
                min(total, Duration.ofSeconds(60)),
                1_048_576,
                16_777_216L);
    }
}
```

Adapter 以 `target.endpointRequestTimeoutSeconds()` 创建 `ProviderExchangeLimits` 并传给 `exchange`；一次调用的总超时继续由 Adapter 的 `timeout(Duration.ofSeconds(...))` 保证。Transport 固定连接上限并保留以下常量：

```java
static final int CONNECT_TIMEOUT_MILLIS = 10_000;
static final int MAX_PROVIDER_FRAME_BYTES = 1_048_576;
static final long MAX_PROVIDER_RESPONSE_BYTES = 16_777_216L;
```

`HttpClient` 设置 `ChannelOption.CONNECT_TIMEOUT_MILLIS`；响应头 Publisher 使用 `limits.responseHeaderTimeout()`，收到 Header 后对 body 使用 `limits.streamIdleTimeout()`。每个 Netty buffer 在复制前校验单帧，复制后用每次订阅独立的累计计数校验总字节；超限立即释放 buffer、取消连接并抛出稳定 transport 异常。使用 Reactor 资源作用域传播取消，不缓存响应正文。收到 `error` 或 `done` 后通过每次订阅独立的原子终态门丢弃晚到事件。

- [ ] **Step 7: 运行 HTTP/流测试，确认 GREEN**

Run: `mvn -pl services/inference-gateway -am -Dtest=InferenceControllerTest,ReactorNettyHttpAttemptTest,OpenAiChatCompletionsAdapterTest,EndpointCredentialResolverTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: PASS。

- [ ] **Step 8: 主代理提交**

```bash
git add services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/interfaces/http services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/chat services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/transport/ProviderExchangeLimits.java services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/transport/ProviderExchangeClient.java services/inference-gateway/src/main/java/io/github/yanhuo218/autumnwind/inference/transport/ReactorNettyProviderExchangeClient.java services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/interfaces/http/InferenceControllerTest.java services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/transport/ReactorNettyHttpAttemptTest.java services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/chat/OpenAiChatCompletionsAdapterTest.java
git commit -m "feat: 提供推理NDJSON内部接口"
```

### Task 7: Fake Registry/Provider 集成、安全回归与中文运维文档

**Files:**
- Create: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/integration/FakeModelRegistry.java`
- Modify: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/integration/FakeOpenAiProvider.java`
- Create: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/integration/InferenceHttpIntegrationTest.java`
- Modify: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/integration/InferenceGatewayIntegrationTest.java`
- Create: `services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/security/InferenceSensitiveDataTest.java`
- Create: `docs/development/inference-internal-ingress.md`
- Modify: `README.md`

**Interfaces:**
- Consumes: 完整内部入口、双向 JWT、Model Registry Client、SecretStore、OpenAI Adapter。
- Produces: 从受信任 HTTP 请求到 Fake Registry 与 Fake Provider 的真实非 Mock 进程内链路证据、部署配置清单和剩余边界说明。

- [ ] **Step 1: 写端到端失败测试**

`InferenceHttpIntegrationTest` 启动真实 Spring Context、受控 Fake Registry 与 Fake HTTPS Provider，使用测试 RSA/CA/AES 临时文件。覆盖：

```java
@Test
void 入站Actor经Registry解析并调用流式Provider后输出NDJSON() {
    Flux<String> lines = authorizedRequest(streamingModelRequest());
    StepVerifier.create(lines)
            .assertNext(line -> assertType(line, "start"))
            .assertNext(line -> assertType(line, "text_delta"))
            .assertNext(line -> assertType(line, "usage"))
            .assertNext(line -> assertType(line, "done"))
            .verifyComplete();
    fakeRegistry.assertActor(OWNER);
    fakeProvider.assertNoPlatformIdentifiers();
}

@Test
void 非流式Provider仍输出相同NDJSON事件序列() {
    assertThat(readTypes(authorizedRequest(nonStreamingModelRequest())))
            .containsExactly("start", "text_delta", "usage", "done");
}
```

同时覆盖 Registry Token scope/actor、Provider 认证、错误前/中途、取消、凭据清零、非法 Inference Token 不触达 Registry、Registry 与 Inference AES Key ID 不一致时稳定失败。

- [ ] **Step 2: 运行集成测试，确认 RED**

Run: `mvn -pl services/inference-gateway -am -Dtest=InferenceHttpIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test`

Expected: FAIL，Fake Registry 与完整 Spring 测试尚不存在。

- [ ] **Step 3: 实现受控 Fake 服务与完整链路**

Fake Registry 必须验证 Inference Gateway 签发的 RS256 Token、固定 Issuer/Subject/Audience/scope/actor，只返回测试生成的加密信封；Fake Provider 只接受占位 Authorization，并记录是否收到平台内部 ID。测试 teardown 必须关闭服务器、EventLoop 和临时资源，不把 PEM、Token、凭据或正文写入报告。

- [ ] **Step 4: 增加敏感数据与安全回归**

`InferenceSensitiveDataTest` 反射构造所有请求、命令、事件、目标与配置 DTO，断言 `toString()` 不包含消息、System Prompt、Token、凭据、URL、密钥路径内容；捕获日志只搜索占位 canary，禁止打印真实安全材料。

- [ ] **Step 5: 编写中文开发文档**

`docs/development/inference-internal-ingress.md` 必须说明：调用链、内部路径、JWT claims、环境变量、AES 共享/RSA 隔离、HTTP/NDJSON 错误边界、取消与大小限制、测试命令、最终 Compose 尚待完成。示例只使用：

```text
YOUR_PRIVATE_KEY_PATH_HERE
YOUR_PUBLIC_KEY_PATH_HERE
YOUR_MASTER_KEY_FILE_HERE
https://inference.example.invalid
```

README 只增加文档入口和“当前完成到内部推理依赖，不代表浏览器真实聊天链路完成”的状态说明。

- [ ] **Step 6: 运行模块与全仓验证**

Run: `mvn -pl services/inference-gateway -am test`

Expected: PASS，无环境型 skip。

Run: `mvn -pl services/model-registry-service -am test`

Expected: PASS；仅允许既有 PostgreSQL 环境测试按现有条件跳过。

Run: `pwsh -NoProfile -File scripts/verify-contracts.ps1`

Expected: PASS。

Run: `mvn test`

Expected: BUILD SUCCESS；仅允许已知 PostgreSQL 环境测试跳过。

Run: `git diff --check`

Expected: 无输出，退出码 0。

- [ ] **Step 7: 主代理提交**

```bash
git add services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/integration services/inference-gateway/src/test/java/io/github/yanhuo218/autumnwind/inference/security/InferenceSensitiveDataTest.java docs/development/inference-internal-ingress.md README.md
git commit -m "test: 验证真实推理内部链路"
```

## 计划自检

- 规格覆盖：Task 1 覆盖契约与配置；Task 2 覆盖入站 JWT；Task 3 覆盖出站 JWT/JWKS；Task 4 覆盖 Registry 双信任链；Task 5 覆盖应用服务、生产 Bean 与 Actuator；Task 6 覆盖严格 HTTP、NDJSON、取消和资源上限；Task 7 覆盖完整 Fake 集成、敏感数据与中文文档。
- 占位符检查：计划没有未决实现标记、跨任务省略或模糊的代码步骤；示例凭据路径是明确的安全占位文本。
- 类型一致性：入口统一调用 `ChatInferenceService.infer(ChatInferenceRequest, UUID, String)`；Registry Token 统一经 `ServiceJwtIssuer.issue(ServiceJwtRequest.actor(...))`；关联 ID 全链统一 16..64；输出统一 `Flux<InferenceEvent>`。
- 范围检查：本计划只完成 Inference 内部入口及其 Registry 信任依赖；Conversation 公共链路、前端与最终 Compose 保持在后续独立计划。
