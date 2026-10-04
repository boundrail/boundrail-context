package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.internal.ModeExecutor;
import java.util.ArrayList;
import java.util.Arrays;
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
 * The executor beans of one application context, for the executor evidence of violations (DIAG-04) and the startup audit (DIAG-06). Built
 * when the context has refreshed, so a violation reported earlier has no evidence. It covers only singleton executor beans of the context
 * itself that are already instantiated, and not executors produced by a {@code FactoryBean}; it instantiates none (DIAG-12).
 */
final class ExecutorTable implements ApplicationListener<ContextRefreshedEvent>, ApplicationContextAware {

    /**
     * An executor bean and its coverage.
     *
     * @param bean the bean name
     * @param mode the mode if the bean is covered: decorated by the library (EXE-03) or returned by {@code BoundExecutors} (EXE-04); empty
     *     for a gap
     */
    record Entry(String bean, Optional<Boundary> mode) {

        /** {@return {@code <bean> (<status>)}, the status being {@code GAP} or {@code COVERED} and the mode in lowercase} */
        String evidence() {
            return bean + " (" + mode.map(covered -> "COVERED " + covered.name().toLowerCase(Locale.ROOT)).orElse("GAP") + ")";
        }
    }

    /** A thread-name prefix of an entry. */
    private record Prefix(String prefix, Entry entry) {
    }

    private ApplicationContext context;
    private volatile List<Entry> entries = List.of();
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
        String[] beanNames = beanFactory.getBeanNamesForType(Executor.class, false, false);
        Arrays.sort(beanNames);
        List<Entry> table = new ArrayList<>();
        List<Prefix> tablePrefixes = new ArrayList<>();
        for (String beanName : beanNames) {
            // A FactoryBean's singleton is the factory, not the executor it produces, so it is skipped here too.
            if (!beanFactory.containsSingleton(beanName) || !(beanFactory.getSingleton(beanName) instanceof Executor executor)) {
                continue;
            }
            Optional<Boundary> decoratedMode = postProcessors.stream()
                    .flatMap(postProcessor -> postProcessor.decoratedMode(beanName).stream()).findFirst();
            Entry entry = new Entry(beanName, executor instanceof ModeExecutor bound ? Optional.of(bound.mode()) : decoratedMode);
            table.add(entry);
            tablePrefixes.add(new Prefix(beanName, entry));
            if ((executor instanceof ThreadPoolTaskExecutor || executor instanceof SimpleAsyncTaskExecutor
                    || executor instanceof ThreadPoolTaskScheduler) && executor instanceof CustomizableThreadCreator creator
                    && !creator.getThreadNamePrefix().isEmpty()) {
                tablePrefixes.add(new Prefix(creator.getThreadNamePrefix(), entry));
            }
        }
        entries = List.copyOf(table);
        prefixes = List.copyOf(tablePrefixes);
    }

    /** {@return the executor beans, in ascending order of bean name} */
    List<Entry> entries() {
        return entries;
    }

    /**
     * {@return the evidence for the executor bean whose prefix is the longest one the thread's name starts with, if any}; between equally
     * long prefixes, the first bean name in ascending order wins.
     */
    Optional<String> evidence(String threadName) {
        Prefix best = null;
        for (Prefix prefix : prefixes) {
            if (threadName.startsWith(prefix.prefix()) && (best == null || prefix.prefix().length() > best.prefix().length())) {
                best = prefix;
            }
        }
        return Optional.ofNullable(best).map(prefix -> prefix.entry().evidence());
    }
}
