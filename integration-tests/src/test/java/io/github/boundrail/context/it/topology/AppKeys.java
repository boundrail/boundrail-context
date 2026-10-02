package io.github.boundrail.context.it.topology;

import io.github.boundrail.context.ContextKey;

/** The application keys of the test topology's policy (001 Test Topology). */
public final class AppKeys {

    public static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    public static final ContextKey<Long> PRIORITY = ContextKey.of("priority", Long.class);

    private AppKeys() {
    }
}
