package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Context;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskDecorator;

/**
 * The library's {@code TaskDecorator}. Spring calls {@code decorate} on the submitting thread, so it captures the submitter's snapshot,
 * filters it with the effective policy for the executor's mode, and returns the task wrapped in the filtered snapshot (EXE-02, EXE-03).
 */
final class ExecutorDecorator implements TaskDecorator {

    private final ObjectProvider<EffectivePolicy> policy;
    private final Boundary mode;

    /**
     * Creates a decorator.
     *
     * @param policy the effective policy, resolved when a task is first decorated, so no {@code ContextPolicy} bean is created while
     *     post-processors are registered
     * @param mode the executor's mode
     */
    ExecutorDecorator(ObjectProvider<EffectivePolicy> policy, Boundary mode) {
        this.policy = policy;
        this.mode = mode;
    }

    @Override
    public Runnable decorate(Runnable task) {
        return policy.getObject().policy().filter(Context.capture(), mode).wrap(task);
    }
}
