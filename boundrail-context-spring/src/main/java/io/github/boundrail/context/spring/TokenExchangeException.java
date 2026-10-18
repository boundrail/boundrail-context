package io.github.boundrail.context.spring;

/**
 * The token endpoint could not be reached or gave no valid response; the message holds no token, secret or response body (ID-12, ID-06).
 * Only the library throws it.
 */
public final class TokenExchangeException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** A response with a status other than 200. */
    TokenExchangeException(String audience, int status) {
        super("Token exchange for audience " + audience + " failed with status " + status);
    }

    /** A response whose body is not valid; {@code reason} is fixed text, never the body or a parser's message. */
    TokenExchangeException(String audience, int status, String reason) {
        super("Token exchange for audience " + audience + " failed with status " + status + ": " + reason);
    }

    /** No response; {@code reason} is fixed text. */
    TokenExchangeException(String audience, String reason, Throwable cause) {
        super("Token exchange for audience " + audience + " failed: " + reason, cause);
    }
}
