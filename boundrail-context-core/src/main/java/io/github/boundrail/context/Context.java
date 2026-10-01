package io.github.boundrail.context;

/** Entry points for binding context for a scope (CORE-07). */
public final class Context {

    private Context() {
    }

    /** {@return the current snapshot itself, not a copy, or {@link ContextSnapshot#EMPTY} if none is current (CORE-09)} */
    public static ContextSnapshot capture() {
        return ContextRuntime.SLOT.current();
    }

    /**
     * Returns a binding of a key to a value.
     *
     * @param key the key
     * @param value the value
     * @param <T> the value type
     * @return a new binding
     * @throws NullPointerException if the key or the value is {@code null}
     * @throws ClassCastException if the value is not an instance of the key's type (CORE-04)
     */
    public static <T> ContextBinding with(ContextKey<T> key, T value) {
        return ContextBinding.of(key, value);
    }

    /**
     * Returns a binding that removes a key.
     *
     * @param key the key
     * @return a new binding
     * @throws NullPointerException if the key is {@code null}
     * @throws IllegalArgumentException if the key is {@link BuiltInKeys#DEADLINE}, which can be shortened but never removed (DL-11)
     */
    public static ContextBinding without(ContextKey<?> key) {
        return ContextBinding.removing(key);
    }
}
