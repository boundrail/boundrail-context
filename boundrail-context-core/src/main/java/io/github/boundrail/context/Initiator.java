package io.github.boundrail.context;

import java.util.Objects;

/**
 * Who started the work, such as {@code user:alice} or {@code service:billing} (ID-07).
 *
 * <p>Values are 1 to 256 UTF-16 chars long. {@code equals} and {@code hashCode} compare values, and {@code toString()} is the value.
 */
public final class Initiator {

    private static final int MAX_LENGTH = 256;

    private final String value;

    private Initiator(String value) {
        this.value = value;
    }

    /** {@return an initiator with {@code value}, which must be 1 to 256 chars long} */
    public static Initiator of(String value) {
        Objects.requireNonNull(value, "value");
        if (value.isEmpty() || value.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("Initiator must be 1 to " + MAX_LENGTH + " chars long, was " + value.length());
        }
        return new Initiator(value);
    }

    /** {@return the value} */
    public String value() {
        return value;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Initiator initiator && value.equals(initiator.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }

    /** {@return the value unchanged} */
    @Override
    public String toString() {
        return value;
    }
}
