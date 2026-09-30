package io.github.boundrail.context;

/** Entry points for binding context for a scope (CORE-07). */
public final class Context {

    private Context() {
    }

    /**
     * Returns a binding of a key to a value.
     *
     * @param key the key
     * @param value the value
     * @param <T> the value type
     * @return a new binding
     * @throws NullPointerException if the key is {@code null}
     */
    public static <T> ContextBinding with(ContextKey<T> key, T value) {
        return ContextBinding.of(key, value);
    }
}
