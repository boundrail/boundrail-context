package io.github.boundrail.context.spring;

import org.springframework.security.core.Authentication;

/**
 * Contributes context values from the authentication of a request. Every bean of this type is invoked, in bean order, for each authenticated
 * request, and the rest of the chain runs with the contributed values bound (IN-04, IN-05).
 */
public interface ContextContributor {

    /**
     * Contributes values for an authenticated request.
     *
     * @param authentication the request's authentication
     * @param contributions where the values are contributed
     */
    void contribute(Authentication authentication, ContextContributions contributions);
}
