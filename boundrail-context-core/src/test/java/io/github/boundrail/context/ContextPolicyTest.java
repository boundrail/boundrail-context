package io.github.boundrail.context;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.DETACHED;
import static io.github.boundrail.context.Boundary.EXTERNAL_CALL;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Boundary.MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Named.named;

import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class ContextPolicyTest {

    private static final ContextKey<String> TENANT = ContextKey.of("acme.tenant", String.class);
    private static final ContextKey<Long> ORDER = ContextKey.of("acme.order", Long.class);
    private static final ContextKey<Locale> LOCALE = ContextKey.of("acme.locale", Locale.class);
    private static final ContextKey<String> UNREGISTERED = ContextKey.of("acme.unregistered", String.class);

    @Test
    @DisplayName("POL-01 a built policy reports each key's codec, wire name, required, loggable and boundaries")
    void aBuiltPolicyReportsEachKeysRules() {
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("X-Tenant").required().loggable()
                        .propagateTo(ATTACHED, INTERNAL_CALL, MESSAGE))
                .key(ORDER, rules -> rules.codec(Codecs.longValue()).wireName("X-Order").propagateTo(DETACHED, EXTERNAL_CALL))
                .build();

        assertThat(policy.codec(TENANT)).containsSame(Codecs.string());
        assertThat(policy.wireName(TENANT)).contains("X-Tenant");
        assertThat(policy.isRequired(TENANT)).isTrue();
        assertThat(policy.isLoggable(TENANT)).isTrue();
        assertThat(Stream.of(Boundary.values()).filter(boundary -> policy.propagates(TENANT, boundary)))
                .containsExactly(ATTACHED, INTERNAL_CALL, MESSAGE);

        assertThat(policy.codec(ORDER)).containsSame(Codecs.longValue());
        assertThat(policy.wireName(ORDER)).contains("X-Order");
        assertThat(policy.isRequired(ORDER)).isFalse();
        assertThat(policy.isLoggable(ORDER)).isFalse();
        assertThat(Stream.of(Boundary.values()).filter(boundary -> policy.propagates(ORDER, boundary)))
                .containsExactly(DETACHED, EXTERNAL_CALL);
    }

    @Test
    @DisplayName("POL-01 codec and wireName are empty when none is registered")
    void codecAndWireNameAreEmptyWhenNoneIsRegistered() {
        ContextPolicy policy = ContextPolicy.builder().key(TENANT, rules -> rules.propagateTo(ATTACHED)).build();

        assertThat(policy.codec(TENANT)).isEmpty();
        assertThat(policy.wireName(TENANT)).isEmpty();
        assertThat(policy.codec(UNREGISTERED)).isEmpty();
        assertThat(policy.wireName(UNREGISTERED)).isEmpty();
    }

    @Test
    @DisplayName("POL-01 a built policy does not change when its builder is used again")
    void aBuiltPolicyDoesNotChangeWhenItsBuilderIsUsedAgain() {
        ContextPolicy.Builder builder = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("X-Tenant").propagateTo(INTERNAL_CALL));
        ContextPolicy policy = builder.build();

        builder.key(ORDER, rules -> rules.codec(Codecs.longValue()).wireName("X-Order").required().loggable()
                .propagateTo(EXTERNAL_CALL));
        ContextPolicy later = builder.build();

        assertThat(later.isRequired(ORDER)).isTrue();
        assertThat(policy.propagates(TENANT, INTERNAL_CALL)).isTrue();
        assertThat(policy.codec(ORDER)).isEmpty();
        assertThat(policy.wireName(ORDER)).isEmpty();
        assertThat(policy.isRequired(ORDER)).isFalse();
        assertThat(policy.isLoggable(ORDER)).isFalse();
        assertThat(policy.propagates(ORDER, EXTERNAL_CALL)).isFalse();
    }

    @Test
    @DisplayName("POL-01 a repeated codec, wireName or propagateTo call replaces the earlier value")
    void aRepeatedCodecWireNameOrPropagateToCallReplacesTheEarlierValue() {
        ContextCodec<String> first = new StringCodec();
        ContextCodec<String> second = new StringCodec();

        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(first).codec(second).wireName("X-One").wireName("X-Two")
                        .propagateTo(ATTACHED).propagateTo(INTERNAL_CALL))
                .build();

        assertThat(policy.codec(TENANT)).containsSame(second);
        assertThat(policy.wireName(TENANT)).contains("X-Two");
        assertThat(Stream.of(Boundary.values()).filter(boundary -> policy.propagates(TENANT, boundary)))
                .containsExactly(INTERNAL_CALL);
    }

    @Test
    @DisplayName("POL-01 a boundary listed twice in one propagateTo call counts once")
    void aBoundaryListedTwiceInOnePropagateToCallCountsOnce() {
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("X-Tenant").propagateTo(INTERNAL_CALL, INTERNAL_CALL))
                .build();

        assertThat(Stream.of(Boundary.values()).filter(boundary -> policy.propagates(TENANT, boundary)))
                .containsExactly(INTERNAL_CALL);
    }

    /** A codec with instance equality, so two instances are distinct. */
    private static final class StringCodec implements ContextCodec<String> {

        @Override
        public String encode(String value) {
            return value;
        }

        @Override
        public String decode(String text) {
            return text;
        }
    }

    @Test
    @DisplayName("POL-02 an unregistered key propagates to ATTACHED and DETACHED only and is neither required nor loggable")
    void anUnregisteredKeyPropagatesToAttachedAndDetachedOnly() {
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("X-Tenant").required().loggable()
                        .propagateTo(INTERNAL_CALL, EXTERNAL_CALL, MESSAGE))
                .build();

        assertThat(Stream.of(Boundary.values()).filter(boundary -> policy.propagates(UNREGISTERED, boundary)))
                .containsExactly(ATTACHED, DETACHED);
        assertThat(policy.isRequired(UNREGISTERED)).isFalse();
        assertThat(policy.isLoggable(UNREGISTERED)).isFalse();
    }

    @Test
    @DisplayName("POL-03 a key propagates to a remote boundary only if the policy lists it")
    void aKeyPropagatesToARemoteBoundaryOnlyIfThePolicyListsIt() {
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("X-Tenant").propagateTo(INTERNAL_CALL))
                .key(ORDER, rules -> rules.codec(Codecs.longValue()).wireName("X-Order").propagateTo(EXTERNAL_CALL, MESSAGE))
                .key(LOCALE, rules -> rules.codec(Codecs.locale()).wireName("X-Locale").propagateTo(ATTACHED, DETACHED))
                .build();

        assertThat(Stream.of(INTERNAL_CALL, EXTERNAL_CALL, MESSAGE).filter(boundary -> policy.propagates(TENANT, boundary)))
                .containsExactly(INTERNAL_CALL);
        assertThat(Stream.of(ATTACHED, DETACHED).filter(boundary -> policy.propagates(TENANT, boundary))).isEmpty();
        assertThat(Stream.of(INTERNAL_CALL, EXTERNAL_CALL, MESSAGE).filter(boundary -> policy.propagates(ORDER, boundary)))
                .containsExactly(EXTERNAL_CALL, MESSAGE);
        assertThat(Stream.of(INTERNAL_CALL, EXTERNAL_CALL, MESSAGE).filter(boundary -> policy.propagates(LOCALE, boundary)))
                .isEmpty();
    }

    @Test
    @DisplayName("POL-04 filter keeps exactly the bindings whose keys propagate to the boundary")
    void filterKeepsExactlyTheBindingsWhoseKeysPropagateToTheBoundary() {
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("X-Tenant").propagateTo(ATTACHED, INTERNAL_CALL))
                .build();
        ContextSnapshot snapshot = Context.with(TENANT, "t1").with(UNREGISTERED, "u1").call(Context::capture);

        ContextSnapshot internalCall = policy.filter(snapshot, INTERNAL_CALL);
        ContextSnapshot detached = policy.filter(snapshot, DETACHED);

        assertThat(internalCall.keys()).containsExactly(TENANT);
        assertThat(internalCall.find(TENANT)).contains("t1");
        assertThat(detached.keys()).containsExactly(UNREGISTERED);
        assertThat(detached.find(UNREGISTERED)).contains("u1");
    }

    @Test
    @DisplayName("CORE-03 filter rejects a null snapshot or boundary with NullPointerException")
    void filterRejectsANullSnapshotOrBoundary() {
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("X-Tenant").propagateTo(ATTACHED, INTERNAL_CALL))
                .build();

        assertThatNullPointerException().isThrownBy(() -> policy.filter(null, ATTACHED));
        // EMPTY, not a non-empty snapshot: propagates would throw for a bound key and hide a missing check in filter
        assertThatNullPointerException().isThrownBy(() -> policy.filter(ContextSnapshot.EMPTY, null));
    }

    /** A check that asserts {@code NullPointerException} at a call. */
    interface NullCheck {
        void verify();
    }

    /** The call itself throws. */
    private static NullCheck atCall(ThrowingCallable call) {
        return () -> assertThatNullPointerException().isThrownBy(call);
    }

    /**
     * The {@code KeyRules} call throws inside the rules consumer. The consumer sets a flag after its assertion, and the flag is checked
     * after {@code key} and {@code build}, so a consumer that never ran fails the check instead of passing it vacuously.
     */
    private static NullCheck insideRules(Consumer<ContextPolicy.KeyRules<String>> call) {
        return () -> {
            AtomicBoolean asserted = new AtomicBoolean();

            ContextPolicy.builder().key(TENANT, rules -> {
                assertThatNullPointerException().isThrownBy(() -> call.accept(rules));
                asserted.set(true);
            }).build();

            assertThat(asserted).isTrue();
        };
    }

    static Stream<Named<NullCheck>> nullArguments() {
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("X-Tenant").required().loggable()
                        .propagateTo(ATTACHED, INTERNAL_CALL))
                .build();
        return Stream.of(
                named("Builder.key(null, rules)", atCall(() -> ContextPolicy.builder().key(null, rules -> { }))),
                named("Builder.key(key, null)", atCall(() -> ContextPolicy.builder().key(TENANT, null))),
                named("KeyRules.codec(null)", insideRules(rules -> rules.codec(null))),
                named("KeyRules.wireName(null)", insideRules(rules -> rules.wireName(null))),
                named("KeyRules.propagateTo((Boundary[]) null)", insideRules(rules -> rules.propagateTo((Boundary[]) null))),
                named("KeyRules.propagateTo(ATTACHED, null)", insideRules(rules -> rules.propagateTo(ATTACHED, null))),
                named("propagates(null, ATTACHED)", atCall(() -> policy.propagates(null, ATTACHED))),
                named("propagates(registeredKey, null)", atCall(() -> policy.propagates(TENANT, null))),
                named("propagates(unregisteredKey, null)", atCall(() -> policy.propagates(UNREGISTERED, null))),
                named("isRequired(null)", atCall(() -> policy.isRequired(null))),
                named("isLoggable(null)", atCall(() -> policy.isLoggable(null))),
                named("wireName(null)", atCall(() -> policy.wireName(null))),
                named("codec(null)", atCall(() -> policy.codec(null))));
    }

    @ParameterizedTest
    @MethodSource("nullArguments")
    @DisplayName("CORE-03 the policy builder, KeyRules and queries reject null with NullPointerException")
    void thePolicyBuilderKeyRulesAndQueriesRejectNull(NullCheck check) {
        check.verify();
    }
}
