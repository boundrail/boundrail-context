package io.github.boundrail.context;

/**
 * Converts context values to and from their wire text (WIRE-03).
 *
 * @param <T> the value type
 */
public interface ContextCodec<T> {

    /**
     * Encodes a value as wire text.
     *
     * @param value the value
     * @return the wire text
     * @throws NullPointerException if the value is {@code null}
     */
    String encode(T value);

    /**
     * Decodes wire text into a value.
     *
     * @param text the wire text
     * @return the value
     * @throws NullPointerException if the text is {@code null}
     * @throws IllegalArgumentException if the text is invalid
     */
    T decode(String text);
}
