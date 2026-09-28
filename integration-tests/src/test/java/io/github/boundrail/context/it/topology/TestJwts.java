package io.github.boundrail.context.it.topology;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.NoSuchAlgorithmException;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Duration;
import java.time.Instant;
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
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .subject("alice")
                .claim("tenant", "acme")
                .issuedAt(now)
                .expiresAt(now.plus(Duration.ofHours(1)))
                .build();
        JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
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
