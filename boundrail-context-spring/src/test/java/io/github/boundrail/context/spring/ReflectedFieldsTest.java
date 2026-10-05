package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.core.task.AsyncTaskExecutor;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/** Pins the private Spring fields the Spring module reads (EXE-10), so a Spring upgrade that renames one fails here. */
class ReflectedFieldsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class));

    @ParameterizedTest(name = "{0}")
    @ValueSource(classes = {ThreadPoolTaskExecutor.class, SimpleAsyncTaskExecutor.class, ThreadPoolTaskScheduler.class})
    @DisplayName("EXE-10 the private taskDecorator field of ThreadPoolTaskExecutor, SimpleAsyncTaskExecutor and ThreadPoolTaskScheduler is found by name and type")
    void thePrivateTaskDecoratorFieldIsFoundByNameAndType(Class<?> type) {
        runner.run(context -> {
            Object executor = type.getDeclaredConstructor().newInstance();
            TaskDecorator decorator = task -> task;
            switch (executor) {
                case ThreadPoolTaskExecutor pool -> pool.setTaskDecorator(decorator);
                case SimpleAsyncTaskExecutor simple -> simple.setTaskDecorator(decorator);
                case ThreadPoolTaskScheduler scheduler -> scheduler.setTaskDecorator(decorator);
                default -> throw new AssertionError(type);
            }

            Field field = context.getBean(ExecutorDecoratingPostProcessor.class).taskDecoratorField(type);

            assertThat(field).isNotNull();
            assertThat(field.getName()).isEqualTo("taskDecorator");
            assertThat(field.getType()).isEqualTo(TaskDecorator.class);
            assertThat(field.get(executor)).as("the value set through the public setter").isSameAs(decorator);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RenamedField {

        @Bean
        static ExecutorDecoratingPostProcessor renamedFieldPostProcessor(ObjectProvider<EffectivePolicy> policy, Environment environment) {
            return new ExecutorDecoratingPostProcessor(policy, environment, "renamedTaskDecorator");
        }

        @Bean
        @Lazy(false)
        ThreadPoolTaskExecutor pool() {
            return new ThreadPoolTaskExecutor();
        }
    }

    @ParameterizedTest(name = "lazy initialization: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("EXE-10 a missing field fails startup naming the class and the field, also with lazy initialization")
    void aMissingFieldFailsStartupNamingTheClassAndTheField(boolean lazy) {
        ApplicationContextRunner configured = runner.withUserConfiguration(RenamedField.class);
        if (lazy) {
            configured = configured.withPropertyValues("spring.main.lazy-initialization=true")
                    .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
        }

        configured.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(ThreadPoolTaskExecutor.class.getName())
                    .hasMessageContaining("renamedTaskDecorator");
        });
    }

    @Test
    @DisplayName("EXE-10 the private taskExecutor field of RequestMappingHandlerAdapter is found by name and type")
    void thePrivateTaskExecutorFieldIsFoundByNameAndType() {
        runner.run(context -> {
            RequestMappingHandlerAdapter adapter = new RequestMappingHandlerAdapter();
            AsyncTaskExecutor executor = new SimpleAsyncTaskExecutor();
            adapter.setTaskExecutor(executor);

            Field field = context.getBean(MvcAsyncExecutorPostProcessor.class).taskExecutorField();

            assertThat(field).isNotNull();
            assertThat(field.getName()).isEqualTo("taskExecutor");
            assertThat(field.getType()).isEqualTo(AsyncTaskExecutor.class);
            assertThat(field.get(adapter)).as("the value set through the public setter").isSameAs(executor);
        });
    }

    @Configuration(proxyBeanMethods = false)
    static class RenamedMvcField {

        @Bean
        static MvcAsyncExecutorPostProcessor renamedFieldPostProcessor(ObjectProvider<EffectivePolicy> policy) {
            return new MvcAsyncExecutorPostProcessor(policy, "renamedTaskExecutor");
        }

        @Bean
        @Lazy(false)
        RequestMappingHandlerAdapter adapter() {
            return new RequestMappingHandlerAdapter();
        }
    }

    @ParameterizedTest(name = "lazy initialization: {0}")
    @ValueSource(booleans = {false, true})
    @DisplayName("EXE-10 a missing taskExecutor field fails startup naming the class and the field, also with lazy initialization")
    void aMissingTaskExecutorFieldFailsStartupNamingTheClassAndTheField(boolean lazy) {
        ApplicationContextRunner configured = runner.withUserConfiguration(RenamedMvcField.class);
        if (lazy) {
            configured = configured.withPropertyValues("spring.main.lazy-initialization=true")
                    .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
        }

        configured.run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause()
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(RequestMappingHandlerAdapter.class.getName())
                    .hasMessageContaining("renamedTaskExecutor");
        });
    }
}
