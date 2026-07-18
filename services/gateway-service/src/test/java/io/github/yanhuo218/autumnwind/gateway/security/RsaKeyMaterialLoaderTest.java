package io.github.yanhuo218.autumnwind.gateway.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RsaKeyMaterialLoaderTest {

    @TempDir
    Path tempDirectory;

    private final RsaKeyMaterialLoader loader = new RsaKeyMaterialLoader();

    @Test
    void 加载PKCS8私钥和X509公钥并保留KeyID() throws Exception {
        KeyPair keyPair = generateKeyPair(2048);
        Path privateKeyPath = writePem("private.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        Path publicKeyPath = writePem("public.pem", "PUBLIC KEY", keyPair.getPublic().getEncoded());

        RsaKeyMaterial material = loader.load(privateKeyPath, publicKeyPath, "gateway-key-2026");

        assertEquals("RSA", material.privateKey().getAlgorithm());
        assertEquals("RSA", material.publicKey().getAlgorithm());
        assertEquals(2048, material.publicKey().getModulus().bitLength());
        assertEquals(((RSAPublicKey) keyPair.getPublic()).getModulus(), material.publicKey().getModulus());
        assertEquals("gateway-key-2026", material.keyId());
        assertFalse(material.toString().contains(Base64.getEncoder().encodeToString(keyPair.getPrivate().getEncoded())));
    }

    @Test
    void 拒绝不足2048位的RSA密钥() throws Exception {
        KeyPair keyPair = generateKeyPair(1024);
        Path privateKeyPath = writePem("private.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        Path publicKeyPath = writePem("public.pem", "PUBLIC KEY", keyPair.getPublic().getEncoded());

        assertThrows(IllegalArgumentException.class,
                () -> loader.load(privateKeyPath, publicKeyPath, "gateway-key-2026"));
    }

    @Test
    void 拒绝不匹配的公私钥() throws Exception {
        KeyPair privateKeyPair = generateKeyPair(2048);
        KeyPair publicKeyPair = generateKeyPair(2048);
        Path privateKeyPath = writePem("private.pem", "PRIVATE KEY", privateKeyPair.getPrivate().getEncoded());
        Path publicKeyPath = writePem("public.pem", "PUBLIC KEY", publicKeyPair.getPublic().getEncoded());

        assertThrows(IllegalArgumentException.class,
                () -> loader.load(privateKeyPath, publicKeyPath, "gateway-key-2026"));
    }

    @Test
    void 拒绝非PKCS8私钥和非X509公钥标签() throws Exception {
        KeyPair keyPair = generateKeyPair(2048);
        Path wrongPrivateKeyPath = writePem(
                "wrong-private.pem", "RSA PRIVATE KEY", keyPair.getPrivate().getEncoded());
        Path publicKeyPath = writePem("public.pem", "PUBLIC KEY", keyPair.getPublic().getEncoded());
        Path privateKeyPath = writePem("private.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        Path wrongPublicKeyPath = writePem(
                "wrong-public.pem", "RSA PUBLIC KEY", keyPair.getPublic().getEncoded());

        assertThrows(IllegalArgumentException.class,
                () -> loader.load(wrongPrivateKeyPath, publicKeyPath, "gateway-key-2026"));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(privateKeyPath, wrongPublicKeyPath, "gateway-key-2026"));
    }

    @Test
    void 拒绝空白KeyID() throws Exception {
        KeyPair keyPair = generateKeyPair(2048);
        Path privateKeyPath = writePem("private.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        Path publicKeyPath = writePem("public.pem", "PUBLIC KEY", keyPair.getPublic().getEncoded());

        assertThrows(IllegalArgumentException.class, () -> loader.load(privateKeyPath, publicKeyPath, " "));
    }

    @Test
    void 接受1到128个Unicode码点的KeyID() throws Exception {
        KeyPair keyPair = generateKeyPair(2048);
        Path privateKeyPath = writePem("private.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        Path publicKeyPath = writePem("public.pem", "PUBLIC KEY", keyPair.getPublic().getEncoded());
        String maximumKeyId = "k".repeat(127) + "\uD83D\uDD11";

        assertEquals("k", loader.load(privateKeyPath, publicKeyPath, "k").keyId());
        assertEquals(maximumKeyId, loader.load(privateKeyPath, publicKeyPath, maximumKeyId).keyId());
        assertEquals(128, maximumKeyId.codePointCount(0, maximumKeyId.length()));
    }

    @Test
    void 拒绝超过128码点或任意位置含空白的KeyID() throws Exception {
        KeyPair keyPair = generateKeyPair(2048);
        Path privateKeyPath = writePem("private.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        Path publicKeyPath = writePem("public.pem", "PUBLIC KEY", keyPair.getPublic().getEncoded());
        String tooLongKeyId = "k".repeat(128) + "\uD83D\uDD11";

        assertEquals(129, tooLongKeyId.codePointCount(0, tooLongKeyId.length()));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(privateKeyPath, publicKeyPath, tooLongKeyId));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(privateKeyPath, publicKeyPath, "key\tid"));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(privateKeyPath, publicKeyPath, "key\u00A0id"));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(privateKeyPath, publicKeyPath, " key"));
        assertThrows(IllegalArgumentException.class,
                () -> loader.load(privateKeyPath, publicKeyPath, "key\u3000"));
    }

    private Path writePem(String fileName, String type, byte[] encoded) throws Exception {
        String body = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(encoded);
        Path path = tempDirectory.resolve(fileName);
        Files.writeString(path, "-----BEGIN " + type + "-----\n" + body
                + "\n-----END " + type + "-----\n", StandardCharsets.US_ASCII);
        return path;
    }

    private static KeyPair generateKeyPair(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }
}
