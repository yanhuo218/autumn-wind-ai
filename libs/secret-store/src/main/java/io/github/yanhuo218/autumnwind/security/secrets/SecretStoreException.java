package io.github.yanhuo218.autumnwind.security.secrets;

/**
 * 表示凭据加密、解密或主密钥加载失败，不携带敏感上下文。
 */
public final class SecretStoreException extends RuntimeException {

    public SecretStoreException(String message) {
        super(message);
    }

    SecretStoreException(String message, Throwable cause) {
        super(message, cause);
    }
}
