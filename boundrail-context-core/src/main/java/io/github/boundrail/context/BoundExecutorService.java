package io.github.boundrail.context;

import io.github.boundrail.context.internal.ModeExecutor;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * An executor service returned by {@link BoundExecutors}: every task submitted through {@code execute}, {@code submit}, {@code invokeAll}
 * or {@code invokeAny} runs in the submitter's snapshot, filtered for the mode (EXE-02). A task submitted with {@code submit},
 * {@code invokeAll} or {@code invokeAny} whose captured deadline has expired when it starts is not run, and its future fails with
 * {@code DeadlineExceededException} at {@code EXECUTOR} (EXE-08). Lifecycle methods delegate.
 */
final class BoundExecutorService implements ExecutorService, ModeExecutor {

    private final ExecutorService delegate;
    private final ContextPolicy policy;
    private final Boundary mode;

    BoundExecutorService(ExecutorService delegate, ContextPolicy policy, Boundary mode) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.mode = mode;
    }

    @Override
    public Boundary mode() {
        return mode;
    }

    /** {@return the submitter's current snapshot, filtered for the mode} */
    private ContextSnapshot captured() {
        return policy.filter(Context.capture(), mode);
    }

    /** {@return the tasks, each wrapped in one snapshot captured now; a {@code null} task throws NullPointerException} */
    private <T> List<Callable<T>> wrapped(Collection<? extends Callable<T>> tasks) {
        ContextSnapshot snapshot = captured();
        return tasks.stream().map(task -> refusingExpired(snapshot, snapshot.wrap(task))).toList();
    }

    /**
     * {@return the wrapped task, which first throws {@code DeadlineExceededException} with stage {@code EXECUTOR}, without running the task,
     * if the snapshot's deadline has expired when it starts} (EXE-08) A {@code DETACHED} snapshot holds no deadline (POL-04).
     */
    private static <T> Callable<T> refusingExpired(ContextSnapshot snapshot, Callable<T> wrapped) {
        return () -> {
            checkDeadline(snapshot);
            return wrapped.call();
        };
    }

    private static Runnable refusingExpired(ContextSnapshot snapshot, Runnable wrapped) {
        return () -> {
            checkDeadline(snapshot);
            wrapped.run();
        };
    }

    private static void checkDeadline(ContextSnapshot snapshot) {
        if (snapshot.find(BuiltInKeys.DEADLINE).filter(Deadline::isExpired).isPresent()) {
            throw new DeadlineExceededException(DeadlineExceededException.Stage.EXECUTOR);
        }
    }

    /** {@return the task wrapped in the snapshot captured now, refused if that snapshot's deadline has expired when it starts} */
    private Runnable bound(Runnable task) {
        ContextSnapshot snapshot = captured();
        return refusingExpired(snapshot, snapshot.wrap(task));
    }

    private <T> Callable<T> bound(Callable<T> task) {
        ContextSnapshot snapshot = captured();
        return refusingExpired(snapshot, snapshot.wrap(task));
    }

    @Override
    public void execute(Runnable task) {
        delegate.execute(captured().wrap(task));
    }

    @Override
    public Future<?> submit(Runnable task) {
        return delegate.submit(bound(task));
    }

    @Override
    public <T> Future<T> submit(Runnable task, T result) {
        return delegate.submit(bound(task), result);
    }

    @Override
    public <T> Future<T> submit(Callable<T> task) {
        return delegate.submit(bound(task));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks) throws InterruptedException {
        return delegate.invokeAll(wrapped(tasks));
    }

    @Override
    public <T> List<Future<T>> invokeAll(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.invokeAll(wrapped(tasks), timeout, unit);
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks) throws InterruptedException, ExecutionException {
        return delegate.invokeAny(wrapped(tasks));
    }

    @Override
    public <T> T invokeAny(Collection<? extends Callable<T>> tasks, long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        return delegate.invokeAny(wrapped(tasks), timeout, unit);
    }

    @Override
    public void shutdown() {
        delegate.shutdown();
    }

    @Override
    public List<Runnable> shutdownNow() {
        return delegate.shutdownNow();
    }

    @Override
    public boolean isShutdown() {
        return delegate.isShutdown();
    }

    @Override
    public boolean isTerminated() {
        return delegate.isTerminated();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return delegate.awaitTermination(timeout, unit);
    }

    @Override
    public void close() {
        delegate.close();
    }
}
