package io.github.boundrail.context.spring;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.ScopeListener;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;

/**
 * Mirrors the loggable keys of the context's effective policy into SLF4J's MDC for the duration of each scope (LOG-01): on entry, each bound
 * loggable key is put under its name, encoded with its codec or {@code toString()}; on exit, the previous MDC value of each of those names is
 * put back, or the name removed where it had none. The MDC is never cleared. {@code AUTHORITY} and {@code DEADLINE} are never written
 * (LOG-02). Registers itself with {@link ContextRuntime} when created.
 */
final class MdcScopeListener implements ScopeListener {

    private final ContextPolicy policy;
    private final List<ContextKey<?>> loggable;

    MdcScopeListener(EffectivePolicy effectivePolicy) {
        this.policy = effectivePolicy.policy();
        this.loggable = policy.keys().stream()
                .filter(policy::isLoggable)
                .filter(key -> key != BuiltInKeys.AUTHORITY && key != BuiltInKeys.DEADLINE)
                .toList();
        ContextRuntime.addScopeListener(this);
    }

    @Override
    public Restorer onEnter(ContextSnapshot entered) {
        Map<String, String> previous = new HashMap<>();
        for (ContextKey<?> key : loggable) {
            put(key, entered, previous);
        }
        return () -> previous.forEach((name, old) -> {
            if (old == null) {
                MDC.remove(name);
            } else {
                MDC.put(name, old);
            }
        });
    }

    /** Puts the key's encoded value under its name if it is bound, remembering the name's previous value ({@code null} if absent). */
    private <T> void put(ContextKey<T> key, ContextSnapshot entered, Map<String, String> previous) {
        entered.find(key).ifPresent(value -> {
            previous.put(key.name(), MDC.get(key.name()));
            MDC.put(key.name(), policy.codec(key).map(codec -> codec.encode(value)).orElseGet(value::toString));
        });
    }
}
