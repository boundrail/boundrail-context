package io.github.boundrail.context.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Violation;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Demo 2 of design.md §15: a tenant is stripped from an external call. */
class ExternalCallDemoTest {

    private final ByteArrayOutputStream printed = new ByteArrayOutputStream();

    @Test
    @DisplayName("REL-02 demo 2 the tenant set on the partner call is stripped and reported")
    void theTenantIsStrippedAndReported() throws InterruptedException {
        try (DemoServices services = DemoServices.start()) {
            client(services).externalCall();
        }

        List<String> lines = printed.toString().lines().toList();
        assertThat(lines).hasSizeBetween(2, 4);
        assertThat(lines.getFirst()).startsWith("Demo 2");
        assertThat(lines.subList(1, lines.size()))
                .anySatisfy(line -> assertThat(line).contains("edge", "Tenant-Id: acme"))
                .anySatisfy(line -> assertThat(line).contains("partner", "no Tenant-Id"))
                .anySatisfy(line -> assertThat(line).contains("edge", "FORBIDDEN_HEADER"));
    }

    @Test
    @DisplayName("REL-02 demo 2 a run in which partner received the Tenant-Id fails")
    void aRunInWhichPartnerReceivedTheTenantFails() {
        try (DemoServices services = DemoServices.start(TenantAllowedToPartner.class)) {
            DemoClient client = client(services);

            assertThatThrownBy(client::externalCall).isInstanceOf(IllegalStateException.class).hasMessageContaining("Tenant-Id");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 2 a run in which edge reported no FORBIDDEN_HEADER fails")
    void aRunInWhichEdgeReportedNothingFails() {
        try (DemoServices services = DemoServices.start(ViolationsDiscarded.class)) {
            DemoClient client = client(services);

            assertThatThrownBy(client::externalCall).isInstanceOf(IllegalStateException.class).hasMessageContaining("FORBIDDEN_HEADER");
        }

        assertThat(printed.toString()).isEmpty();
    }

    @Test
    @DisplayName("REL-02 demo 2 a first call that runs out of budget is discarded as the warm-up")
    void aFirstCallThatRunsOutOfBudgetIsDiscarded() throws InterruptedException {
        try (DemoServices services = DemoServices.start(DemoServices.FirstCallTimesOut.class)) {
            client(services).externalCall();
        }

        assertThat(printed.toString().lines().toList()).hasSize(4);
    }

    private DemoClient client(DemoServices services) {
        return new DemoClient(services.edge(), new PrintStream(printed, true));
    }

    /** Replaces {@code edge}'s policy with one that lets the tenant through, so the library has nothing to strip. */
    @Configuration(proxyBeanMethods = false)
    static class TenantAllowedToPartner {

        @Bean
        static ContextPolicy applicationPolicy() {
            return ContextPolicy.builder()
                    .key(DemoPolicy.TENANT, rules -> rules
                            .codec(Codecs.string())
                            .wireName("Tenant-Id")
                            .propagateTo(Boundary.EXTERNAL_CALL))
                    .build();
        }
    }

    /** Replaces {@code edge}'s listener with one that keeps nothing. */
    @Configuration(proxyBeanMethods = false)
    static class ViolationsDiscarded {

        @Bean
        static EdgeService.ReportedViolations reportedViolations() {
            return new EdgeService.ReportedViolations() {
                @Override
                public void onViolation(Violation violation) {
                }
            };
        }
    }
}
