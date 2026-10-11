package io.github.boundrail.context.spring;

import io.github.boundrail.context.DeadlineExceededException;

/**
 * Counts a deadline expiry where the Spring module detects it (DL-09). With Micrometer, the auto-configuration supplies one that increments
 * {@code boundrail.context.deadline.exceeded} in the context's unique or primary {@code MeterRegistry}; without it, nothing is counted.
 */
@FunctionalInterface
interface DeadlineCounter {

    /** Counts one expiry detected at {@code stage}. */
    void increment(DeadlineExceededException.Stage stage);
}
