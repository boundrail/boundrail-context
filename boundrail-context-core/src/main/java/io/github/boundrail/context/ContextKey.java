package io.github.boundrail.context;

import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A typed context key with a name and an optional default value (CORE-01, CORE-03). Keys compare by instance (CORE-02).
 *
 * @param <T> the type of the values bound to this key
 */
public final class ContextKey<T> {

    private static final Pattern NAME = Pattern.compile("[a-z][a-z0-9-]*(\\.[a-z][a-z0-9-]*)*");
    private static final String RESERVED_PREFIX = "boundrail.";

    private final String name;
    private final Class<T> type;
    private final T defaultValue;

    private ContextKey(String name, Class<T> type, T defaultValue) {
        this.name = name;
        this.type = type;
        this.defaultValue = defaultValue;
    }

    /**
     * Creates a key without a default value.
     *
     * @param name the key name, matching {@code [a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*} and not starting with {@code boundrail.}
     * @param type the value type, not primitive
     * @param <T> the value type
     * @return a new key
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the name is invalid or reserved, or the type is primitive
     */
    public static <T> ContextKey<T> of(String name, Class<T> type) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        validate(name, type);
        return new ContextKey<>(name, type, null);
    }

    /**
     * Creates a key with a default value.
     *
     * @param name the key name, matching {@code [a-z][a-z0-9-]*(\.[a-z][a-z0-9-]*)*} and not starting with {@code boundrail.}
     * @param type the value type, not primitive
     * @param defaultValue the value used when the key is not bound
     * @param <T> the value type
     * @return a new key
     * @throws NullPointerException if an argument is {@code null}
     * @throws IllegalArgumentException if the name is invalid or reserved, or the type is primitive
     */
    public static <T> ContextKey<T> of(String name, Class<T> type, T defaultValue) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(defaultValue, "defaultValue");
        validate(name, type);
        return new ContextKey<>(name, type, defaultValue);
    }

    private static void validate(String name, Class<?> type) {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Invalid context key name: \"" + name + "\"");
        }
        if (name.startsWith(RESERVED_PREFIX)) {
            throw new IllegalArgumentException("Context key name uses the reserved prefix " + RESERVED_PREFIX + ": " + name);
        }
        if (type.isPrimitive()) {
            throw new IllegalArgumentException("Context key type must not be primitive: " + type);
        }
    }

    /** {@return the key name} */
    public String name() {
        return name;
    }

    /** {@return the value type} */
    public Class<T> type() {
        return type;
    }

    /** {@return the default value, or empty if the key has none} */
    public Optional<T> defaultValue() {
        return Optional.ofNullable(defaultValue);
    }
}
