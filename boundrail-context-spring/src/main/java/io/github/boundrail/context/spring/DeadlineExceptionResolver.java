package io.github.boundrail.context.spring;

import io.github.boundrail.context.DeadlineExceededException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.core.Ordered;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

/**
 * Maps a {@code DeadlineExceededException} escaping a request handler to {@code expired-status} through {@code sendError}, as Spring does for
 * {@code ResponseStatusException} (DL-08). Ordered last, so the application's {@code @ExceptionHandler} methods run first.
 */
final class DeadlineExceptionResolver implements HandlerExceptionResolver, Ordered {

    private final boolean mapException;
    private final int expiredStatus;

    DeadlineExceptionResolver(BoundrailContextProperties.Deadline deadline) {
        this.mapException = deadline.mapException();
        this.expiredStatus = deadline.expiredStatus();
    }

    /**
     * Calls {@code sendError(expired-status)} and returns an empty {@code ModelAndView} when {@code map-exception} is on, the exception
     * itself is a {@code DeadlineExceededException}, not one found as a cause, and the response is not committed; otherwise returns
     * {@code null}, so the exception propagates.
     */
    @Override
    public ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        if (!mapException || !(ex instanceof DeadlineExceededException) || response.isCommitted()) {
            return null;
        }
        try {
            response.sendError(expiredStatus);
        } catch (IOException e) {
            return null;
        }
        return new ModelAndView();
    }

    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
