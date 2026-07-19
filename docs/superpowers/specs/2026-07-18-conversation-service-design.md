# Conversation Service 与前端流契约设计

## 1. 目标与范围

本设计实现文本对话闭环，并为图片、文件和后续视频能力保留稳定扩展点。首批交付包括：

- 会话、消息、生成任务和标准化用量记录。
- 文本上下文组装、模型能力校验和文件 Projection 引用。
- 面向浏览器的生成 API、SSE 转发、显式停止和断线重放。
- 面向 Inference Gateway 的内部文本流调用。
- 可替换的 Mock API 与 Fake SSE 场景，供前端和后端并行开发。

本批不实现文件上传与解析、图片生成、视频生成、语义检索、内容审核或自动重试付费生成。它们通过内容块、生成状态和 Artifact 事件扩展，不进入首批文本闭环。

## 2. 架构选择

采用独立 Conversation Service。浏览器不能直接调用 Inference Gateway，固定调用方向为：

```text
Web -> BFF/API Gateway -> Conversation Service -> Inference Gateway -> Provider
```

各组件职责如下：

- BFF/API Gateway 负责校验浏览器会话、转发认证上下文、关闭 SSE 代理缓冲和提供同源接口，不保存会话业务事实。
- Conversation Service 是会话、消息、生成状态、上下文和用量的唯一所有者。
- Inference Gateway 负责目标解析、凭据解密、SSRF/DNS/TLS 防护、Provider 协议适配和内部标准事件，不读写 Conversation schema。
- Model Registry 是模型定义、模型版本和能力声明的唯一所有者。
- File Service 是文件元数据、对象和 Projection 的唯一所有者。

文本生成使用同步内部流，以保持低首字延迟。`GenerationCompleted` 和 `GenerationFailed` 等领域事件通过事务 Outbox 发布。耗时较长的图片或视频任务后续可以使用队列 Worker，但不改变 Conversation 公共生成模型。

## 3. 数据所有权与持久化

Conversation Service 使用独立的 `conversation` schema 和迁移历史，不跨 schema 读取其他服务的数据。

### 3.1 conversations

保存会话 ID、所有者用户 ID、标题、状态、当前活动消息 ID、创建时间、更新时间和乐观锁版本。V1 同一会话只允许一个非终态生成任务，避免消息顺序和上下文分支产生歧义。

### 3.2 messages

保存消息 ID、会话 ID、所有者用户 ID、角色、父消息 ID、结构化内容块、内容块版本、结果完整性、创建时间和顺序信息。

`parent_message_id` 表达消息树。重新生成会创建同一用户消息的另一个助手子消息，不覆盖旧结果；`conversations.active_message_id` 指向当前展示分支。结果完整性区分 `COMPLETE` 和 `PARTIAL`，失败、停止或中断后可以保留部分助手消息。

### 3.3 generations

保存生成 ID、会话 ID、用户消息 ID、可空的结果消息 ID、`client_request_id`、模型 ID、模型版本快照、参数快照、上下文决策快照、状态、部分文本、最近事件序号、错误码、关联 ID、开始/结束时间和乐观锁版本。

对 `(owner_user_id, conversation_id, client_request_id)` 建立唯一约束。同一个请求重复到达时返回已有生成记录，不再次调用模型。

### 3.4 usage_records

以追加方式保存生成 ID、Provider attempt 标识、输入 Token、输出 Token、总 Token、Provider 原始用量中的受控字段、计量单位和记录时间。未知用量保持为空，不根据文本长度伪造 Token 数。

### 3.5 Outbox

与生成终态在同一数据库事务写入 Outbox。事件只包含资源 ID、稳定状态、用量摘要和关联 ID，不包含会话正文、附件正文、Provider 响应、端点地址或凭据。

## 4. 内容块与附件边界

消息使用带 `schemaVersion` 的 JSONB 内容块。V1 支持：

- `text`：用户或助手文本。
- `image_ref`：File Service 图片引用。
- `file_ref`：File Service 文件引用。

Conversation 数据库不保存文件二进制或解析正文。创建生成任务时，Conversation Service 使用用户身份向 File Service 获取绑定目标模型的 Projection：

- 模型与适配器均支持原生文件时，使用受控原生文件引用。
- 模型支持图片输入时，使用规范化图片 Projection。
- 模型不支持原生文件时，使用已完成的文本提取 Projection。
- Projection 尚未完成时返回 `FILE_NOT_READY`，不静默忽略附件。

每次读取文件或 Projection 都由 File Service 校验所有者。Conversation Service 保存 Projection ID 和版本快照，不复制 File Service 的持久数据。

上下文组装保留 System Prompt 和当前用户消息，再从当前活动分支由新到旧纳入历史消息。历史超出模型上下文预算时进行确定性裁剪，并把裁剪决策写入生成快照；当前消息和附件 Projection 本身超限时返回 `CONTEXT_LIMIT_EXCEEDED`，不静默截断当前输入。

## 5. 生成状态机

状态只允许以下转换：

```text
PENDING -> STREAMING -> SUCCEEDED
    |          |------> FAILED
    |          |------> STOPPED
    |          |------> INTERRUPTED
    |-----------------> STOPPED / FAILED / INTERRUPTED
```

- `PENDING` 表示业务事实已创建，但尚未产生上游内容。
- `STREAMING` 表示已接收到 Inference Gateway 的 `start` 或首个内容事件。
- `SUCCEEDED` 表示收到合法 `done`，最终助手消息已经提交。
- `FAILED` 表示收到稳定业务或上游错误。
- `STOPPED` 只表示用户执行了显式停止。
- `INTERRUPTED` 表示服务进程、租约或流链路异常终止，且无法确认成功。

终态不可再次转换。重复停止返回当前状态。服务重启只恢复持久状态和部分输出，不自动重发可能产生费用的 Provider 请求。

## 6. 公共 API

Conversation Service 提供 `/api/v1` 版本化接口。BFF/API Gateway 保持路径和响应语义，不在代理层重建业务状态。

### 6.1 会话

- `POST /api/v1/conversations`：创建会话。
- `GET /api/v1/conversations`：按更新时间游标分页查询当前用户会话。
- `GET /api/v1/conversations/{conversationId}`：读取当前活动分支及必要生成状态。
- `DELETE /api/v1/conversations/{conversationId}`：逻辑归档会话。

### 6.2 生成

- `POST /api/v1/conversations/{conversationId}/generations`：原子创建用户消息和生成任务，返回 `202 Accepted`、用户消息 ID、生成 ID、状态地址和事件地址。
- `POST /api/v1/generations/{generationId}/regenerate`：基于原用户消息创建新的生成任务和分支。
- `GET /api/v1/generations/{generationId}`：读取持久化生成快照及最终或部分结果。
- `GET /api/v1/generations/{generationId}/events`：订阅 SSE，接受 `Last-Event-ID`。
- `POST /api/v1/generations/{generationId}/stop`：显式停止上游并保留部分结果。

创建和重新生成请求包含前端生成的 UUID `clientRequestId`、模型 ID、结构化输入和受控生成参数。网络超时后使用原 ID 重试；真正的重新生成必须使用新 ID。

同一会话已有非终态生成时，新请求返回 `409 GENERATION_IN_PROGRESS`。请求参数不能提交 Provider 模型 ID、端点 URL、Authorization Header 或其他绕过 Registry 快照的字段。

## 7. SSE 契约与重放

所有公共事件使用统一信封：

```json
{
  "eventId": "01J...",
  "eventType": "content.delta",
  "generationId": "01J...",
  "sequence": 12,
  "occurredAt": "2026-07-18T12:00:00Z",
  "payloadVersion": 1,
  "payload": {}
}
```

V1 事件包括：

- `generation.started`
- `reasoning.delta`
- `content.delta`
- `content.checkpoint`
- `usage.updated`
- `generation.completed`
- `generation.failed`
- `generation.stopped`
- `generation.interrupted`
- `stream.heartbeat`
- `replay.reset`

事件在单个生成内按 `sequence` 严格递增，但允许因恢复产生序号空洞。客户端按 `eventId` 去重，不能把重复 SSE 当作新内容追加。

Redis 只保存近期 SSE 事件，不保存永久业务事实。默认重放窗口为 15 分钟，并受每个生成的事件数和字节数硬上限保护；具体值通过配置调整。客户端携带 `Last-Event-ID` 时，服务优先重放缺失事件，再接入实时流。

事件已过期、Redis 数据丢失或服务无法证明重放连续性时，服务发送 `replay.reset`。前端必须使用 `GET /api/v1/generations/{generationId}` 替换本地内容，然后继续等待非终态生成的新事件。服务不能伪装已经完整重放。

浏览器断线、刷新或切换页面不停止生成。只有显式停止接口会取消上游订阅。未来多模态输出在相同信封中增加 `artifact.created`、`artifact.progress` 和 `artifact.completed`，文本客户端必须忽略未知事件。

## 8. Inference Gateway 内部流

Conversation Service 使用短时 Service JWT 调用 Inference Gateway 的内部版本化流接口。请求只包含：

- 所有者用户 ID 和 Registry 模型 ID。
- 已组装的标准消息与受控 Projection。
- 温度、最大输出长度等目标模型允许的参数。
- 生成 ID 和取消传播所需的 invocation attempt 标识；关联 ID 通过 `X-Correlation-ID` Header 传递。

Conversation Service 消费既有内部标准事件 `start`、`reasoning`、`text_delta`、`usage`、`error` 和 `done`。它不解释 Provider 私有字段，也不接收端点凭据。`reasoning` 是否向用户展示由模型能力和产品策略共同决定，但不得写入普通日志。

## 9. 事务与线程边界

Conversation Service 使用 Spring WebFlux 承接 SSE，使用 JPA 保存业务事实。二者按以下边界隔离：

1. 在数据库事务外从 Model Registry 和 File Service 取得带版本的模型能力与 Projection 快照；远程调用不能占用数据库事务。
2. 使用短数据库事务校验本地会话所有权、幂等键和并发状态，并创建用户消息和 `PENDING` 生成；远程快照标识随生成记录保存。
3. 事务提交后再调用 Inference Gateway，任何网络流期间都不持有数据库事务或连接。
4. JPA 调用进入容量受限的阻塞线程池，不占用 Reactor Netty 事件循环。
5. 文本增量先在内存中聚合，达到约 1 秒或累计 2 KiB 任一阈值时，以独立短事务保存部分输出检查点。
6. 成功时在同一短事务中创建完整助手消息、更新活动分支、提交生成终态、写入标准化用量和 Outbox。
7. 失败、停止或中断时，以同一终态事务保存可用部分消息和对应 Outbox。

停止、超时、客户端取消和服务关闭都必须触发资源释放。上游取消完成前不复用 attempt，晚到事件通过生成版本和终态检查丢弃。

## 10. 错误、安全与配额

公共错误只暴露稳定错误码、用户可理解消息和关联 ID。错误码遵守 `AW-{BOUNDARY}-{CATEGORY}-{NUMBER}` 约定。首批映射如下：

| 业务语义 | 公共错误码 |
| --- | --- |
| 会话不存在 | `AW-CONVERSATION-NOT_FOUND-0001` |
| 会话已有生成任务 | `AW-CONVERSATION-CONFLICT-0001` |
| 模型不可用 | `AW-CONVERSATION-DEPENDENCY-0001` |
| 模型能力不匹配 | `AW-CONVERSATION-VALIDATION-0001` |
| 文件 Projection 未就绪 | `AW-CONVERSATION-DEPENDENCY-0002` |
| 上下文超过限制 | `AW-CONVERSATION-VALIDATION-0002` |
| 上游超时 | `AW-CONVERSATION-DEPENDENCY-0003` |
| 上游限流 | `AW-CONVERSATION-RATE_LIMIT-0001` |
| 生成异常中断 | `AW-CONVERSATION-DEPENDENCY-0004` |
| 内部错误 | `AW-CONVERSATION-INTERNAL-0001` |

Provider 原始响应、完整 Base URL、解析 IP、请求正文、凭据、Authorization Header 和内部堆栈不得返回浏览器或进入普通日志。

Conversation Service 在本服务内校验会话所有权，并要求 Model Registry 和 File Service 分别校验模型和文件所有权。不能只信任前端、BFF 或网络位置。用户输入大小、附件数量、并发生成数、SSE 连接数、重放窗口和最大输出长度同时设置可配置限制与服务端硬上限。

## 11. 故障语义

- 浏览器连接中断：生成继续，前端使用 `Last-Event-ID` 重连。
- 用户停止：取消上游，状态变为 `STOPPED`，保留部分输出。
- Inference Gateway 在首事件前执行其既有限次重试；Conversation Service 不额外重试。
- 上游在已输出内容后失败：状态变为 `FAILED`，部分输出标记为 `PARTIAL`。
- Conversation 实例或生成租约失效：状态变为 `INTERRUPTED`，不自动再次计费调用。
- Redis 不可用：禁止声称可重放；持久消息仍以 PostgreSQL 为准。
- RabbitMQ 不可用：终态和 Outbox 可以提交，由发布器恢复后继续投递。
- PostgreSQL 不可用：不能创建或确认生成成功，服务返回失败并取消相关上游工作。

## 12. 测试策略

测试随实现批次按 TDD 插入，不在功能完成后集中补写。

### 12.1 单元测试

覆盖状态转换、终态不可逆、上下文组装、历史裁剪、内容块版本、能力判断、幂等、停止语义、权限判断和错误映射。

### 12.2 契约测试

覆盖公共 OpenAPI、SSE 信封、事件顺序、未知字段兼容、`Last-Event-ID`、Inference Gateway 内部流和稳定错误码。

### 12.3 集成测试

使用 Testcontainers 覆盖 PostgreSQL 迁移与约束、Redis 重放、RabbitMQ Outbox、乐观锁、租约恢复、JPA/WebFlux 线程边界和多实例竞争。

Fake Provider 覆盖正常流、慢流、首帧超时、中途断开、限流、非法事件、超大响应、显式停止和事件后禁止重试。

### 12.4 安全与端到端测试

覆盖跨用户会话、模型和文件访问，重复请求不产生第二次模型调用，错误与日志脱敏，以及资源上限。

前端先对 Mock API/Fake SSE 验证发送、停止、重连、重放过期、失败恢复和重新生成；真实链路完成后使用 Playwright 验证桌面和移动端关键流程。

## 13. 前端进入条件

前端不等待 Conversation Service 全部实现。以下三项完成后立即进入前端开发：

1. Conversation 公共 OpenAPI。
2. SSE 事件与生成状态契约。
3. 可替换的 Mock API 和 Fake SSE 场景。

第一批前端实现聊天壳、会话侧栏、模型选择器、消息列、稳定尺寸输入区和文本流状态。布局参考 DeepSeek Chat 的低干扰聊天工作区，但不复制其 Logo、品牌色、文案或精确布局。

前端使用既定 React、Vite、TypeScript、React Router、TanStack Query、Tailwind CSS、Radix UI、Lucide 和 Playwright 技术栈。公共契约生成 API 类型，Mock 与真实 Client 使用同一接口，切换时不修改页面业务状态。

## 14. 交付顺序

1. 建立 Conversation Service 模块、数据库迁移和领域状态机。
2. 编写公共 OpenAPI、内部流契约、Fake SSE 和前端 Mock。
3. 启动前端聊天工作区，与后端持久化和真实 Gateway 流并行开发。
4. 完成检查点、停止、重放、租约恢复和 Outbox。
5. 接入模型能力与文件 Projection，完成前后端联调。
6. 执行安全回归、Playwright 桌面与移动端检查和完整项目验证。
