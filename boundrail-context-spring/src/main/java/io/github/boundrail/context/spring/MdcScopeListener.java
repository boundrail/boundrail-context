package io.github.boundrail.context.spring;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.ScopeListener;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.MDC;
import org.springframework.beans.factory.DisposableBean;

/**
 * Mirrors the loggable keys of the context's effective policy into SLF4J's MDC for the duration of each scope (LOG-01): on entry, each bound
 * loggable key is put under its name, encoded with its codec or {@code toString()}, and each unbound one is removed; on exit, the previous
 * MDC value of each of those names is put back, or the name removed where it had none. The MDC is never cleared. {@code AUTHORITY} and
 * {@code DEADLINE} are never written (LOG-02). Registers itself with {@link ContextRuntime} when created, and removes itself when its
 * context closes, so each context handles only its own policy's keys (DESIGN D44).
 */
final class MdcScopeListener implements ScopeListener, DisposableBean {

    private final ContextPolicy policy;
    /** In name order, so entry is deterministic. */
    private final List<ContextKey<?>> loggable;

    MdcScopeListener(EffectivePolicy effectivePolicy) {
        this.policy = effectivePolicy.policy();
        this.loggable = policy.keys().stream()
                .filter(policy::isLoggable)
                .filter(key -> key != BuiltInKeys.AUTHORITY && key != BuiltInKeys.DEADLINE)
                .sorted(Comparator.comparing(ContextKey::name))
                .toList();
        ContextRuntime.addScopeListener(this);
    }

    /**
     * Writes the loggable keys of the entered snapshot to the MDC. If a codec throws, a {@code RuntimeException} or an {@code Error}, the
     * names already written are put back before it propagates unchanged, so the MDC is left as it was (RT-07 then decides the scope's
     * outcome).
     */
    @Override
    public Restorer onEnter(ContextSnapshot snapshot) {
        Map<String, String> previous = new HashMap<>();
        Restorer restorer = () -> previous.forEach((name, old) -> {
            if (old == null) {
                MDC.remove(name);
            } else {
                MDC.put(name, old);
            }
        });
        boolean entered = false;
        try {
            for (ContextKey<?> key : loggable) {
                mirror(key, snapshot, previous);
            }
            entered = true;
        } finally {
            if (!entered) {
                restorer.restore();
            }
        }
        return restorer;
    }

    /** Removes this listener when its context closes (LOG-01). */
    @Override
    public void destroy() {
        ContextRuntime.removeScopeListener(this);
    }

    /**
     * Puts the key's encoded value under its name if it is bound, or removes the name if it is not, remembering the name's previous value
     * ({@code null} if absent) when it changes.
     */
    private <T> void mirror(ContextKey<T> key, ContextSnapshot entered, Map<String, String> previous) {
        String name = key.name();
        String old = MDC.get(name);
        Optional<T> value = entered.find(key);
        if (value.isPresent()) {
            String encoded = policy.codec(key).map(codec -> codec.encode(value.get())).orElseGet(() -> value.get().toString());
            previous.put(name, old);
            MDC.put(name, encoded);
        } else if (old != null) {
            previous.put(name, old);
            MDC.remove(name);
        }
    }
}
