package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ViolationTest {

    private static final Optional<Destination> ORDERS =
            Optional.of(new Destination(Destination.Kind.INTERNAL, Optional.of("orders")));

    @Test
    @DisplayName("DIAG-01 Violation.Kind has exactly the six kinds of the contract")
    void violationKindHasExactlyTheSixKindsOfTheContract() {
        assertThat(Violation.Kind.values()).extracting(Enum::name).containsExactly(
                "MISSING_REQUIRED", "UNTRUSTED_HEADER", "INVALID_VALUE",
                "OVERSIZED_VALUE", "FORBIDDEN_HEADER", "MISSING_AUDIENCE");
    }

    @Test
    @DisplayName("DIAG-01 a violation exposes its evidence")
    void aViolationExposesItsEvidence() {
        Violation violation = new Violation(Violation.Kind.OVERSIZED_VALUE, "tenant", Boundary.EXTERNAL_CALL, ORDERS,
                "worker-1", Optional.of("capture-site"), Optional.of("executor-evidence"));

        assertThat(violation.kind()).isEqualTo(Violation.Kind.OVERSIZED_VALUE);
        assertThat(violation.key()).isEqualTo("tenant");
        assertThat(violation.boundary()).isEqualTo(Boundary.EXTERNAL_CALL);
        assertThat(violation.destination())
                .contains(new Destination(Destination.Kind.INTERNAL, Optional.of("orders")));
        assertThat(violation.thread()).isEqualTo("worker-1");
        assertThat(violation.captureSite()).contains("capture-site");
        assertThat(violation.executorEvidence()).contains("executor-evidence");
    }

    @Test
    @DisplayName("DIAG-02 ContextViolationException carries its violation")
    void contextViolationExceptionCarriesItsViolation() {
        Violation violation = new Violation(Violation.Kind.MISSING_REQUIRED, "tenant", Boundary.INTERNAL_CALL, ORDERS,
                "worker-1", Optional.empty(), Optional.empty());

        ContextViolationException exception = new ContextViolationException(violation);

        assertThat(exception.violation()).isSameAs(violation);
        assertThat(exception).isInstanceOf(RuntimeException.class);
        assertThat(exception.getMessage()).contains("MISSING_REQUIRED", "tenant", "INTERNAL_CALL");
    }

    @Test
    @DisplayName("CORE-13 violation types reject null")
    void violationTypesRejectNull() {
        Violation.Kind kind = Violation.Kind.MISSING_REQUIRED;
        Boundary boundary = Boundary.INTERNAL_CALL;
        Optional<String> none = Optional.empty();

        assertThatNullPointerException().isThrownBy(
                () -> new Violation(null, "tenant", boundary, ORDERS, "worker-1", none, none));
        assertThatNullPointerException().isThrownBy(
                () -> new Violation(kind, null, boundary, ORDERS, "worker-1", none, none));
        assertThatNullPointerException().isThrownBy(
                () -> new Violation(kind, "tenant", null, ORDERS, "worker-1", none, none));
        assertThatNullPointerException().isThrownBy(
                () -> new Violation(kind, "tenant", boundary, null, "worker-1", none, none));
        assertThatNullPointerException().isThrownBy(
                () -> new Violation(kind, "tenant", boundary, ORDERS, null, none, none));
        assertThatNullPointerException().isThrownBy(
                () -> new Violation(kind, "tenant", boundary, ORDERS, "worker-1", null, none));
        assertThatNullPointerException().isThrownBy(
                () -> new Violation(kind, "tenant", boundary, ORDERS, "worker-1", none, null));
        assertThatNullPointerException().isThrownBy(() -> new ContextViolationException(null));
    }
}
