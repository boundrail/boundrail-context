package io.github.boundrail.context.spring;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.Initiator;
import io.micrometer.observation.ObservationRegistry;
import java.lang.reflect.Method;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.support.ScheduledMethodRunnable;

/**
 * Spring's scheduled-annotation post-processor, registered by {@link ScheduledContextRegistrar} in place of Spring's own definition, so that
 * each run of an ordinary {@code @Scheduled} method invoked by the scheduler gets a fresh snapshot containing only
 * {@code INITIATOR = "system:scheduled:<beanName>.<methodName>"} (EXE-06). A direct call of the method by application code does not go
 * through the scheduled runnable, so it keeps the caller's context. Reactive and Kotlin {@code suspend} methods are scheduled by Spring
 * through another path and are not covered.
 */
class ContextScheduledAnnotationBeanPostProcessor extends ScheduledAnnotationBeanPostProcessor {

    /**
     * The name of the bean whose {@code @Scheduled} methods are being processed: {@code createRunnable}'s third argument is the scheduler
     * qualifier, not the bean name. Startup bookkeeping of this one bean, set only while a bean is processed.
     */
    private final ThreadLocal<String> processedBean = new ThreadLocal<>();

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        processedBean.set(beanName);
        try {
            return super.postProcessAfterInitialization(bean, beanName);
        } finally {
            processedBean.remove();
        }
    }

    @Override
    protected Runnable createRunnable(Object target, Method method, String qualifier) {
        Runnable runnable = super.createRunnable(target, method, qualifier);
        return runnable instanceof ScheduledMethodRunnable scheduled
                ? new SystemContextRunnable(scheduled, "system:scheduled:" + processedBean.get() + "." + method.getName())
                : runnable;
    }

    /**
     * Runs Spring's runnable in a fresh snapshot with only the initiator bound. A {@code ScheduledMethodRunnable} with the same target,
     * method and qualifier, so the scheduled task keeps its description.
     */
    private static final class SystemContextRunnable extends ScheduledMethodRunnable {

        private final ScheduledMethodRunnable delegate;
        private final Initiator initiator;

        SystemContextRunnable(ScheduledMethodRunnable delegate, String initiator) {
            // Only run() observes, and it delegates to Spring's runnable, which has the registrar's observation registry.
            super(delegate.getTarget(), delegate.getMethod(), delegate.getQualifier(), () -> ObservationRegistry.NOOP);
            this.delegate = delegate;
            this.initiator = Initiator.of(initiator);
        }

        @Override
        public void run() {
            ContextSnapshot.EMPTY.run(() -> Context.with(BuiltInKeys.INITIATOR, initiator).run(delegate));
        }
    }
}
