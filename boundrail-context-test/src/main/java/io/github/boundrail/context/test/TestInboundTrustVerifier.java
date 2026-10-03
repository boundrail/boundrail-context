package io.github.boundrail.context.test;

import io.github.boundrail.context.spring.InboundTrustVerifier;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import org.springframework.web.client.RestClient;

/**
 * Simulates mesh identity in tests (TEST-01): trusts a request exactly when it carries {@code Boundrail-Test-Peer} once, with a secret
 * generated once per JVM and shared by every instance. Clients that should be trusted apply {@link #peer()}. Not auto-configured: register
 * it as a bean.
 */
public final class TestInboundTrustVerifier implements InboundTrustVerifier {

    private static final String HEADER = "Boundrail-Test-Peer";

    private static final String SECRET = newSecret();

    /** Creates a verifier that trusts the per-JVM secret. */
    public TestInboundTrustVerifier() {
    }

    /**
     * Returns a customizer that adds {@code Boundrail-Test-Peer} with the per-JVM secret to every request of the client it configures. Apply
     * it to internal clients only, so the secret never reaches an external service.
     *
     * @return the customizer
     */
    public static Consumer<RestClient.Builder> peer() {
        return builder -> builder.defaultHeader(HEADER, SECRET);
    }

    /**
     * {@inheritDoc}
     *
     * @throws NullPointerException if the request is {@code null}
     */
    @Override
    public Trust verify(HttpServletRequest request) {
        Objects.requireNonNull(request, "request");
        List<String> values = Collections.list(request.getHeaders(HEADER));
        return values.size() == 1 && MessageDigest.isEqual(values.getFirst().getBytes(StandardCharsets.UTF_8),
                SECRET.getBytes(StandardCharsets.UTF_8)) ? Trust.TRUSTED_INTERNAL : Trust.UNTRUSTED;
    }

    private static String newSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
