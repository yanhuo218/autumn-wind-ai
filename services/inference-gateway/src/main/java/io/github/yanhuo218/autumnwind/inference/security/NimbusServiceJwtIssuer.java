package io.github.yanhuo218.autumnwind.inference.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import io.github.yanhuo218.autumnwind.inference.configuration.InferenceServiceJwtProperties;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

public final class NimbusServiceJwtIssuer implements ServiceJwtIssuer {

    private final JwtEncoder encoder;
    private final InferenceServiceJwtProperties properties;
    private final Clock clock;

    public NimbusServiceJwtIssuer(
            RsaKeyMaterial keyMaterial,
            InferenceServiceJwtProperties properties,
            Clock clock
    ) {
        this.encoder = createEncoder(Objects.requireNonNull(keyMaterial, "RSA 密钥材料不能为空。"));
        this.properties = Objects.requireNonNull(properties, "Service JWT 配置不能为空。");
        this.clock = Objects.requireNonNull(clock, "时钟不能为空。");
    }

    @Override
    public String issue(ServiceJwtRequest request) {
        Objects.requireNonNull(request, "Service JWT 请求不能为空。");
        Instant issuedAt = clock.instant();
        JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(InferenceServiceJwtProperties.SUBJECT)
                .audience(List.of(request.audience()))
                .issuedAt(issuedAt)
                .expiresAt(issuedAt.plus(properties.lifetime()))
                .id(UUID.randomUUID().toString())
                .claim("scope", String.join(" ", new TreeSet<>(request.scopes())));
        if (request.actorUserId() != null) {
            claims.claim("actor_user_id", request.actorUserId().toString());
        }
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256)
                .keyId(properties.keyId())
                .build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static JwtEncoder createEncoder(RsaKeyMaterial keyMaterial) {
        RSAKey rsaKey = new RSAKey.Builder(keyMaterial.publicKey())
                .privateKey(keyMaterial.privateKey())
                .keyUse(KeyUse.SIGNATURE)
                .algorithm(JWSAlgorithm.RS256)
                .keyID(keyMaterial.keyId())
                .build();
        JWKSource<SecurityContext> jwkSource =
                (selector, context) -> selector.select(new JWKSet(rsaKey));
        return new NimbusJwtEncoder(jwkSource);
    }
}
