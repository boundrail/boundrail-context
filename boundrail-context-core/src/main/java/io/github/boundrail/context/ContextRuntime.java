package io.github.boundrail.context;

import io.github.boundrail.context.internal.ContextSlot;
import io.github.boundrail.context.internal.SlotFactory;

/** The runtime that holds the current snapshot in a single slot per thread of execution (RT-01). */
public final class ContextRuntime {

    static final ContextSlot SLOT = SlotFactory.create();

    private ContextRuntime() {
    }
}
