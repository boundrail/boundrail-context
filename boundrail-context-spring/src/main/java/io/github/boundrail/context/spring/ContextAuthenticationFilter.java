package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextBinding;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.Violation;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.authentication.AuthenticationTrustResolver;
import org.springframework.security.authentication.AuthenticationTrustResolverImpl;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Binds the values the {@link ContextContributor} beans contribute from the authentication of a request, for the rest of the chain (IN-04,
 * IN-05), and reports one {@code INVALID_VALUE} for each key a contributor reports as invalid (IN-07).
 */
final class ContextAuthenticationFilter extends OncePerRequestFilter {

    /** Defines an authenticated request: an authentication that is not {@code null}, reports {@code isAuthenticated()}, and is not anonymous. */
    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    private final ObjectProvider<ContextContributor> contributors;
    private final ViolationReporter reporter;

    ContextAuthenticationFilter(ObjectProvider<ContextContributor> contributors, ViolationReporter reporter) {
        this.contributors = contributors;
        this.reporter = reporter;
    }

    /**
     * For an authenticated request, invokes every contributor in bean order and runs the rest of the chain in a nested scope that binds the
     * contributed values, then restores the previous snapshot, rethrowing the chain's exception unchanged; otherwise invokes no contributor
     * and runs the rest of the chain in the current scope (IN-04).
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (!TRUST_RESOLVER.isAuthenticated(authentication)) {
            chain.doFilter(request, response);
            return;
        }
        Contributions contributions = new Contributions(reporter);
        contributors.orderedStream().forEach(contributor -> contributor.contribute(authentication, contributions));
        if (contributions.binding == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            contributions.binding.call(() -> {
                chain.doFilter(request, response);
                return null;
            });
        } catch (Exception e) {
            ContextAuthenticationFilter.<RuntimeException>sneakyThrow(e);
        }
    }

    /** Rethrows {@code t} unchanged, checked or not, without declaring it. */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * Keeps the first contribution for each key that is not already bound (IN-05); rejects a {@code null} key or value and a value that is not
     * an instance of the key's type (CORE-13). {@code invalid} reports one {@code INVALID_VALUE} with boundary {@code INTERNAL_CALL} and no
     * destination, binds nothing and leaves the key's contributions unaffected (IN-07, DIAG-08); it rejects a {@code null} key (CORE-13).
     */
    private static final class Contributions implements ContextContributions {

        private final ViolationReporter reporter;
        private final Set<ContextKey<?>> contributed = new HashSet<>();
        private ContextBinding binding;

        Contributions(ViolationReporter reporter) {
            this.reporter = reporter;
        }

        @Override
        public <T> void put(ContextKey<T> key, T value) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(value, "value");
            T checked = key.type().cast(value);
            if (!key.isBound() && contributed.add(key)) {
                binding = binding == null ? Context.with(key, checked) : binding.with(key, checked);
            }
        }

        @Override
        public void invalid(ContextKey<?> key) {
            Objects.requireNonNull(key, "key");
            reporter.report(new Violation(Violation.Kind.INVALID_VALUE, key.name(), Boundary.INTERNAL_CALL, Optional.empty(),
                    Thread.currentThread().getName(), Optional.empty(), Optional.empty()));
        }
    }
}
