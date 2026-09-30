package io.github.boundrail.context;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

/**
 * The rules for each context key: its codec, wire name, required and loggable flags, and the boundaries it propagates to (POL-01). Built
 * policies are immutable. A key not registered in a policy propagates to {@link Boundary#ATTACHED} and {@link Boundary#DETACHED} only, and is
 * neither required nor loggable (POL-02).
 */
public final class ContextPolicy {

    private static final Set<Boundary> UNREGISTERED_BOUNDARIES = Set.of(Boundary.ATTACHED, Boundary.DETACHED);

    private final Map<ContextKey<?>, Rules> rules;

    private ContextPolicy(Map<ContextKey<?>, Rules> rules) {
        this.rules = Map.copyOf(rules);
    }

    /** {@return a new builder without registered keys} */
    public static Builder builder() {
        return new PolicyBuilder();
    }

    /**
     * Returns whether the key propagates to the boundary (POL-02, POL-03).
     *
     * @param key the key
     * @param boundary the boundary
     * @return {@code true} if the policy lists the boundary for the key, or the key is not registered and the boundary is {@code ATTACHED}
     *     or {@code DETACHED}
     * @throws NullPointerException if an argument is {@code null}
     */
    public boolean propagates(ContextKey<?> key, Boundary boundary) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(boundary, "boundary");
        Rules registered = rules.get(key);
        return (registered != null ? registered.boundaries() : UNREGISTERED_BOUNDARIES).contains(boundary);
    }

    /**
     * Returns whether the key is required.
     *
     * @param key the key
     * @return {@code true} if the key is registered as required
     * @throws NullPointerException if the key is {@code null}
     */
    public boolean isRequired(ContextKey<?> key) {
        Objects.requireNonNull(key, "key");
        Rules registered = rules.get(key);
        return registered != null && registered.required();
    }

    /**
     * Returns whether the key is loggable.
     *
     * @param key the key
     * @return {@code true} if the key is registered as loggable
     * @throws NullPointerException if the key is {@code null}
     */
    public boolean isLoggable(ContextKey<?> key) {
        Objects.requireNonNull(key, "key");
        Rules registered = rules.get(key);
        return registered != null && registered.loggable();
    }

    /**
     * Returns the wire name registered for the key (POL-01).
     *
     * @param key the key
     * @return the wire name, or empty if none is registered
     * @throws NullPointerException if the key is {@code null}
     */
    public Optional<String> wireName(ContextKey<?> key) {
        Objects.requireNonNull(key, "key");
        Rules registered = rules.get(key);
        return registered != null ? Optional.ofNullable(registered.wireName()) : Optional.empty();
    }

    /**
     * Returns the codec registered for the key (POL-01).
     *
     * @param key the key
     * @param <T> the value type
     * @return the codec, or empty if none is registered
     * @throws NullPointerException if the key is {@code null}
     */
    @SuppressWarnings("unchecked") // KeyRules<T> only accepts a ContextCodec<T> for a ContextKey<T>
    public <T> Optional<ContextCodec<T>> codec(ContextKey<T> key) {
        Objects.requireNonNull(key, "key");
        Rules registered = rules.get(key);
        return registered != null ? Optional.ofNullable((ContextCodec<T>) registered.codec()) : Optional.empty();
    }

    /** Registers the rules of each key and builds a policy. */
    public interface Builder {

        /**
         * Registers rules for a key. The rules consumer runs once, on fresh rules, before this method returns.
         *
         * @param key the key
         * @param rules configures the key's rules
         * @param <T> the value type
         * @return this builder
         * @throws NullPointerException if an argument is {@code null}
         */
        <T> Builder key(ContextKey<T> key, Consumer<KeyRules<T>> rules);

        /** {@return a policy with the rules registered so far; later use of this builder does not change it} */
        ContextPolicy build();
    }

    /**
     * The rules of one key. Without a call, a key has no codec and no wire name, is neither required nor loggable, and propagates to no
     * boundary.
     *
     * @param <T> the value type
     */
    public interface KeyRules<T> {

        /**
         * Sets the codec.
         *
         * @param codec the codec
         * @return these rules
         * @throws NullPointerException if the codec is {@code null}
         */
        KeyRules<T> codec(ContextCodec<T> codec);

        /**
         * Sets the wire name.
         *
         * @param name the wire name
         * @return these rules
         * @throws NullPointerException if the name is {@code null}
         */
        KeyRules<T> wireName(String name);

        /** {@return these rules, with the key required} */
        KeyRules<T> required();

        /** {@return these rules, with the key loggable} */
        KeyRules<T> loggable();

        /**
         * Sets the boundaries the key propagates to.
         *
         * @param boundaries the boundaries
         * @return these rules
         * @throws NullPointerException if the array or one of its elements is {@code null}
         */
        KeyRules<T> propagateTo(Boundary... boundaries);
    }

    private record Rules(ContextCodec<?> codec, String wireName, boolean required, boolean loggable, Set<Boundary> boundaries) {
    }

    private static final class PolicyBuilder implements Builder {

        private final Map<ContextKey<?>, Rules> rules = new HashMap<>();

        @Override
        public <T> Builder key(ContextKey<T> key, Consumer<KeyRules<T>> rules) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(rules, "rules");
            MutableKeyRules<T> keyRules = new MutableKeyRules<>();
            rules.accept(keyRules);
            this.rules.putIfAbsent(key, keyRules.toRules());
            return this;
        }

        @Override
        public ContextPolicy build() {
            return new ContextPolicy(rules);
        }
    }

    private static final class MutableKeyRules<T> implements KeyRules<T> {

        private ContextCodec<T> codec;
        private String wireName;
        private boolean required;
        private boolean loggable;
        private Set<Boundary> boundaries = Set.of();

        @Override
        public KeyRules<T> codec(ContextCodec<T> codec) {
            this.codec = Objects.requireNonNull(codec, "codec");
            return this;
        }

        @Override
        public KeyRules<T> wireName(String name) {
            this.wireName = Objects.requireNonNull(name, "name");
            return this;
        }

        @Override
        public KeyRules<T> required() {
            required = true;
            return this;
        }

        @Override
        public KeyRules<T> loggable() {
            loggable = true;
            return this;
        }

        @Override
        public KeyRules<T> propagateTo(Boundary... boundaries) {
            Objects.requireNonNull(boundaries, "boundaries");
            for (Boundary boundary : boundaries) {
                Objects.requireNonNull(boundary, "boundary");
            }
            this.boundaries = Set.copyOf(Arrays.asList(boundaries));
            return this;
        }

        Rules toRules() {
            return new Rules(codec, wireName, required, loggable, boundaries);
        }
    }
}
