package io.github.boundrail.context.spring;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.Executor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.util.CustomizableThreadCreator;

/**
 * The executor beans of one application context, by thread-name prefix, for the executor evidence of violations (DIAG-04). Built when the
 * context has refreshed, so a violation reported earlier has no evidence. It covers only singleton executor beans of the context itself that
 * are already instantiated, and instantiates none (DIAG-12).
 */
final class ExecutorTable implements ApplicationListener<ContextRefreshedEvent>, ApplicationContextAware {

    /** A thread-name prefix and the evidence for a thread whose name starts with it. */
    private record Prefix(String prefix, String evidence) {
    }

    private ApplicationContext context;
    private volatile List<Prefix> prefixes = List.of();

    @Override
    public void setApplicationContext(ApplicationContext context) {
        this.context = context;
    }

    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        if (event.getApplicationContext() != context || !(context instanceof ConfigurableApplicationContext configurable)) {
            return;
        }
        ConfigurableListableBeanFactory beanFactory = configurable.getBeanFactory();
        List<ExecutorDecoratingPostProcessor> postProcessors =
                List.copyOf(beanFactory.getBeansOfType(ExecutorDecoratingPostProcessor.class, false, false).values());
        List<Prefix> table = new ArrayList<>();
        for (String beanName : beanFactory.getBeanNamesForType(Executor.class, false, false)) {
            if (!beanFactory.containsSingleton(beanName) || !(beanFactory.getSingleton(beanName) instanceof Executor executor)) {
                continue;
            }
            String status = postProcessors.stream().flatMap(postProcessor -> postProcessor.decoratedMode(beanName).stream()).findFirst()
                    .map(mode -> "COVERED " + mode.name().toLowerCase(Locale.ROOT)).orElse("GAP");
            String evidence = beanName + " (" + status + ")";
            table.add(new Prefix(beanName, evidence));
            if ((executor instanceof ThreadPoolTaskExecutor || executor instanceof SimpleAsyncTaskExecutor
                    || executor instanceof ThreadPoolTaskScheduler) && executor instanceof CustomizableThreadCreator creator) {
                table.add(new Prefix(creator.getThreadNamePrefix(), evidence));
            }
        }
        prefixes = List.copyOf(table);
    }

    /** {@return the evidence for the executor bean whose prefix is the longest one the thread's name starts with, if any} */
    Optional<String> evidence(String threadName) {
        return prefixes.stream().filter(prefix -> threadName.startsWith(prefix.prefix()))
                .max(Comparator.comparingInt(prefix -> prefix.prefix().length()))
                .map(Prefix::evidence);
    }
}
