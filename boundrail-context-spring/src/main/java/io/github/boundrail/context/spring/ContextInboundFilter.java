package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextBinding;
import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.Initiator;
import io.github.boundrail.context.Violation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the context headers of a trusted request and runs the rest of the chain inside that scope; reports those of an untrusted request
 * (IN-01, IN-02). On the initial dispatch it stores the current snapshot as a request attribute, which {@link ContextAuthenticationFilter}
 * replaces inside its own scope, and on an {@code ASYNC} dispatch it runs the rest of the chain inside the stored snapshot (IN-09).
 */
final class ContextInboundFilter extends OncePerRequestFilter {

    /**
     * The default of {@code spring.security.filter.order}, Spring Boot's {@code SecurityFilterProperties.DEFAULT_FILTER_ORDER}; the filter is
     * ordered one below that property (IN-01). Copied, because Spring Security is optional.
     */
    static final int SECURITY_FILTER_ORDER_DEFAULT = -100;

    /** The request attribute holding the snapshot of the innermost scope the library's filters enter for the request (IN-09). */
    static final String SNAPSHOT_ATTRIBUTE = ContextInboundFilter.class.getName() + ".SNAPSHOT";

    private final InboundTrustVerifier verifier;
    private final ContextPolicy policy;
    private final ViolationReporter reporter;
    private final int maxValueLength;
    private final Optional<Duration> defaultInbound;
    private final Duration maxInbound;
    private final int expiredStatus;

    ContextInboundFilter(InboundTrustVerifier verifier, EffectivePolicy policy, ViolationReporter reporter, int maxValueLength,
            BoundrailContextProperties.Deadline deadline) {
        this.verifier = verifier;
        this.policy = policy.policy();
        this.reporter = reporter;
        this.maxValueLength = maxValueLength;
        this.defaultInbound = deadline.defaultInboundDuration();
        this.maxInbound = deadline.maxInboundDuration();
        this.expiredStatus = deadline.expiredStatus();
    }

    /**
     * For a {@code TRUSTED_INTERNAL} request, binds each key that propagates to {@code INTERNAL_CALL} and whose wire header is present, decoded
     * with the key's codec (IN-02, WIRE-01); servlet header names are matched ignoring case (WIRE-06). The initiator is bound from
     * {@code Boundrail-Initiator} with {@code Initiator.of} (WIRE-02); the authority is not bound (IN-02). A key whose wire header appears
     * more than once is left unbound and reported as {@code INVALID_VALUE}, with none of its values decoded (IN-02). A value longer than
     * {@code maxValueLength} is left unbound and reported as {@code OVERSIZED_VALUE}, without being decoded (WIRE-04). A value that fails to
     * decode is left unbound and reported as {@code INVALID_VALUE} (WIRE-05). For an {@code UNTRUSTED} request, binds nothing and reports
     * each policy wire header present (IN-02). In the same scope it binds the deadline: from a trusted request's single
     * {@code Boundrail-Deadline-Ms} of ASCII digits, capped at {@code max-inbound}; otherwise from {@code default-inbound}, if set (DL-03).
     * If that deadline has already expired, it sets {@code expired-status}, writes no body, does not call {@code sendError} and does not
     * run the rest of the chain (DL-04); an {@code ASYNC} dispatch is not checked again. Otherwise it runs the rest of the chain inside
     * that scope and restores the previous snapshot, rethrowing the chain's exception unchanged (IN-01, CORE-08).
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (isAsyncDispatch(request)) {
            reenter(request, response, chain);
            return;
        }
        ContextBinding binding = null;
        Optional<Duration> budget = Optional.empty();
        if (verifier.verify(request) == InboundTrustVerifier.Trust.TRUSTED_INTERNAL) {
            for (ContextKey<?> key : policy.keys()) {
                if (key != BuiltInKeys.DEADLINE && key != BuiltInKeys.AUTHORITY && policy.propagates(key, Boundary.INTERNAL_CALL)) {
                    binding = bind(binding, key, request);
                }
            }
            budget = trustedBudget(request);
        } else {
            reportUntrusted(request);
        }
        budget = budget.or(() -> defaultInbound);
        if (budget.isPresent()) {
            Deadline deadline = Deadline.after(budget.get());
            if (deadline.isExpired()) {
                response.setStatus(expiredStatus);
                return;
            }
            binding = binding == null ? Context.with(BuiltInKeys.DEADLINE, deadline) : binding.with(BuiltInKeys.DEADLINE, deadline);
        }
        if (binding == null) {
            request.setAttribute(SNAPSHOT_ATTRIBUTE, Context.capture());
            chain.doFilter(request, response);
            return;
        }
        try {
            binding.call(() -> {
                request.setAttribute(SNAPSHOT_ATTRIBUTE, Context.capture());
                chain.doFilter(request, response);
                return null;
            });
        } catch (Exception e) {
            ContextInboundFilter.<RuntimeException>sneakyThrow(e);
        }
    }

    /** Runs on {@code ASYNC} dispatches too, to re-enter the stored snapshot (IN-09). */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    /**
     * Runs the rest of the chain of an {@code ASYNC} dispatch inside the snapshot stored on the initial dispatch, without asking the verifier,
     * decoding headers or reporting violations; with no stored snapshot, in the current scope (IN-09). Rethrows the chain's exception
     * unchanged.
     */
    private static void reenter(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (!(request.getAttribute(SNAPSHOT_ATTRIBUTE) instanceof ContextSnapshot stored)) {
            chain.doFilter(request, response);
            return;
        }
        try {
            stored.wrap(() -> {
                chain.doFilter(request, response);
                return null;
            }).call();
        } catch (Exception e) {
            ContextInboundFilter.<RuntimeException>sneakyThrow(e);
        }
    }

    /**
     * Reports one {@code UNTRUSTED_HEADER} for each key of the policy whose wire header is present, however many lines carry it, even if
     * empty (IN-02, DIAG-08). The violation names the key, never the header value (DIAG-01, DIAG-09).
     */
    private void reportUntrusted(HttpServletRequest request) {
        for (ContextKey<?> key : policy.keys()) {
            if (policy.wireName(key).filter(name -> request.getHeaders(name).hasMoreElements()).isPresent()) {
                reporter.report(reporter.violation(Violation.Kind.UNTRUSTED_HEADER, key.name(), Boundary.INTERNAL_CALL, Optional.empty()));
            }
        }
    }

    /**
     * {@return the budget of a trusted request's {@code Boundrail-Deadline-Ms}, if it is carried on one header line and is one or more ASCII
     * digits, capped at {@code max-inbound}; a value too large for a {@code long} is {@code max-inbound}} A header on several lines is
     * reported once as {@code INVALID_VALUE}, with no line length-checked; otherwise a value longer than {@code maxValueLength} is reported
     * as {@code OVERSIZED_VALUE} (WIRE-04), and a value that is not digits as {@code INVALID_VALUE}. Each gives no budget, so
     * {@code default-inbound} applies (DL-03, DIAG-09: the value is never reported).
     */
    private Optional<Duration> trustedBudget(HttpServletRequest request) {
        List<String> values = policy.wireName(BuiltInKeys.DEADLINE)
                .<List<String>>map(name -> Collections.list(request.getHeaders(name)))
                .orElse(List.of());
        if (values.isEmpty()) {
            return Optional.empty();
        }
        // As IN-02 does for every key: several lines are one INVALID_VALUE, and no line is length-checked (D57).
        if (values.size() > 1) {
            reportDeadline(Violation.Kind.INVALID_VALUE);
            return Optional.empty();
        }
        String value = values.getFirst();
        if (value.length() > maxValueLength) {
            reportDeadline(Violation.Kind.OVERSIZED_VALUE);
            return Optional.empty();
        }
        if (!isDigits(value)) {
            reportDeadline(Violation.Kind.INVALID_VALUE);
            return Optional.empty();
        }
        // 18 digits always fit in a long. A practical bound: a longer value is at least 10^18 ms, about 31.7 million years, so it
        // exceeds any realistic max-inbound and is capped to it.
        if (value.length() > 18) {
            return Optional.of(maxInbound);
        }
        Duration requested = Duration.ofMillis(Long.parseLong(value));
        return Optional.of(requested.compareTo(maxInbound) < 0 ? requested : maxInbound);
    }

    private void reportDeadline(Violation.Kind kind) {
        reporter.report(reporter.violation(kind, BuiltInKeys.DEADLINE.name(), Boundary.INTERNAL_CALL, Optional.empty()));
    }

    /** {@return whether the value is one or more ASCII digits} (DL-03) */
    static boolean isDigits(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Rethrows {@code t} unchanged, checked or not, without declaring it. */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * Adds the key's decoded header value, if the key has a wire name and the header is present once: the initiator with
     * {@code Initiator.of} (WIRE-02), any other key with its codec, if it has one. A header present more than once is reported as
     * {@code INVALID_VALUE} and adds nothing (IN-02, DIAG-08). A value longer than {@code maxValueLength} is reported as
     * {@code OVERSIZED_VALUE} before it is decoded, and adds nothing (WIRE-04, DIAG-08). A value whose decoding throws
     * {@code IllegalArgumentException} or a subclass is reported as {@code INVALID_VALUE}, without the value or the exception's message, and
     * adds nothing; any other exception propagates unchanged (WIRE-05, DIAG-09).
     */
    private <T> ContextBinding bind(ContextBinding binding, ContextKey<T> key, HttpServletRequest request) {
        List<String> values = policy.wireName(key)
                .<List<String>>map(name -> Collections.list(request.getHeaders(name)))
                .orElse(List.of());
        if (values.isEmpty()) {
            return binding;
        }
        if (values.size() > 1) {
            reporter.report(reporter.violation(Violation.Kind.INVALID_VALUE, key.name(), Boundary.INTERNAL_CALL, Optional.empty()));
            return binding;
        }
        String header = values.getFirst();
        if (header.length() > maxValueLength) {
            reporter.report(reporter.violation(Violation.Kind.OVERSIZED_VALUE, key.name(), Boundary.INTERNAL_CALL, Optional.empty()));
            return binding;
        }
        T value;
        try {
            if (key == BuiltInKeys.INITIATOR) {
                value = key.type().cast(Initiator.of(header));
            } else {
                Optional<ContextCodec<T>> codec = policy.codec(key);
                if (codec.isEmpty()) {
                    return binding;
                }
                value = codec.get().decode(header);
            }
        } catch (IllegalArgumentException e) {
            reporter.report(reporter.violation(Violation.Kind.INVALID_VALUE, key.name(), Boundary.INTERNAL_CALL, Optional.empty()));
            return binding;
        }
        return binding == null ? Context.with(key, value) : binding.with(key, value);
    }
}
