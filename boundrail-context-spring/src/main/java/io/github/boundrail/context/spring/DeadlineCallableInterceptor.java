package io.github.boundrail.context.spring;

import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.DeadlineExceededException;
import io.github.boundrail.context.Deadlines;
import java.util.concurrent.Callable;
import org.springframework.core.Ordered;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.async.CallableProcessingInterceptor;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Refuses a handler's {@code Callable}, or a {@code WebAsyncTask}, whose captured deadline has expired when it would start: {@code preProcess}
 * runs on MVC's executor thread inside the snapshot {@code AttachedAsyncTaskExecutor} entered (EXE-09) and throws, so MVC does not call the
 * task and completes the request with the exception, which DL-08 maps (EXE-08). It only checks: it binds and restores no context (DESIGN
 * D39, D56).
 */
final class DeadlineCallableInterceptor implements CallableProcessingInterceptor {

    /**
     * Throws {@code DeadlineExceededException} with stage {@code EXECUTOR} if the deadline current on MVC's executor thread has expired.
     */
    @Override
    public <T> void preProcess(NativeWebRequest request, Callable<T> task) {
        if (Deadlines.current().filter(Deadline::isExpired).isPresent()) {
            throw new DeadlineExceededException(DeadlineExceededException.Stage.EXECUTOR);
        }
    }

    /**
     * Registers the interceptor through Spring MVC's configuration, ordered last, so the application's own {@code Callable} interceptors run
     * their {@code preProcess} first and still get {@code postProcess} with the exception. A {@code RequestMappingHandlerAdapter} not built
     * by {@code WebMvcConfigurationSupport} does not get it. "Last" is last among equal orders by registration: an application configurer
     * at the same lowest order that is processed after this auto-configuration registers its interceptors after this one.
     */
    static final class Registration implements WebMvcConfigurer, Ordered {

        @Override
        public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
            configurer.registerCallableInterceptors(new DeadlineCallableInterceptor());
        }

        @Override
        public int getOrder() {
            return Ordered.LOWEST_PRECEDENCE;
        }
    }
}
