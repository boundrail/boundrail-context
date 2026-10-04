package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.spring.BoundrailContextProperties.Executors;
import java.lang.reflect.Field;
import java.util.Locale;
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
 * (EXE-04). Each decorated executor has the mode {@code boundrail.context.executors.modes} gives its bean name, {@code DETACHED} by default
 * (EXE-01).
 */
final class ExecutorDecoratingPostProcessor implements BeanPostProcessor {

    private static final String EXECUTORS = "boundrail.context.executors";
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
        if (!(bean instanceof ThreadPoolTaskExecutor || bean instanceof SimpleAsyncTaskExecutor || bean instanceof ThreadPoolTaskScheduler)) {
            return bean;
        }
        Executors executors = executors();
        if (!executors.autoDecorate()) {
            return bean;
        }
        Boundary mode = executors.mode(beanName);
        switch (bean) {
            case ThreadPoolTaskExecutor executor ->
                    decorate(executor, ThreadPoolTaskExecutor.class, executor::setTaskDecorator, beanName, mode);
            case SimpleAsyncTaskExecutor executor ->
                    decorate(executor, SimpleAsyncTaskExecutor.class, executor::setTaskDecorator, beanName, mode);
            case ThreadPoolTaskScheduler scheduler ->
                    decorate(scheduler, ThreadPoolTaskScheduler.class, scheduler::setTaskDecorator, beanName, mode);
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

    /**
     * Sets the library's decorator around the existing one, unless the existing one is already the library's (EXE-03): the same instance
     * post-processed again, under the same or another bean name.
     *
     * @throws IllegalStateException if the instance was decorated under another bean name with a different mode (EXE-01)
     */
    private void decorate(Object executor, Class<?> type, Consumer<TaskDecorator> setter, String beanName, Boundary mode) {
        TaskDecorator existing = (TaskDecorator) ReflectionUtils.getField(taskDecoratorField(type), executor);
        if (!(existing instanceof ExecutorDecorator ours)) {
            setter.accept(new ExecutorDecorator(policy, mode, existing, beanName));
        } else if (ours.mode() != mode) {
            throw new IllegalStateException("The executor beans " + ours.beanName() + " and " + beanName + " are one instance with different "
                    + "modes in boundrail.context.executors.modes: " + ours.mode().name().toLowerCase(Locale.ROOT) + " and "
                    + mode.name().toLowerCase(Locale.ROOT));
        }
    }

    /** {@return the {@code executors} properties, with relaxed binding and their defaults} */
    private Executors executors() {
        return Binder.get(environment).bind(EXECUTORS, Executors.class).orElseGet(() -> new Executors(true, Map.of()));
    }
}
