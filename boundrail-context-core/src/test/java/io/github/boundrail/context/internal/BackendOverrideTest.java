package io.github.boundrail.context.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.ResourceBundle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class BackendOverrideTest {

    /** A logger that records the level of every message it receives. */
    private static final class RecordingLogger implements System.Logger {

        final List<Level> levels = new ArrayList<>();

        @Override
        public String getName() {
            return "recording";
        }

        @Override
        public boolean isLoggable(Level level) {
            return true;
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String msg, Throwable thrown) {
            levels.add(level);
        }

        @Override
        public void log(Level level, ResourceBundle bundle, String format, Object... params) {
            levels.add(level);
        }
    }

    private final RecordingLogger logger = new RecordingLogger();

    @Test
    @DisplayName("RT-04 thread-local forces the ThreadLocal backend")
    void threadLocalForcesTheThreadLocalBackend() {
        assertThat(BackendOverride.forcesThreadLocal("thread-local", logger)).isTrue();
        assertThat(logger.levels).isEmpty();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = "auto")
    @DisplayName("RT-04 auto and unset select automatically")
    void autoAndUnsetSelectAutomatically(String value) {
        assertThat(BackendOverride.forcesThreadLocal(value, logger)).isFalse();
        assertThat(logger.levels).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"THREAD-LOCAL", "Thread-Local", "threadlocal", ""})
    @DisplayName("RT-04 an unrecognised value logs a warning and falls back to automatic selection")
    void anUnrecognisedValueLogsAWarningAndFallsBackToAutomaticSelection(String value) {
        assertThat(BackendOverride.forcesThreadLocal(value, logger)).isFalse();
        assertThat(logger.levels).containsExactly(Level.WARNING);
    }
}
