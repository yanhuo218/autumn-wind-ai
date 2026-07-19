# Gateway Service 开发说明

## 责任边界

Gateway 是浏览器请求与内部服务之间的认证边界，不保存会话、用户资料或模型目录事实。当前版本只提供以下能力：

- 透明代理六条 Identity 认证接口：CSRF、注册选项、注册、登录、当前会话和注销。
- 使用 `AW_SESSION` 调用 Identity Session Introspection，得到当前用户身份。
- 代理 `GET /api/v1/model-registry/models`，为每次请求签发最小权限的 Service JWT。
- 暴露内部 `GET /internal/v1/security/jwks`，供下游校验 Gateway 签发的 JWT。

真实 Conversation 生产代理、附件上传和多模态请求尚未在 Gateway 接入；当前用户端的文本工作区仍使用既有开发链路。

## 运行参数

默认监听端口为 `8080`，可通过 `GATEWAY_SERVER_PORT` 覆盖。下游地址和 Service JWT 配置必须通过环境变量提供：

| 环境变量 | 用途 |
| --- | --- |
| `GATEWAY_IDENTITY_BASE_URL` | Identity 内部基地址 |
| `GATEWAY_MODEL_REGISTRY_BASE_URL` | Model Registry 内部基地址 |
| `GATEWAY_SERVICE_JWT_ISSUER` | Service JWT 的 issuer |
| `GATEWAY_SERVICE_JWT_PRIVATE_KEY_PATH` | PKCS#8 RSA 私钥 PEM 路径 |
| `GATEWAY_SERVICE_JWT_PUBLIC_KEY_PATH` | X.509 RSA 公钥 PEM 路径 |
| `GATEWAY_SERVICE_JWT_KEY_ID` | JWKS 中发布的 Key ID |

下游地址必须是绝对 HTTPS URI；本地开发才允许指向回环地址的绝对 HTTP URI。生产环境应使用内部 HTTPS，并由部署系统挂载 PEM 文件。仓库不提供密钥正文或真实地址示例。

## 安全约束

- Service JWT 固定使用 RS256，subject 为 `gateway-service`，有效期为 60 秒，每次下游请求重新签发。
- Identity Introspection Token 只包含 `identity-service` audience 和 `identity.session.introspect` scope，不包含 `actor_user_id`。
- Model Registry Token 只包含 `model-registry-service` audience、`model-registry.model.read` scope 和可信用户 `actor_user_id`。
- 原始 `AW_SESSION`、CSRF、浏览器 Authorization 和用户伪造 Header 不进入 Model Registry。
- Identity 请求正文上限为 16 KiB；下游响应正文上限为 1 MiB；Identity、Introspection 和 Model Registry 请求总超时为 5 秒，其中会话校验按 2 秒失败关闭。
- 仅转发白名单响应 Header 和已声明的 OpenAPI 业务错误；其他状态、媒体类型或 JSON 结构统一映射为公开错误，不回显下游地址或正文。
- 关联 ID 使用 `X-Correlation-ID`，缺失或不符合格式时由 Gateway 生成并始终回写响应。

## 测试与验证

Gateway 定向测试：

```powershell
mvn "-Dmaven.repo.local=$PWD\.m2\repository" -pl services/gateway-service test
```

根工程验证：

```powershell
pwsh -NoProfile -File scripts/verify-contracts.ps1
mvn "-Dmaven.repo.local=$PWD\.m2\repository" test
pnpm contracts:frontend
pnpm check
pnpm test
pnpm build
git diff --check
```

跨边界回归覆盖未声明路由拒绝、JWKS 不含私钥字段、身份 DTO 脱敏、17 KiB 请求拒绝、Cookie 隔离、JWT audience/scope/actor 约束、下游错误脱敏以及连接失败和超时映射。
