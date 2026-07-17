package io.github.yanhuo218.autumnwind.security.secrets;

/**
 * 提供与具体密钥管理系统无关的凭据加密边界。
 */
public interface SecretStore {

    /**
     * 加密凭据。调用方仍负责及时清空传入的明文字节数组。
     *
     * @param plaintext 明文字节
     * @param context 凭据所属上下文
     * @return 可持久化的加密结果
     */
    EncryptedSecret encrypt(byte[] plaintext, SecretContext context);

    /**
     * 解密凭据。返回数组由调用方持有，使用后必须清空。
     *
     * @param encryptedSecret 加密结果
     * @param context 凭据所属上下文
     * @return 新分配的明文字节数组
     */
    byte[] decrypt(EncryptedSecret encryptedSecret, SecretContext context);
}
