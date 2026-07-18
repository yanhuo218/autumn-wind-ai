# 用户端聊天工作区实施计划

> **面向代理执行者：** 必须使用 `superpowers:subagent-driven-development` 按任务顺序实施；每个任务使用独立实现上下文，完成 TDD、自查和双维度评审后再进入下一任务。所有步骤使用复选框跟踪。

**目标：** 交付可在 Conversation Mock 上完整运行的用户端文本聊天工作区，覆盖会话、模型选择、发送、流式状态、停止、重连、`replay.reset`、失败恢复和重新生成，并为后续真实 BFF、附件与多模态接入保留稳定边界。

**架构：** pnpm 工作区新增独立 `apps/user-web`，公共 OpenAPI 与 SSE Schema 生成到 `packages/api-contracts`。页面业务只依赖 `ConversationClient`、`ModelCatalog` 和生成流状态机，不读取端点或凭据；Vite 开发代理把同源 `/api` 请求转发到本地 Mock/服务，生产环境继续由 BFF/API Gateway 保持同一路径。

**技术栈：** Node 26、pnpm 11、React 19、Vite 8、TypeScript 5.9、React Router 8、TanStack Query 5、Tailwind CSS 4、Radix UI、Lucide、Ajv 2020、React Markdown、Vitest、Testing Library、Playwright。

## 全局约束

- 人工项目文档和代码注释统一使用简体中文；生成代码中的第三方英文注释保持原样并注明生成方式。
- 不修改既有服务目录路径，不创建额外 worktree；实现任务不得并行写共享工作区。
- 子代理禁止执行 `git add`、`git commit`、`git push`、分支切换、历史改写或清理；Git 由主代理在评审通过后统一执行。
- Node 必须满足根 `package.json` 的 `>=24 <27`，pnpm 必须满足 `>=11 <12`；本批实测基线为 Node `26.5.0`、pnpm `11.12.0`。
- 依赖精确锁定：React/React DOM `19.2.7`、Vite `8.1.5`、TypeScript `5.9.3`、React Router `8.2.0`、TanStack Query `5.101.2`、Tailwind CSS `4.3.3`、Radix UI `1.6.2`、Lucide React `1.25.0`、Vitest `4.1.10`、Playwright `1.61.1`。
- TypeScript 不升级到 `7.0.2`；`openapi-typescript 7.13.0` 的正式 peer 约束为 `typescript ^5.x`。
- 浏览器请求固定使用同源 `/api/v1` 和 `credentials: "include"`；前端不得接收或展示端点 URL、API Key、Authorization Header、Cookie、Provider 原始响应或内部堆栈。
- Conversation Mock 只绑定 `127.0.0.1:4174`；Vite 开发服务器固定 `127.0.0.1:4173`，通过开发代理访问 Mock，页面代码不得写死 `4174`。
- Model Registry 本地真实服务端口为 `8083`；开发默认使用无凭据的 `MockModelCatalog`，通过 `VITE_MODEL_CATALOG_MODE=http` 显式切换真实目录。
- 第一批只实现文本输入/文本输出，不显示附件、图片生成、视频生成、管理端或深色主题控件。
- 页面遵循 `docs/superpowers/specs/2026-07-19-chat-workspace-visual-design.md`；不复制 DeepSeek Logo、品牌色、文案或精确布局。
- 所有网络数据先经过生成类型和运行时校验；SSE 使用 Draft 2020-12 Schema 与 Ajv 2020，不手写另一套事件名或状态枚举。
- `content.delta` 不逐段进入 live region；生命周期状态使用 polite/atomic 播报，生成容器正确维护 `aria-busy`。
- 测试和示例只能使用固定占位 UUID 与 Mock 文案，不得写入真实账号、密钥、Token、端点或用户对话。

## 文件与职责

- `packages/api-contracts/`：OpenAPI/SSE 生成类型、由公共契约结构化提取的运行时 Schema 和 Ajv 校验入口。
- `scripts/generate-frontend-contracts.mjs`：从仓库公共契约生成 TypeScript，不维护第二份手写契约。
- `apps/user-web/src/lib/`：HTTP、错误和 Query Client 基础设施。
- `apps/user-web/src/features/models/`：模型目录接口、HTTP/Mock 适配器和能力筛选。
- `apps/user-web/src/features/conversation/api/`：Conversation HTTP Client 与增量 SSE Parser。
- `apps/user-web/src/features/conversation/state/`：事件幂等 reducer、流控制器和重连策略。
- `apps/user-web/src/features/conversation/components/`：会话侧栏、消息列、状态轨、模型选择器和输入区。
- `apps/user-web/src/routes/`：路由级查询编排和页面状态，不下沉网络协议细节。
- `apps/user-web/e2e/`：Mock 驱动的桌面、断点和移动端关键流程。

---

### Task 1：补齐会话详情的只读消息投影

**文件：**

- 修改：`contracts/openapi/conversation.openapi.json`
- 修改：`scripts/verify-contracts.ps1`
- 修改：`scripts/mock-conversation-api.mjs`
- 修改：`scripts/tests/mock-conversation-api.test.mjs`
- 修改：`contracts/README.md`

**接口：**

- `ConversationDetailView.messages`：当前活动分支、按显示顺序排列的 `MessageView[]`。
- `MessageView`：必填 `messageId`、`role`、`content`、`completeness`、`generationId`、`createdAt`，响应允许新增字段。
- `MessageRole`：公共 V1 只暴露 `USER`、`ASSISTANT`；System Prompt 不返回浏览器。
- `MessageCompleteness`：`COMPLETE`、`PARTIAL`。
- 用户消息 `generationId` 为 `null`；助手消息使用对应生成 UUID。
- Mock 会话维护按顺序的 turn；重新生成复用原 `userMessageId`，只替换该 turn 的活动助手生成，不重复用户消息。

- [ ] **Step 1：先写消息投影与重新生成红灯测试**

在 `scripts/tests/mock-conversation-api.test.mjs` 新增精确断言：

```javascript
test('会话详情返回当前活动分支的用户与助手消息', async () => {
  const conversation = await createConversation();
  const accepted = await createGeneration(conversation.conversationId, randomUUID());
  await readSse(accepted.eventsUrl);

  const detail = await requestJson(`/api/v1/conversations/${conversation.conversationId}`);

  assert.deepEqual(detail.messages.map((message) => message.role), ['USER', 'ASSISTANT']);
  assert.equal(detail.messages[0].content.blocks[0].text, '请生成一段测试文本。');
  assert.equal(detail.messages[0].generationId, null);
  assert.equal(detail.messages[1].generationId, accepted.generationId);
  assert.equal(detail.messages[1].completeness, 'COMPLETE');
});

test('重新生成复用用户消息并替换活动助手分支', async () => {
  const conversation = await createConversation();
  const first = await createGeneration(conversation.conversationId, randomUUID());
  await readSse(first.eventsUrl);
  const regenerated = await requestJson(`/api/v1/generations/${first.generationId}/regenerate`, {
    method: 'POST',
    body: JSON.stringify({ clientRequestId: randomUUID() })
  }, 202);
  await readSse(regenerated.eventsUrl);

  const detail = await requestJson(`/api/v1/conversations/${conversation.conversationId}`);

  assert.deepEqual(detail.messages.map((message) => message.role), ['USER', 'ASSISTANT']);
  assert.equal(detail.messages[1].generationId, regenerated.generationId);
});
```

- [ ] **Step 2：运行红灯**

运行：

```powershell
node --test scripts/tests/mock-conversation-api.test.mjs
```

预期：新测试因 `detail.messages` 不存在而失败，既有 15 项保持通过。

- [ ] **Step 3：扩展公共 OpenAPI 与 verifier**

在 `ConversationDetailView.required` 加入 `messages`，定义：

```json
{
  "MessageRole": { "type": "string", "enum": ["USER", "ASSISTANT"] },
  "MessageCompleteness": { "type": "string", "enum": ["COMPLETE", "PARTIAL"] },
  "MessageView": {
    "type": "object",
    "additionalProperties": true,
    "required": ["messageId", "role", "content", "completeness", "generationId", "createdAt"],
    "properties": {
      "messageId": { "type": "string", "format": "uuid" },
      "role": { "$ref": "#/components/schemas/MessageRole" },
      "content": { "$ref": "#/components/schemas/MessageContent" },
      "completeness": { "$ref": "#/components/schemas/MessageCompleteness" },
      "generationId": {
        "oneOf": [
          { "type": "string", "format": "uuid" },
          { "type": "null" }
        ]
      },
      "createdAt": { "type": "string", "format": "date-time" }
    },
    "allOf": [
      {
        "oneOf": [
          {
            "properties": {
              "role": { "const": "USER" },
              "generationId": { "type": "null" }
            }
          },
          {
            "properties": {
              "role": { "const": "ASSISTANT" },
              "generationId": { "type": "string", "format": "uuid" }
            }
          }
        ]
      }
    ]
  }
}
```

`verify-contracts.ps1` 必须精确校验：`messages` 必填、数组项引用、两个枚举、`MessageView` 六个必填字段、`generationId` 的 UUID/null 分支、`USER -> null` 与 `ASSISTANT -> UUID` 的角色分支，以及响应 `additionalProperties` 兼容策略。

- [ ] **Step 4：实现 Mock 活动分支投影**

会话对象新增：

```javascript
turns: []
```

首次生成追加 `{ userMessageId, content, createdAt, activeGenerationId }`；重新生成查找原 turn、复用 `userMessageId` 并更新 `activeGenerationId`。详情响应使用当前活动生成构造一条 USER 与一条 ASSISTANT 消息；`SUCCEEDED` 为 `COMPLETE`，其他状态为 `PARTIAL`。

- [ ] **Step 5：运行 Task 1 验证**

```powershell
./scripts/verify-contracts.ps1
pnpm test:conversation-mock
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/conversation-service -am test
git diff --check
```

预期：契约通过；Mock 共 17 项通过；Conversation 6 项通过；无空白错误。

- [ ] **Step 6：评审通过后提交**

```text
feat: 补充会话消息只读投影
```

---

### Task 2：建立用户端工作区与生成契约包

**文件：**

- 修改：`package.json`
- 修改：`pnpm-lock.yaml`
- 新建：`scripts/generate-frontend-contracts.mjs`
- 新建：`packages/api-contracts/package.json`
- 新建：`packages/api-contracts/tsconfig.json`
- 新建：`packages/api-contracts/src/index.ts`
- 新建：`packages/api-contracts/src/validate-conversation-event.ts`
- 新建：`packages/api-contracts/src/validate-http-response.ts`
- 生成：`packages/api-contracts/src/generated/conversation.ts`
- 生成：`packages/api-contracts/src/generated/model-registry.ts`
- 生成：`packages/api-contracts/src/generated/conversation-stream-event.ts`
- 生成：`packages/api-contracts/src/generated/conversation-stream-event.schema.json`
- 生成：`packages/api-contracts/src/generated/http-response.schema.json`
- 新建：`packages/api-contracts/src/validate-conversation-event.test.ts`
- 新建：`packages/api-contracts/src/validate-http-response.test.ts`
- 新建：`apps/user-web/package.json`
- 新建：`apps/user-web/index.html`
- 新建：`apps/user-web/tsconfig.json`
- 新建：`apps/user-web/vite.config.ts`
- 新建：`apps/user-web/vitest.config.ts`
- 新建：`apps/user-web/src/main.tsx`
- 新建：`apps/user-web/src/app.tsx`
- 新建：`apps/user-web/src/app.test.tsx`
- 新建：`apps/user-web/src/test/setup.ts`

**接口：**

- 包名：`@autumn-wind/api-contracts`、`@autumn-wind/user-web`。
- 生成入口导出 `Conversation*`、`MessageView`、`ModelView`、`ConversationStreamEventV1`、SSE 校验器，以及 Conversation/Model Registry HTTP 响应校验器。
- Vite 同源代理：`/api/v1/conversations` 与 `/api/v1/generations` -> `127.0.0.1:4174`；`/api/v1/model-registry` -> `127.0.0.1:8083`。

- [ ] **Step 1：建立缺失工作区红灯**

先在根 `package.json` 加入命令但不创建包：

```json
{
  "contracts:frontend": "pnpm --filter @autumn-wind/api-contracts --fail-if-no-match generate",
  "dev:user-web": "pnpm --filter @autumn-wind/user-web --fail-if-no-match dev",
  "test:user-web": "pnpm --filter @autumn-wind/user-web --fail-if-no-match test"
}
```

运行 `pnpm contracts:frontend`，预期因包不存在而失败。

- [ ] **Step 2：锁定依赖并创建包清单**

根开发依赖固定：

```json
{
  "openapi-typescript": "7.13.0",
  "json-schema-to-typescript": "15.0.4",
  "typescript": "5.9.3"
}
```

`apps/user-web/package.json` 固定使用：

```json
{
  "name": "@autumn-wind/user-web",
  "private": true,
  "type": "module",
  "scripts": {
    "dev": "vite --host 127.0.0.1 --port 4173",
    "build": "tsc -b && vite build",
    "check": "tsc -b --pretty false",
    "test": "vitest run"
  },
  "dependencies": {
    "@autumn-wind/api-contracts": "workspace:*",
    "@tanstack/react-query": "5.101.2",
    "lucide-react": "1.25.0",
    "radix-ui": "1.6.2",
    "react": "19.2.7",
    "react-dom": "19.2.7",
    "react-markdown": "10.1.0",
    "react-router": "8.2.0",
    "remark-gfm": "4.0.1"
  },
  "devDependencies": {
    "@playwright/test": "1.61.1",
    "@tailwindcss/vite": "4.3.3",
    "@testing-library/dom": "10.4.1",
    "@testing-library/react": "16.3.2",
    "@testing-library/user-event": "14.6.1",
    "@types/node": "26.1.1",
    "@types/react": "19.2.17",
    "@types/react-dom": "19.2.3",
    "@vitejs/plugin-react": "6.0.3",
    "jsdom": "29.1.1",
    "tailwindcss": "4.3.3",
    "typescript": "5.9.3",
    "vite": "8.1.5",
    "vitest": "4.1.10"
  }
}
```

`packages/api-contracts` 依赖 `ajv 8.20.0`、`ajv-formats 3.0.1`，开发依赖 `typescript 5.9.3`、`vitest 4.1.10`。

- [ ] **Step 3：实现确定性契约生成脚本**

`scripts/generate-frontend-contracts.mjs` 必须使用 `openapi-typescript` 的 `openapiTS`/`astToString`、`json-schema-to-typescript` 的 `compile` 和 Node `fs/promises`，从以下唯一来源生成文件：

```javascript
const sources = {
  conversation: 'contracts/openapi/conversation.openapi.json',
  modelRegistry: 'contracts/openapi/model-registry.openapi.json',
  stream: 'contracts/events/conversation-stream-event.v1.schema.json'
};
```

脚本先解析 JSON，再输出；不得用正则改写契约。它必须把两个 OpenAPI 的 `components.schemas` 结构化复制到生成文件的 `$defs`，递归把内部 Schema 引用转换为 `$defs` 引用，并为前端实际读取的 `ConversationListView`、`ConversationDetailView`、`ConversationView`、`GenerationAcceptedView`、`GenerationView`、`ErrorResponse` 和 `ModelView[]` 建立根 Schema。不得手写第二份字段定义；每次生成相同输入必须得到字节一致输出。

- [ ] **Step 4：实现 Ajv 2020 运行时验证并先写测试**

SSE 测试至少覆盖合法 `content.delta` 通过、缺少 `sequence` 失败、未知 `eventType` 失败。HTTP 响应测试至少覆盖合法会话详情与模型数组通过、缺少必填字段失败、错误字段类型失败。实现入口：

```typescript
export function isConversationStreamEvent(value: unknown): value is ConversationStreamEventV1;
export function isConversationListView(value: unknown): value is ConversationListView;
export function isConversationDetailView(value: unknown): value is ConversationDetailView;
export function isConversationView(value: unknown): value is ConversationView;
export function isGenerationAcceptedView(value: unknown): value is GenerationAcceptedView;
export function isGenerationView(value: unknown): value is GenerationView;
export function isModelViewList(value: unknown): value is ModelView[];
export function isPublicErrorResponse(value: unknown): value is ErrorResponse;
```

使用 `Ajv2020` 与 `addFormats` 编译生成的 Schema；校验失败只抛出稳定公共错误，不得记录原始响应、失败事件或 Ajv 的数据值。

- [ ] **Step 5：创建最小 Vite 应用与代理**

`vite.config.ts` 使用 `react()`、`tailwindcss()`，并固定：

```typescript
server: {
  host: '127.0.0.1',
  port: 4173,
  strictPort: true,
  proxy: {
    '/api/v1/conversations': 'http://127.0.0.1:4174',
    '/api/v1/generations': 'http://127.0.0.1:4174',
    '/api/v1/model-registry': 'http://127.0.0.1:8083'
  }
}
```

最小 `App` 只渲染 `<main data-testid="user-web-root" />`，不提前实现页面。增加一项真实渲染 smoke test，并保持 Vitest 在零测试发现时失败，不使用 `passWithNoTests`。

- [ ] **Step 6：安装、生成和验证**

```powershell
pnpm install
pnpm contracts:frontend
pnpm --filter @autumn-wind/api-contracts test
pnpm --filter @autumn-wind/api-contracts check
pnpm --filter @autumn-wind/user-web test
pnpm --filter @autumn-wind/user-web build
git diff --check
```

预期：SSE 与 HTTP 响应运行时校验测试通过，用户端 smoke test 1 项通过，两个包类型检查通过，用户端构建成功。

- [ ] **Step 7：评审通过后提交**

```text
build: 建立用户端前端工作区
```

---

### Task 3：实现 Conversation Client、SSE Parser 与流状态机

**文件：**

- 新建：`apps/user-web/src/lib/http-error.ts`
- 新建：`apps/user-web/src/lib/fetch-json.ts`
- 新建：`apps/user-web/src/features/conversation/api/conversation-client.ts`
- 新建：`apps/user-web/src/features/conversation/api/sse-parser.ts`
- 新建：`apps/user-web/src/features/conversation/api/conversation-client.test.ts`
- 新建：`apps/user-web/src/features/conversation/api/sse-parser.test.ts`
- 新建：`apps/user-web/src/features/conversation/state/generation-state.ts`
- 新建：`apps/user-web/src/features/conversation/state/generation-reducer.ts`
- 新建：`apps/user-web/src/features/conversation/state/generation-reducer.test.ts`

**接口：**

```typescript
export interface ConversationClient {
  listConversations(signal?: AbortSignal): Promise<ConversationListView>;
  getConversation(conversationId: string, signal?: AbortSignal): Promise<ConversationDetailView>;
  createConversation(title?: string, signal?: AbortSignal): Promise<ConversationView>;
  archiveConversation(conversationId: string, signal?: AbortSignal): Promise<void>;
  createGeneration(conversationId: string, request: GenerationCreateRequest, signal?: AbortSignal): Promise<GenerationAcceptedView>;
  getGeneration(generationId: string, signal?: AbortSignal): Promise<GenerationView>;
  streamGeneration(eventsUrl: string, lastEventId?: string, signal?: AbortSignal): AsyncGenerator<ConversationStreamEventV1>;
  stopGeneration(generationId: string, signal?: AbortSignal): Promise<GenerationView>;
  regenerate(generationId: string, request: RegenerateRequest, signal?: AbortSignal): Promise<GenerationAcceptedView>;
}
```

- [ ] **Step 1：先写 SSE 分块红灯测试**

覆盖同一 frame 被拆成多个字节块、多个 frame 同块、CRLF、注释 heartbeat、`id/event/data`、非法 JSON、Schema 不通过和末尾残留。合法 frame 只产生经 Ajv 验证的事件。

- [ ] **Step 2：实现增量 SSE Parser**

使用 `TextDecoder` 流式解码，以空行分 frame；同一 frame 的多个 `data:` 行用换行拼接。不得使用整响应 `response.text()`，不得把错误 frame 原文写入日志。

- [ ] **Step 3：先写 HTTP Client 红灯测试**

用注入的 `fetchImpl` 验证：

```typescript
expect(request.credentials).toBe('include');
expect(request.headers.get('Accept')).toBe('application/json');
expect(request.headers.get('Last-Event-ID')).toBe(lastEventId);
```

每个成功 JSON 响应在返回给业务层前必须调用 `@autumn-wind/api-contracts` 中对应的 Ajv 校验器；错误响应先使用公共错误 Schema 校验，再转换为只包含 `status`、公共 `code`、`message`、`correlationId` 的 `HttpError`。校验失败统一转换为不含原始响应的协议错误。

- [ ] **Step 4：实现 HTTP Client**

所有 URL 以同源相对路径解析；只允许服务返回的 `statusUrl`/`eventsUrl` 是 `/api/v1/` 相对地址，拒绝绝对外部 URL。SSE 请求声明 `Accept: text/event-stream`。

- [ ] **Step 5：先写 reducer 红灯测试**

覆盖 11 个事件、`eventId` 幂等、sequence 回退忽略、文本/推理追加、checkpoint 替换、usage 的 null、六个状态、`replay.reset` 进入 `SYNCING`，以及快照替换退出同步。

- [ ] **Step 6：实现纯函数 reducer**

```typescript
export interface GenerationUiState {
  generationId: string;
  status: GenerationStatus | 'SYNCING';
  content: string;
  reasoning: string;
  usage: UsageState;
  lastEventId?: string;
  lastSequence: number;
  seenEventIds: ReadonlySet<string>;
  error?: PublicError;
}
```

Reducer 不执行 fetch、路由或计时器；`replay.reset` 只改变同步状态，由上层控制器获取快照。

- [ ] **Step 7：运行 Task 3 验证并提交**

```powershell
pnpm --filter @autumn-wind/user-web test
pnpm --filter @autumn-wind/user-web check
git diff --check
```

提交：

```text
feat: 实现前端会话客户端与流状态
```

---

### Task 4：实现视觉 Token、基础组件与响应式聊天壳

**文件：**

- 新建：`apps/user-web/src/styles.css`
- 新建：`apps/user-web/src/components/icon-button.tsx`
- 新建：`apps/user-web/src/components/icon-button.test.tsx`
- 新建：`apps/user-web/src/components/app-shell.tsx`
- 新建：`apps/user-web/src/components/app-shell.test.tsx`
- 新建：`apps/user-web/src/features/conversation/components/generation-state-rail.tsx`
- 新建：`apps/user-web/src/features/conversation/components/generation-state-rail.test.tsx`
- 修改：`apps/user-web/src/app.tsx`
- 修改：`apps/user-web/src/main.tsx`

**接口：**

- `IconButton` 固定 `36px`，触摸断点 `40px`，必须传 `label` 并使用 Radix Tooltip。
- `AppShell` 提供稳定的 sidebar/header/message/composer 四区，不管理业务数据。
- `GenerationStateRail` 接受六状态与 `SYNCING`，负责可视状态、`aria-busy` 和 polite/atomic 生命周期播报。

- [x] **Step 1：先写语义与稳定尺寸红灯测试**

断言无 label 时 TypeScript 不通过；运行时检查按钮可访问名称、Tooltip 内容、状态轨 `role=status`、`aria-live=polite`、`aria-atomic=true` 和 `aria-busy`。

- [x] **Step 2：实现视觉 Token**

`styles.css` 必须使用规格中的固定值：

```css
:root {
  --aw-canvas: #f6f8f7;
  --aw-surface: #ffffff;
  --aw-ink: #18211e;
  --aw-muted: #66736e;
  --aw-line: #d9e0dd;
  --aw-pine: #167a68;
  --aw-rust: #b54a3a;
  --aw-amber: #8a570f;
  --aw-radius: 6px;
}
```

禁止渐变、装饰光斑、负字间距、视口字体缩放和超过 `8px` 的卡片圆角。

- [x] **Step 3：实现响应式 AppShell**

`>=1024px` 固定 `256px` 侧栏和 `56px` 顶栏；`<1024px` 使用 Radix Dialog 全高抽屉和 `64px` 双行顶栏。消息列 `max-width:780px`，输入区 `max-width:820px`。所有固定控件使用明确宽高与 `min-width:0`。

- [x] **Step 4：实现状态轨**

状态映射使用 pine/ink/muted/amber/rust 和 Lucide 图标；`content.delta` 不更新 live region 文案。`prefers-reduced-motion` 禁用流式轨动画。

- [x] **Step 5：运行 Task 4 验证并提交**

```powershell
pnpm --filter @autumn-wind/user-web test
pnpm --filter @autumn-wind/user-web check
pnpm --filter @autumn-wind/user-web build
git diff --check
```

提交：

```text
feat: 建立用户端响应式聊天壳
```

---

### Task 5：实现会话侧栏与能力驱动模型选择

**文件：**

- 新建：`apps/user-web/src/lib/query-client.ts`
- 新建：`apps/user-web/src/features/models/model-catalog.ts`
- 新建：`apps/user-web/src/features/models/http-model-catalog.ts`
- 新建：`apps/user-web/src/features/models/mock-model-catalog.ts`
- 新建：`apps/user-web/src/features/models/model-catalog.test.ts`
- 新建：`apps/user-web/src/features/models/components/model-selector.tsx`
- 新建：`apps/user-web/src/features/models/components/model-selector.test.tsx`
- 新建：`apps/user-web/src/features/conversation/components/conversation-sidebar.tsx`
- 新建：`apps/user-web/src/features/conversation/components/conversation-sidebar.test.tsx`
- 新建：`apps/user-web/src/routes/chat-route.tsx`
- 修改：`apps/user-web/src/app.tsx`

**接口：**

```typescript
export interface ModelCatalog {
  listAvailableTextModels(signal?: AbortSignal): Promise<ModelView[]>;
}
```

筛选条件必须同时满足 `enabled`、`interfaceType === 'CHAT_COMPLETIONS'`、`inputModalities` 包含 `TEXT`、`outputModality === 'TEXT'`。默认模型优先 `defaultModel`，否则选择排序后的首项；不得按名字猜能力。

- [x] **Step 1：先写模型筛选与适配器红灯测试**

覆盖禁用模型、图片生成模型、无文本输入模型被排除；HTTP 适配器固定请求 `/api/v1/model-registry/models` 且携带 Cookie；Mock 只返回固定 UUID 和能力，不含端点字段。

- [x] **Step 2：实现模型目录工厂**

```typescript
export function createModelCatalog(mode: 'mock' | 'http'): ModelCatalog;
```

开发默认 `mock`，只有 `VITE_MODEL_CATALOG_MODE=http` 使用真实服务；生产构建默认 `http`。

- [x] **Step 3：实现模型选择器**

使用 Radix Select，菜单项显示展示名和文本/视觉/文件能力图标。宽屏入口位于顶栏；中小屏只渲染中央第二行入口。长名称省略，`Esc` 关闭并恢复焦点。

- [x] **Step 4：实现会话侧栏查询与归档**

TanStack Query Key 固定：

```typescript
const conversationKeys = {
  all: ['conversations'] as const,
  detail: (id: string) => ['conversations', id] as const
};
```

列表按“今天 / 过去 7 天 / 更早”分组；归档成功后失效 `all`，当前会话被归档时导航到 `/chat`。不进行跨页乐观伪造。

- [x] **Step 5：实现路由**

路由固定 `/chat` 与 `/chat/:conversationId`；根路径重定向 `/chat`。路由层只编排 Client、Query 和选择状态。

- [x] **Step 6：运行 Task 5 验证并提交**

```powershell
pnpm --filter @autumn-wind/user-web test
pnpm --filter @autumn-wind/user-web check
pnpm --filter @autumn-wind/user-web build
git diff --check
```

提交：

```text
feat: 实现会话导航与模型选择
```

---

### Task 6：实现消息列、输入区与发送/停止闭环

**文件：**

- 新建：`apps/user-web/src/features/conversation/components/message-list.tsx`
- 新建：`apps/user-web/src/features/conversation/components/message-list.test.tsx`
- 新建：`apps/user-web/src/features/conversation/components/composer.tsx`
- 新建：`apps/user-web/src/features/conversation/components/composer.test.tsx`
- 新建：`apps/user-web/src/features/conversation/state/use-conversation-session.ts`
- 新建：`apps/user-web/src/features/conversation/state/use-conversation-session.test.tsx`
- 修改：`apps/user-web/src/routes/chat-route.tsx`

**接口：**

```typescript
interface SubmitMessageInput {
  text: string;
  modelId: string;
}

interface ConversationSession {
  submit(input: SubmitMessageInput): Promise<void>;
  stop(): Promise<void>;
  activeGeneration?: GenerationUiState;
  submitting: boolean;
}
```

- [x] **Step 1：先写输入区交互红灯测试**

覆盖空文本禁用、未选模型禁用、`Enter` 发送、`Shift+Enter` 换行、`nativeEvent.isComposing` 时不发送、生成中同尺寸按钮切换为停止、重复提交被阻止。

- [x] **Step 2：实现稳定输入区**

文本域初始 `56px`、最大 `200px`；按钮固定尺寸。第一版不渲染附件按钮。发送时生成 UUID：

```typescript
{
  clientRequestId: crypto.randomUUID(),
  modelId,
  content: { schemaVersion: 1, blocks: [{ type: 'text', text }] }
}
```

- [x] **Step 3：先写会话提交红灯测试**

无 conversationId 时先创建会话并导航；已有会话直接生成。收到 accepted 后立即显示用户消息和 PENDING 助手轨，流事件经 reducer 更新。停止调用后保留部分文本并进入 STOPPED。

- [x] **Step 4：实现会话控制 Hook**

Hook 持有 `AbortController`，组件卸载只断开浏览器订阅，不自动停止服务端生成。提交成功或终态后失效会话列表与详情 Query。网络超时重试创建请求时必须复用同一 `clientRequestId`。

- [x] **Step 5：实现消息列**

用户消息使用轻边界块；助手正文无卡片并配状态轨。Markdown 使用 `react-markdown` + `remark-gfm`，不启用原始 HTML。复制、停止、重新生成使用 Lucide 图标和 Tooltip。

- [x] **Step 6：运行 Task 6 验证并提交**

```powershell
pnpm --filter @autumn-wind/user-web test
pnpm --filter @autumn-wind/user-web check
pnpm --filter @autumn-wind/user-web build
git diff --check
```

提交：

```text
feat: 完成文本发送与停止闭环
```

---

### Task 7：实现重连、`replay.reset`、失败恢复与重新生成

**文件：**

- 新建：`apps/user-web/src/features/conversation/state/generation-stream-controller.ts`
- 新建：`apps/user-web/src/features/conversation/state/generation-stream-controller.test.ts`
- 新建：`apps/user-web/src/features/conversation/components/generation-actions.tsx`
- 新建：`apps/user-web/src/features/conversation/components/generation-actions.test.tsx`
- 修改：`apps/user-web/src/features/conversation/state/use-conversation-session.ts`
- 修改：`apps/user-web/src/features/conversation/components/message-list.tsx`
- 修改：`scripts/mock-conversation-api.mjs`
- 修改：`scripts/tests/mock-conversation-api.test.mjs`

**接口：**

```typescript
export interface GenerationStreamControllerOptions {
  accepted: GenerationAcceptedView;
  client: ConversationClient;
  onEvent(event: ConversationStreamEventV1): void;
  onSnapshot(snapshot: GenerationView): void;
  onConnectionState(state: 'CONNECTED' | 'RECONNECTING' | 'DISCONNECTED'): void;
  signal: AbortSignal;
}
```

- [ ] **Step 1：先写重连红灯测试**

模拟流在非终态关闭；下一次请求必须携带最后 `eventId`，退避固定 `250/500/1000/2000/4000ms`，最多 5 次。使用注入 `sleep` 和假时钟，不在单元测试真实等待。Conversation Mock 新增 `disconnect-once` 场景：首次订阅在至少一条 `content.delta` 后主动关闭连接但保持非终态；携带最后事件 ID 的后续订阅继续到终态。Mock 回归测试必须证明只断开一次、续传内容不重复且最终成功。

- [ ] **Step 2：先写 reset 红灯测试**

收到 `replay.reset` 后必须：进入 `SYNCING` -> 请求 `snapshotUrl` 对应生成快照 -> 原子替换内容/状态 -> 非终态时用 reset eventId 继续订阅。快照请求失败不得把旧内容标为完成。

- [ ] **Step 3：实现流控制器**

控制器不操作 DOM；只协调 Client、重连、快照和回调。终态后不重连；用户 stop 后允许消费 `generation.stopped`，随后结束。

- [ ] **Step 4：实现失败/中断/重新生成操作**

FAILED 和 INTERRUPTED 显示稳定错误摘要、关联 ID 和重新生成按钮；STOPPED 允许重新生成。重新生成每次创建新 `clientRequestId`，成功后切换到新 `generationId/eventsUrl`，不覆盖旧结果对象。

- [ ] **Step 5：验证无障碍播报**

测试生命周期只播报开始、停止、失败、中断、重连、同步完成和完成；多个 `content.delta` 不改变 live region 文案。错误详情不得包含端点、凭据或内部堆栈。

- [ ] **Step 6：运行 Task 7 验证并提交**

```powershell
pnpm --filter @autumn-wind/user-web test
pnpm --filter @autumn-wind/user-web check
pnpm --filter @autumn-wind/user-web build
git diff --check
```

提交：

```text
feat: 完善生成重连与失败恢复
```

---

### Task 8：完成 Playwright、响应式视觉 QA 与开发说明

**文件：**

- 新建：`apps/user-web/playwright.config.ts`
- 新建：`apps/user-web/e2e/chat-workspace.spec.ts`
- 新建：`apps/user-web/e2e/responsive-layout.spec.ts`
- 修改：`apps/user-web/package.json`
- 修改：`package.json`
- 新建：`docs/development/user-web.md`
- 修改：`README.md`

**接口：**

- Playwright `baseURL`: `http://127.0.0.1:4173`。
- `webServer` 同时启动根 `pnpm mock:conversation` 和用户端 Vite，均设置 `reuseExistingServer: false`、固定端口和合理超时。
- 测试通过 URL 查询参数选择仅开发可用的 Mock 场景；生产构建忽略该参数。

- [ ] **Step 1：先写桌面关键流程 E2E**

覆盖：进入 `/chat`、选择模型、新建会话、发送成功、停止 slow、failed 错误与关联 ID、interrupted、重新生成、断线续传和 `replay.reset` 快照替换。

- [ ] **Step 2：写响应式与键盘 E2E**

逐一验证 `1440×900`、`1024×768`、`800×900`、`768×1024`、`390×844`、`360×800`。断言：

```typescript
expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true);
```

同时覆盖抽屉、唯一模型入口、长名称省略、`Esc` 焦点恢复、Tab 顺序、Enter/Shift+Enter、reduced motion 和图标可访问名称。

- [ ] **Step 3：实现仅开发 Mock 场景选择**

只在 `import.meta.env.DEV` 读取 `scenario`，允许值固定 `success/slow/failed/interrupted/replay-reset/disconnect-once`；生产构建不拼接 `scenario`。

- [ ] **Step 4：运行截图与重叠检查**

主代理启动本地服务后，用 Playwright 在 `1440×900`、`800×900`、`390×844` 截图，检查非空、侧栏/抽屉、消息列、输入区、状态轨、长文本和弹层无重叠。截图保存在 `.superpowers/` 本地评审目录，不提交仓库。

- [ ] **Step 5：编写中文开发说明**

`docs/development/user-web.md` 记录依赖版本、目录边界、启动命令、Mock/HTTP 模型目录切换、开发代理、测试命令和“Mock 不是生产服务”。不记录任何真实凭据。

- [ ] **Step 6：运行完整前端验证**

```powershell
./scripts/verify-contracts.ps1
pnpm contracts:frontend
pnpm check
pnpm test
pnpm build
pnpm --filter @autumn-wind/user-web test:e2e
git diff --check
```

预期：契约、类型检查、Vitest、生产构建与 Playwright 全部通过；六个视口无横向溢出或控件重叠。

- [ ] **Step 7：安全与范围检查**

确认暂存文件不包含 `.agents/`、`AGENTS.md`、`.codex/`、真实端点、API Key、Token、Cookie、Authorization Header、用户对话或本地截图。

- [ ] **Step 8：评审通过后提交并启动开发服务器**

```text
test: 覆盖用户端聊天关键流程
```

完成后保持 `127.0.0.1:4174` Mock 和 `127.0.0.1:4173` 用户端开发服务器运行，并把用户端 URL 交付给用户试用。

## 完成标准与后续边界

本计划完成时必须满足：

- 刷新会话详情可从服务端消息投影恢复当前活动分支，不依赖浏览器伪造历史。
- 前端只依赖公共生成类型、由 OpenAPI 生成并经 Ajv 校验的 JSON 响应与 Ajv 校验后的 SSE，不手写漂移事件目录或响应 Schema。
- Mock 与未来真实 BFF 使用同一 `ConversationClient`；切换时不修改页面业务状态。
- 文本发送、流式显示、停止、重连、reset、失败、中断和重新生成均有单元与 E2E 证据。
- 桌面、断点和移动视口符合视觉规格，无重叠、横向溢出或不稳定控件尺寸。
- 页面不暴露端点、凭据或内部实现，也不提供尚未就绪的附件和多模态控件。

后续独立计划负责：Identity 登录/CSRF 接入、真实 BFF/API Gateway、Conversation PostgreSQL 与消息持久化、Model Registry 真实用户目录、File Service 附件 Projection、图片/视频生成、管理端和深色主题。不得在本计划中用前端 Mock 伪装这些生产能力已经完成。
