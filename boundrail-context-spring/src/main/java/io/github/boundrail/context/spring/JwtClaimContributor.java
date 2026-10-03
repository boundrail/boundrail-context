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
    private final BiConsumer<ContextContributions, String> contribution;

    private JwtClaimContributor(String claim, BiConsumer<ContextContributions, String> contribution) {
        this.claim = claim;
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
        return new JwtClaimContributor(claim, (contributions, text) -> contributions.put(key, codec.decode(text)));
    }

    /**
     * Contributes the claim of a JWT authentication, decoded with the codec; contributes nothing for a missing claim or an authentication that
     * is not a JWT authentication (IN-06).
     *
     * @throws NullPointerException if an argument is {@code null}
     */
    @Override
    public void contribute(Authentication authentication, ContextContributions contributions) {
        Objects.requireNonNull(authentication, "authentication");
        Objects.requireNonNull(contributions, "contributions");
        if (authentication instanceof JwtAuthenticationToken jwt) {
            Object value = jwt.getToken().getClaims().get(claim);
            if (value != null) {
                contribution.accept(contributions, (String) value);
            }
        }
    }
}
