package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.ContextRuntime.Backend;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ContextRuntimeTest {

    @Test
    @DisplayName("RT-05 backend reports the backend of the slot the runtime uses")
    void backendReportsTheBackendOfTheSlotTheRuntimeUses() {
        assertThat(ContextRuntime.backend()).isEqualTo(ContextRuntime.SLOT.backend()).isEqualTo(Backend.THREAD_LOCAL);
    }
}
