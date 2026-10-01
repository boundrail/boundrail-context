package io.github.boundrail.context;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
    private static final Set<Boundary> REMOTE_BOUNDARIES = EnumSet.of(Boundary.INTERNAL_CALL, Boundary.EXTERNAL_CALL, Boundary.MESSAGE);
    private static final String RESERVED_WIRE_NAME = "Authorization";
    private static final String RESERVED_WIRE_PREFIX = "Boundrail-";

    /**
     * The built-in rules (POL-03, POL-06): no codec, and the WIRE-02 header names for {@code DEADLINE} and {@code INITIATOR}. Every policy
     * holds these same instances.
     */
    private static final Map<ContextKey<?>, Rules> BUILT_IN_RULES = Map.of(
            BuiltInKeys.DEADLINE, new Rules(null, "Boundrail-Deadline-Ms", false, false, Set.of(Boundary.ATTACHED, Boundary.INTERNAL_CALL)),
            BuiltInKeys.AUTHORITY, new Rules(null, null, false, false, Set.of(Boundary.ATTACHED, Boundary.INTERNAL_CALL)),
            BuiltInKeys.INITIATOR, new Rules(null, "Boundrail-Initiator", false, true,
                    Set.of(Boundary.ATTACHED, Boundary.DETACHED, Boundary.INTERNAL_CALL, Boundary.MESSAGE)));

    private static final ContextPolicy BUILT_IN = new ContextPolicy(BUILT_IN_RULES);

    private final Map<ContextKey<?>, Rules> rules;

    private ContextPolicy(Map<ContextKey<?>, Rules> rules) {
        this.rules = Map.copyOf(rules);
    }

    /** {@return a new builder without registered keys} */
    public static Builder builder() {
        return new PolicyBuilder();
    }

    /**
     * Returns the policy with only the built-in rules (POL-06). It is not built by a builder, so POL-03's codec and wire-name check, POL-06's
     * rejection of built-in keys and POL-09's reserved wire names do not apply to it.
     *
     * @return the built-in policy
     */
    static ContextPolicy builtIn() {
        return BUILT_IN;
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

    /**
     * Returns a snapshot with exactly the bindings whose keys propagate to the boundary (POL-04).
     *
     * @param snapshot the snapshot
     * @param boundary the boundary
     * @return the filtered snapshot
     * @throws NullPointerException if an argument is {@code null}
     */
    public ContextSnapshot filter(ContextSnapshot snapshot, Boundary boundary) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(boundary, "boundary");
        Map<ContextKey<?>, Object> kept = new HashMap<>();
        for (ContextKey<?> key : snapshot.keys()) {
            if (propagates(key, boundary)) {
                kept.put(key, snapshot.find(key).orElseThrow());
            }
        }
        return new ImmutableContextSnapshot(kept);
    }

    /**
     * Returns a policy with the rules of this policy and the other policy (POL-05). A key in both policies must have equal rules there, and
     * is registered once.
     *
     * @param other the other policy
     * @return the merged policy
     * @throws NullPointerException if the other policy is {@code null}
     * @throws IllegalStateException if one key has conflicting rules in the two policies, or the result has two different keys with the same
     *     name or wire names equal ignoring case (POL-05)
     */
    public ContextPolicy merge(ContextPolicy other) {
        Objects.requireNonNull(other, "other");
        Map<ContextKey<?>, Rules> merged = new HashMap<>(rules);
        other.rules.forEach((key, otherRules) -> {
            Rules first = merged.putIfAbsent(key, otherRules);
            if (first != null && !first.sameAs(otherRules)) {
                throw new IllegalStateException("Context key " + key.name() + " has conflicting rules in the merged policies");
            }
        });
        checkDistinctNames(merged);
        return new ContextPolicy(merged);
    }

    /**
     * Checks that no two different keys have the same name or wire names equal ignoring case (POL-05).
     *
     * @throws IllegalStateException if they do
     */
    private static void checkDistinctNames(Map<ContextKey<?>, Rules> rules) {
        Map<String, ContextKey<?>> names = new HashMap<>();
        Map<String, ContextKey<?>> wireNames = new HashMap<>();
        rules.forEach((key, keyRules) -> {
            if (names.putIfAbsent(key.name(), key) != null) {
                throw new IllegalStateException("Two different context keys are named " + key.name());
            }
            String wireName = keyRules.wireName();
            if (wireName == null) {
                return;
            }
            ContextKey<?> other = wireNames.putIfAbsent(wireName.toLowerCase(Locale.ROOT), key);
            if (other != null) {
                throw new IllegalStateException(
                        "Context keys " + other.name() + " and " + key.name() + " have wire names equal ignoring case: " + wireName);
            }
        });
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

        /**
         * Builds a policy with the rules registered so far and the built-in rules (POL-06); later use of this builder does not change it.
         *
         * @return the policy
         * @throws IllegalStateException if a key lists a remote boundary without both a codec and a wire name (POL-03), one key is
         *     registered with conflicting rules, two different keys have the same name or wire names equal ignoring case (POL-05), a
         *     built-in key is registered (POL-06), or a wire name is reserved (POL-09)
         */
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

        /**
         * Returns whether these rules equal the other rules (POL-05). Not {@code equals}, which would compare codecs with their own
         * {@code equals}: codecs compare by instance.
         */
        boolean sameAs(Rules other) {
            return codec == other.codec && Objects.equals(wireName, other.wireName) && required == other.required
                    && loggable == other.loggable && boundaries.equals(other.boundaries);
        }
    }

    private static final class PolicyBuilder implements Builder {

        private final Map<ContextKey<?>, Rules> rules = new HashMap<>();
        private final List<String> conflicts = new ArrayList<>();

        @Override
        public <T> Builder key(ContextKey<T> key, Consumer<KeyRules<T>> rules) {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(rules, "rules");
            MutableKeyRules<T> keyRules = new MutableKeyRules<>();
            rules.accept(keyRules);
            Rules registered = keyRules.toRules();
            Rules first = this.rules.putIfAbsent(key, registered);
            if (first != null && !first.sameAs(registered)) {
                conflicts.add("Context key " + key.name() + " is registered with conflicting rules");
            }
            return this;
        }

        @Override
        public ContextPolicy build() {
            if (!conflicts.isEmpty()) {
                throw new IllegalStateException(conflicts.getFirst());
            }
            for (ContextKey<?> key : rules.keySet()) {
                if (BUILT_IN_RULES.containsKey(key)) {
                    throw new IllegalStateException("Built-in context key " + key.name() + " cannot be registered");
                }
            }
            rules.forEach((key, keyRules) -> {
                String wireName = keyRules.wireName();
                boolean remote = keyRules.boundaries().stream().anyMatch(REMOTE_BOUNDARIES::contains);
                if (remote && (keyRules.codec() == null || wireName == null)) {
                    throw new IllegalStateException(
                            "Context key " + key.name() + " propagates to a remote boundary without both a codec and a wire name");
                }
                if (wireName == null) {
                    return;
                }
                if (wireName.equalsIgnoreCase(RESERVED_WIRE_NAME)
                        || wireName.regionMatches(true, 0, RESERVED_WIRE_PREFIX, 0, RESERVED_WIRE_PREFIX.length())) {
                    throw new IllegalStateException("Context key " + key.name() + " uses the reserved wire name " + wireName);
                }
            });
            Map<ContextKey<?>, Rules> withBuiltIn = new HashMap<>(rules);
            withBuiltIn.putAll(BUILT_IN_RULES);
            checkDistinctNames(withBuiltIn);
            return new ContextPolicy(withBuiltIn);
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
