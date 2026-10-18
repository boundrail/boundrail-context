package io.github.boundrail.context.it.topology;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/** Test JWTs, signed with an RSA key generated per instance; the topology creates one instance per run. */
public final class TestJwts {

    private final RSAPublicKey publicKey;
    private final JwtEncoder encoder;

    public TestJwts() {
        KeyPair keys = generateRsaKeyPair();
        this.publicKey = (RSAPublicKey) keys.getPublic();
        this.encoder = NimbusJwtEncoder.withKeyPair(publicKey, (RSAPrivateKey) keys.getPrivate()).build();
    }

    /** The key that verifies the tokens this instance signs. */
    public RSAPublicKey publicKey() {
        return publicKey;
    }

    /** A token for user {@code alice} with the claim {@code tenant=acme}. */
    public String alice() {
        return encode(claims().claim("tenant", "acme"));
    }

    /** A token for user {@code alice} with no {@code tenant} claim. */
    public String withoutTenant() {
        return encode(claims());
    }

    /** The common claims, with a random {@code jti}, so two tokens issued in the same second differ. */
    private static JwtClaimsSet.Builder claims() {
        Instant now = Instant.now();
        return JwtClaimsSet.builder()
                .id(UUID.randomUUID().toString())
                .subject("alice")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1)));
    }

    private String encode(JwtClaimsSet.Builder claims) {
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("RSA is required by every JDK", e);
        }
    }
}
