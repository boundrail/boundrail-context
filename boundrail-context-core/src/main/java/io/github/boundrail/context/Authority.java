package io.github.boundrail.context;

import java.util.Objects;

/**
 * The caller's authority: a subject and its credential (ID-01).
 *
 * <p>{@code toString()} redacts the credential, and {@code equals} and {@code hashCode} are identity-based, so credentials are never
 * printed, compared or hashed.
 */
public final class Authority {

    private final String subject;
    private final String credential;

    private Authority(String subject, String credential) {
        this.subject = subject;
        this.credential = credential;
    }

    /** {@return an authority for {@code subject} holding {@code credential}} */
    public static Authority of(String subject, String credential) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(credential, "credential");
        return new Authority(subject, credential);
    }

    /** {@return the subject} */
    public String subject() {
        return subject;
    }

    /** {@return the credential} */
    public String credential() {
        return credential;
    }

    /** {@return the subject with the credential redacted} */
    @Override
    public String toString() {
        return "Authority[subject=" + subject + ", credential=<redacted>]";
    }
}
