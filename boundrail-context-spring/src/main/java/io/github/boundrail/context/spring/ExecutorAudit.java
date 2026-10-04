package io.github.boundrail.context.spring;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.commons.logging.Log;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.ApplicationListener;

/**
 * The startup executor audit (DIAG-06): when its application is ready, logs every executor bean of the {@link ExecutorTable} in ascending order
 * of bean name, as {@code COVERED} with its mode at INFO or as a {@code GAP} at WARN, then a summary at INFO, then a WARN entry for each
 * {@code executors.modes} key that matches no decorated executor. With {@code fail-on-gap} and a gap, it throws, which fails startup.
 */
final class ExecutorAudit implements ApplicationListener<ApplicationReadyEvent>, ApplicationContextAware {

    private final ExecutorTable table;
    private final Set<String> modeKeys;
    private final boolean failOnGap;
    private final Log log;
    private ApplicationContext context;

    /**
     * Creates an audit.
     *
     * @param table the context's executor table
     * @param modeKeys the keys of {@code boundrail.context.executors.modes}
     * @param failOnGap whether a gap fails startup
     * @param log where the entries are logged
     */
    ExecutorAudit(ExecutorTable table, Set<String> modeKeys, boolean failOnGap, Log log) {
        this.table = table;
        this.modeKeys = modeKeys;
        this.failOnGap = failOnGap;
        this.log = log;
    }

    @Override
    public void setApplicationContext(ApplicationContext context) {
        this.context = context;
    }

    /**
     * Audits the executor beans.
     *
     * @throws IllegalStateException with {@code fail-on-gap} and at least one gap, listing every gap
     */
    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (event.getApplicationContext() != context) {
            return;
        }
        List<ExecutorTable.Entry> entries = table.entries();
        for (ExecutorTable.Entry entry : entries) {
            entry.mode().ifPresentOrElse(
                    mode -> log.info("Executor audit: " + entry.bean() + " COVERED (" + mode.name().toLowerCase(Locale.ROOT) + ")"),
                    () -> log.warn("Executor audit: " + entry.bean() + " GAP"));
        }
        List<String> gaps = entries.stream().filter(entry -> entry.mode().isEmpty()).map(ExecutorTable.Entry::bean).toList();
        log.info("Executor audit: " + (entries.size() - gaps.size()) + " covered, " + gaps.size() + " gaps");
        Set<String> decorated = table.decorated();
        modeKeys.stream().sorted()
                .filter(key -> decorated.stream().noneMatch(key::equalsIgnoreCase))
                .forEach(key -> log.warn("Executor audit: executors.modes." + key + " matches no decorated executor"));
        if (failOnGap && !gaps.isEmpty()) {
            throw new IllegalStateException("Executor audit found executor beans whose tasks do not get the submitter's context, and "
                    + "boundrail.context.audit.fail-on-gap is true: " + String.join(", ", gaps));
        }
    }
}
