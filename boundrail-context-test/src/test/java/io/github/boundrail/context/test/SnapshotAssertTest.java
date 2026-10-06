package io.github.boundrail.context.test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextSnapshot;
import org.assertj.core.api.AbstractAssert;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code ContextAssertions.assertThat(snapshot)} and {@code SnapshotAssert} (TEST-03, TEST-04, CORE-14). */
class SnapshotAssertTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<Long> PRIORITY = ContextKey.of("priority", Long.class);

    /** A snapshot binding only {@code tenant=acme}. */
    private static ContextSnapshot tenantAcme() {
        return Context.with(TENANT, "acme").call(Context::capture);
    }

    @Test
    @DisplayName("TEST-03 assertThat(snapshot) checks contains, doesNotContain and isEmpty")
    void assertThatSnapshotChecksContainsDoesNotContainAndIsEmpty() {
        ContextSnapshot snapshot = tenantAcme();

        ContextAssertions.assertThat(snapshot).contains(TENANT, "acme").doesNotContain(PRIORITY);
        ContextAssertions.assertThat(ContextSnapshot.EMPTY).isEmpty().doesNotContain(TENANT);

        assertThatThrownBy(() -> ContextAssertions.assertThat(snapshot).contains(TENANT, "other"))
                .as("contains with another value")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("tenant")
                .hasMessageNotContaining("acme");
        assertThatThrownBy(() -> ContextAssertions.assertThat(snapshot).contains(PRIORITY, 7L))
                .as("contains with an unbound key")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("priority");
        assertThatThrownBy(() -> ContextAssertions.assertThat(snapshot).doesNotContain(TENANT))
                .as("doesNotContain with a bound key")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("tenant")
                .hasMessageNotContaining("acme");
        assertThatThrownBy(() -> ContextAssertions.assertThat(snapshot).isEmpty())
                .as("isEmpty with a bound key")
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("tenant")
                .hasMessageNotContaining("acme");
    }

    @Test
    @DisplayName("TEST-04 SnapshotAssert extends AbstractAssert")
    void snapshotAssertExtendsAbstractAssert() {
        assertThat(ContextAssertions.assertThat(tenantAcme())).isInstanceOf(AbstractAssert.class);
    }

    @Test
    @DisplayName("CORE-14 ContextAssertions.assertThat(null) and contains(null, value) throw NullPointerException")
    void assertThatNullAndContainsNullThrowNullPointerException() {
        SnapshotAssert snapshotAssert = ContextAssertions.assertThat(tenantAcme());

        assertThatThrownBy(() -> ContextAssertions.assertThat(null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> snapshotAssert.contains(null, "acme")).isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("CORE-14 contains(key, null) and doesNotContain(null) throw NullPointerException")
    void containsNullValueAndDoesNotContainNullThrowNullPointerException() {
        SnapshotAssert snapshotAssert = ContextAssertions.assertThat(ContextSnapshot.EMPTY);

        assertThatThrownBy(() -> snapshotAssert.contains(TENANT, null)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> snapshotAssert.doesNotContain(null)).isInstanceOf(NullPointerException.class);
    }
}
