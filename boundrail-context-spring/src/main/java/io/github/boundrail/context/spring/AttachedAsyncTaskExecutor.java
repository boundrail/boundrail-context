package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Context;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.AsyncTaskExecutor;

/**
 * Spring MVC's executor for a handler's {@code Callable} and a {@code WebAsyncTask} without an executor of its own. It captures the
 * request's snapshot when MVC submits the task, filters it for {@code ATTACHED} with the effective policy, because the request awaits the
 * result, and hands the task wrapped in that snapshot to MVC's original executor. The wrap is inside the original executor's own
 * decorator, so the task runs in the {@code ATTACHED} snapshot whatever that executor's mode (EXE-09). {@code submit} goes through
 * {@code execute}, so it is covered too.
 */
final class AttachedAsyncTaskExecutor implements AsyncTaskExecutor {

    private final ObjectProvider<EffectivePolicy> policy;
    private final AsyncTaskExecutor delegate;

    /**
     * Creates the executor.
     *
     * @param policy the effective policy, resolved when a task is submitted
     * @param delegate MVC's original executor, which runs the wrapped tasks
     */
    AttachedAsyncTaskExecutor(ObjectProvider<EffectivePolicy> policy, AsyncTaskExecutor delegate) {
        this.policy = policy;
        this.delegate = delegate;
    }

    @Override
    public void execute(Runnable task) {
        delegate.execute(policy.getObject().policy().filter(Context.capture(), Boundary.ATTACHED).wrap(task));
    }
}
