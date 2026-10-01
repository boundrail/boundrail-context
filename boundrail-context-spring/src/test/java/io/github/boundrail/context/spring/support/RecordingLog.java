package io.github.boundrail.context.spring.support;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.apache.commons.logging.Log;

/** A Commons Logging {@link Log} that records every entry, with every level enabled. */
public final class RecordingLog implements Log {

    /** The level of a recorded entry. */
    public enum Level { TRACE, DEBUG, INFO, WARN, ERROR, FATAL }

    /**
     * One recorded entry.
     *
     * @param level the level it was logged at
     * @param message the message, as its {@code String.valueOf}
     * @param throwable the throwable logged with it, or {@code null}
     */
    public record Entry(Level level, String message, Throwable throwable) {
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();

    /** {@return the entries recorded so far, in order} */
    public List<Entry> entries() {
        return List.copyOf(entries);
    }

    private void record(Level level, Object message, Throwable throwable) {
        entries.add(new Entry(level, String.valueOf(message), throwable));
    }

    @Override
    public boolean isTraceEnabled() {
        return true;
    }

    @Override
    public boolean isDebugEnabled() {
        return true;
    }

    @Override
    public boolean isInfoEnabled() {
        return true;
    }

    @Override
    public boolean isWarnEnabled() {
        return true;
    }

    @Override
    public boolean isErrorEnabled() {
        return true;
    }

    @Override
    public boolean isFatalEnabled() {
        return true;
    }

    @Override
    public void trace(Object message) {
        record(Level.TRACE, message, null);
    }

    @Override
    public void trace(Object message, Throwable t) {
        record(Level.TRACE, message, t);
    }

    @Override
    public void debug(Object message) {
        record(Level.DEBUG, message, null);
    }

    @Override
    public void debug(Object message, Throwable t) {
        record(Level.DEBUG, message, t);
    }

    @Override
    public void info(Object message) {
        record(Level.INFO, message, null);
    }

    @Override
    public void info(Object message, Throwable t) {
        record(Level.INFO, message, t);
    }

    @Override
    public void warn(Object message) {
        record(Level.WARN, message, null);
    }

    @Override
    public void warn(Object message, Throwable t) {
        record(Level.WARN, message, t);
    }

    @Override
    public void error(Object message) {
        record(Level.ERROR, message, null);
    }

    @Override
    public void error(Object message, Throwable t) {
        record(Level.ERROR, message, t);
    }

    @Override
    public void fatal(Object message) {
        record(Level.FATAL, message, null);
    }

    @Override
    public void fatal(Object message, Throwable t) {
        record(Level.FATAL, message, t);
    }
}
