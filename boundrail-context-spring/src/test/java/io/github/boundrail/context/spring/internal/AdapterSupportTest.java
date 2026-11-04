package io.github.boundrail.context.spring.internal;

import static io.github.boundrail.context.Boundary.MESSAGE;
import static io.github.boundrail.context.Violation.Kind.INVALID_VALUE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Violation;
import io.github.boundrail.context.spring.BoundrailContextAutoConfiguration;
import io.github.boundrail.context.spring.ViolationListener;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AdapterSupportTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);

    private final List<Violation> received = new CopyOnWriteArrayList<>();

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            .withBean(SimpleMeterRegistry.class)
            .withBean(ViolationListener.class, () -> received::add)
            .withBean(ContextPolicy.class, () -> ContextPolicy.builder()
                    .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(MESSAGE))
                    .build());

    @Test
    @DisplayName("POL-07 AdapterSupport gives the effective policy, merged with every ContextPolicy bean")
    void givesTheEffectivePolicy() {
        runner.run(context -> {
            ContextPolicy policy = context.getBean(AdapterSupport.class).policy();

            assertThat(policy.keys()).containsAll(BuiltInKeys.defaultPolicy().keys()).contains(TENANT);
        });
    }

    @Test
    @DisplayName("DIAG-03 a violation reported through AdapterSupport is logged and counted by the context's reporter and passed to its"
            + " ViolationListener beans")
    void reportsThroughTheContextsReporter(CapturedOutput output) {
        runner.run(context -> {
            AdapterSupport support = context.getBean(AdapterSupport.class);
            Violation violation = support.violation(INVALID_VALUE, "tenant", MESSAGE);

            support.report(violation);

            assertThat(context.getBean(MeterRegistry.class).get("boundrail.context.violations")
                    .tags("kind", "invalid_value", "key", "tenant", "boundary", "message").counter().count()).isEqualTo(1.0);
            assertThat(output.getOut().lines().filter(line -> line.contains("WARN") && line.contains("INVALID_VALUE")))
                    .singleElement().asString()
                    .contains("key tenant", "wire name Tenant-Id", "boundary MESSAGE");
            assertThat(received).as("DIAG-11 the listener receives the same instance").singleElement().isSameAs(violation);
        });
    }

    @Test
    @DisplayName("DIAG-04 a violation built by AdapterSupport carries the current thread's name and no destination")
    void builtViolationCarriesTheThreadAndNoDestination() {
        runner.run(context -> {
            Violation violation = context.getBean(AdapterSupport.class).violation(INVALID_VALUE, "tenant", MESSAGE);

            assertThat(violation.kind()).isEqualTo(INVALID_VALUE);
            assertThat(violation.key()).isEqualTo("tenant");
            assertThat(violation.boundary()).isEqualTo(MESSAGE);
            assertThat(violation.thread()).isEqualTo(Thread.currentThread().getName());
            assertThat(violation.destination()).isEmpty();
        });
    }

    @Test
    @DisplayName("DIAG-02 AdapterSupport gives violations.mode and wire.max-value-length as bound")
    void givesTheViolationModeAndMaxValueLengthAsBound() {
        runner.run(context -> {
            AdapterSupport support = context.getBean(AdapterSupport.class);

            assertThat(support.failOnMissingRequired()).as("the default mode is fail").isTrue();
            assertThat(support.maxValueLength()).as("the default maximum").isEqualTo(256);
        });
        runner.withPropertyValues("boundrail.context.violations.mode=report", "boundrail.context.wire.max-value-length=10").run(context -> {
            AdapterSupport support = context.getBean(AdapterSupport.class);

            assertThat(support.failOnMissingRequired()).isFalse();
            assertThat(support.maxValueLength()).isEqualTo(10);
        });
    }
}
