package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
}
