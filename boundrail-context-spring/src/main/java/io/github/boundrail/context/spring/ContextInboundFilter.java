package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextBinding;
import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Optional;
import org.springframework.web.filter.OncePerRequestFilter;

/** Binds the context headers of a trusted request and runs the rest of the chain inside that scope (IN-01, IN-02). */
final class ContextInboundFilter extends OncePerRequestFilter {

    /**
     * One before Spring Boot's {@code SecurityFilterProperties.DEFAULT_FILTER_ORDER} ({@code -100}), so the filter runs before Spring Security's
     * filter chain (IN-01). Copied, because Spring Security is optional.
     */
    static final int ORDER = -101;

    private final InboundTrustVerifier verifier;
    private final ContextPolicy policy;

    ContextInboundFilter(InboundTrustVerifier verifier, EffectivePolicy policy) {
        this.verifier = verifier;
        this.policy = policy.policy();
    }

    /**
     * For a {@code TRUSTED_INTERNAL} request, binds each key that propagates to {@code INTERNAL_CALL} and whose wire header is present, decoded
     * with the key's codec (IN-02, WIRE-01); servlet header names are matched ignoring case (WIRE-06). Keys without a codec, the built-in
     * keys, are not bound. Then runs the rest of the chain inside
     * that scope and restores the previous snapshot, rethrowing the chain's exception unchanged (IN-01, CORE-08).
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        ContextBinding binding = null;
        if (verifier.verify(request) == InboundTrustVerifier.Trust.TRUSTED_INTERNAL) {
            for (ContextKey<?> key : policy.keys()) {
                if (policy.propagates(key, Boundary.INTERNAL_CALL)) {
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
        } catch (IOException | ServletException | RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new UndeclaredThrowableException(e); // unreachable: the chain throws only the exceptions above
        }
    }

    /** Adds the key's decoded header value, if the key has a wire name and codec and the header is present. */
    private <T> ContextBinding bind(ContextBinding binding, ContextKey<T> key, HttpServletRequest request) {
        Optional<ContextCodec<T>> codec = policy.codec(key);
        Optional<String> header = policy.wireName(key).map(request::getHeader);
        if (codec.isEmpty() || header.isEmpty()) {
            return binding;
        }
        T value = codec.get().decode(header.get());
        return binding == null ? Context.with(key, value) : binding.with(key, value);
    }
}
