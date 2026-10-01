package io.github.boundrail.context;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.DETACHED;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Boundary.MESSAGE;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BuiltInKeysTest {

    @Test
    @DisplayName("CORE-01 built-in keys use their reserved names")
    void builtInKeysUseTheirReservedNames() {
        assertThat(BuiltInKeys.DEADLINE.name()).isEqualTo("boundrail.deadline");
        assertThat(BuiltInKeys.DEADLINE.type()).isEqualTo(Deadline.class);
        assertThat(BuiltInKeys.AUTHORITY.name()).isEqualTo("boundrail.authority");
        assertThat(BuiltInKeys.AUTHORITY.type()).isEqualTo(Authority.class);
        assertThat(BuiltInKeys.INITIATOR.name()).isEqualTo("boundrail.initiator");
        assertThat(BuiltInKeys.INITIATOR.type()).isEqualTo(Initiator.class);
    }

    @Test
    @DisplayName("DL-02 Deadlines.current is equivalent to DEADLINE.find")
    void deadlinesCurrentIsEquivalentToDeadlineFind() {
        assertThat(Deadlines.current()).isEmpty().isEqualTo(BuiltInKeys.DEADLINE.find());

        Deadline deadline = Deadline.after(Duration.ofHours(1));
        List<Optional<Deadline>> current = new ArrayList<>();
        List<Optional<Deadline>> found = new ArrayList<>();
        Context.with(BuiltInKeys.DEADLINE, deadline).run(() -> {
            current.add(Deadlines.current());
            found.add(BuiltInKeys.DEADLINE.find());
        });

        assertThat(current).containsExactly(Optional.of(deadline));
        assertThat(found).containsExactly(Optional.of(deadline));
    }

    @Test
    @DisplayName("DL-02 DEADLINE uses the built-in default policy")
    void deadlineUsesTheBuiltInDefaultPolicy() {
        ContextPolicy policy = BuiltInKeys.defaultPolicy();

        assertThat(Stream.of(Boundary.values()).filter(boundary -> policy.propagates(BuiltInKeys.DEADLINE, boundary)))
                .containsExactly(ATTACHED, INTERNAL_CALL);
        assertThat(policy.isRequired(BuiltInKeys.DEADLINE)).isFalse();
        assertThat(policy.isLoggable(BuiltInKeys.DEADLINE)).isFalse();
        assertThat(policy.wireName(BuiltInKeys.DEADLINE)).contains("Boundrail-Deadline-Ms");
    }

    @Test
    @DisplayName("POL-06 AUTHORITY uses the built-in default policy")
    void authorityUsesTheBuiltInDefaultPolicy() {
        ContextPolicy policy = BuiltInKeys.defaultPolicy();

        assertThat(Stream.of(Boundary.values()).filter(boundary -> policy.propagates(BuiltInKeys.AUTHORITY, boundary)))
                .containsExactly(ATTACHED, INTERNAL_CALL);
        assertThat(policy.isRequired(BuiltInKeys.AUTHORITY)).isFalse();
        assertThat(policy.isLoggable(BuiltInKeys.AUTHORITY)).isFalse();
        assertThat(policy.wireName(BuiltInKeys.AUTHORITY)).isEmpty();
    }

    @Test
    @DisplayName("POL-06 INITIATOR uses the built-in default policy")
    void initiatorUsesTheBuiltInDefaultPolicy() {
        ContextPolicy policy = BuiltInKeys.defaultPolicy();

        assertThat(Stream.of(Boundary.values()).filter(boundary -> policy.propagates(BuiltInKeys.INITIATOR, boundary)))
                .containsExactly(ATTACHED, DETACHED, INTERNAL_CALL, MESSAGE);
        assertThat(policy.isRequired(BuiltInKeys.INITIATOR)).isFalse();
        assertThat(policy.isLoggable(BuiltInKeys.INITIATOR)).isTrue();
        assertThat(policy.wireName(BuiltInKeys.INITIATOR)).contains("Boundrail-Initiator");
    }

    @Test
    @DisplayName("POL-06 defaultPolicy holds only the built-in rules")
    void defaultPolicyHoldsOnlyTheBuiltInRules() {
        ContextKey<String> tenant = ContextKey.of("acme.tenant", String.class);
        ContextPolicy policy = BuiltInKeys.defaultPolicy();

        assertThat(Stream.of(Boundary.values()).filter(boundary -> policy.propagates(tenant, boundary)))
                .containsExactly(ATTACHED, DETACHED);
        assertThat(policy.isRequired(tenant)).isFalse();
        assertThat(policy.isLoggable(tenant)).isFalse();
        assertThat(policy.wireName(tenant)).isEmpty();
        assertThat(policy.codec(tenant)).isEmpty();
    }

    @Test
    @DisplayName("POL-03 the built-in rules have no codec and build although they list remote boundaries")
    void theBuiltInRulesHaveNoCodecAndBuildAlthoughTheyListRemoteBoundaries() {
        ContextPolicy policy = BuiltInKeys.defaultPolicy();

        assertThat(policy.propagates(BuiltInKeys.DEADLINE, INTERNAL_CALL)).isTrue();
        assertThat(policy.propagates(BuiltInKeys.AUTHORITY, INTERNAL_CALL)).isTrue();
        assertThat(policy.propagates(BuiltInKeys.INITIATOR, MESSAGE)).isTrue();
        assertThat(policy.wireName(BuiltInKeys.DEADLINE)).contains("Boundrail-Deadline-Ms");
        assertThat(policy.wireName(BuiltInKeys.INITIATOR)).contains("Boundrail-Initiator");
        assertThat(policy.codec(BuiltInKeys.DEADLINE)).isEmpty();
        assertThat(policy.codec(BuiltInKeys.AUTHORITY)).isEmpty();
        assertThat(policy.codec(BuiltInKeys.INITIATOR)).isEmpty();
    }
}
