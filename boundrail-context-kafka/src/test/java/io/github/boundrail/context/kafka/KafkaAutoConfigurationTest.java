package io.github.boundrail.context.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.spring.BoundrailContextAutoConfiguration;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.annotation.ImportCandidates;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaTemplate;

/** The Kafka auto-configuration, its activation and the {@code boundrail.context.kafka.*} properties (KAF-04, KAF-05). */
class KafkaAutoConfigurationTest {

    private static final String TRUST_HEADERS = "boundrail.context.kafka.trust-headers";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class, BoundrailKafkaAutoConfiguration.class));

    /** Lazy initialization as {@code SpringApplication} sets it up for {@code spring.main.lazy-initialization=true}. */
    private static ApplicationContextRunner lazy(ApplicationContextRunner runner) {
        return runner.withPropertyValues("spring.main.lazy-initialization=true")
                .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
    }

    @Test
    @DisplayName("KAF-05 the Kafka auto-configuration provides one KafkaContext bean, and KafkaContext has no public constructor")
    void providesOneKafkaContextBean() {
        runner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(KafkaContext.class);
        });
        assertThat(Arrays.stream(KafkaContext.class.getDeclaredConstructors()).filter(c -> Modifier.isPublic(c.getModifiers())))
                .as("public constructors of KafkaContext")
                .isEmpty();
    }

    @Test
    @DisplayName("KAF-05 the Kafka auto-configuration is registered in AutoConfiguration.imports and ordered after the Spring module's and"
            + " Spring Boot's Kafka auto-configuration")
    void isRegisteredAndOrdered() {
        ImportCandidates candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader());
        AutoConfiguration annotation = BoundrailKafkaAutoConfiguration.class.getAnnotation(AutoConfiguration.class);

        assertThat(candidates.getCandidates()).contains(BoundrailKafkaAutoConfiguration.class.getName());
        assertThat(annotation).as("@AutoConfiguration").isNotNull();
        assertThat(annotation.after()).contains(BoundrailContextAutoConfiguration.class);
        assertThat(annotation.afterName()).contains("org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration");
    }

    @Test
    @DisplayName("KAF-04 with Spring Kafka and boundrail-context-spring on the class path the Kafka auto-configuration is active, and no"
            + " property turns it off")
    void isActiveAndHasNoOffSwitch() {
        runner.run(context -> assertThat(context).as("by default").hasSingleBean(KafkaContext.class));
        runner.withPropertyValues("boundrail.context.kafka.enabled=false")
                .run(context -> assertThat(context).as("kafka.enabled=false").hasSingleBean(KafkaContext.class));
    }

    @Test
    @DisplayName("KAF-04 without Spring Kafka no Kafka bean is created and the context starts")
    void backsOffWithoutSpringKafkaOrTheSpringModule() {
        runner.run(context -> assertThat(context).as("control: default context").hasSingleBean(KafkaContext.class));

        runner.withClassLoader(new FilteredClassLoader(KafkaTemplate.class)).run(context -> {
            assertThat(context).as("without Spring Kafka").hasNotFailed();
            assertThat(context).doesNotHaveBean(KafkaContext.class).doesNotHaveBean(BoundrailKafkaProperties.class);
        });

        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(BoundrailKafkaAutoConfiguration.class)).run(context -> {
            assertThat(context).as("without the Spring module's auto-configuration").hasNotFailed();
            assertThat(context).doesNotHaveBean(KafkaContext.class).doesNotHaveBean(BoundrailKafkaProperties.class);
        });
    }

    static Stream<Arguments> trustHeadersValues() {
        return Stream.of(
                Arguments.of(null, true),
                Arguments.of("true", true), Arguments.of("on", true), Arguments.of("YES", true), Arguments.of("1", true),
                Arguments.of("false", false), Arguments.of("Off", false), Arguments.of("no", false), Arguments.of("0", false));
    }

    @ParameterizedTest(name = "trust-headers={0} -> {1}")
    @MethodSource("trustHeadersValues")
    @DisplayName("KAF-04 kafka.trust-headers defaults to true and is bound with Boot's boolean binding")
    void trustHeadersIsBoundAsBootBoolean(String value, boolean expected) {
        ApplicationContextRunner configured = value == null ? runner : runner.withPropertyValues(TRUST_HEADERS + "=" + value);
        configured.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(BoundrailKafkaProperties.class).trustHeaders()).isEqualTo(expected);
        });
    }

    static Stream<Arguments> invalidTrustHeaders() {
        return Stream.of(Arguments.of("maybe", false), Arguments.of("maybe", true), Arguments.of("2", false), Arguments.of("2", true));
    }

    @ParameterizedTest(name = "trust-headers={0}, lazy initialization: {1}")
    @MethodSource("invalidTrustHeaders")
    @DisplayName("KAF-04 a kafka.trust-headers that Boot cannot convert to a boolean fails startup, also with lazy initialization")
    void anUnconvertibleTrustHeadersFailsStartup(String value, boolean lazy) {
        ApplicationContextRunner configured = runner.withPropertyValues(TRUST_HEADERS + "=" + value);
        (lazy ? lazy(configured) : configured).run(context -> {
            assertThat(context).hasFailed();
            List<String> boundNames = new ArrayList<>();
            for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
                if (cause instanceof BindException bind) {
                    boundNames.add(bind.getName().toString());
                }
            }
            assertThat(boundNames).contains(TRUST_HEADERS);
        });
    }
}
