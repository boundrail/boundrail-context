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

/** Binds the context headers of a trusted request and runs the rest of the chain inside that scope (IN-01, IN-02). */
final class ContextInboundFilter extends OncePerRequestFilter {

    /**
     * The default of {@code spring.security.filter.order}, Spring Boot's {@code SecurityFilterProperties.DEFAULT_FILTER_ORDER}; the filter is
     * ordered one below that property (IN-01). Copied, because Spring Security is optional.
     */
    static final int SECURITY_FILTER_ORDER_DEFAULT = -100;

    private final InboundTrustVerifier verifier;
    private final ContextPolicy policy;
    private final ViolationReporter reporter;

    ContextInboundFilter(InboundTrustVerifier verifier, EffectivePolicy policy, ViolationReporter reporter) {
        this.verifier = verifier;
        this.policy = policy.policy();
        this.reporter = reporter;
    }

    /**
     * For a {@code TRUSTED_INTERNAL} request, binds each key that propagates to {@code INTERNAL_CALL} and whose wire header is present, decoded
     * with the key's codec (IN-02, WIRE-01); servlet header names are matched ignoring case (WIRE-06). The initiator is bound from
     * {@code Boundrail-Initiator} with {@code Initiator.of} (WIRE-02); the deadline and authority are not bound (IN-02). A key whose wire
     * header appears more than once is left unbound and reported as {@code INVALID_VALUE}, with none of its values decoded (IN-02). Then runs
     * the rest of the chain inside that scope and restores the previous snapshot, rethrowing the chain's exception unchanged (IN-01, CORE-08).
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

    /** Rethrows {@code t} unchanged, checked or not, without declaring it. */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * Adds the key's decoded header value, if the key has a wire name and the header is present once: the initiator with
     * {@code Initiator.of} (WIRE-02), any other key with its codec, if it has one. A header present more than once is reported as
     * {@code INVALID_VALUE} and adds nothing (IN-02, DIAG-08).
     */
    private <T> ContextBinding bind(ContextBinding binding, ContextKey<T> key, HttpServletRequest request) {
        List<String> values = policy.wireName(key)
                .<List<String>>map(name -> Collections.list(request.getHeaders(name)))
                .orElse(List.of());
        if (values.isEmpty()) {
            return binding;
        }
        if (values.size() > 1) {
            reporter.report(new Violation(Violation.Kind.INVALID_VALUE, key.name(), Boundary.INTERNAL_CALL, Optional.empty(),
                    Thread.currentThread().getName(), Optional.empty(), Optional.empty()));
            return binding;
        }
        String header = values.getFirst();
        T value;
        if (key == BuiltInKeys.INITIATOR) {
            value = key.type().cast(Initiator.of(header));
        } else {
            Optional<ContextCodec<T>> codec = policy.codec(key);
            if (codec.isEmpty()) {
                return binding;
            }
            value = codec.get().decode(header);
        }
        return binding == null ? Context.with(key, value) : binding.with(key, value);
    }
}
