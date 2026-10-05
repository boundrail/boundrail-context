package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextCodec;
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
            "boundrail.deadline", "alpha", "beta", "closed.key", "a.good", "b.bad", "b.error");

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

    @Test
    @DisplayName("LOG-01 a nested Context.without(tenant) removes tenant from the MDC and restores it on exit")
    void aNestedContextWithoutRemovesTenantFromTheMdcAndRestoresItOnExit() {
        Map<String, String> nested = new HashMap<>();
        Map<String, String> afterNested = new HashMap<>();

        runner.run(context -> Context.with(TENANT, "acme").run(() -> {
            Context.without(TENANT).run(() -> nested.putAll(mdc()));
            afterNested.putAll(mdc());
        }));

        assertThat(nested).as("inside Context.without(tenant)").isEmpty();
        assertThat(afterNested).as("back in the outer scope").containsExactlyInAnyOrderEntriesOf(Map.of("tenant", "acme"));
    }

    private static final ContextKey<String> ALPHA = ContextKey.of("alpha", String.class);
    private static final ContextKey<String> BETA = ContextKey.of("beta", String.class);

    @Test
    @DisplayName("LOG-01 each application context's listener writes only its own policy's loggable keys")
    void eachApplicationContextsListenerWritesOnlyItsOwnPolicysLoggableKeys() {
        ApplicationContextRunner base = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));
        Map<String, String> bothOpen = new HashMap<>();
        Map<String, String> alphaClosed = new HashMap<>();

        base.withBean(ContextPolicy.class, () -> ContextPolicy.builder().key(BETA, rules -> rules.loggable()).build()).run(betaContext -> {
            base.withBean(ContextPolicy.class, () -> ContextPolicy.builder().key(ALPHA, rules -> rules.loggable()).build())
                    .run(alphaContext -> Context.with(ALPHA, "a1").with(BETA, "b1").run(() -> bothOpen.putAll(mdc())));
            Context.with(ALPHA, "a1").with(BETA, "b1").run(() -> alphaClosed.putAll(mdc()));
        });

        assertThat(bothOpen).as("both contexts open").containsExactlyInAnyOrderEntriesOf(Map.of("alpha", "a1", "beta", "b1"));
        assertThat(alphaClosed).as("after the alpha context closed").containsExactlyInAnyOrderEntriesOf(Map.of("beta", "b1"));
    }

    private static final ContextKey<String> CLOSED_KEY = ContextKey.of("closed.key", String.class);

    @Test
    @DisplayName("LOG-01 the listener is removed when its context closes")
    void theListenerIsRemovedWhenItsContextCloses() {
        Map<String, String> whileOpen = new HashMap<>();
        Map<String, String> afterClose = new HashMap<>();
        ApplicationContextRunner closedKeyRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
                .withBean(ContextPolicy.class, () -> ContextPolicy.builder().key(CLOSED_KEY, rules -> rules.loggable()).build());

        closedKeyRunner.run(context -> Context.with(CLOSED_KEY, "c1").run(() -> whileOpen.putAll(mdc())));
        Context.with(CLOSED_KEY, "c1").run(() -> afterClose.putAll(mdc()));

        assertThat(whileOpen).as("while the context is open").containsExactlyInAnyOrderEntriesOf(Map.of("closed.key", "c1"));
        assertThat(afterClose).as("after it closed").isEmpty();
    }

    private static final ContextKey<String> GOOD = ContextKey.of("a.good", String.class);
    private static final ContextKey<String> BAD = ContextKey.of("b.bad", String.class);

    /** A codec whose {@code encode} throws. */
    private static final ContextCodec<String> THROWING = new ContextCodec<>() {
        @Override
        public String encode(String value) {
            throw new IllegalStateException("encode failed");
        }

        @Override
        public String decode(String text) {
            return text;
        }
    };

    @Test
    @DisplayName("LOG-01 a codec that throws while entering leaves the MDC as it was")
    void aCodecThatThrowsWhileEnteringLeavesTheMdcAsItWas() {
        Map<String, String> inside = new HashMap<>();
        Map<String, String> after = new HashMap<>();
        boolean[] ran = new boolean[1];

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
                .withBean(ContextPolicy.class, () -> ContextPolicy.builder()
                        .key(GOOD, rules -> rules.codec(Codecs.string()).loggable())
                        .key(BAD, rules -> rules.codec(THROWING).loggable())
                        .build())
                .run(context -> {
                    MDC.put("a.good", "outer");
                    Context.with(GOOD, "g1").with(BAD, "b1").run(() -> {
                        ran[0] = true;
                        inside.putAll(mdc());
                    });
                    after.putAll(mdc());
                });

        assertThat(ran[0]).as("the operation ran (RT-07)").isTrue();
        assertThat(inside).as("inside the scope").containsExactlyInAnyOrderEntriesOf(Map.of("a.good", "outer"));
        assertThat(after).as("after the scope").containsExactlyInAnyOrderEntriesOf(Map.of("a.good", "outer"));
    }

    private static final ContextKey<String> ERROR_KEY = ContextKey.of("b.error", String.class);

    /** An {@code Error} thrown by a codec. */
    private static final class CodecError extends Error {
        CodecError() {
            super("encode failed with an Error");
        }
    }

    private static final CodecError CODEC_ERROR = new CodecError();

    /** A codec whose {@code encode} throws an {@code Error}. */
    private static final ContextCodec<String> THROWING_ERROR = new ContextCodec<>() {
        @Override
        public String encode(String value) {
            throw CODEC_ERROR;
        }

        @Override
        public String decode(String text) {
            return text;
        }
    };

    @Test
    @DisplayName("LOG-01 a codec that throws an Error while entering leaves the MDC as it was, and the Error propagates")
    void aCodecThatThrowsAnErrorWhileEnteringLeavesTheMdcAsItWas() {
        Map<String, String> after = new HashMap<>();
        Throwable[] thrown = new Throwable[1];
        boolean[] ran = new boolean[1];

        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
                .withBean(ContextPolicy.class, () -> ContextPolicy.builder()
                        .key(GOOD, rules -> rules.codec(Codecs.string()).loggable())
                        .key(ERROR_KEY, rules -> rules.codec(THROWING_ERROR).loggable())
                        .build())
                .run(context -> {
                    MDC.put("a.good", "outer");
                    thrown[0] = catchThrowable(() -> Context.with(GOOD, "g1").with(ERROR_KEY, "e1").run(() -> ran[0] = true));
                    after.putAll(mdc());
                });

        assertThat(thrown[0]).as("the Error propagates (RT-07)").isSameAs(CODEC_ERROR);
        assertThat(ran[0]).as("the operation ran").isFalse();
        assertThat(after).as("after the failed entry").containsExactlyInAnyOrderEntriesOf(Map.of("a.good", "outer"));
    }
}
