package io.github.boundrail.context.internal;

import io.github.boundrail.context.Boundary;

/** An executor returned by {@code BoundExecutors}, so the Spring module can recognise it as covered, with its mode (EXE-04, DIAG-06). */
public interface ModeExecutor {

    /** {@return the mode the executor filters for: {@code ATTACHED} or {@code DETACHED}} */
    Boundary mode();
}
