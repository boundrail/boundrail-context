package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.ContextRuntime.CaptureSites;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CaptureSiteRecorderTest {

    private static final ContextKey<String> TENANT = ContextKey.of("acme.tenant", String.class);

    private final AtomicInteger walks = new AtomicInteger();
    private Supplier<String> savedWalker;
    private CaptureSites savedMode;
    private double savedRate;

    @BeforeEach
    void countWalks() {
        savedMode = ContextRuntime.captureSites();
        savedRate = ContextRuntime.sampleRate();
        savedWalker = CaptureSiteRecorder.walker;
        CaptureSiteRecorder.walker = () -> {
            walks.incrementAndGet();
            return "counted";
        };
    }

    @AfterEach
    void restore() {
        CaptureSiteRecorder.walker = savedWalker;
        ContextRuntime.captureSites(savedMode, savedRate);
    }

    @Test
    @DisplayName("SC-003 with capture sites off no stack walking happens, and with on one walk per binding")
    void withCaptureSitesOffNoStackWalkingHappensAndWithOnOneWalkPerBinding() {
        ContextRuntime.captureSites(CaptureSites.OFF, savedRate);
        Context.with(TENANT, "t1").run(() -> { });

        assertThat(walks).hasValue(0);

        ContextRuntime.captureSites(CaptureSites.ON, savedRate);
        ContextSnapshot recorded = Context.with(TENANT, "t1").call(Context::capture);

        assertThat(walks).hasValue(1);
        assertThat(recorded.captureSite()).contains("counted");
    }
}
