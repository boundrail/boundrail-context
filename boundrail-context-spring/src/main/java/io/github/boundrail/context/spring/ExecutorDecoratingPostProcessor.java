package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.ReflectionUtils;

/**
 * Installs the library's decorator on every {@code ThreadPoolTaskExecutor}, {@code SimpleAsyncTaskExecutor} and
 * {@code ThreadPoolTaskScheduler} bean before its initialisation, unless {@code boundrail.context.executors.auto-decorate} is {@code false}
 * (EXE-03). A decorator the bean already has is read from Spring's private {@code taskDecorator} field, which has no getter, and runs
 * inside the library's scope; the composed decorator is set through the public setter (EXE-10). No other executor bean is touched
 * (EXE-04). Every decorated executor is {@code DETACHED}.
 */
final class ExecutorDecoratingPostProcessor implements BeanPostProcessor {

    private static final String AUTO_DECORATE = "boundrail.context.executors.auto-decorate";
    private static final String TASK_DECORATOR_FIELD = "taskDecorator";

    private final ObjectProvider<EffectivePolicy> policy;
    private final Environment environment;
    private final String fieldName;
    private final Map<Class<?>, Field> fields = new ConcurrentHashMap<>();

    ExecutorDecoratingPostProcessor(ObjectProvider<EffectivePolicy> policy, Environment environment) {
        this(policy, environment, TASK_DECORATOR_FIELD);
    }

    /** Creates a post-processor that reads the decorator from {@code fieldName}; tests use it to simulate a renamed Spring field. */
    ExecutorDecoratingPostProcessor(ObjectProvider<EffectivePolicy> policy, Environment environment, String fieldName) {
        this.policy = policy;
        this.environment = environment;
        this.fieldName = fieldName;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (!(bean instanceof ThreadPoolTaskExecutor || bean instanceof SimpleAsyncTaskExecutor || bean instanceof ThreadPoolTaskScheduler)
                || !autoDecorate()) {
            return bean;
        }
        switch (bean) {
            case ThreadPoolTaskExecutor executor -> decorate(executor, ThreadPoolTaskExecutor.class, executor::setTaskDecorator);
            case SimpleAsyncTaskExecutor executor -> decorate(executor, SimpleAsyncTaskExecutor.class, executor::setTaskDecorator);
            case ThreadPoolTaskScheduler scheduler -> decorate(scheduler, ThreadPoolTaskScheduler.class, scheduler::setTaskDecorator);
            default -> { }
        }
        return bean;
    }

    /**
     * {@return the private {@code taskDecorator} field of one of the EXE-03 types}, looked up once per type.
     *
     * @throws IllegalStateException if the type has no such field, which fails startup (EXE-10)
     */
    Field taskDecoratorField(Class<?> type) {
        return fields.computeIfAbsent(type, this::findField);
    }

    private Field findField(Class<?> type) {
        Field field = ReflectionUtils.findField(type, fieldName, TaskDecorator.class);
        if (field == null) {
            throw new IllegalStateException("Cannot compose with the existing task decorator of " + type.getName() + ": its field "
                    + fieldName + " of type TaskDecorator is missing (EXE-10)");
        }
        ReflectionUtils.makeAccessible(field);
        return field;
    }

    /** Sets the library's decorator around the existing one, unless the existing one is already the library's (EXE-03). */
    private void decorate(Object executor, Class<?> type, Consumer<TaskDecorator> setter) {
        TaskDecorator existing = (TaskDecorator) ReflectionUtils.getField(taskDecoratorField(type), executor);
        if (!(existing instanceof ExecutorDecorator)) {
            setter.accept(new ExecutorDecorator(policy, Boundary.DETACHED, existing));
        }
    }

    /** {@return the value of {@code executors.auto-decorate}, with relaxed binding; {@code true} if unset} */
    private boolean autoDecorate() {
        return Binder.get(environment).bind(AUTO_DECORATE, Boolean.class).orElse(true);
    }
}
