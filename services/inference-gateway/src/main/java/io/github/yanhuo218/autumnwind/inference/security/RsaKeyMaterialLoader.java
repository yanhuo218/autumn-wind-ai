package io.github.yanhuo218.autumnwind.inference.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;

public final class RsaKeyMaterialLoader {

    private static final int MINIMUM_RSA_BITS = 2048;
    private static final byte[] KEY_MATCH_CHALLENGE = createKeyMatchChallenge();

    public RsaKeyMaterial load(Path privateKeyPath, Path publicKeyPath, String keyId) {
        Objects.requireNonNull(privateKeyPath, "RSA 私钥路径不能为空。");
        Objects.requireNonNull(publicKeyPath, "RSA 公钥路径不能为空。");
        keyId = RsaKeyMaterial.requireValidKeyId(keyId);
        try {
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            RSAPrivateKey privateKey = loadPrivateKey(keyFactory, privateKeyPath);
            RSAPublicKey publicKey = loadPublicKey(keyFactory, publicKeyPath);
            requireMinimumStrength(privateKey, publicKey);
            requireMatchingPair(privateKey, publicKey);
            return new RsaKeyMaterial(privateKey, publicKey, keyId);
        } catch (IOException | GeneralSecurityException exception) {
            throw new IllegalArgumentException("RSA 密钥加载失败。", exception);
        }
    }

    private static RSAPrivateKey loadPrivateKey(KeyFactory keyFactory, Path path)
            throws IOException, GeneralSecurityException {
        byte[] encoded = decodePem(Files.readString(path, StandardCharsets.US_ASCII), "PRIVATE KEY");
        try {
            if (!(keyFactory.generatePrivate(new PKCS8EncodedKeySpec(encoded)) instanceof RSAPrivateKey privateKey)) {
                throw new IllegalArgumentException("私钥不是 RSA 密钥。");
            }
            return privateKey;
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static RSAPublicKey loadPublicKey(KeyFactory keyFactory, Path path)
            throws IOException, GeneralSecurityException {
        byte[] encoded = decodePem(Files.readString(path, StandardCharsets.US_ASCII), "PUBLIC KEY");
        try {
            if (!(keyFactory.generatePublic(new X509EncodedKeySpec(encoded)) instanceof RSAPublicKey publicKey)) {
                throw new IllegalArgumentException("公钥不是 RSA 密钥。");
            }
            return publicKey;
        } finally {
            Arrays.fill(encoded, (byte) 0);
        }
    }

    private static byte[] decodePem(String pem, String type) {
        String begin = "-----BEGIN " + type + "-----";
        String end = "-----END " + type + "-----";
        String normalized = pem.trim();
        if (!normalized.startsWith(begin) || !normalized.endsWith(end)) {
            throw new IllegalArgumentException("PEM 密钥格式不合法。");
        }
        String body = normalized.substring(begin.length(), normalized.length() - end.length());
        if (body.contains("-----")) {
            throw new IllegalArgumentException("PEM 密钥格式不合法。");
        }
        try {
            return Base64.getDecoder().decode(body.replaceAll("\\s", ""));
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("PEM 密钥格式不合法。", exception);
        }
    }

    private static void requireMinimumStrength(RSAPrivateKey privateKey, RSAPublicKey publicKey) {
        if (privateKey.getModulus().bitLength() < MINIMUM_RSA_BITS
                || publicKey.getModulus().bitLength() < MINIMUM_RSA_BITS) {
            throw new IllegalArgumentException("RSA 密钥长度不得低于 2048 位。");
        }
    }

    private static void requireMatchingPair(RSAPrivateKey privateKey, RSAPublicKey publicKey)
            throws GeneralSecurityException {
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(privateKey);
        signer.update(KEY_MATCH_CHALLENGE);
        byte[] signature = signer.sign();
        try {
            Signature verifier = Signature.getInstance("SHA256withRSA");
            verifier.initVerify(publicKey);
            verifier.update(KEY_MATCH_CHALLENGE);
            if (!verifier.verify(signature)) {
                throw new IllegalArgumentException("RSA 公私钥不匹配。");
            }
        } finally {
            Arrays.fill(signature, (byte) 0);
        }
    }

    private static byte[] createKeyMatchChallenge() {
        byte[] challenge = new byte[32];
        new SecureRandom().nextBytes(challenge);
        return challenge;
    }
}
