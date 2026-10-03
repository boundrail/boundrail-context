package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextBinding;
import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.Initiator;
import io.github.boundrail.context.Violation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the context headers of a trusted request and runs the rest of the chain inside that scope; reports those of an untrusted request
 * (IN-01, IN-02).
 */
final class ContextInboundFilter extends OncePerRequestFilter {

    /**
     * The default of {@code spring.security.filter.order}, Spring Boot's {@code SecurityFilterProperties.DEFAULT_FILTER_ORDER}; the filter is
     * ordered one below that property (IN-01). Copied, because Spring Security is optional.
     */
    static final int SECURITY_FILTER_ORDER_DEFAULT = -100;

    private final InboundTrustVerifier verifier;
    private final ContextPolicy policy;
    private final ViolationReporter reporter;
    private final int maxValueLength;

    ContextInboundFilter(InboundTrustVerifier verifier, EffectivePolicy policy, ViolationReporter reporter, int maxValueLength) {
        this.verifier = verifier;
        this.policy = policy.policy();
        this.reporter = reporter;
        this.maxValueLength = maxValueLength;
    }

    /**
     * For a {@code TRUSTED_INTERNAL} request, binds each key that propagates to {@code INTERNAL_CALL} and whose wire header is present, decoded
     * with the key's codec (IN-02, WIRE-01); servlet header names are matched ignoring case (WIRE-06). The initiator is bound from
     * {@code Boundrail-Initiator} with {@code Initiator.of} (WIRE-02); the deadline and authority are not bound (IN-02). A key whose wire
     * header appears more than once is left unbound and reported as {@code INVALID_VALUE}, with none of its values decoded (IN-02). A value
     * longer than {@code maxValueLength} is left unbound and reported as {@code OVERSIZED_VALUE}, without being decoded (WIRE-04). A value
     * that fails to decode is left unbound and reported as {@code INVALID_VALUE} (WIRE-05). For an {@code UNTRUSTED} request, binds nothing
     * and reports each policy wire header present (IN-02). Then runs the rest of the chain inside that scope and restores the previous
     * snapshot, rethrowing the chain's exception unchanged (IN-01, CORE-08).
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContextBinding binding = null;
        if (verifier.verify(request) == InboundTrustVerifier.Trust.TRUSTED_INTERNAL) {
            for (ContextKey<?> key : policy.keys()) {
                if (key != BuiltInKeys.DEADLINE && key != BuiltInKeys.AUTHORITY && policy.propagates(key, Boundary.INTERNAL_CALL)) {
                    binding = bind(binding, key, request);
                }
            }
        } else {
            reportUntrusted(request);
        }
        if (binding == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            binding.call(() -> {
                chain.doFilter(request, response);
                return null;
            });
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
