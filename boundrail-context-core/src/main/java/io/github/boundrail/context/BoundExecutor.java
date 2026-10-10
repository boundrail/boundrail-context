package io.github.boundrail.context;

import io.github.boundrail.context.internal.ModeExecutor;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * An executor returned by {@link BoundExecutors}: captures the submitter's snapshot, filters it for the mode, and runs the task in it
 * (EXE-02). A task whose captured deadline has expired when it starts is not run; its exception goes to the thread's
 * {@code UncaughtExceptionHandler} (EXE-08).
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
        ContextSnapshot snapshot = policy.filter(Context.capture(), mode);
        delegate.execute(handingOverExpired(snapshot, snapshot.wrap(task)));
    }

    /**
     * {@return the wrapped task, which, if the snapshot's deadline has expired when it starts, does not run the task and passes
     * {@code DeadlineExceededException} with stage {@code EXECUTOR} to the current thread's {@code UncaughtExceptionHandler}, then returns
     * normally} (EXE-08) Returning instead of throwing keeps a pool thread alive and makes an inline executor behave the same (D55).
     */
    static Runnable handingOverExpired(ContextSnapshot snapshot, Runnable wrapped) {
        return () -> {
            if (isExpired(snapshot)) {
                Thread current = Thread.currentThread();
                current.getUncaughtExceptionHandler()
                        .uncaughtException(current, new DeadlineExceededException(DeadlineExceededException.Stage.EXECUTOR));
                return;
            }
            wrapped.run();
        };
    }

    /** {@return whether the snapshot holds a deadline that has expired}; a {@code DETACHED} snapshot holds none (POL-04) */
    static boolean isExpired(ContextSnapshot snapshot) {
        return snapshot.find(BuiltInKeys.DEADLINE).filter(Deadline::isExpired).isPresent();
    }
}
