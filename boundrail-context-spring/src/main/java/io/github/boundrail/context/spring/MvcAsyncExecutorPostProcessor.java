package io.github.boundrail.context.spring;

import java.lang.reflect.Field;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.util.ReflectionUtils;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/**
 * Wraps the executor of every {@code RequestMappingHandlerAdapter} bean after its initialisation, so a handler's {@code Callable}, or a
 * {@code WebAsyncTask} without an executor of its own, runs in the request's snapshot filtered for {@code ATTACHED} (EXE-09). The
 * adapter's executor, its internal default included, is read from Spring's private {@code taskExecutor} field, which has no getter, and
 * the wrapper is set through the public setter (EXE-10).
 */
final class MvcAsyncExecutorPostProcessor implements BeanPostProcessor {

    private static final String TASK_EXECUTOR_FIELD = "taskExecutor";

    private final ObjectProvider<EffectivePolicy> policy;
    private final Field field;

    MvcAsyncExecutorPostProcessor(ObjectProvider<EffectivePolicy> policy) {
        this(policy, TASK_EXECUTOR_FIELD);
    }

    /**
     * Creates a post-processor that reads the executor from {@code fieldName}; tests use it to simulate a renamed Spring field. The field is
     * looked up here, so a missing one fails startup before any adapter is created, even a lazy one (EXE-10).
     *
     * @throws IllegalStateException if {@code RequestMappingHandlerAdapter} has no such field
     */
    MvcAsyncExecutorPostProcessor(ObjectProvider<EffectivePolicy> policy, String fieldName) {
        this.policy = policy;
        Field found = ReflectionUtils.findField(RequestMappingHandlerAdapter.class, fieldName, AsyncTaskExecutor.class);
        if (found == null) {
            throw new IllegalStateException("Cannot run Spring MVC's async tasks in the request's context: the field " + fieldName
                    + " of type AsyncTaskExecutor of " + RequestMappingHandlerAdapter.class.getName() + " is missing (EXE-10)");
        }
        ReflectionUtils.makeAccessible(found);
        this.field = found;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof RequestMappingHandlerAdapter adapter) {
            AsyncTaskExecutor executor = (AsyncTaskExecutor) ReflectionUtils.getField(field, adapter);
            adapter.setTaskExecutor(new AttachedAsyncTaskExecutor(policy, executor));
        }
        return bean;
    }

    /** {@return the private {@code taskExecutor} field of {@code RequestMappingHandlerAdapter}}, looked up once, at construction */
    Field taskExecutorField() {
        return field;
    }
}
