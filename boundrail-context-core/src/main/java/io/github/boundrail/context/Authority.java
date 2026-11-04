package io.github.boundrail.context;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * The caller's authority: a subject and its credential (ID-01).
 *
 * <p>{@code toString()} redacts the credential, and {@code equals} and {@code hashCode} are identity-based, so credentials are never
 * printed, compared or hashed.
 */
public final class Authority {

    private final String subject;
    private final String credential;
    private final Instant expiresAt;

    private Authority(String subject, String credential, Instant expiresAt) {
        this.subject = subject;
        this.credential = credential;
        this.expiresAt = expiresAt;
    }

    /**
     * {@return an authority for {@code subject} holding {@code credential}}
     *
     * @param subject the subject
     * @param credential the credential
     */
    public static Authority of(String subject, String credential) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(credential, "credential");
        return new Authority(subject, credential, null);
    }

    /**
     * {@return an authority for {@code subject} holding {@code credential} that expires at {@code expiresAt}}
     *
     * @param subject the subject
     * @param credential the credential
     * @param expiresAt when the credential expires
     */
    public static Authority of(String subject, String credential, Instant expiresAt) {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(credential, "credential");
        Objects.requireNonNull(expiresAt, "expiresAt");
        return new Authority(subject, credential, expiresAt);
    }

    /** {@return the subject} */
    public String subject() {
        return subject;
    }

    /** {@return the credential} */
    public String credential() {
        return credential;
    }

    /** {@return the credential's expiry, or empty for an authority made with {@link #of(String, String)}} */
    public Optional<Instant> expiresAt() {
        return Optional.ofNullable(expiresAt);
    }

    /** {@return the subject with the credential redacted} */
    @Override
    public String toString() {
        return "Authority[subject=" + subject + ", credential=<redacted>]";
    }
}
