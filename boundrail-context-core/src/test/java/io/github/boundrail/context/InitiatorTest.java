package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InitiatorTest {

    /** U+1F600, one supplementary character: two UTF-16 chars. */
    private static final String SUPPLEMENTARY = "😀";

    @Test
    @DisplayName("ID-07 accepts values of 1 and 256 characters")
    void acceptsValuesOfOneAnd256Characters() {
        String longest = "x".repeat(256);

        assertThat(Initiator.of("x").value()).isEqualTo("x");
        assertThat(Initiator.of(longest).value()).isEqualTo(longest);
    }

    @Test
    @DisplayName("ID-07 counts length in UTF-16 chars")
    void countsLengthInUtf16Chars() {
        String fits = SUPPLEMENTARY.repeat(128);
        String tooLong = SUPPLEMENTARY.repeat(129);

        assertThat(Initiator.of(fits).value()).isEqualTo(fits);
        assertThatIllegalArgumentException().isThrownBy(() -> Initiator.of(tooLong));
    }

    @Test
    @DisplayName("ID-07 rejects an empty value with IllegalArgumentException")
    void rejectsAnEmptyValue() {
        assertThatIllegalArgumentException().isThrownBy(() -> Initiator.of(""));
    }

    @Test
    @DisplayName("ID-07 rejects a value longer than 256 characters with IllegalArgumentException")
    void rejectsAValueLongerThan256Characters() {
        assertThatIllegalArgumentException().isThrownBy(() -> Initiator.of("x".repeat(257)));
    }

    @Test
    @DisplayName("ID-07 equals and hashCode compare values")
    void equalsAndHashCodeCompareValues() {
        Initiator alice = Initiator.of("user:alice");
        Initiator sameValue = Initiator.of(new String("user:alice"));
        Initiator bob = Initiator.of("user:bob");

        assertThat(alice).isEqualTo(sameValue).isNotEqualTo(bob);
        assertThat(alice.hashCode()).isEqualTo(sameValue.hashCode());
    }

    @Test
    @DisplayName("ID-07 toString returns the value unchanged")
    void toStringReturnsTheValueUnchanged() {
        assertThat(Initiator.of("service:billing")).hasToString("service:billing");
    }

    @Test
    @DisplayName("CORE-03 Initiator.of rejects null with NullPointerException")
    void ofRejectsNull() {
        assertThatNullPointerException().isThrownBy(() -> Initiator.of(null));
    }

    @Test
    @DisplayName("CORE-03 Initiator.equals(null) is false")
    void equalsNullIsFalse() {
        assertThat(Initiator.of("user:alice").equals(null)).isFalse();
    }

    @Test
    @DisplayName("CORE-12 a snapshot's toString shows an initiator's value")
    void aSnapshotsToStringShowsAnInitiatorsValue() {
        ContextSnapshot snapshot = Context.with(BuiltInKeys.INITIATOR, Initiator.of("user:alice")).call(Context::capture);

        assertThat(snapshot.toString()).contains("boundrail.initiator=user:alice");
    }
}
