package io.github.boundrail.context;

import java.util.Map;

/** An immutable set of key bindings; the current snapshot is the one visible to the running code. */
public interface ContextSnapshot {

    /** The snapshot with no bindings. */
    ContextSnapshot EMPTY = new ImmutableContextSnapshot(Map.of());
}
