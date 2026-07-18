# Conversation 核心与前端契约实施计划

> **面向代理执行者：** 必须使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans`，按任务逐项实施本计划。步骤使用复选框（`- [ ]`）跟踪。

**目标：** 建立可编译、可测试的 Conversation Service 领域基线，并交付前端可以直接使用的公共 OpenAPI、SSE Schema 和无第三方依赖 Mock API。

**架构：** Conversation Service 使用独立 Maven 模块和 `conversation` 包根，首批只实现无外部 I/O 的状态与内容领域对象。公共契约继续集中在 `contracts/`，本地 Mock 使用 Node.js 标准库实现，前端与后端共享同一套路径、状态和事件信封。

**技术栈：** Java 21、Spring Boot 4.1.0、Spring WebFlux、Spring Data JPA、Flyway、PostgreSQL、JUnit 5、OpenAPI 3.1、JSON Schema Draft 2020-12、Node.js 24 LTS、pnpm 11。

## 全局约束

- 所有人工编写的项目文档和代码注释统一使用简体中文。
- 不提交或输出真实密码、API Key、Token、Cookie、私钥或数据库凭据。
- 不修改现有目录路径；新增服务固定为 `services/conversation-service`。
- Conversation Service 默认端口为 `8085`，环境变量为 `CONVERSATION_SERVER_PORT`。
- 浏览器不能直接调用 Inference Gateway；本批不伪造尚不存在的 Gateway 内部 HTTP 流端点。
- PostgreSQL 保存永久业务事实，Redis 只保存近期 SSE 重放事件；本批不实现 Redis、RabbitMQ 或文件解析。
- 新增领域行为必须先观察失败测试，再实现最小代码并观察测试通过。
- 子代理不得执行 `git add`、`git commit`、`git push` 或改写历史；全部 Git 写操作由主代理统一完成。
- 每个任务完成后必须经过规格符合性和代码质量只读评审，再由主代理提交。

## 文件结构与职责

- `services/conversation-service/pom.xml`：Conversation 模块依赖与构建边界。
- `services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/ConversationServiceApplication.java`：Spring Boot 入口。
- `services/conversation-service/src/main/resources/application.yaml`：服务名、端口、数据库和严格 JSON 基线。
- `services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/domain/generation/GenerationStatus.java`：生成状态与合法转换。
- `services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/domain/message/MessageRole.java`：消息角色。
- `services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/domain/message/MessageCompleteness.java`：完整或部分结果。
- `services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/domain/message/ContentBlockType.java`：V1 内容块类型。
- `services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/domain/message/ContentBlock.java`：文本或 File Service 引用的单个内容块。
- `services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/domain/message/MessageContent.java`：带版本的不可变内容块集合。
- `contracts/openapi/conversation.openapi.json`：浏览器/BFF 可见公共 API 源定义。
- `contracts/events/conversation-stream-event.v1.schema.json`：公共 SSE 事件信封源定义。
- `scripts/mock-conversation-api.mjs`：本地开发 Mock API 与 Fake SSE 服务。
- `scripts/tests/mock-conversation-api.test.mjs`：Mock 行为自动化测试。

## 执行分工

- 主代理：维护计划、解决跨模块决策、暂存/提交/推送、运行完整验证并处理评审结论。
- 领域实现子代理：只允许修改 Task 1 列出的根 POM 与 `services/conversation-service` 文件，按 TDD 报告红绿测试证据，不接触 `contracts/`。
- 契约实现子代理：只允许修改 Task 2 列出的 `contracts/`、`scripts/`、`package.json` 和契约说明文件，不接触 Java 服务源码。
- 评审子代理：只读检查任务差异，分别给出规格符合性和代码质量结论，不修改文件、不执行 Git 写操作。
- 两个实现任务包含共享验证脚本，按 Task 1、Task 2 顺序执行，不并行写工作区。

---

### Task 1：Conversation 模块与领域基线

**文件：**

- 修改：`pom.xml`
- 新建：`services/conversation-service/pom.xml`
- 新建：`services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/ConversationServiceApplication.java`
- 新建：`services/conversation-service/src/main/resources/application.yaml`
- 新建：`services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/domain/generation/GenerationStatus.java`
- 新建：`services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/domain/message/MessageRole.java`
- 新建：`services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/domain/message/MessageCompleteness.java`
- 新建：`services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/domain/message/ContentBlockType.java`
- 新建：`services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/domain/message/ContentBlock.java`
- 新建：`services/conversation-service/src/main/java/io/github/yanhuo218/autumnwind/conversation/domain/message/MessageContent.java`
- 测试：`services/conversation-service/src/test/java/io/github/yanhuo218/autumnwind/conversation/domain/generation/GenerationStatusTest.java`
- 测试：`services/conversation-service/src/test/java/io/github/yanhuo218/autumnwind/conversation/domain/message/MessageContentTest.java`

**接口：**

- 产出：根 Maven reactor 可识别的 `conversation-service` 模块。
- 产出：`GenerationStatus.canTransitionTo(GenerationStatus): boolean`、`requireTransitionTo(GenerationStatus): void` 和 `terminal(): boolean`。
- 产出：`ContentBlock.text(String)`、`ContentBlock.imageReference(UUID)`、`ContentBlock.fileReference(UUID)`。
- 产出：`MessageContent(int schemaVersion, List<ContentBlock> blocks)`，当前版本固定为 `1`。

- [x] **Step 1：建立缺失模块的构建红灯**

先只在根 `pom.xml` 的 `<modules>` 中加入：

```xml
<module>services/conversation-service</module>
```

运行：

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/conversation-service test
```

预期：构建因 `services/conversation-service/pom.xml` 不存在而失败，证明 reactor 确实要求新模块。

- [x] **Step 2：创建最小模块与配置并恢复构建**

`services/conversation-service/pom.xml` 继承根 POM，并加入以下依赖：

```xml
<dependencies>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-validation</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-data-jpa</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-flyway</artifactId>
    </dependency>
    <dependency>
        <groupId>org.flywaydb</groupId>
        <artifactId>flyway-database-postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.postgresql</groupId>
        <artifactId>postgresql</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-actuator</artifactId>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-webflux-test</artifactId>
        <scope>test</scope>
    </dependency>
    <dependency>
        <groupId>org.springframework.security</groupId>
        <artifactId>spring-security-test</artifactId>
        <scope>test</scope>
    </dependency>
</dependencies>
```

应用入口只包含 `@SpringBootApplication` 和 `main`。`application.yaml` 固定以下基线，不提供数据库密码默认值：

```yaml
spring:
  application:
    name: conversation-service
  datasource:
    url: ${CONVERSATION_DATABASE_URL:jdbc:postgresql://localhost:5432/autumn_wind}
    username: ${CONVERSATION_DATABASE_USERNAME:autumn_wind}
    password: ${CONVERSATION_DATABASE_PASSWORD}
  flyway:
    create-schemas: true
    default-schema: conversation
    schemas: conversation
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        default_schema: conversation
    open-in-view: false
  jackson:
    datatype:
      enum:
        fail-on-numbers-for-enums: true
    deserialization:
      accept-float-as-int: false
      fail-on-unknown-properties: true
    mapper:
      allow-coercion-of-scalars: false

server:
  port: ${CONVERSATION_SERVER_PORT:8085}
  shutdown: graceful

management:
  endpoints:
    web:
      exposure:
        include: health,info
```

再次运行 Step 1 命令，预期 `BUILD SUCCESS` 且没有测试。

- [x] **Step 3：编写生成状态红灯测试**

测试必须覆盖所有合法边、禁止回退、终态不可变和 `null`：

```java
@Test
void 只允许设计内的生成状态转换() {
    assertTrue(PENDING.canTransitionTo(STREAMING));
    assertTrue(PENDING.canTransitionTo(STOPPED));
    assertTrue(STREAMING.canTransitionTo(SUCCEEDED));
    assertTrue(STREAMING.canTransitionTo(FAILED));
    assertTrue(STREAMING.canTransitionTo(STOPPED));
    assertTrue(STREAMING.canTransitionTo(INTERRUPTED));
    assertFalse(STREAMING.canTransitionTo(PENDING));
    assertFalse(SUCCEEDED.canTransitionTo(FAILED));
    assertFalse(FAILED.canTransitionTo(STREAMING));
    assertFalse(STOPPED.canTransitionTo(STOPPED));
    assertFalse(INTERRUPTED.canTransitionTo(SUCCEEDED));
}

@Test
void 非法转换抛出稳定领域异常() {
    assertThrows(IllegalStateException.class,
            () -> SUCCEEDED.requireTransitionTo(STREAMING));
    assertThrows(NullPointerException.class,
            () -> PENDING.requireTransitionTo(null));
}
```

运行：

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/conversation-service "-Dtest=GenerationStatusTest" test
```

预期：因 `GenerationStatus` 不存在而编译失败。

- [x] **Step 4：实现最小生成状态机并观察绿灯**

枚举值固定为 `PENDING`、`STREAMING`、`SUCCEEDED`、`FAILED`、`STOPPED`、`INTERRUPTED`。合法边固定为：

```java
PENDING -> STREAMING, FAILED, STOPPED, INTERRUPTED
STREAMING -> SUCCEEDED, FAILED, STOPPED, INTERRUPTED
```

`terminal()` 只对 `SUCCEEDED`、`FAILED`、`STOPPED`、`INTERRUPTED` 返回 `true`；同状态转换也返回 `false`。运行 Step 3 命令，预期全部通过。

- [x] **Step 5：编写消息内容红灯测试**

测试必须证明文本去除首尾空白、空文本被拒绝、引用 ID 不可空、块集合被防御性复制、空集合和非版本 1 被拒绝：

```java
@Test
void 创建带版本的不可变混合内容() {
    UUID imageId = UUID.randomUUID();
    List<ContentBlock> source = new ArrayList<>(List.of(
            ContentBlock.text(" 你好 "),
            ContentBlock.imageReference(imageId)
    ));

    MessageContent content = new MessageContent(1, source);
    source.clear();

    assertEquals("你好", content.blocks().getFirst().text());
    assertEquals(imageId, content.blocks().get(1).resourceId());
    assertThrows(UnsupportedOperationException.class,
            () -> content.blocks().add(ContentBlock.text("其他")));
}

@Test
void 拒绝非法内容块和版本() {
    assertThrows(IllegalArgumentException.class, () -> ContentBlock.text("  "));
    assertThrows(NullPointerException.class, () -> ContentBlock.fileReference(null));
    assertThrows(IllegalArgumentException.class, () -> new MessageContent(2, List.of(ContentBlock.text("文本"))));
    assertThrows(IllegalArgumentException.class, () -> new MessageContent(1, List.of()));
}
```

运行：

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/conversation-service "-Dtest=MessageContentTest" test
```

预期：因消息内容类型不存在而编译失败。

- [x] **Step 6：实现最小消息内容类型并观察绿灯**

`ContentBlock` 使用单个 record 和三个静态工厂提供清晰入口；公共规范构造器必须执行同样的互斥校验，不能让调用方组合冲突字段：

```java
public record ContentBlock(ContentBlockType type, String text, UUID resourceId) {
    public static ContentBlock text(String text) {
        String normalized = Objects.requireNonNull(text, "文本内容不能为空。").trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("文本内容不能为空白。");
        }
        return new ContentBlock(ContentBlockType.TEXT, normalized, null);
    }

    public static ContentBlock imageReference(UUID resourceId) {
        return new ContentBlock(ContentBlockType.IMAGE_REF, null,
                Objects.requireNonNull(resourceId, "图片引用不能为空。"));
    }

    public static ContentBlock fileReference(UUID resourceId) {
        return new ContentBlock(ContentBlockType.FILE_REF, null,
                Objects.requireNonNull(resourceId, "文件引用不能为空。"));
    }
}
```

record 的规范构造器必须再次校验 `type` 与 `text/resourceId` 的互斥关系，防止调用公共构造器绕过静态工厂。`MessageContent` 使用 `List.copyOf`。运行 Step 5 命令，预期全部通过。

- [x] **Step 7：运行 Task 1 验证**

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/conversation-service -am test
git diff --check
```

预期：Conversation 定向测试和其 reactor 依赖全部通过，差异无空白错误。主代理在双重评审通过后提交：

```text
build: 建立会话服务领域基线
```

---

### Task 2：公共 OpenAPI、SSE Schema 与本地 Mock

**文件：**

- 新建：`contracts/openapi/conversation.openapi.json`
- 新建：`contracts/events/conversation-stream-event.v1.schema.json`
- 新建：`scripts/mock-conversation-api.mjs`
- 新建：`scripts/tests/mock-conversation-api.test.mjs`
- 修改：`scripts/verify-contracts.ps1`
- 修改：`contracts/README.md`
- 修改：`package.json`

**接口：**

- 公共 API 前缀：`/api/v1`。
- 生成请求幂等字段：`clientRequestId: UUID`。
- SSE 信封字段：`eventId`、`eventType`、`generationId`、`sequence`、`occurredAt`、`payloadVersion`、`payload`。
- Mock 启动命令：`pnpm mock:conversation`，默认监听 `127.0.0.1:4174`。
- Mock 测试命令：`pnpm test:conversation-mock`。

- [ ] **Step 1：先扩展契约校验并观察红灯**

在 `scripts/verify-contracts.ps1` 中读取两个尚不存在的契约，并加入以下精确断言：

```powershell
$conversationOpenApi = Get-Content -Raw (Join-Path $projectRoot "contracts/openapi/conversation.openapi.json") | ConvertFrom-Json
$conversationStreamSchema = Get-Content -Raw (Join-Path $projectRoot "contracts/events/conversation-stream-event.v1.schema.json") | ConvertFrom-Json

if ($conversationOpenApi.openapi -notmatch "^3\.1\.") {
    throw "Conversation OpenAPI 必须使用 3.1.x。"
}

$requiredConversationPaths = @(
    "/api/v1/conversations",
    "/api/v1/conversations/{conversationId}",
    "/api/v1/conversations/{conversationId}/generations",
    "/api/v1/generations/{generationId}",
    "/api/v1/generations/{generationId}/events",
    "/api/v1/generations/{generationId}/stop",
    "/api/v1/generations/{generationId}/regenerate"
)
foreach ($path in $requiredConversationPaths) {
    if ($null -eq $conversationOpenApi.paths.$path) {
        throw "Conversation OpenAPI 缺少必要路径：$path"
    }
}

if ($conversationStreamSchema.'$schema' -ne "https://json-schema.org/draft/2020-12/schema") {
    throw "Conversation SSE Schema 必须使用 JSON Schema Draft 2020-12。"
}
```

运行：

```powershell
./scripts/verify-contracts.ps1
```

预期：因 `conversation.openapi.json` 不存在而失败。

- [ ] **Step 2：编写完整公共 OpenAPI 并观察契约部分绿灯**

`conversation.openapi.json` 必须定义以下操作与成功状态：

| 操作 | 成功状态 |
| --- | --- |
| `POST /api/v1/conversations` | `201` |
| `GET /api/v1/conversations` | `200` |
| `GET /api/v1/conversations/{conversationId}` | `200` |
| `DELETE /api/v1/conversations/{conversationId}` | `204` |
| `POST /api/v1/conversations/{conversationId}/generations` | `202` |
| `GET /api/v1/generations/{generationId}` | `200` |
| `GET /api/v1/generations/{generationId}/events` | `200 text/event-stream` |
| `POST /api/v1/generations/{generationId}/stop` | `200` |
| `POST /api/v1/generations/{generationId}/regenerate` | `202` |

请求和响应 Schema 必须满足：

- 所有对象请求 `additionalProperties: false`，响应允许兼容新增字段。
- `GenerationCreateRequest` 必填 `clientRequestId`、`modelId`、`content`，其中 UUID 使用 `format: uuid`。
- `MessageContent` 必填 `schemaVersion: 1` 和 1 至 100 个 `blocks`。
- `ContentBlock` 使用 `oneOf` 严格区分 `text`、`image_ref`、`file_ref`，引用只包含 UUID `resourceId`。
- `GenerationStatus` 枚举与 Java 领域值完全一致。
- `GenerationAcceptedView` 必填 `userMessageId`、`generationId`、`statusUrl`、`eventsUrl`。
- 所有错误响应引用公共 `ErrorResponse`，错误码匹配 `^AW-CONVERSATION-[A-Z][A-Z0-9_]{1,31}-[0-9]{4}$`。
- 所有成功和错误响应声明 `X-Correlation-ID`；带请求体操作声明 `415`，所有操作声明 `406` 和 `500`。
- Service JWT 说明必须包含 `conversation.manage`、`conversation.generate` 和 `actor_user_id`。
- SSE 操作声明可选 `Last-Event-ID` Header，且响应与代理缓冲禁用要求写入中文说明。

完成后运行 Step 1。此时预期只因 SSE Schema 尚不存在或不完整而失败。

- [ ] **Step 3：编写 SSE Schema 并完成契约绿灯**

Schema 使用 `oneOf` 定义以下事件，公共字段全部必填：

```text
generation.started
reasoning.delta
content.delta
content.checkpoint
usage.updated
generation.completed
generation.failed
generation.stopped
generation.interrupted
stream.heartbeat
replay.reset
```

公共字段约束固定为：

```json
{
  "eventId": { "type": "string", "minLength": 16, "maxLength": 64 },
  "generationId": { "type": "string", "format": "uuid" },
  "sequence": { "type": "integer", "minimum": 1 },
  "occurredAt": { "type": "string", "format": "date-time" },
  "payloadVersion": { "const": 1 }
}
```

Payload 规则：

- `content.delta` 和 `reasoning.delta` 必填非空 `delta`。
- `content.checkpoint` 必填完整 `content` 和最后已覆盖 `throughSequence`。
- `usage.updated` 的三个 Token 字段接受非负整数或 `null`，不得伪造缺失值。
- 四个生成终态事件必填 `status`；失败和中断事件另含公共格式错误码及关联 ID。
- `replay.reset` 必填 `snapshotUrl`，通知客户端替换本地内容。
- 每个事件分支 `additionalProperties: false`，顶层 `oneOf` 精确定义 11 个事件。

运行：

```powershell
./scripts/verify-contracts.ps1
```

预期：公共契约及各服务契约校验通过。

- [ ] **Step 4：先编写 Mock API 红灯测试**

使用 Node.js 内置 `node:test`、`assert`、`child_process` 和 `fetch`，不增加 npm 依赖。测试启动随机可用端口并覆盖：

```javascript
test('重复 clientRequestId 返回同一个生成且只产生一条 started 事件', async () => {
  const first = await createGeneration('11111111-1111-4111-8111-111111111111');
  const second = await createGeneration('11111111-1111-4111-8111-111111111111');

  assert.equal(first.generationId, second.generationId);
  assert.equal(first.eventsUrl, second.eventsUrl);
});

test('成功场景输出有序 SSE 并以 SUCCEEDED 结束', async () => {
  const accepted = await createGeneration(crypto.randomUUID());
  const events = await readSse(accepted.eventsUrl);

  assert.deepEqual(events.map((event) => event.eventType), [
    'generation.started',
    'content.delta',
    'usage.updated',
    'generation.completed'
  ]);
  assert.deepEqual(events.map((event) => event.sequence), [1, 2, 3, 4]);
});
```

另覆盖显式停止得到 `generation.stopped`、`Last-Event-ID` 只重放后续事件、`scenario=replay-reset` 返回 `replay.reset`、未知资源返回公共错误结构，以及响应不包含端点或凭据字段。

运行：

```powershell
node --test scripts/tests/mock-conversation-api.test.mjs
```

预期：因 `scripts/mock-conversation-api.mjs` 不存在而失败。

- [ ] **Step 5：实现无依赖 Mock API 与 Fake SSE 并观察绿灯**

Mock 只绑定 `127.0.0.1`，使用进程内 Map 保存会话和生成；测试占位数据不得包含真实凭据。支持以下场景：

- `success`：started、text delta、usage、completed。
- `slow`：每个事件间隔 500 毫秒。
- `failed`：产生部分文本后以稳定错误结束。
- `interrupted`：产生部分文本后进入 `INTERRUPTED`。
- `replay-reset`：首个事件为 `replay.reset`。

每个 SSE frame 使用标准格式：

```text
id: <eventId>
event: <eventType>
data: <JSON 信封>

```

实现与 OpenAPI 相同的会话创建、列表、详情、归档、生成创建、快照、事件、停止和重新生成路径。重复 `clientRequestId` 必须返回同一个生成且不重新排定事件。收到 `Last-Event-ID` 时只输出其后事件；无法找到该事件时输出 `replay.reset`。

运行 Step 4，预期全部通过且子进程正常退出。

- [ ] **Step 6：接入 pnpm 命令并更新中文契约说明**

在根 `package.json` 的 `scripts` 中加入：

```json
{
  "mock:conversation": "node scripts/mock-conversation-api.mjs",
  "test:conversation-mock": "node --test scripts/tests/mock-conversation-api.test.mjs"
}
```

`contracts/README.md` 增加 Conversation OpenAPI、SSE Schema、Mock 启动方式和“Mock 不是生产服务”的边界说明。

- [ ] **Step 7：运行 Task 2 验证**

```powershell
./scripts/verify-contracts.ps1
pnpm test:conversation-mock
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/conversation-service -am test
git diff --check
```

预期：契约、Mock 和 Conversation 模块全部通过，差异无空白错误。主代理在双重评审通过后提交：

```text
feat: 提供会话契约与流式Mock
```

## 完成标准与下一阶段

本计划完成时必须满足：

- 根 Maven reactor 包含可测试的 Conversation Service 模块。
- 领域状态机与 V1 内容块规则有真实红绿测试证据。
- OpenAPI、SSE Schema 与 Mock 使用完全一致的路径、状态和事件名。
- 契约校验和 Mock 自动化测试纳入仓库命令。
- 工作区不包含代理配置、真实凭据或无关格式变化。

完成后立即使用 `frontend-design` 为用户端聊天工作区确定视觉细节，再编写独立前端实施计划。Conversation 的 PostgreSQL V1、幂等创建、Gateway 内部 HTTP 流、检查点、Redis 重放和 Outbox 分别进入后续后端计划，避免在本批虚构未就绪依赖。
