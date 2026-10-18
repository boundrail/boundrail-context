package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextBinding;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.Initiator;
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
 * IN-05), {@code AUTHORITY} from a verified OAuth2 token authentication, whether or not it is already bound (ID-02), and {@code INITIATOR =
 * user:<name>} when it is neither bound nor contributed (ID-08); and reports one {@code INVALID_VALUE} for each key a contributor reports
 * as invalid (IN-07), and for {@code INITIATOR} when the name is empty or the derived value is not a valid initiator (ID-08). Inside its
 * scope it replaces the snapshot stored for the request's {@code ASYNC} dispatches. It does not run on {@code ASYNC} dispatches, so
 * contributors are invoked once per request (IN-09).
 */
final class ContextAuthenticationFilter extends OncePerRequestFilter {

    /** Defines an authenticated request: an authentication that is not {@code null}, reports {@code isAuthenticated()}, and is not anonymous. */
    private static final AuthenticationTrustResolver TRUST_RESOLVER = new AuthenticationTrustResolverImpl();

    private final ObjectProvider<ContextContributor> contributors;
    private final AuthorityExtraction extraction;
    private final ViolationReporter reporter;

    ContextAuthenticationFilter(ObjectProvider<ContextContributor> contributors, AuthorityExtraction extraction,
            ViolationReporter reporter) {
        this.contributors = contributors;
        this.extraction = extraction;
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
        extraction.from(authentication).ifPresent(authority -> contributions.bind(BuiltInKeys.AUTHORITY, authority));
        if (!BuiltInKeys.INITIATOR.isBound() && !contributions.contributed.contains(BuiltInKeys.INITIATOR)) {
            derivedInitiator(authentication.getName()).ifPresentOrElse(
                    initiator -> contributions.bind(BuiltInKeys.INITIATOR, initiator),
                    () -> contributions.invalid(BuiltInKeys.INITIATOR));
        }
        if (contributions.binding == null) {
            chain.doFilter(request, response);
            return;
        }
        try {
            contributions.binding.call(() -> {
                request.setAttribute(ContextInboundFilter.SNAPSHOT_ATTRIBUTE, Context.capture());
                chain.doFilter(request, response);
                return null;
            });
        } catch (Exception e) {
            ContextAuthenticationFilter.<RuntimeException>sneakyThrow(e);
        }
    }

    /** {@return {@code user:<name>}, or empty if {@code name} is {@code null} or empty or the result is not a valid initiator (ID-07, ID-08)} */
    private static Optional<Initiator> derivedInitiator(String name) {
        if (name == null || name.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Initiator.of("user:" + name));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** Rethrows {@code t} unchanged, checked or not, without declaring it. */
    @SuppressWarnings("unchecked")
    private static <E extends Throwable> void sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * Keeps the first contribution for each key that is not already bound (IN-05); rejects a {@code null} key or value, then {@code AUTHORITY}
     * (ID-02), then a value that is not an instance of the key's type (CORE-13). {@code invalid} reports one {@code INVALID_VALUE} with
     * boundary {@code INTERNAL_CALL} and no destination, binds nothing and leaves the key's contributions unaffected (IN-07, DIAG-08); it
     * rejects a {@code null} key (CORE-13).
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
            if (key.equals(BuiltInKeys.AUTHORITY)) {
                throw new IllegalArgumentException(
                        "boundrail.authority cannot be contributed; it is bound only from the authentication (ID-02)");
            }
            T checked = key.type().cast(value);
            if (!key.isBound() && contributed.add(key)) {
                bind(key, checked);
            }
        }

        /** Adds the key and value to the binding. */
        <T> void bind(ContextKey<T> key, T value) {
            binding = binding == null ? Context.with(key, value) : binding.with(key, value);
        }

        @Override
        public void invalid(ContextKey<?> key) {
            Objects.requireNonNull(key, "key");
            reporter.report(reporter.violation(Violation.Kind.INVALID_VALUE, key.name(), Boundary.INTERNAL_CALL, Optional.empty()));
        }
    }
}
