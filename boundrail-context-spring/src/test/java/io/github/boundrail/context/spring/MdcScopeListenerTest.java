package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.Initiator;
import java.time.Duration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** Loggable keys are mirrored into SLF4J's MDC for the duration of each scope (LOG-01, LOG-02). The runner closes each context. */
class MdcScopeListenerTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<Long> PRIORITY = ContextKey.of("priority", Long.class);
    private static final ContextKey<Locale> LANGUAGE = ContextKey.of("language", Locale.class);

    private static final ContextPolicy POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.codec(Codecs.string()).loggable())
            .key(PRIORITY, rules -> rules.codec(Codecs.longValue()).loggable())
            .key(LANGUAGE, rules -> rules.codec(Codecs.locale()).loggable())
            .build();

    /** The MDC names the tests use, and the built-in key names that must never appear. */
    private static final List<String> NAMES = List.of("tenant", "priority", "language", "boundrail.initiator", "boundrail.authority",
            "boundrail.deadline");

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            .withBean(ContextPolicy.class, () -> POLICY);

    @AfterEach
    void removeTestEntries() {
        NAMES.forEach(MDC::remove);
    }

    /** The MDC values of {@link #NAMES} that are present. */
    private static Map<String, String> mdc() {
        Map<String, String> present = new LinkedHashMap<>();
        NAMES.forEach(name -> {
            if (MDC.get(name) != null) {
                present.put(name, MDC.get(name));
            }
        });
        return present;
    }

    @Test
    @DisplayName("LOG-01 a bound loggable key is in the MDC for the scope, encoded with its codec, and the previous value is restored")
    void aBoundLoggableKeyIsInTheMdcForTheScopeAndThePreviousValueIsRestored() {
        Map<String, String> inside = new HashMap<>();
        Map<String, String> after = new HashMap<>();

        runner.run(context -> {
            MDC.put("tenant", "outer");
            Context.with(TENANT, "acme").with(PRIORITY, 5L).with(LANGUAGE, Locale.UK).run(() -> inside.putAll(mdc()));
            after.putAll(mdc());
        });

        assertThat(inside).as("inside the scope")
                .containsExactlyInAnyOrderEntriesOf(Map.of("tenant", "acme", "priority", "5", "language", "en-GB"));
        assertThat(after).as("after the scope").containsExactlyInAnyOrderEntriesOf(Map.of("tenant", "outer"));
    }

    @Test
    @DisplayName("LOG-01 a loggable key without a codec is written with toString()")
    void aLoggableKeyWithoutACodecIsWrittenWithToString() {
        Map<String, String> inside = new HashMap<>();

        runner.run(context -> Context.with(BuiltInKeys.INITIATOR, Initiator.of("user:alice")).run(() -> inside.putAll(mdc())));

        assertThat(inside).containsExactlyInAnyOrderEntriesOf(Map.of("boundrail.initiator", "user:alice"));
    }

    @Test
    @DisplayName("LOG-02 AUTHORITY and DEADLINE are never written to the MDC while loggable keys are")
    void authorityAndDeadlineAreNeverWrittenToTheMdc() {
        Map<String, String> inside = new HashMap<>();

        runner.run(context -> Context.with(TENANT, "acme")
                .with(BuiltInKeys.AUTHORITY, Authority.of("alice", "secret-token"))
                .with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofMinutes(1)))
                .run(() -> inside.putAll(mdc())));

        assertThat(inside).containsExactlyInAnyOrderEntriesOf(Map.of("tenant", "acme"));
    }

    @Test
    @DisplayName("LOG-01 without SLF4J no MDC listener is registered")
    void withoutSlf4jNoMdcListenerIsRegistered() {
        runner.withClassLoader(new FilteredClassLoader(MDC.class)).run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(MdcScopeListener.class);
        });
    }
}
