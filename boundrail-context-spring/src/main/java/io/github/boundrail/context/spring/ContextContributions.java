package io.github.boundrail.context.spring;

import io.github.boundrail.context.ContextKey;

/**
 * The values contributed for one request by the {@link ContextContributor} beans (IN-04, IN-05), and the keys they report as invalid (IN-07).
 */
public interface ContextContributions {

    /**
     * Contributes a value for a key. A key already bound keeps its value, and the first contribution for a key wins (IN-05).
     *
     * @param key the key
     * @param value the value
     * @param <T> the value type
     * @throws NullPointerException if the key or the value is {@code null}
     * @throws IllegalArgumentException if the key is {@code BuiltInKeys.AUTHORITY}, which is bound only from the authentication (ID-02);
     *     checked before the value's type
     * @throws ClassCastException if the value is not an instance of the key's type
     */
    <T> void put(ContextKey<T> key, T value);

    /**
     * Reports one {@code INVALID_VALUE} violation for a key whose value the contributor found invalid. Binds nothing, and does not affect any
     * contribution for the key (IN-07).
     *
     * @param key the key
     * @throws NullPointerException if the key is {@code null}
     */
    void invalid(ContextKey<?> key);
}
