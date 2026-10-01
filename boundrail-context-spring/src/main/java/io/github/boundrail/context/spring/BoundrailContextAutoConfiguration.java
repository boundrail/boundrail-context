package io.github.boundrail.context.spring;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.ContextPolicy;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;

/** Spring Boot auto-configuration for Boundrail Context. */
@AutoConfiguration
public class BoundrailContextAutoConfiguration {

    /** The built-in policy merged, in bean order, with every {@code ContextPolicy} bean; a conflict fails startup (POL-07, POL-05). */
    @Bean
    @Lazy(false)
    EffectivePolicy boundrailEffectivePolicy(ObjectProvider<ContextPolicy> policies) {
        ContextPolicy effective = BuiltInKeys.defaultPolicy();
        for (ContextPolicy policy : policies.orderedStream().toList()) {
            effective = effective.merge(policy);
        }
        return new EffectivePolicy(effective);
    }
}
