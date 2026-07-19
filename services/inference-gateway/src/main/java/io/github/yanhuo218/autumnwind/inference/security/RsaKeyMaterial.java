package io.github.yanhuo218.autumnwind.inference.security;

import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.util.Objects;

public record RsaKeyMaterial(RSAPrivateKey privateKey, RSAPublicKey publicKey, String keyId) {

    private static final int MAXIMUM_KEY_ID_CODE_POINTS = 128;

    public RsaKeyMaterial {
        privateKey = Objects.requireNonNull(privateKey, "RSA 私钥不能为空。");
        publicKey = Objects.requireNonNull(publicKey, "RSA 公钥不能为空。");
        keyId = requireValidKeyId(keyId);
    }

    public static String requireValidKeyId(String keyId) {
        if (keyId == null) {
            throw new IllegalArgumentException("RSA Key ID 必须包含 1 到 128 个无空白 Unicode 码点。");
        }
        int codePointCount = keyId.codePointCount(0, keyId.length());
        if (codePointCount < 1
                || codePointCount > MAXIMUM_KEY_ID_CODE_POINTS
                || keyId.codePoints().anyMatch(codePoint -> Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint))) {
            throw new IllegalArgumentException("RSA Key ID 必须包含 1 到 128 个无空白 Unicode 码点。");
        }
        return keyId;
    }

    @Override
    public String toString() {
        return "RsaKeyMaterial[keyId=" + keyId + "]";
    }
}
