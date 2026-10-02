package io.github.boundrail.context.spring;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Decides whether an inbound request comes from a trusted internal caller, whose context headers are bound (IN-02). A bean of this type
 * replaces the built-in verifier (IN-03).
 */
public interface InboundTrustVerifier {

    /** The verdict for an inbound request. */
    enum Trust {
        /** The caller is a trusted internal service: its context headers are bound. */
        TRUSTED_INTERNAL,
        /** The caller is not trusted: its context headers are not bound. */
        UNTRUSTED
    }

    /**
     * Returns the verdict for a request.
     *
     * @param request the request
     * @return the verdict
     * @throws NullPointerException if the request is {@code null}
     */
    Trust verify(HttpServletRequest request);
}
