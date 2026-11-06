package io.github.boundrail.context.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextViolationException;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.test.BoundrailTest;
import io.github.boundrail.context.test.ContextAssertions;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Demo 1 of design.md §15: context loss is caught by a test. The same scenario as the README's {@code ContextLossTest}. It also runs during the
 * image build, where there is no broker and no Docker for Testcontainers, so {@code edge}'s listener stays stopped.
 */
@BoundrailTest
@SpringBootTest(classes = EdgeService.class, properties = "spring.kafka.listener.auto-startup=false")
class ContextLossDemoTest {

    @Autowired
    EdgeService edge;

    @Test
    @DisplayName("REL-02 demo 1 a plain executor loses the tenant and the test catches MISSING_REQUIRED")
    void aPlainExecutorLosesTheTenant() {
        try (ExecutorService plain = Executors.newSingleThreadExecutor()) {
            Future<OrdersService.OrderPlaced> call = Context.with(DemoPolicy.TENANT, "acme").call(() -> plain.submit(edge::callOrders));

            Throwable failure = catchThrowable(call::get);

            assertThat(failure).isInstanceOf(ExecutionException.class).hasCauseInstanceOf(ContextViolationException.class);
            ContextAssertions.assertThatViolations().hasSize(1).containsKind(Violation.Kind.MISSING_REQUIRED);
            Violation violation = ((ContextViolationException) failure.getCause()).violation();
            assertThat(violation.key()).isEqualTo("tenant");
            assertThat(violation.boundary()).isEqualTo(Boundary.INTERNAL_CALL);

            System.out.println("Demo 1: context loss is caught by a test");
            System.out.println("  test      a plain executor lost the tenant");
            System.out.printf("  caught    %s, key %s, boundary %s%n", violation.kind(), violation.key(), violation.boundary());
        }
    }
}
