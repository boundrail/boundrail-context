package io.github.boundrail.context.internal;

import io.github.boundrail.context.ContextCallable;
import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.ContextSnapshot;

/**
 * The ScopedValue backend of the slot (RT-01, RT-02). A binding lasts exactly as long as the operation, so the previous snapshot is current
 * again when the operation returns or throws.
 */
final class ScopedValueSlot implements ContextSlot {

    private final ScopedValue<ContextSnapshot> current = ScopedValue.newInstance();

    @Override
    public ContextRuntime.Backend backend() {
        return ContextRuntime.Backend.SCOPED_VALUE;
    }

    @Override
    public ContextSnapshot current() {
        return current.orElse(ContextSnapshot.EMPTY);
    }

    @Override
    public <R, X extends Throwable> R call(ContextSnapshot snapshot, ContextCallable<R, X> op) throws X {
        return ScopedValue.where(current, snapshot).call(op::call);
    }
}
