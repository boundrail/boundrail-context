package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.Environment;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Installs the library's decorator on every {@code ThreadPoolTaskExecutor}, {@code SimpleAsyncTaskExecutor} and
 * {@code ThreadPoolTaskScheduler} bean before its initialisation, unless {@code boundrail.context.executors.auto-decorate} is {@code false}
 * (EXE-03). No other executor bean is touched (EXE-04). Every decorated executor is {@code DETACHED}.
 */
final class ExecutorDecoratingPostProcessor implements BeanPostProcessor {

    private static final String AUTO_DECORATE = "boundrail.context.executors.auto-decorate";

    private final ObjectProvider<EffectivePolicy> policy;
    private final Environment environment;

    ExecutorDecoratingPostProcessor(ObjectProvider<EffectivePolicy> policy, Environment environment) {
        this.policy = policy;
        this.environment = environment;
    }

    @Override
    public Object postProcessBeforeInitialization(Object bean, String beanName) {
        if (!(bean instanceof ThreadPoolTaskExecutor || bean instanceof SimpleAsyncTaskExecutor || bean instanceof ThreadPoolTaskScheduler)
                || !autoDecorate()) {
            return bean;
        }
        ExecutorDecorator decorator = new ExecutorDecorator(policy, Boundary.DETACHED);
        switch (bean) {
            case ThreadPoolTaskExecutor executor -> executor.setTaskDecorator(decorator);
            case SimpleAsyncTaskExecutor executor -> executor.setTaskDecorator(decorator);
            case ThreadPoolTaskScheduler scheduler -> scheduler.setTaskDecorator(decorator);
            default -> { }
        }
        return bean;
    }

    /** {@return the value of {@code executors.auto-decorate}, with relaxed binding; {@code true} if unset} */
    private boolean autoDecorate() {
        return Binder.get(environment).bind(AUTO_DECORATE, Boolean.class).orElse(true);
    }
}
