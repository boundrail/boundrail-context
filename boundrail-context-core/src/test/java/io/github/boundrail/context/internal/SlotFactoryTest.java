package io.github.boundrail.context.internal;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.ContextRuntime.Backend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SlotFactoryTest {

    @Test
    @DisplayName("RT-02 the base SlotFactory creates the ThreadLocal slot whether or not the override forces it")
    void theBaseSlotFactoryCreatesTheThreadLocalSlotWhetherOrNotTheOverrideForcesIt() {
        assertThat(SlotFactory.create(true).backend()).isEqualTo(Backend.THREAD_LOCAL);
        assertThat(SlotFactory.create(false).backend()).isEqualTo(Backend.THREAD_LOCAL);
    }
}
