package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthorityTest {

    private static final String CREDENTIAL = "s3cr3t-bearer-token";
    private static final Instant EXPIRY = Instant.parse("2026-10-13T12:00:00Z");

    @Test
    @DisplayName("ID-01 toString never contains the credential")
    void toStringNeverContainsTheCredential() {
        Authority authority = Authority.of("alice", CREDENTIAL);

        assertThat(authority.toString())
                .isEqualTo("Authority[subject=alice, credential=<redacted>]")
                .doesNotContain(CREDENTIAL);
    }

    @Test
    @DisplayName("ID-01 a snapshot's toString never contains the credential")
    void aSnapshotsToStringNeverContainsTheCredential() {
        ContextSnapshot snapshot = Context.with(BuiltInKeys.AUTHORITY, Authority.of("alice", CREDENTIAL)).call(Context::capture);

        assertThat(snapshot.toString())
                .contains("boundrail.authority=Authority[subject=alice, credential=<redacted>]")
                .doesNotContain(CREDENTIAL);
    }

    @Test
    @DisplayName("ID-01 equals and hashCode are identity-based")
    void equalsAndHashCodeAreIdentityBased() {
        Authority first = Authority.of("alice", CREDENTIAL);
        Authority second = Authority.of("alice", CREDENTIAL);

        assertThat(first).isNotEqualTo(second).isEqualTo(first);
        assertThat(first.hashCode()).isEqualTo(System.identityHashCode(first));
        assertThat(second.hashCode()).isEqualTo(System.identityHashCode(second));
    }

    @Test
    @DisplayName("CORE-03 Authority.of rejects a null subject or credential with NullPointerException")
    void ofRejectsANullSubjectOrCredential() {
        assertThatNullPointerException().isThrownBy(() -> Authority.of(null, CREDENTIAL)).withMessageNotContaining(CREDENTIAL);
        assertThatNullPointerException().isThrownBy(() -> Authority.of("alice", null));
    }

    @Test
    @DisplayName("ID-01 subject and credential return what was given")
    void subjectAndCredentialReturnWhatWasGiven() {
        Authority authority = Authority.of("service:billing", CREDENTIAL);

        assertThat(authority.subject()).isEqualTo("service:billing");
        assertThat(authority.credential()).isEqualTo(CREDENTIAL);
    }

    @Test
    @DisplayName("ID-10 Authority.of with an expiry returns it from expiresAt, and of(subject, credential) has none")
    void ofWithAnExpiryReturnsItFromExpiresAt() {
        Authority withExpiry = Authority.of("alice", CREDENTIAL, EXPIRY);
        Authority withoutExpiry = Authority.of("alice", CREDENTIAL);

        assertThat(withExpiry.expiresAt()).contains(Instant.parse("2026-10-13T12:00:00Z"));
        assertThat(withExpiry.subject()).isEqualTo("alice");
        assertThat(withExpiry.credential()).isEqualTo(CREDENTIAL);
        assertThat(withoutExpiry.expiresAt()).isEmpty();
    }

    @Test
    @DisplayName("ID-10 an authority with an expiry keeps ID-01: toString without the credential, identity-based equals and hashCode")
    void anAuthorityWithAnExpiryKeepsId01() {
        Authority first = Authority.of("alice", CREDENTIAL, EXPIRY);
        Authority second = Authority.of("alice", CREDENTIAL, EXPIRY);

        assertThat(first.toString()).isEqualTo("Authority[subject=alice, credential=<redacted>]");
        assertThat(first).isNotEqualTo(second).isEqualTo(first);
        assertThat(first.hashCode()).isEqualTo(System.identityHashCode(first));
        assertThat(second.hashCode()).isEqualTo(System.identityHashCode(second));
    }

    @Test
    @DisplayName("CORE-16 Authority.of rejects a null subject, credential or expiry with NullPointerException")
    void ofWithAnExpiryRejectsNullArguments() {
        assertThatNullPointerException().isThrownBy(() -> Authority.of(null, CREDENTIAL, EXPIRY)).withMessageNotContaining(CREDENTIAL);
        assertThatNullPointerException().isThrownBy(() -> Authority.of("alice", null, EXPIRY));
        assertThatNullPointerException().isThrownBy(() -> Authority.of("alice", CREDENTIAL, null)).withMessageNotContaining(CREDENTIAL);
    }
}
