package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AuthorityTest {

    private static final String CREDENTIAL = "s3cr3t-bearer-token";

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
}
