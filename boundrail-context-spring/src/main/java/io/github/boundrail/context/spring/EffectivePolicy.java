package io.github.boundrail.context.spring;

import io.github.boundrail.context.ContextPolicy;

/**
 * Holds the effective policy: the built-in policy merged with every {@code ContextPolicy} bean (POL-07). It is not a {@code ContextPolicy}
 * bean itself, so it is not one of the beans it merges.
 */
record EffectivePolicy(ContextPolicy policy) {
}
