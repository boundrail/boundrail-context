package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Context;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.task.TaskDecorator;

/**
 * The library's {@code TaskDecorator}. Spring calls {@code decorate} on the submitting thread, so it captures the submitter's snapshot,
 * filters it with the effective policy for the executor's mode, and returns the task, decorated by the executor's existing decorator if
 * there is one, wrapped in the filtered snapshot, so the existing decorator's runnable runs inside the scope (EXE-02, EXE-03).
 */
final class ExecutorDecorator implements TaskDecorator {

    private final ObjectProvider<EffectivePolicy> policy;
    private final Boundary mode;
    private final TaskDecorator existing;
    private final String beanName;

    /**
     * Creates a decorator.
     *
     * @param policy the effective policy, resolved when a task is first decorated, so no {@code ContextPolicy} bean is created while
     *     post-processors are registered
     * @param mode the executor's mode
     * @param existing the decorator the executor already had, run inside the library's scope, or {@code null}
     * @param beanName the name of the executor bean it was installed for
     */
    ExecutorDecorator(ObjectProvider<EffectivePolicy> policy, Boundary mode, TaskDecorator existing, String beanName) {
        this.policy = policy;
        this.mode = mode;
        this.existing = existing;
        this.beanName = beanName;
    }

    /** {@return the executor's mode} */
    Boundary mode() {
        return mode;
    }

    /** {@return the name of the executor bean it was installed for} */
    String beanName() {
        return beanName;
    }

    @Override
    public Runnable decorate(Runnable task) {
        Runnable inner = existing == null ? task : existing.decorate(task);
        return policy.getObject().policy().filter(Context.capture(), mode).wrap(inner);
    }
}
