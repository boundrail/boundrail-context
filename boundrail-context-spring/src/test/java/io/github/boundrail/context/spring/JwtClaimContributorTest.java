package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextKey;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

class JwtClaimContributorTest {

    private static final ContextKey<Long> PRIORITY = ContextKey.of("priority", Long.class);

    /** Records every call, in order, as text. */
    static final class RecordingContributions implements ContextContributions {

        final List<String> calls = new ArrayList<>();

        @Override
        public <T> void put(ContextKey<T> key, T value) {
            calls.add("put " + key.name() + "=" + value + " (" + value.getClass().getSimpleName() + ")");
        }

        @Override
        public void invalid(ContextKey<?> key) {
            calls.add("invalid " + key.name());
        }
    }

    @Test
    @DisplayName("IN-06 the named claim of a JWT authentication is decoded with the codec and contributed")
    void theNamedClaimOfAJwtAuthenticationIsDecodedWithTheCodecAndContributed() {
        RecordingContributions contributions = new RecordingContributions();

        JwtClaimContributor.of("priority", PRIORITY, Codecs.longValue())
                .contribute(jwtAuthentication("priority", "5"), contributions);

        assertThat(contributions.calls).containsExactly("put priority=5 (Long)");
    }

    @Test
    @DisplayName("IN-06 a missing claim leaves the key unbound and reports nothing")
    void aMissingClaimLeavesTheKeyUnboundAndReportsNothing() {
        RecordingContributions contributions = new RecordingContributions();

        JwtClaimContributor.of("priority", PRIORITY, Codecs.longValue())
                .contribute(jwtAuthentication("tenant", "acme"), contributions);

        assertThat(contributions.calls).isEmpty();
    }

    @Test
    @DisplayName("IN-06 a non-JWT authentication contributes nothing")
    void aNonJwtAuthenticationContributesNothing() {
        RecordingContributions contributions = new RecordingContributions();
        // Its name, credentials and details all decode as a priority, so only the check for a JWT authentication keeps them out.
        UsernamePasswordAuthenticationToken authentication = UsernamePasswordAuthenticationToken.authenticated("5", "5", List.of());
        authentication.setDetails("5");

        JwtClaimContributor.of("priority", PRIORITY, Codecs.longValue()).contribute(authentication, contributions);

        assertThat(contributions.calls).isEmpty();
    }

    @Test
    @DisplayName("CORE-13 JwtClaimContributor rejects null")
    void jwtClaimContributorRejectsNull() {
        JwtClaimContributor contributor = JwtClaimContributor.of("priority", PRIORITY, Codecs.longValue());
        Authentication nonJwt = UsernamePasswordAuthenticationToken.authenticated("alice", null, List.of());

        assertThatNullPointerException().as("null claim").isThrownBy(() -> JwtClaimContributor.of(null, PRIORITY, Codecs.longValue()));
        assertThatNullPointerException().as("null key").isThrownBy(() -> JwtClaimContributor.of("priority", null, Codecs.longValue()));
        assertThatNullPointerException().as("null codec").isThrownBy(() -> JwtClaimContributor.of("priority", PRIORITY, null));
        assertThatNullPointerException().as("null authentication")
                .isThrownBy(() -> contributor.contribute(null, new RecordingContributions()));
        assertThatNullPointerException().as("null contributions, JWT authentication")
                .isThrownBy(() -> contributor.contribute(jwtAuthentication("priority", "5"), null));
        assertThatNullPointerException().as("null contributions, non-JWT authentication")
                .isThrownBy(() -> contributor.contribute(nonJwt, null));
    }

    private static JwtAuthenticationToken jwtAuthentication(String claim, String value) {
        Jwt jwt = Jwt.withTokenValue("token").header("alg", "none").subject("alice").claim(claim, value).build();
        return new JwtAuthenticationToken(jwt);
    }
}
