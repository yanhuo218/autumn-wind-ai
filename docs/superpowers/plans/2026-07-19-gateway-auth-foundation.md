# Gateway 认证基础实施计划

> **面向代理执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 逐任务实施。所有步骤使用复选框跟踪；生产代码必须先有预期失败测试。

**目标：** 建立 Java WebFlux Gateway，透明代理 Identity 公共认证接口，以 Session Introspection 校验浏览器会话，并用最小权限 Service JWT 读取当前用户模型目录。

**架构：** Gateway 不保存会话或业务事实。认证路由只转发白名单 Header 与受限正文；业务请求把唯一 `AW_SESSION` 交给 Identity Introspection，随后为目标服务签发限定 Audience、scope 和 actor 的 60 秒 RS256 Token。首批只开放模型列表 GET，不接入 Conversation 生产代理。

**技术栈：** Java 21、Spring Boot 4.1.0、Spring WebFlux、Spring Security、Spring OAuth2 JOSE、Nimbus JOSE JWT、JUnit 5、WebTestClient、OpenAPI 3.1、PowerShell 契约校验。

## 全局约束

- 设计基线为 `docs/superpowers/specs/2026-07-19-gateway-auth-foundation-design.md`。
- 所有人工文档和代码注释统一使用简体中文。
- 不创建额外 worktree，不修改已有服务目录路径；新增模块固定为 `services/gateway-service`。
- Gateway 默认端口固定为 `8080`，包根固定为 `io.github.yanhuo218.autumnwind.gateway`。
- 不引入 Spring Cloud Gateway，不连接数据库、Redis、RabbitMQ 或对象存储。
- 不提交或输出真实密码、PEM、私钥、API Key、Token、Cookie、Authorization Header 或用户数据。
- RSA 至少 2048 位；Service JWT 固定 RS256、`sub=gateway-service`、有效期 60 秒。
- Gateway 第一批只代理六个 Identity 认证操作和 `GET /api/v1/model-registry/models`。
- Identity 认证写操作继续由 Identity 校验 CSRF；Gateway 不开放其他业务写接口。
- 原始 `AW_SESSION` 只能进入 Identity 认证或 Introspection，不得进入 Model Registry。
- 子代理不得执行 `git add`、`git commit`、`git push`、分支切换或历史改写；Git 由主代理在评审通过后执行。
- 每个任务结束后必须进行规格符合性与代码质量只读评审，Critical/Important 问题修复后才可提交。

## 文件结构

- `services/gateway-service/pom.xml`：Gateway 依赖和构建边界。
- `services/gateway-service/src/main/java/.../GatewayServiceApplication.java`：启动入口。
- `.../configuration/`：下游地址、JWT 密钥、WebClient 与 Security 配置。
- `.../security/`：RSA 密钥加载、Service JWT、JWKS、Session 提取和认证过滤器。
- `.../identity/`：Identity 公共代理和 Introspection Client。
- `.../model/`：只读模型目录代理。
- `.../web/`：关联 ID、公共错误和响应 Header 基线。
- `docs/development/gateway-service.md`：中文部署与安全说明。

---

### Task 1：Gateway 模块、配置与公共错误基线（已完成）

**文件：**

- 修改：`pom.xml`
- 新建：`services/gateway-service/pom.xml`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/GatewayServiceApplication.java`
- 新建：`services/gateway-service/src/main/resources/application.yaml`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/configuration/GatewayDownstreamProperties.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/web/GatewayErrorCode.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/web/GatewayException.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/web/GatewayErrorResponse.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/web/CorrelationIdWebFilter.java`
- 测试：`services/gateway-service/src/test/java/io/github/yanhuo218/autumnwind/gateway/configuration/GatewayDownstreamPropertiesTest.java`
- 测试：`services/gateway-service/src/test/java/io/github/yanhuo218/autumnwind/gateway/web/CorrelationIdWebFilterTest.java`

**接口：**

```java
@ConfigurationProperties("autumn-wind.gateway.downstream")
public record GatewayDownstreamProperties(URI identityBaseUrl, URI modelRegistryBaseUrl) {}

public enum GatewayErrorCode {
    INVALID_SESSION("AW-GATEWAY-AUTH-0001"),
    ROUTE_NOT_ALLOWED("AW-GATEWAY-ROUTING-0001"),
    REQUEST_TOO_LARGE("AW-GATEWAY-VALIDATION-0001"),
    IDENTITY_UNAVAILABLE("AW-GATEWAY-DEPENDENCY-0001"),
    MODEL_REGISTRY_UNAVAILABLE("AW-GATEWAY-DEPENDENCY-0002"),
    DOWNSTREAM_PROTOCOL_ERROR("AW-GATEWAY-DEPENDENCY-0003"),
    INTERNAL_ERROR("AW-GATEWAY-INTERNAL-0001");
}
```

- [x] **Step 1：先写配置与关联 ID 红灯测试**

测试必须断言：接受不含用户信息、Query、Fragment 的绝对 HTTPS URI，以及仅指向回环地址的绝对 HTTP URI；拒绝相对 URI、非回环 HTTP URI和其他危险格式；接受 16-64 位公共关联 ID；非法或缺失时生成新值；响应始终回写 `X-Correlation-ID`。

- [x] **Step 2：运行红灯**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/gateway-service -am test
```

预期：因根 reactor 尚无 Gateway 模块或类型不存在而失败。

- [x] **Step 3：实现最小模块与公共类型**

`application.yaml` 固定：

```yaml
spring:
  application:
    name: gateway-service
  codec:
    max-in-memory-size: 1MB
server:
  port: ${GATEWAY_SERVER_PORT:8080}
  shutdown: graceful
management:
  endpoints:
    web:
      exposure:
        include: health,info
autumn-wind:
  gateway:
    downstream:
      identity-base-url: ${GATEWAY_IDENTITY_BASE_URL}
      model-registry-base-url: ${GATEWAY_MODEL_REGISTRY_BASE_URL}
```

`CorrelationIdWebFilter` 使用 `^[A-Za-z0-9._-]{16,64}$`，否则生成不含敏感上下文的 UUID 字符串；把值放入 Exchange Attribute，并写入响应 Header。

- [x] **Step 4：运行绿灯与 reactor 检查**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/gateway-service -am test
git diff --check
```

预期：Gateway 定向测试通过，根 reactor 能识别模块。

- [x] **Step 5：双重评审后由主代理提交**

```text
build: 建立Gateway服务基础
```

---

### Task 2：RSA 密钥、Service JWT 与 JWKS（已完成）

**文件：**

- 修改：`services/gateway-service/pom.xml`
- 修改：`services/gateway-service/src/main/resources/application.yaml`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/configuration/GatewayServiceJwtProperties.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/security/RsaKeyMaterial.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/security/RsaKeyMaterialLoader.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/security/ServiceJwtRequest.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/security/ServiceJwtIssuer.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/security/NimbusServiceJwtIssuer.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/security/GatewayJwksController.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/configuration/ServiceJwtConfiguration.java`
- 测试：`services/gateway-service/src/test/java/io/github/yanhuo218/autumnwind/gateway/security/RsaKeyMaterialLoaderTest.java`
- 测试：`services/gateway-service/src/test/java/io/github/yanhuo218/autumnwind/gateway/security/NimbusServiceJwtIssuerTest.java`
- 测试：`services/gateway-service/src/test/java/io/github/yanhuo218/autumnwind/gateway/security/GatewayJwksControllerTest.java`

**接口：**

```java
public record ServiceJwtRequest(String audience, Set<String> scopes, UUID actorUserId) {
    public static ServiceJwtRequest service(String audience, String scope);
    public static ServiceJwtRequest actor(String audience, String scope, UUID actorUserId);
}

public interface ServiceJwtIssuer {
    String issue(ServiceJwtRequest request);
}
```

- [x] **Step 1：写密钥和 Token 红灯测试**

覆盖 PKCS#8 私钥、X.509 公钥、2048 位下限、公私钥匹配、Key ID、RS256、Issuer、Subject、单 Audience、60 秒寿命、唯一 `jti`、scope 排序，以及 actor 在 Registry Token 中存在、在 Introspection Token 中不存在。

- [x] **Step 2：运行红灯**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/gateway-service "-Dtest=RsaKeyMaterialLoaderTest,NimbusServiceJwtIssuerTest,GatewayJwksControllerTest" test
```

预期：类型不存在而编译失败。

- [x] **Step 3：实现最小 JWT 边界**

配置固定读取：

```yaml
service-jwt:
  issuer: ${GATEWAY_SERVICE_JWT_ISSUER}
  private-key-path: ${GATEWAY_SERVICE_JWT_PRIVATE_KEY_PATH}
  public-key-path: ${GATEWAY_SERVICE_JWT_PUBLIC_KEY_PATH}
  key-id: ${GATEWAY_SERVICE_JWT_KEY_ID}
  lifetime: PT60S
```

使用 Java `KeyFactory` 解析 PEM，使用 `SHA256withRSA` 对固定进程内随机挑战签名并验签确认匹配。JWT 使用 `NimbusJwtEncoder`；任何 `toString()` 都不得包含 Token 或私钥。

JWKS 路径固定为 `GET /internal/v1/security/jwks`，只输出 `kty/kid/use/alg/n/e`，并设置 `Cache-Control: public, max-age=300`。

- [x] **Step 4：运行绿灯和敏感字段断言**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/gateway-service "-Dtest=RsaKeyMaterialLoaderTest,NimbusServiceJwtIssuerTest,GatewayJwksControllerTest" test
```

预期：全部通过；JWKS JSON 不含 `d/p/q/dp/dq/qi`。

- [x] **Step 5：双重评审后由主代理提交**

```text
feat: 建立Gateway服务JWT边界
```

---

### Task 3：Identity 公共认证透明代理（已完成）

**文件：**

- 修改：`services/gateway-service/pom.xml`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/configuration/GatewayWebClientConfiguration.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/configuration/GatewaySecurityConfiguration.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/identity/IdentityAuthProxyClient.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/identity/IdentityAuthProxyController.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/web/ProxyResponse.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/web/GatewayErrorResponseWriter.java`
- 测试：`services/gateway-service/src/test/java/io/github/yanhuo218/autumnwind/gateway/identity/IdentityAuthProxyControllerTest.java`
- 测试：`services/gateway-service/src/test/java/io/github/yanhuo218/autumnwind/gateway/configuration/GatewaySecurityConfigurationTest.java`

**接口：**

```java
public record ProxyResponse(HttpStatusCode status, HttpHeaders headers, byte[] body) {}

public interface IdentityAuthProxyClient {
    Mono<ProxyResponse> forward(HttpMethod method, String path, HttpHeaders headers, byte[] body,
                                String correlationId);
}
```

- [x] **Step 1：写六条路径与 Header 白名单红灯测试**

精确覆盖规格中的六条认证路由；断言 Cookie、CSRF、Content-Type、Accept、关联 ID 和 Trace 可转发，浏览器 Authorization、actor 与转发 Header 被删除；`Set-Cookie` 原样返回；17 KiB 登录正文返回 `413` 且下游未收到请求。

- [x] **Step 2：运行红灯**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/gateway-service "-Dtest=IdentityAuthProxyControllerTest,GatewaySecurityConfigurationTest" test
```

预期：代理类型不存在而失败。

- [x] **Step 3：实现显式路由和受限转发**

不使用通配代理 Controller。每个方法只映射规格列出的固定路径；请求正文先使用 `DataBufferUtils.join(..., 16 * 1024)` 限制，再交给配置了 1 MiB响应上限和 5 秒超时的 Identity WebClient。

Gateway Security 禁用 HttpSession、Basic、Form Login 和 Gateway 自身 CSRF；只放行六条认证路由、JWKS、health/info 和模型列表 GET，其他路由拒绝。认证写请求仍携带 Identity CSRF Cookie/Header，由 Identity 终审。

- [x] **Step 4：运行绿灯**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/gateway-service "-Dtest=IdentityAuthProxyControllerTest,GatewaySecurityConfigurationTest" test
```

预期：路径、方法、Header、Cookie、超限和错误映射测试通过。

- [x] **Step 5：双重评审后由主代理提交**

```text
feat: 实现Identity认证透明代理
```

---

### Task 4：Session Introspection 与请求身份（已完成）

**文件：**

- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/security/SessionCookieExtractor.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/security/GatewayUserPrincipal.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/identity/SessionIntrospectionRequest.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/identity/SessionIntrospectionResponse.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/identity/IdentitySessionClient.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/security/GatewaySessionAuthenticationWebFilter.java`
- 测试：`services/gateway-service/src/test/java/io/github/yanhuo218/autumnwind/gateway/security/SessionCookieExtractorTest.java`
- 测试：`services/gateway-service/src/test/java/io/github/yanhuo218/autumnwind/gateway/identity/IdentitySessionClientTest.java`
- 测试：`services/gateway-service/src/test/java/io/github/yanhuo218/autumnwind/gateway/security/GatewaySessionAuthenticationWebFilterTest.java`

**接口：**

```java
public record GatewayUserPrincipal(UUID userId, String role, Instant expiresAt) {}

public interface IdentitySessionClient {
    Mono<GatewayUserPrincipal> introspect(String rawSession, String correlationId);
}
```

- [x] **Step 1：写 Cookie 与 fail-closed 红灯测试**

覆盖无 Cookie、空白、重复、inactive、过期、非 ACTIVE、字段缺失、非法 JSON、错误媒体类型、2 秒超时和正常 ACTIVE。断言 Introspection JWT 为 `aud=identity-service`、scope `identity.session.introspect` 且无 `actor_user_id`。

- [x] **Step 2：运行红灯**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/gateway-service "-Dtest=SessionCookieExtractorTest,IdentitySessionClientTest,GatewaySessionAuthenticationWebFilterTest" test
```

预期：类型不存在而失败。

- [x] **Step 3：实现不缓存身份确认**

过滤器只匹配 `GET /api/v1/model-registry/models`。成功时把 `GatewayUserPrincipal` 放入 Exchange Attribute `gateway.authenticatedUser`；无效会话写 `401 AW-GATEWAY-AUTH-0001`；Identity 故障写 `503 AW-GATEWAY-DEPENDENCY-0001`；协议错误写 `502 AW-GATEWAY-DEPENDENCY-0003`。

`SessionIntrospectionRequest.toString()` 固定返回 `sessionValue=<REDACTED>`，任何错误都不携带原始响应或 Session。

- [x] **Step 4：运行绿灯**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/gateway-service "-Dtest=SessionCookieExtractorTest,IdentitySessionClientTest,GatewaySessionAuthenticationWebFilterTest" test
```

- [x] **Step 5：双重评审后由主代理提交**

```text
feat: 接入Gateway浏览器会话校验
```

---

### Task 5：Model Registry 只读 scope（已完成）

**文件：**

- 修改：`contracts/openapi/model-registry.openapi.json`
- 修改：`scripts/verify-contracts.ps1`
- 修改：`services/model-registry-service/src/main/java/io/github/yanhuo218/autumnwind/modelregistry/infrastructure/configuration/ModelRegistrySecurityConfiguration.java`
- 修改测试：`services/model-registry-service/src/test/java/io/github/yanhuo218/autumnwind/modelregistry/interfaces/http/ModelRegistryModelSecurityTest.java`

**接口：**

```text
model-registry.model.read
```

- [x] **Step 1：写只读 scope 红灯测试和契约断言**

测试只读 Token 可 GET 列表/单项，不可 POST/PUT；既有 manage Token 仍可读写；两种 Token 均必须有合法 `actor_user_id`。验证脚本要求 OpenAPI 描述同时声明 read/manage 的方法边界。

- [x] **Step 2：运行红灯**

```powershell
pwsh -NoProfile -File scripts/verify-contracts.ps1
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/model-registry-service "-Dtest=ModelRegistryModelSecurityTest" test
```

预期：新 scope 尚未授权或契约未声明而失败。

- [x] **Step 3：实现最小权限兼容授权**

GET 接受 `SCOPE_model-registry.model.read` 或 `SCOPE_model-registry.model.manage`；POST/PUT 只接受 manage。复用既有 actor UUID 校验，不放宽端点或内部解析权限。

- [x] **Step 4：运行绿灯**

```powershell
pwsh -NoProfile -File scripts/verify-contracts.ps1
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/model-registry-service "-Dtest=ModelRegistryModelSecurityTest" test
```

- [x] **Step 5：双重评审后由主代理提交**

```text
feat: 增加模型目录只读权限
```

---

### Task 6：Gateway 只读模型目录代理（已完成）

**文件：**

- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/model/ModelRegistryProxyClient.java`
- 新建：`services/gateway-service/src/main/java/io/github/yanhuo218/autumnwind/gateway/model/ModelCatalogProxyController.java`
- 测试：`services/gateway-service/src/test/java/io/github/yanhuo218/autumnwind/gateway/model/ModelCatalogProxyControllerTest.java`

**接口：**

```java
public interface ModelRegistryProxyClient {
    Mono<ProxyResponse> listModels(UUID actorUserId, String correlationId);
}
```

- [x] **Step 1：写身份传播红灯测试**

有效 Session 场景断言 Registry 收到 `aud=model-registry-service`、scope `model-registry.model.read`、正确 `actor_user_id` 与关联 ID；不得收到 Cookie、CSRF、浏览器 Authorization 或伪造 actor Header。覆盖合法业务错误透传、非法错误转 `502`、连接失败转 `503`。

- [x] **Step 2：运行红灯**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/gateway-service "-Dtest=ModelCatalogProxyControllerTest" test
```

预期：代理类型不存在而失败。

- [x] **Step 3：实现只读代理**

Controller 只允许 GET 且从 `gateway.authenticatedUser` 读取身份。Client 每请求签发新 Token，固定请求 `/api/v1/model-registry/models`，5 秒超时，1 MiB JSON 上限；只返回 Content-Type、关联 ID、Retry-After 与安全缓存 Header。

- [x] **Step 4：运行 Gateway 与 Registry 绿灯**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/gateway-service,services/model-registry-service -am test
git diff --check
```

- [x] **Step 5：双重评审后由主代理提交**

```text
feat: 提供Gateway模型目录代理
```

---

### Task 7：安全回归、中文文档与完整验证（已完成）

**文件：**

- 新建：`docs/development/gateway-service.md`
- 修改：`README.md`
- 修改：`docs/development/execution-plan.md`
- 修改：`docs/superpowers/plans/2026-07-19-gateway-auth-foundation.md`
- 按评审结论修改本计划范围内测试或实现文件。

- [x] **Step 1：补充跨边界安全测试**

加入测试证明：JWKS 无私钥字段；所有未声明路由拒绝；下游错误不泄露地址/正文；敏感 DTO 的 `toString()` 脱敏；17 KiB 请求被拒绝；Cookie 不进入 Registry；JWT Audience/scope/actor 不可复用。

- [x] **Step 2：编写中文开发说明**

文档必须记录端口、环境变量名、PEM 格式、内部 HTTPS、JWKS、六条认证路由、只读模型路由、超时、错误、测试命令和真实 Conversation 尚未接入。不得包含密钥示例正文。

- [x] **Step 3：同步总执行状态**

`execution-plan.md` 更新为阶段 1-3 完成、阶段 4 正在完成 Gateway/Conversation 真实链路；删除已经过时的“阶段 3 下一批”描述。README 增加 Gateway 开发文档入口，但不得声称生产文本聊天已经完成。

- [x] **Step 4：运行完整验证**

```powershell
pwsh -NoProfile -File scripts/verify-contracts.ps1
mvn "-Dmaven.repo.local=$PWD\.m2\repository" test
pnpm contracts:frontend
pnpm check
pnpm test
pnpm build
git diff --check
```

预期：全部退出 0；允许保留已经记录的前端主 chunk 非阻断警告。

- [x] **Step 5：安全与范围检查**

确认暂存文件不包含 `.agents/`、`AGENTS.md`、`.codex/`、`.superpowers/`、PEM、JWK 私钥字段、Token、Cookie 值、密码、真实下游地址、构建产物或测试缓存。

- [x] **Step 6：最终评审后由主代理提交并推送**

```text
test: 验证Gateway认证安全边界
```

## 完成标准

- 七个任务全部经过 TDD、定向验证与双重评审。
- Gateway、Identity 和 Model Registry 的信任边界符合设计规格。
- 根 Maven、pnpm、契约和差异验证通过。
- `main` 工作区干净并与 `origin/main` 一致。
- 本批不声称真实 Conversation 链路已完成。
