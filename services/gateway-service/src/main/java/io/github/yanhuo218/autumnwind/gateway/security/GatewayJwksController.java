package io.github.yanhuo218.autumnwind.gateway.security;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@RestController
public final class GatewayJwksController {

    private final Map<String, Object> jwks;

    public GatewayJwksController(RsaKeyMaterial keyMaterial) {
        Map<String, String> publicJwk = Map.of(
                "kty", "RSA",
                "kid", keyMaterial.keyId(),
                "use", "sig",
                "alg", "RS256",
                "n", base64Url(keyMaterial.publicKey().getModulus()),
                "e", base64Url(keyMaterial.publicKey().getPublicExponent()));
        this.jwks = Map.of("keys", List.of(publicJwk));
    }

    @GetMapping(path = "/internal/v1/security/jwks", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=300")
                .body(jwks);
    }

    private static String base64Url(BigInteger value) {
        byte[] signed = value.toByteArray();
        byte[] unsigned = signed.length > 1 && signed[0] == 0
                ? Arrays.copyOfRange(signed, 1, signed.length)
                : signed;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(unsigned);
    }
}
