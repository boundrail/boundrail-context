package io.github.boundrail.context;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Boundary.MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.junit.jupiter.api.Named.named;

import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class ContextPolicyValidationTest {

    private static final ContextKey<String> TENANT = ContextKey.of("acme.tenant", String.class);
    private static final ContextKey<String> REGION = ContextKey.of("acme.region", String.class);

    private static final EqualStringCodec CODEC = new EqualStringCodec();

    /**
     * The rules every re-registration in the conflict cases differs from in exactly one respect. The codec is an {@link EqualStringCodec},
     * so a comparison with {@code equals} instead of instance identity accepts "another codec instance".
     */
    private static final Consumer<ContextPolicy.KeyRules<String>> TENANT_RULES =
            rules -> rules.codec(CODEC).wireName("X-Tenant").propagateTo(INTERNAL_CALL);

    /**
     * A codec whose {@code equals} treats every instance as equal, so two instances are equal but not the same instance. POL-05 compares
     * codecs by instance.
     */
    private static final class EqualStringCodec implements ContextCodec<String> {

        @Override
        public String encode(String value) {
            return value;
        }

        @Override
        public String decode(String text) {
            return text;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof EqualStringCodec;
        }

        @Override
        public int hashCode() {
            return EqualStringCodec.class.hashCode();
        }
    }

    @ParameterizedTest
    @EnumSource(names = {"INTERNAL_CALL", "EXTERNAL_CALL", "MESSAGE"})
    @DisplayName("POL-03 build fails when an application key lists a remote boundary without a codec")
    void buildFailsWhenAKeyListsARemoteBoundaryWithoutACodec(Boundary remote) {
        ContextPolicy.Builder builder = ContextPolicy.builder().key(TENANT, rules -> rules.wireName("X-Tenant").propagateTo(remote));

        assertThatIllegalStateException().isThrownBy(builder::build);
    }

    @ParameterizedTest
    @EnumSource(names = {"INTERNAL_CALL", "EXTERNAL_CALL", "MESSAGE"})
    @DisplayName("POL-03 build fails when an application key lists a remote boundary without a wire name")
    void buildFailsWhenAKeyListsARemoteBoundaryWithoutAWireName(Boundary remote) {
        ContextPolicy.Builder builder = ContextPolicy.builder().key(TENANT, rules -> rules.codec(Codecs.string()).propagateTo(remote));

        assertThatIllegalStateException().isThrownBy(builder::build);
    }

    @Test
    @DisplayName("POL-05 build fails on two different keys with the same name")
    void buildFailsOnTwoDifferentKeysWithTheSameName() {
        ContextKey<String> otherTenant = ContextKey.of("acme.tenant", String.class);
        ContextPolicy.Builder builder = ContextPolicy.builder()
                .key(TENANT, TENANT_RULES)
                .key(otherTenant, rules -> rules.codec(Codecs.string()).wireName("X-Other-Tenant").propagateTo(INTERNAL_CALL));

        assertThatIllegalStateException().isThrownBy(builder::build);
    }

    @Test
    @DisplayName("POL-05 build fails on two different keys with wire names equal ignoring case")
    void buildFailsOnTwoDifferentKeysWithWireNamesEqualIgnoringCase() {
        ContextPolicy.Builder builder = ContextPolicy.builder()
                .key(TENANT, TENANT_RULES)
                .key(REGION, rules -> rules.codec(Codecs.string()).wireName("x-tenant").propagateTo(INTERNAL_CALL));

        assertThatIllegalStateException().isThrownBy(builder::build);
    }

    @Test
    @DisplayName("POL-05 build accepts one key registered twice with equal rules")
    void buildAcceptsOneKeyRegisteredTwiceWithEqualRules() {
        ContextPolicy policy = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("X-Tenant").required().loggable()
                        .propagateTo(ATTACHED, INTERNAL_CALL))
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("X-Tenant").required().loggable()
                        .propagateTo(INTERNAL_CALL, ATTACHED))
                .build();

        assertThat(policy.codec(TENANT)).containsSame(Codecs.string());
        assertThat(policy.wireName(TENANT)).contains("X-Tenant");
        assertThat(policy.isRequired(TENANT)).isTrue();
        assertThat(policy.isLoggable(TENANT)).isTrue();
        assertThat(Stream.of(Boundary.values()).filter(boundary -> policy.propagates(TENANT, boundary)))
                .containsExactly(ATTACHED, INTERNAL_CALL);
    }

    static Stream<Named<Consumer<ContextPolicy.KeyRules<String>>>> conflictingRules() {
        return Stream.of(
                named("another codec instance",
                        rules -> rules.codec(new EqualStringCodec()).wireName("X-Tenant").propagateTo(INTERNAL_CALL)),
                named("a wire name differing only in case", rules -> rules.codec(CODEC).wireName("x-tenant").propagateTo(INTERNAL_CALL)),
                named("a different required flag", rules -> rules.codec(CODEC).wireName("X-Tenant").required().propagateTo(INTERNAL_CALL)),
                named("a different loggable flag", rules -> rules.codec(CODEC).wireName("X-Tenant").loggable().propagateTo(INTERNAL_CALL)),
                named("a different boundary set", rules -> rules.codec(CODEC).wireName("X-Tenant").propagateTo(INTERNAL_CALL, MESSAGE)));
    }

    @ParameterizedTest
    @MethodSource("conflictingRules")
    @DisplayName("POL-05 build fails when one key is registered with conflicting rules")
    void buildFailsWhenOneKeyIsRegisteredWithConflictingRules(Consumer<ContextPolicy.KeyRules<String>> conflicting) {
        ContextPolicy.Builder builder = ContextPolicy.builder().key(TENANT, TENANT_RULES).key(TENANT, conflicting);

        assertThatIllegalStateException().isThrownBy(builder::build);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Authorization", "authorization", "AUTHORIZATION", "aUTHORIZATIOn"})
    @DisplayName("POL-09 build fails on the wire name Authorization in any case")
    void buildFailsOnTheWireNameAuthorizationInAnyCase(String wireName) {
        ContextPolicy.Builder builder = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName(wireName).propagateTo(INTERNAL_CALL));

        assertThatIllegalStateException().isThrownBy(builder::build);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Boundrail-Tenant", "boundrail-tenant", "BOUNDRAIL-TENANT", "bOUNDRAIL-Tenant", "Boundrail-"})
    @DisplayName("POL-09 build fails on a wire name starting with Boundrail- in any case")
    void buildFailsOnAWireNameStartingWithBoundrailInAnyCase(String wireName) {
        ContextPolicy.Builder builder = ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(Codecs.string()).wireName(wireName).propagateTo(INTERNAL_CALL));

        assertThatIllegalStateException().isThrownBy(builder::build);
    }
}
