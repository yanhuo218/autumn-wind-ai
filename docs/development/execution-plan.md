# Autumn Wind Ai V1 执行与提交计划

## 执行原则

- 按可验证的垂直能力推进，不同时铺开所有微服务。
- 每个阶段先定义契约和成功标准，再编写实现。
- 只引入当前阶段需要的依赖和抽象。
- 每次改动都运行与范围匹配的最小验证集。
- 主分支始终保持可构建；未验证的中间状态不推送。

## 提交粒度

- 默认每完成 2 个紧密相关的小步骤提交一次代码。
- 如果一个步骤已经形成独立的构建、测试或回滚边界，则单独提交。
- 每次提交只解决一个主题，不混入无关格式化、重命名或重构。
- 每个阶段通常包含 2-4 个提交；阶段验证完成后统一推送到 `origin/main`。
- 提交前必须检查暂存文件，确认没有 `.env`、密钥、Token、AI 助手配置或本地缓存。

提交信息使用中文描述，并保留必要的类型前缀：

- `build:` 构建、依赖和工程结构。
- `feat:` 用户可见或服务能力。
- `fix:` 缺陷修复。
- `test:` 测试与验证设施。
- `docs:` 项目文档。
- `refactor:` 保持行为不变的结构调整。

## 实施阶段

### 阶段 1：环境与工程基线

交付内容：

- 本地环境基线和版本约束。
- Java、Node.js 和 Python 的根构建入口。
- Monorepo 目录与忽略规则。
- 不依赖业务功能的基础验证命令。

成功标准：Maven、pnpm 和 uv 均能在没有业务代码的情况下执行基础验证，Git 工作区不包含本地计划或缓存文件。

### 阶段 2：基础设施与公共契约

交付内容：

- PostgreSQL、RabbitMQ、Redis 和 MinIO 的 Docker Compose 基线。
- OpenAPI、事件 Schema、统一错误和关联 ID 约定。
- SecretStore 接口及本地加密实现边界。

成功标准：基础设施健康检查通过，契约可以独立校验，敏感信息不进入仓库或日志。

### 阶段 3：身份与管理基础

交付内容：

- Identity Service 的注册、登录、会话和账户状态闭环。
- Notification Worker 的 SMTP 配置和测试邮件闭环。
- 管理端的注册策略和用户管理最小闭环。

### 阶段 4：端点、模型与文本推理

交付内容：

- Model Registry 的端点、凭据引用、模型和能力配置。
- Inference Gateway 的 OpenAI-compatible 文本适配器与 SSRF 防护。
- Conversation Service 和用户端的流式文本对话闭环。

### 阶段 5：文件与多模态

交付内容：

- 对象存储上传、附件状态和生命周期。
- Python 文档解析 Worker 和可选 OCR 边界。
- 图片理解、图片生成和视频抽帧理解闭环。

### 阶段 6：安全、可观测性与 V1 验收

交付内容：

- 租户隔离、SSRF、凭据泄露和恶意文件测试。
- 指标、日志、Trace、备份和恢复演练。
- 核心端到端、迁移和负载验证。
- V1 验收报告。

## 当前执行批次

阶段 1 和阶段 2 已完成并推送到 `main`。阶段 2 包含：

1. PostgreSQL、RabbitMQ、Redis 和 MinIO 本地 Compose 基线。
2. OpenAPI、事件 Schema、错误码和关联 ID 公共契约。
3. SecretStore 接口、AES-256-GCM 本地信封加密实现和自动测试。

阶段 3 第一批已建立 Identity Service 的 HTTP/事件契约、Spring Boot 4.1.0 骨架、独立 `identity` schema 迁移，以及邮箱策略、账户状态和 Argon2id 密码哈希测试。

下一批实现注册、登录、不透明会话 Token 和内部 session introspection，再接入 Notification Worker 与管理端。
