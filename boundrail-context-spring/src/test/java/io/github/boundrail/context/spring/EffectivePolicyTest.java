package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Boundary.MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EffectivePolicyTest {

    private static final ContextKey<String> TENANT = ContextKey.of("acme.tenant", String.class);
    private static final ContextKey<Long> ORDER = ContextKey.of("acme.order", Long.class);

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

    @Test
    @DisplayName("POL-07 with no ContextPolicy bean the effective policy is the built-in policy")
    void withNoContextPolicyBeanTheEffectivePolicyIsTheBuiltInPolicy() {
        runner.run(context -> {
            ContextPolicy policy = context.getBean(EffectivePolicy.class).policy();

            assertThat(policy.keys()).doesNotContain(TENANT);
            assertThat(policy.wireName(BuiltInKeys.INITIATOR)).contains("Boundrail-Initiator");
        });
    }

    @Test
    @DisplayName("POL-07 every ContextPolicy bean is merged with the built-in policy")
    void everyContextPolicyBeanIsMergedWithTheBuiltInPolicy() {
        runner.withBean("tenantPolicy", ContextPolicy.class, () -> ContextPolicy.builder()
                        .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(INTERNAL_CALL))
                        .build())
                .withBean("orderPolicy", ContextPolicy.class, () -> ContextPolicy.builder()
                        .key(ORDER, rules -> rules.codec(Codecs.longValue()).wireName("Order-Id").propagateTo(MESSAGE))
                        .build())
                .run(context -> {
                    ContextPolicy policy = context.getBean(EffectivePolicy.class).policy();

                    assertThat(policy.wireName(TENANT)).contains("Tenant-Id");
                    assertThat(policy.propagates(TENANT, INTERNAL_CALL)).isTrue();
                    assertThat(policy.wireName(ORDER)).contains("Order-Id");
                    assertThat(policy.propagates(ORDER, MESSAGE)).isTrue();
                    assertThat(policy.wireName(BuiltInKeys.INITIATOR)).contains("Boundrail-Initiator");
                });
    }

    @Test
    @DisplayName("POL-07 conflicting ContextPolicy beans fail startup")
    void conflictingContextPolicyBeansFailStartup() {
        runner.withBean("tenantPolicy", ContextPolicy.class, () -> ContextPolicy.builder()
                        .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(INTERNAL_CALL))
                        .build())
                .withBean("otherTenantPolicy", ContextPolicy.class, () -> ContextPolicy.builder()
                        .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("X-Tenant").propagateTo(INTERNAL_CALL))
                        .build())
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class);
                });
    }
}
