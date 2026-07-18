# Model Registry 领域规则设计

## 目标

建立独立的 Model Registry 限界上下文，先固定用户自配置端点和模型能力的领域约束，为后续持久化、SecretStore 和管理 HTTP 提供稳定边界。

## 边界

- Model Registry 负责端点元数据、协议类型、模型记录和能力组合校验。
- Model Registry 不负责调用服务商、不执行连接测试网络请求，也不保存明文 API Key。
- Inference Gateway 后续负责所有服务商网络访问和 SSRF 防护。
- V1 只支持 `OPENAI_COMPATIBLE` 协议、`CHAT_COMPLETIONS` 和 `IMAGE_GENERATION` 两种接口类型。

## 领域对象

### 端点设置

- 用户可见名称：去除首尾空白，不能为空，最多 100 个 Unicode code points。
- Base URL：必须是 `https`，必须包含主机名，不允许用户名、密码、片段、查询参数和路径中的控制字符；V1 允许根路径或普通路径前缀。
- 协议：固定为 `OPENAI_COMPATIBLE`。
- 请求超时：1 到 120 秒。
- 启用状态：新建对象默认启用状态由调用方显式提供。

### 模型能力

- 接口类型：`CHAT_COMPLETIONS` 或 `IMAGE_GENERATION`。
- 输入模态：`TEXT`、`IMAGE`、`FILE`、`VIDEO`，至少声明一种。
- 输出模态：`TEXT` 或 `IMAGE`。
- 行为能力：流式输出、System Prompt、推理内容。
- 上下文长度和最大输出长度必须为正数，最大输出长度不得超过上下文长度。
- `CHAT_COMPLETIONS` 必须输出文本；`IMAGE_GENERATION` 必须输出图片，且不能声明流式、System Prompt 或推理内容。

## 错误策略

领域对象使用 `IllegalArgumentException` 拒绝不合法输入；错误信息使用简体中文。HTTP 错误码映射留到管理接口批次，不在本批引入跨层错误协议。

## 测试策略

- 纯领域单元测试覆盖 URL 安全边界、名称规范化、超时范围、能力组合冲突和上下文长度边界。
- 测试不使用网络、不依赖数据库、不读取凭据。
- 新模块必须加入根 Maven reactor，并通过模块定向测试及现有全量验证。
