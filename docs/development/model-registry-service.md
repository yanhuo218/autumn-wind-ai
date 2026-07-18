# Model Registry Service 开发说明

## 职责边界

Model Registry Service 拥有用户端点元数据、加密凭据引用、模型记录和模型能力配置。它不访问外部服务商端点，不执行推理，也不向读取接口返回 API Key 或密文内容。

Inference Gateway 后续作为唯一的服务商网络出口，负责连接测试、SSRF 防护、重定向限制和协议适配。

## 数据归属

服务使用独立的 `model_registry` schema：

- `endpoints`：用户所有者、Base URL、协议、超时、启用状态、连接测试摘要和当前凭据引用。
- `endpoint_credentials`：SecretStore 信封加密结果和替换时间，不保存明文 API Key。
- `models`：端点下的服务商模型 ID、接口类型、默认状态和能力 Schema 版本。
- `model_capabilities`：输入/输出模态、流式、System Prompt、推理内容和上下文长度。
- `endpoint_connection_test_jobs`：连接测试任务、固定端点版本和固定凭据引用，不保存 API Key 或服务商响应正文。

`models` 使用 `(owner_user_id, endpoint_id)` 组合外键约束租户所有权；`endpoints` 使用 `(id, current_credential_id)` 组合外键保证当前凭据属于同一端点。
连接测试任务同时使用 `(owner_user_id, endpoint_id)` 和 `(endpoint_id, credential_id)` 组合外键，避免跨租户或跨端点引用。

## 凭据处理

- 创建端点和替换 API Key 时，明文只在应用服务的临时 UTF-8 字节数组中存在。
- SecretStore 上下文绑定用户 ID、固定用途 `model-endpoint-api-key` 和端点 ID。
- 临时字节数组在 `finally` 中清零。
- 读取视图只返回 `credentialConfigured`，不解密、不返回密文结构。
- 替换成功后保留旧密文记录并写入 `replaced_at`，便于审计和后续密钥轮换。

## HTTP 与连接测试边界

- 端点管理使用 `model-registry.endpoint.manage` scope，模型管理使用 `model-registry.model.manage` scope。
- Service JWT 必须携带规范 UUID 格式的 `actor_user_id`，所有读取和写入都绑定该用户。
- `POST /api/v1/model-registry/endpoints/{endpointId}/connection-tests` 只持久化任务并返回 `202 Accepted`。
- 任务创建时固定端点版本和当前凭据 ID；配置变更后，旧任务不能覆盖新配置的测试结果。
- Model Registry 不发起外部网络请求。Inference Gateway 后续负责 SSRF 防护、重定向限制、凭据解密和协议探测。
- 公共契约位于 `contracts/openapi/model-registry.openapi.json`，API Key 字段均为只写字段。

## 本地配置

运行服务需要配置：

- `MODEL_REGISTRY_DATABASE_URL`
- `MODEL_REGISTRY_DATABASE_USERNAME`
- `MODEL_REGISTRY_DATABASE_PASSWORD`
- `MODEL_REGISTRY_SECRET_STORE_MASTER_KEY_FILE`
- `MODEL_REGISTRY_SECRET_STORE_KEY_ID`，默认 `local-v1`
- `MODEL_REGISTRY_SERVER_PORT`，默认 `8083`

真实数据库密码、API Key 和 SecretStore 主密钥文件不得提交到仓库。

## 验证

模块测试：

```powershell
mvn "-Dmaven.repo.local=<项目目录>\.m2\repository" -pl services/model-registry-service test
```

项目全量验证：

```powershell
./scripts/verify-all.ps1
```
