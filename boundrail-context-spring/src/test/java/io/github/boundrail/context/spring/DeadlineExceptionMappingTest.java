package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.DeadlineExceededException;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** An escaping {@code DeadlineExceededException} is mapped to {@code expired-status} by a lowest-precedence resolver (DL-08). */
class DeadlineExceptionMappingTest {

    /** The request attribute under which the test filter stores an exception that escaped the {@code DispatcherServlet}. */
    private static final String ESCAPED = DeadlineExceptionMappingTest.class.getName() + ".ESCAPED";

    /** Throws an expired deadline's exception, stage {@code APPLICATION}. */
    private static String expire() {
        Deadline.after(Duration.ZERO).check();
        throw new AssertionError("the deadline has expired");
    }

    @RestController
    static final class Handlers {

        @GetMapping("/expired")
        String expired() {
            return expire();
        }

        @GetMapping("/wrapped")
        String wrapped() {
            try {
                return expire();
            } catch (RuntimeException e) {
                throw new IllegalStateException("wrapped", e);
            }
        }

        @GetMapping("/committed")
        String committed(HttpServletResponse response) throws IOException {
            response.setStatus(200);
            response.getWriter().write("partial");
            response.flushBuffer();
            return expire();
        }
    }

    /** A controller covered by the application's advice. */
    @RestController
    static final class Advised {

        @GetMapping("/advised")
        String advised() {
            return expire();
        }
    }

    /** The application's handler for the exception, for {@link Advised} only. */
    @RestControllerAdvice(assignableTypes = Advised.class)
    static final class ApplicationAdvice {

        @ExceptionHandler(DeadlineExceededException.class)
        @ResponseStatus(HttpStatus.I_AM_A_TEAPOT)
        String handled() {
            return "application";
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableWebMvc
    static class Mvc {

        @Bean
        Handlers handlers() {
            return new Handlers();
        }

        @Bean
        Advised advised() {
            return new Advised();
        }

        @Bean
        ApplicationAdvice applicationAdvice() {
            return new ApplicationAdvice();
        }
    }

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            .withUserConfiguration(Mvc.class);

    /** A MockMvc whose outermost filter stores an exception that escapes the servlet, so the response can still be inspected. */
    private static MockMvc mockMvc(AssertableWebApplicationContext context) {
        assertThat(context).hasNotFailed();
        Filter catching = (request, response, chain) -> {
            try {
                chain.doFilter(request, response);
            } catch (Exception e) {
                request.setAttribute(ESCAPED, e);
            }
        };
        return MockMvcBuilders.webAppContextSetup(context.getSourceApplicationContext()).addFilters(catching).build();
    }

    private static MvcResult perform(AssertableWebApplicationContext context, String path) throws Exception {
        return mockMvc(context).perform(get(path)).andReturn();
    }

    @Test
    @DisplayName("DL-08 a DeadlineExceededException escaping a handler gets expired-status through sendError")
    void aDeadlineExceededExceptionEscapingAHandlerGetsExpiredStatusThroughSendError() {
        runner.run(context -> {
            MvcResult result = perform(context, "/expired");

            assertThat(result.getResponse().getStatus()).isEqualTo(504);
            assertThat(result.getResponse().isCommitted()).as("sendError commits the response").isTrue();
            assertThat(result.getRequest().getAttribute(ESCAPED)).as("mapped, not escaped").isNull();
        });
        runner.withPropertyValues("boundrail.context.deadline.expired-status=503").run(context ->
                assertThat(perform(context, "/expired").getResponse().getStatus()).isEqualTo(503));
        runner.withPropertyValues("boundrail.context.deadline.map-exception=false").run(context -> {
            MvcResult result = perform(context, "/expired");

            assertThat(result.getRequest().getAttribute(ESCAPED)).as("not mapped: it propagates").isNotNull();
            assertThat(result.getResponse().getStatus()).isNotEqualTo(504);
        });
    }

    @Test
    @DisplayName("DL-08 an application @ExceptionHandler for the exception wins")
    void anApplicationExceptionHandlerForTheExceptionWins() {
        runner.run(context -> {
            assertThat(perform(context, "/advised").getResponse().getStatus()).isEqualTo(418);
            assertThat(perform(context, "/expired").getResponse().getStatus()).as("a controller the advice does not cover").isEqualTo(504);
        });
    }

    @Test
    @DisplayName("DL-08 only the exception itself is mapped")
    void onlyTheExceptionItselfIsMapped() {
        runner.run(context -> {
            MvcResult wrapped = perform(context, "/wrapped");

            assertThat(wrapped.getResponse().getStatus()).isNotEqualTo(504);
            assertThat(wrapped.getRequest().getAttribute(ESCAPED)).as("the wrapping exception propagates").isNotNull();
            assertThat(perform(context, "/expired").getResponse().getStatus()).as("the bare exception").isEqualTo(504);
        });
    }

    @Test
    @DisplayName("DL-08 nothing is mapped once the response is committed")
    void nothingIsMappedOnceTheResponseIsCommitted() {
        runner.run(context -> {
            MvcResult committed = perform(context, "/committed");

            assertThat(committed.getResponse().getStatus()).as("keeps its status").isEqualTo(200);
            assertThat(committed.getResponse().getContentAsString()).isEqualTo("partial");
            assertThat(perform(context, "/expired").getResponse().getStatus()).as("an uncommitted response").isEqualTo(504);
        });
    }

    @Test
    @DisplayName("DL-08 the resolver runs after the application's resolvers and is absent without Spring MVC")
    void theResolverRunsAfterTheApplicationsResolversAndIsAbsentWithoutSpringMvc() {
        runner.run(context -> {
            HandlerExceptionResolver mvcs = (request, response, handler, ex) -> null;
            List<HandlerExceptionResolver> chain = new ArrayList<>(List.of(mvcs));

            context.getBeansOfType(WebMvcConfigurer.class).values().forEach(configurer -> configurer.extendHandlerExceptionResolvers(chain));

            assertThat(chain).as("appended after the resolvers MVC configures").hasSize(2).first().isSameAs(mvcs);
            assertThat(chain.getLast()).isInstanceOf(DeadlineExceptionResolver.class);
            assertThat(context.getBeansOfType(DeadlineExceptionResolver.class)).as("not a bean").isEmpty();
        });
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader(WebMvcConfigurer.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBeanNamesForType(WebMvcConfigurer.class)).isEmpty();
                    assertThat(context.getBeanNamesForType(HandlerExceptionResolver.class)).isEmpty();
                });
    }

    /** An application exception with its own handler. */
    static final class ApplicationException extends RuntimeException {
    }

    @RestController
    static final class ApplicationHandlers {

        @GetMapping("/application")
        String application() {
            throw new ApplicationException();
        }
    }

    @RestControllerAdvice(assignableTypes = ApplicationHandlers.class)
    static final class ApplicationExceptionAdvice {

        @ExceptionHandler(ApplicationException.class)
        @ResponseStatus(HttpStatus.I_AM_A_TEAPOT)
        String handled() {
            return "application";
        }
    }

    /** Controllers and advice only: no {@code @EnableWebMvc}, so {@code DispatcherServlet} installs its default strategies. */
    @Configuration(proxyBeanMethods = false)
    static class PlainDispatcherServlet {

        @Bean
        ApplicationHandlers applicationHandlers() {
            return new ApplicationHandlers();
        }

        @Bean
        ApplicationExceptionAdvice applicationExceptionAdvice() {
            return new ApplicationExceptionAdvice();
        }
    }

    @Test
    @DisplayName("DL-08 the resolver is added to MVC's resolver chain, not as a HandlerExceptionResolver bean")
    void theResolverIsAddedToMvcsResolverChainNotAsABean() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
                .withUserConfiguration(PlainDispatcherServlet.class)
                .run(context -> assertThat(perform(context, "/application").getResponse().getStatus())
                        .as("DispatcherServlet's default resolvers still run the application's @ExceptionHandler").isEqualTo(418));
        runner.withUserConfiguration(PlainDispatcherServlet.class).run(context -> {
            assertThat(perform(context, "/application").getResponse().getStatus()).as("with @EnableWebMvc").isEqualTo(418);
            assertThat(perform(context, "/expired").getResponse().getStatus()).as("an unhandled DeadlineExceededException").isEqualTo(504);
        });
    }
}
