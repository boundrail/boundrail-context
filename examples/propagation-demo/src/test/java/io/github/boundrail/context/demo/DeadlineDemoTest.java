package io.github.boundrail.context.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Demo 3 of design.md §15: a deadline shrinks across hops. */
class DeadlineDemoTest {

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    @Test
    @DisplayName("REL-02 demo 3 the deadline shrinks from edge to orders")
    void theDeadlineShrinksFromEdgeToOrders() {
        try (DemoServices services = DemoServices.start()) {
            client(services).deadline();
        }

        List<String> lines = printed.toString().lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(0)).isEqualTo("Demo 3: a deadline shrinks across hops, and a Kafka event is unaffected");
        assertThat(lines.get(1)).matches("  edge      \\d+ ms left");
        assertThat(lines.get(2)).matches("  orders    \\d+ ms left");
        assertThat(budget(lines.get(1))).isGreaterThan(budget(lines.get(2)));
        assertThat(budget(lines.get(2))).isPositive();
    }

    @Test
    @DisplayName("REL-02 demo 3 a run in which orders received no deadline fails")
    void aRunInWhichOrdersReceivedNoDeadlineFails() {
        try (DemoServices services = DemoServices.startWithOrders("--boundrail.context.inbound.trust=none")) {
            DemoClient client = client(services);

            assertThatThrownBy(client::deadline).isInstanceOf(IllegalStateException.class).hasMessageContaining("orders 0 ms");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 3 a run in which the budget did not shrink at orders fails")
    void aRunInWhichTheBudgetDidNotShrinkFails() {
        try (DemoServices services = DemoServices.startWithOrders("--boundrail.context.inbound.trust=none",
                "--boundrail.context.deadline.default-inbound=1h")) {
            DemoClient client = client(services);

            assertThatThrownBy(client::deadline).isInstanceOf(IllegalStateException.class).hasMessageContaining("did not shrink");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 3 a first call that runs out of budget is discarded as the warm-up")
    void aFirstCallThatRunsOutOfBudgetIsDiscarded() {
        try (DemoServices services = DemoServices.start(DemoServices.FirstCallTimesOut.class)) {
            client(services).deadline();
        }

        assertThat(printed.toString().lines().toList()).hasSize(3);
    }

    private DemoClient client(DemoServices services) {
        return new DemoClient(services.edge(), new PrintStream(printed, true));
    }

    private static long budget(String line) {
        return Long.parseLong(line.replaceAll("\\D", ""));
    }
}
