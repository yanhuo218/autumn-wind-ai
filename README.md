# Autumn Wind Ai

Autumn Wind Ai 是一个面向多用户场景的 BYOK（Bring Your Own Key）AI 对话平台。平台本身不提供固定模型端点，每位用户可以安全配置自己的 OpenAI-compatible API 地址、密钥和模型能力。

> 当前项目处于阶段 3，已完成工程与基础设施基线，正在交付身份、通知和管理基础能力。

## 产品目标

- 提供接近主流 AI 对话产品的简洁交互体验。
- 兼容文本对话、图片理解、图片生成、文档解析和视频抽帧理解。
- 由用户在添加模型时声明能力，界面和请求链根据能力自动调整。
- 模型无法原生解析附件时，由平台提取内容后再发送请求。
- 提供用户、注册、邮件、配额、端点安全策略和运行状态管理。
- 通过协议适配器逐步扩展 Anthropic、Gemini 和视频生成等能力。

## V1 范围

- 邮箱密码注册、邮箱验证和密码找回。
- 用户私有 OpenAI-compatible 端点和模型。
- 文本流式对话、推理内容展示和会话管理。
- 图片理解、图片生成和会话附件。
- PDF、Word、Excel、PowerPoint、文本及可选 OCR 解析。
- 视频元数据和关键帧提取。
- 用户、注册、SMTP、配额、域名白名单/黑名单、审计和服务健康后台。
- 多租户隔离、API Key 加密、SSRF 防护和文件生命周期管理。

## 技术架构

- 用户端与管理端：React、Vite、TypeScript。
- Java 服务：Java 21、Spring Boot、Spring MVC、Spring WebFlux、Spring Security。
- 文件处理：Python、FastAPI、Celery、PyMuPDF、FFmpeg。
- 数据与消息：PostgreSQL、RabbitMQ、Redis、S3-compatible 对象存储。
- 本地部署：Docker Compose。
- 可观测性：OpenTelemetry、Prometheus-compatible 指标和结构化日志。

项目采用可独立部署的粗粒度服务架构。普通控制面服务使用 Spring MVC，只有网关和模型流式调用链使用 Spring WebFlux。Python 仅负责文件、OCR、图片和视频处理，不承担认证、对话或凭据管理。

## 安全原则

- API Key 和 SMTP 密码只可写入和替换，完整内容不会返回浏览器。
- 每次模型调用都校验目标地址，阻止内网、回环、云元数据地址和危险重定向。
- 用户数据、文件、缓存和异步任务均绑定不可变的用户所有者。
- 日志、事件和管理页面不得记录或展示密码、Token、Cookie 或 Authorization Header。
- 上传文件必须经过类型、签名、大小、压缩层级和资源限制校验。

## 项目文档

- [产品与架构设计](docs/superpowers/specs/2026-07-18-autumn-wind-ai-design.md)
- [本地环境基线](docs/development/environment-baseline.md)
- [V1 执行与提交计划](docs/development/execution-plan.md)
- [本地基础设施](docs/development/local-infrastructure.md)
- [公共契约约定](docs/development/contract-conventions.md)
- [SecretStore 凭据保护](docs/development/secret-store.md)
- [Identity Service 开发说明](docs/development/identity-service.md)

设计规格包含产品范围、页面参照、技术边界、服务职责、核心流程、数据归属、安全要求、测试策略和 V1 验收标准。

## 开发状态

阶段 1 和阶段 2 已完成。阶段 3 已建立 Identity Service 契约、Spring Boot 骨架、首个数据库迁移和核心认证策略测试，下一批实现注册、登录与会话闭环。
