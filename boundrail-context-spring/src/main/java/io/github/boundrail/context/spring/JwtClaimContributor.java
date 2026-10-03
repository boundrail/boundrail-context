package io.github.boundrail.context.spring;

import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextKey;
import java.util.Objects;
import java.util.function.BiConsumer;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** Contributes one claim of a JWT authentication, decoded with a codec, as the value of a key (IN-06). */
public final class JwtClaimContributor implements ContextContributor {

    private final String claim;
    private final ContextKey<?> key;
    private final BiConsumer<ContextContributions, String> contribution;

    private JwtClaimContributor(String claim, ContextKey<?> key, BiConsumer<ContextContributions, String> contribution) {
        this.claim = claim;
        this.key = key;
        this.contribution = contribution;
    }

    /**
     * Returns a contributor of the named claim.
     *
     * @param claim the claim's name
     * @param key the key the decoded claim is contributed for
     * @param codec the codec that decodes the claim
     * @param <T> the value type
     * @return the contributor
     * @throws NullPointerException if an argument is {@code null}
     */
    public static <T> JwtClaimContributor of(String claim, ContextKey<T> key, ContextCodec<T> codec) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(codec, "codec");
        return new JwtClaimContributor(claim, key, (contributions, text) -> {
            T value;
            try {
                value = codec.decode(text);
            } catch (IllegalArgumentException e) {
                contributions.invalid(key);
                return;
            }
            contributions.put(key, value);
        });
    }

    /**
     * Contributes the claim of a JWT authentication, decoded with the codec; contributes nothing for a missing claim or an authentication that
     * is not a JWT authentication. A claim whose value is not a {@code String}, or whose decoding throws {@code IllegalArgumentException} or a
     * subclass, is reported through {@code invalid(key)} and not contributed; any other exception from the codec propagates unchanged. No
     * length limit applies to the claim (IN-06).
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    @Override
    public void contribute(Authentication authentication, ContextContributions contributions) {
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(contributions, "contributions");
        if (authentication instanceof JwtAuthenticationToken jwt) {
            Object value = jwt.getToken().getClaims().get(claim);
            if (value instanceof String text) {
                contribution.accept(contributions, text);
            } else if (value != null) {
                contributions.invalid(key);
            }
        }
    }
}
