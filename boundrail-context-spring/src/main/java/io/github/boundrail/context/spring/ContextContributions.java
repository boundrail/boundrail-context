package io.github.boundrail.context.spring;

import io.github.boundrail.context.ContextKey;

/** The values contributed for one request by the {@link ContextContributor} beans (IN-04, IN-05). */
public interface ContextContributions {

    /**
     * Contributes a value for a key. A key already bound keeps its value, and the first contribution for a key wins (IN-05).
     *
     * @param key the key
     * @param value the value
     * @param <T> the value type
     * @throws NullPointerException if the key or the value is {@code null}
     * @throws ClassCastException if the value is not an instance of the key's type
     */
    <T> void put(ContextKey<T> key, T value);
}
