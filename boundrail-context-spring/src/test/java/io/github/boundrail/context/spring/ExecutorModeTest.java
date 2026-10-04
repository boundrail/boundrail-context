package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.DETACHED;
import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.BoundExecutors;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class ExecutorModeTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    private static final ContextKey<String> AUDIT = ContextKey.of("audit", String.class);
    private static final String MODES = "boundrail.context.executors.modes";

    /** {@code tenant} propagates to both executor modes, {@code audit} only to {@code ATTACHED}. */
    private static final ContextPolicy POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.propagateTo(ATTACHED, DETACHED))
            .key(AUDIT, rules -> rules.propagateTo(ATTACHED))
            .build();

    private static final String ATTACHED_SEES = "tenant=acme audit=a1";
    private static final String DETACHED_SEES = "tenant=acme audit=-";
    private static final String NOTHING = "tenant=- audit=-";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            .withBean(ContextPolicy.class, () -> POLICY);

    /** The bound tenant and audit values, or "-". */
    private static String seen() {
        return "tenant=" + TENANT.find().orElse("-") + " audit=" + AUDIT.find().orElse("-");
    }

    /** Submits a task from inside {@code tenant=acme, audit=a1} and returns what it saw. */
    private static String submittedFromBinding(ExecutorService executor) throws Exception {
        return Context.with(TENANT, "acme").with(AUDIT, "a1").call(() -> executor.submit(ExecutorModeTest::seen)).get(5, TimeUnit.SECONDS);
    }

    /** Submits a task from inside {@code tenant=acme, audit=a1} to the named {@code ThreadPoolTaskExecutor} and returns what it saw. */
    private static String submittedFromBinding(ThreadPoolTaskExecutor executor) throws Exception {
        return Context.with(TENANT, "acme").with(AUDIT, "a1").call(() -> executor.submit(ExecutorModeTest::seen)).get(5, TimeUnit.SECONDS);
    }

    /** Adds the properties as the first property source, so values keep their spaces and may be empty. */
    private static ApplicationContextRunner withProperties(ApplicationContextRunner runner, Map<String, Object> properties) {
        return runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new MapPropertySource("test", properties)));
    }

    /** Lazy initialization as {@code SpringApplication} sets it up for {@code spring.main.lazy-initialization=true}. */
    private static ApplicationContextRunner lazy(ApplicationContextRunner runner) {
        return runner.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
    }

    @Configuration(proxyBeanMethods = false)
    static class TwoPools {

        @Bean
        ThreadPoolTaskExecutor attachedPool() {
            return new ThreadPoolTaskExecutor();
        }

        @Bean
        ThreadPoolTaskExecutor otherPool() {
            return new ThreadPoolTaskExecutor();
        }
    }

    @Test
    @DisplayName("EXE-01 executors.modes.<bean-name>=attached makes that executor attached, and others stay detached")
    void attachedModeMakesThatExecutorAttachedAndOthersStayDetached() {
        runner.withUserConfiguration(TwoPools.class).withPropertyValues(MODES + ".attachedPool=attached").run(context -> {
            assertThat(submittedFromBinding(context.getBean("attachedPool", ThreadPoolTaskExecutor.class))).isEqualTo(ATTACHED_SEES);
            assertThat(submittedFromBinding(context.getBean("otherPool", ThreadPoolTaskExecutor.class))).isEqualTo(DETACHED_SEES);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RawExecutor {

        @Bean
        ThreadPoolTaskExecutor rawExecutor() {
            return new ThreadPoolTaskExecutor();
        }
    }

    /** What a task on {@code rawExecutor} saw, with the given properties. */
    private String seenOnRawExecutor(ApplicationContextRunner configured) {
        StringBuilder seen = new StringBuilder();
        configured.withUserConfiguration(RawExecutor.class).run(context -> {
            assertThat(context).hasNotFailed();
            seen.append(submittedFromBinding(context.getBean("rawExecutor", ThreadPoolTaskExecutor.class)));
        });
        return seen.toString();
    }

    @Test
    @DisplayName("EXE-01 modes are matched with relaxed enum binding and a blank value means detached")
    void modesAreMatchedWithRelaxedEnumBindingAndABlankValueMeansDetached() {
        Map<String, String> seen = new LinkedHashMap<>();
        for (String mode : List.of("Attached", "ATTACHED", " attached ", "")) {
            seen.put("'" + mode + "'", seenOnRawExecutor(withProperties(runner, Map.of(MODES + ".rawExecutor", mode))));
        }

        assertThat(seen).containsExactly(
                Map.entry("'Attached'", ATTACHED_SEES),
                Map.entry("'ATTACHED'", ATTACHED_SEES),
                Map.entry("' attached '", ATTACHED_SEES),
                Map.entry("''", DETACHED_SEES));
    }

    @ParameterizedTest(name = "lazy initialization: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("EXE-01 an invalid mode fails startup, also with lazy initialization")
    void anInvalidModeFailsStartup(boolean lazy) {
        ApplicationContextRunner configured = runner.withPropertyValues(MODES + ".rawExecutor=bogus");
        if (lazy) {
            configured = lazy(configured);
        }

        configured.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class).hasMessageContaining("bogus");
        });
    }

    /** An {@code @Async} method that returns what it saw. */
    static class AsyncWork {

        @Async("attachedExecutor")
        CompletableFuture<String> work() {
            return CompletableFuture.completedFuture(seen());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAsync
    static class AsyncOnAttachedExecutor {

        @Bean
        ThreadPoolTaskExecutor attachedExecutor() {
            return new ThreadPoolTaskExecutor();
        }

        @Bean
        AsyncWork asyncWork() {
            return new AsyncWork();
        }
    }

    @Test
    @DisplayName("EXE-05 an @Async method on an attached executor runs with the attached context")
    void anAsyncMethodOnAnAttachedExecutorRunsWithTheAttachedContext() {
        runner.withUserConfiguration(AsyncOnAttachedExecutor.class).withPropertyValues(MODES + ".attachedExecutor=attached").run(context -> {
            AsyncWork work = context.getBean(AsyncWork.class);

            String seen = Context.with(TENANT, "acme").with(AUDIT, "a1").call(work::work).get(5, TimeUnit.SECONDS);

            assertThat(seen).isEqualTo(ATTACHED_SEES);
        });
    }

    @Test
    @DisplayName("EXE-01 the bean name is matched ignoring case")
    void theBeanNameIsMatchedIgnoringCase() {
        String fromLowercaseKey = seenOnRawExecutor(withProperties(runner, Map.of(MODES + ".rawexecutor", "attached")));
        String fromEnvironmentVariable = seenOnRawExecutor(runner.withInitializer(context -> context.getEnvironment().getPropertySources()
                .addFirst(new SystemEnvironmentPropertySource("test-systemEnvironment",
                        Map.of("BOUNDRAIL_CONTEXT_EXECUTORS_MODES_RAWEXECUTOR", "attached")))));

        assertThat(fromLowercaseKey).as("executors.modes.rawexecutor").isEqualTo(ATTACHED_SEES);
        assertThat(fromEnvironmentVariable).as("BOUNDRAIL_CONTEXT_EXECUTORS_MODES_RAWEXECUTOR").isEqualTo(ATTACHED_SEES);
    }

    @Configuration(proxyBeanMethods = false)
    static class DecoratedBoundAndPlain {

        final ExecutorService plainInstance = Executors.newSingleThreadExecutor();

        @Bean
        ThreadPoolTaskExecutor decorated() {
            return new ThreadPoolTaskExecutor();
        }

        @Bean
        ExecutorService bound() {
            return BoundExecutors.detached(Executors.newSingleThreadExecutor(), POLICY);
        }

        @Bean
        ExecutorService plain() {
            return plainInstance;
        }
    }

    @Test
    @DisplayName("EXE-01 executors.modes applies to decorated beans only")
    void executorsModesAppliesToDecoratedBeansOnly() {
        runner.withUserConfiguration(DecoratedBoundAndPlain.class)
                .withPropertyValues(MODES + ".decorated=attached", MODES + ".bound=attached", MODES + ".plain=attached")
                .run(context -> {
                    ExecutorService plain = context.getBean("plain", ExecutorService.class);

                    assertThat(submittedFromBinding(context.getBean("decorated", ThreadPoolTaskExecutor.class))).as("decorated")
                            .isEqualTo(ATTACHED_SEES);
                    assertThat(submittedFromBinding(context.getBean("bound", ExecutorService.class))).as("BoundExecutors.detached")
                            .isEqualTo(DETACHED_SEES);
                    assertThat(plain).as("plain").isSameAs(context.getBean(DecoratedBoundAndPlain.class).plainInstance);
                    assertThat(submittedFromBinding(plain)).as("plain").isEqualTo(NOTHING);
                });
    }

    @ParameterizedTest(name = "lazy initialization: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("EXE-01 two modes keys equal ignoring case with different modes fail startup, also with lazy initialization, and the same mode under both is accepted")
    void twoModesKeysEqualIgnoringCaseWithDifferentModesFailStartup(boolean lazy) {
        ApplicationContextRunner conflicting = withProperties(runner.withUserConfiguration(RawExecutor.class),
                Map.of(MODES + "[rawExecutor]", "attached", MODES + "[RAWEXECUTOR]", "detached"));
        ApplicationContextRunner agreeing = withProperties(runner,
                Map.of(MODES + "[rawExecutor]", "attached", MODES + "[RAWEXECUTOR]", "Attached"));
        if (lazy) {
            conflicting = lazy(conflicting);
            agreeing = lazy(agreeing);
        }

        conflicting.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("rawExecutor").hasMessageContaining("RAWEXECUTOR");
        });
        assertThat(seenOnRawExecutor(agreeing)).as("the same mode under both keys").isEqualTo(ATTACHED_SEES);
    }

    /** One executor instance registered under two bean names. */
    @Configuration(proxyBeanMethods = false)
    static class OneInstanceTwoNames {

        final ThreadPoolTaskExecutor shared = new ThreadPoolTaskExecutor();

        @Bean
        ThreadPoolTaskExecutor firstName() {
            return shared;
        }

        @Bean
        ThreadPoolTaskExecutor secondName() {
            return shared;
        }
    }

    @Test
    @DisplayName("EXE-01 one executor instance decorated under two bean names with different modes fails startup naming both beans, and the same mode under both is accepted")
    void oneExecutorInstanceUnderTwoBeanNamesWithDifferentModesFailsStartup() {
        ApplicationContextRunner twoNames = runner.withUserConfiguration(OneInstanceTwoNames.class);

        twoNames.withPropertyValues(MODES + ".firstName=attached", MODES + ".secondName=detached").run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("firstName").hasMessageContaining("secondName");
        });
        twoNames.withPropertyValues(MODES + ".firstName=attached", MODES + ".secondName=attached").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(submittedFromBinding(context.getBean("secondName", ThreadPoolTaskExecutor.class))).isEqualTo(ATTACHED_SEES);
        });
    }
}
