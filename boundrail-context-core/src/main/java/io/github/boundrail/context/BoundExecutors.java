package io.github.boundrail.context;

import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;

/**
 * Wraps executors so that every task runs in the snapshot current when it was submitted, filtered for the executor's mode: {@link
 * Boundary#ATTACHED} for work the submitter waits for, {@link Boundary#DETACHED} for work that outlives it (EXE-02). The worker thread is left
 * with no context after each task.
 *
 * <p><b>Policy.</b> The methods that take a {@link ContextPolicy} filter with that policy. The methods that do not take one filter with
 * {@link BuiltInKeys#defaultPolicy()}, which holds only the built-in rules, so the rules an application policy declares for its own keys at
 * {@code ATTACHED} or {@code DETACHED} are not applied: an application key is then unregistered and propagates to both. To apply the
 * application's rules, pass its policy (EXE-04).
 *
 * <p><b>Expired deadlines.</b> An executor returned by {@code attached} does not run a task whose captured deadline has expired when the
 * task starts (EXE-08). A task submitted with {@code submit}, {@code invokeAll} or {@code invokeAny} completes its future exceptionally, so
 * {@code get} throws {@code ExecutionException} whose cause is {@link DeadlineExceededException} with stage {@code EXECUTOR}; for a task
 * given to {@code execute}, that exception is passed to the current thread's {@code UncaughtExceptionHandler} and {@code execute} returns
 * normally, so a pool thread survives and an inline executor behaves the same. A {@code detached} executor's snapshot holds no deadline, so
 * it always runs the task. Executor beans the Spring module decorates in {@code ATTACHED} mode carry the deadline but run the task whether or
 * not it has expired, because their decorator cannot fail the future Spring created. In Spring MVC, a handler's {@code Callable} that starts
 * after its deadline has expired is not called; that check is registered through a {@code WebMvcConfigurer}, so a
 * {@code RequestMappingHandlerAdapter} not built by {@code WebMvcConfigurationSupport} does not get it. Expiries detected here, in
 * {@code boundrail-context-core}, are not counted, because core has no meter registry (DL-09).
 *
 * <p><b>Authority.</b> Detached work carries no {@link BuiltInKeys#AUTHORITY}, including {@code @Async} methods on executors in the default
 * detached mode, so its calls to internal services send no {@code Authorization} header (ID-09). When the caller awaits the result, use an
 * attached executor; otherwise, have the task obtain a service credential itself.
 *
 * <p><b>Not covered.</b> {@code CompletableFuture.*Async} methods called without an explicit executor run on the common pool ({@link
 * java.util.concurrent.ForkJoinPool#commonPool()}) and are not covered; pass an executor returned by this class instead. {@code @Scheduled}
 * methods that return a reactive {@code Publisher}, and Kotlin {@code suspend} {@code @Scheduled} methods, are not given a system context
 * (EXE-06, EXE-07).
 *
 * <p><b>Startup audit.</b> The Spring module's startup audit only sees Spring beans: singleton executor beans defined in the application
 * context itself and instantiated at startup. Lazy beans not yet instantiated, prototype beans, beans of ancestor contexts and executors
 * produced by a {@code FactoryBean} are not audited (DIAG-12). Executors created in code are not audited either; runtime validation
 * (POL-08) covers them at the next remote crossing: a library adapter, for example an HTTP client with the library's interceptor, reports
 * {@code MISSING_REQUIRED} for a required key the task lost. Calls through clients without a library adapter are not checked (DIAG-07).
 */
public final class BoundExecutors {

    private BoundExecutors() {
    }

    /**
     * Returns an executor whose tasks run in the snapshot captured at submission, filtered for {@code ATTACHED} with
     * {@link BuiltInKeys#defaultPolicy()}; an application policy's rules are not applied (EXE-04).
     *
     * @param delegate the executor that runs the tasks
     * @return the wrapping executor
     * @throws NullPointerException if the delegate is {@code null}
     */
    public static Executor attached(Executor delegate) {
        return attached(delegate, BuiltInKeys.defaultPolicy());
    }

    /**
     * Returns an executor whose tasks run in the snapshot captured at submission, filtered for {@code DETACHED} with
     * {@link BuiltInKeys#defaultPolicy()}; an application policy's rules are not applied (EXE-04).
     *
     * @param delegate the executor that runs the tasks
     * @return the wrapping executor
     * @throws NullPointerException if the delegate is {@code null}
     */
    public static Executor detached(Executor delegate) {
        return detached(delegate, BuiltInKeys.defaultPolicy());
    }

    /**
     * Returns an executor whose tasks run in the snapshot captured at submission, filtered for {@code ATTACHED} with the given policy.
     *
     * @param delegate the executor that runs the tasks
     * @param policy the policy to filter with
     * @return the wrapping executor
     * @throws NullPointerException if an argument is {@code null}
     */
    public static Executor attached(Executor delegate, ContextPolicy policy) {
        return new BoundExecutor(delegate, policy, Boundary.ATTACHED);
    }

    /**
     * Returns an executor whose tasks run in the snapshot captured at submission, filtered for {@code DETACHED} with the given policy.
     *
     * @param delegate the executor that runs the tasks
     * @param policy the policy to filter with
     * @return the wrapping executor
     * @throws NullPointerException if an argument is {@code null}
     */
    public static Executor detached(Executor delegate, ContextPolicy policy) {
        return new BoundExecutor(delegate, policy, Boundary.DETACHED);
    }

    /**
     * Returns an executor service whose tasks run in the snapshot captured at submission, filtered for {@code ATTACHED} with
     * {@link BuiltInKeys#defaultPolicy()}; an application policy's rules are not applied (EXE-04). Lifecycle methods delegate.
     *
     * @param delegate the executor service that runs the tasks
     * @return the wrapping executor service
     * @throws NullPointerException if the delegate is {@code null}
     */
    public static ExecutorService attached(ExecutorService delegate) {
        return attached(delegate, BuiltInKeys.defaultPolicy());
    }

    /**
     * Returns an executor service whose tasks run in the snapshot captured at submission, filtered for {@code DETACHED} with
     * {@link BuiltInKeys#defaultPolicy()}; an application policy's rules are not applied (EXE-04). Lifecycle methods delegate.
     *
     * @param delegate the executor service that runs the tasks
     * @return the wrapping executor service
     * @throws NullPointerException if the delegate is {@code null}
     */
    public static ExecutorService detached(ExecutorService delegate) {
        return detached(delegate, BuiltInKeys.defaultPolicy());
    }

    /**
     * Returns an executor service whose tasks run in the snapshot captured at submission, filtered for {@code ATTACHED} with the given
     * policy. Lifecycle methods delegate.
     *
     * @param delegate the executor service that runs the tasks
     * @param policy the policy to filter with
     * @return the wrapping executor service
     * @throws NullPointerException if an argument is {@code null}
     */
    public static ExecutorService attached(ExecutorService delegate, ContextPolicy policy) {
        return new BoundExecutorService(delegate, policy, Boundary.ATTACHED);
    }

    /**
     * Returns an executor service whose tasks run in the snapshot captured at submission, filtered for {@code DETACHED} with the given
     * policy. Lifecycle methods delegate.
     *
     * @param delegate the executor service that runs the tasks
     * @param policy the policy to filter with
     * @return the wrapping executor service
     * @throws NullPointerException if an argument is {@code null}
     */
    public static ExecutorService detached(ExecutorService delegate, ContextPolicy policy) {
        return new BoundExecutorService(delegate, policy, Boundary.DETACHED);
    }
}
