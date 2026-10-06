package io.github.boundrail.context;

import io.github.boundrail.context.internal.ModeExecutor;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * An executor returned by {@link BoundExecutors}: captures the submitter's snapshot, filters it for the mode, and runs the task in it
 * (EXE-02).
 */
final class BoundExecutor implements Executor, ModeExecutor {

    private final Executor delegate;
    private final ContextPolicy policy;
    private final Boundary mode;

    BoundExecutor(Executor delegate, ContextPolicy policy, Boundary mode) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.mode = mode;
    }

    @Override
    public Boundary mode() {
        return mode;
    }

    @Override
    public void execute(Runnable task) {
        delegate.execute(policy.filter(Context.capture(), mode).wrap(task));
    }
}
