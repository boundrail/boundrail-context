package io.github.boundrail.context.spring;

import io.github.boundrail.context.Authority;
import java.util.Optional;
import org.springframework.security.core.Authentication;

/**
 * Extracts the caller's authority from a verified authentication (ID-02). Its one implementation, defined where Spring Security's OAuth2
 * resource server is present, is the only class that names its types, so {@code ContextAuthenticationFilter} loads without it.
 */
@FunctionalInterface
interface AuthorityExtraction {

    /** {@return the authority of {@code authentication}, or empty if it carries none} */
    Optional<Authority> from(Authentication authentication);
}
