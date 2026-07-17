# SecretStore 凭据保护

## 边界

`libs/secret-store` 定义凭据加密接口和本地 AES-256-GCM 实现。Model Registry、Notification Service 等业务模块只能依赖 `SecretStore` 接口，不能自行加密、记录或返回 API Key、SMTP 密码等凭据。

V1 本地实现使用信封加密：

1. 每次写入生成独立的 256 位数据密钥。
2. 使用数据密钥和独立随机 Nonce 加密凭据正文。
3. 使用部署主密钥和另一随机 Nonce 加密数据密钥。
4. 只持久化密钥标识、两个 Nonce、加密后的数据密钥和正文密文。
5. 租户、用途、所有者、密文版本和密钥标识作为 AAD 参与认证，不能被替换或跨租户复用。

## 主密钥

主密钥必须是 Base64 编码的 32 字节随机值，并通过容器 Secret 或只读文件挂载。不得把主密钥写入源码、镜像、数据库、环境示例、日志或 Git。

应用启动时可以调用：

```java
SecretStore secretStore = AesGcmSecretStore.fromBase64File(masterKeyPath, "local-v1");
```

`keyId` 用于识别加密该数据密钥的主密钥版本。当前本地实现一次只持有一个主密钥；正式密钥轮换需要增加能够按 `keyId` 解密旧密文的适配器，并在后台重新包裹数据密钥。

## 调用约束

- 明文使用 `byte[]` 传递，调用方使用后必须立即覆盖数组。
- `EncryptedSecret` 的数组字段使用防御性复制，持久化层负责转换为数据库支持的二进制格式。
- 解密失败只返回稳定的通用错误，不能向用户或日志暴露密钥标识、租户上下文、密文或底层异常细节。
- SecretStore 不负责访问控制；业务服务必须先完成租户和资源所有权校验。
- SecretStore 不负责持久化；密文生命周期由拥有该凭据的服务管理。
- SecretStore 不得用于用户密码。用户密码必须使用专用的密码哈希算法处理。

## 替换生产实现

Vault、云 KMS 或 Secret Manager 适配器必须保持 `SecretStore` 语义：上下文绑定、不可返回完整凭据、失败关闭、支持密钥版本识别。业务数据库仍然只保存引用或密文，不保存可直接调用上游服务的明文凭据。

## 验证

```powershell
pwsh -NoProfile -File scripts/verify-java.ps1
```

测试覆盖正常加解密、随机密文、上下文错配、密文篡改、防御性复制、挂载文件加载和无效主密钥长度。
