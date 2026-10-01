package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DeadlineScopeTest {

    private static final ContextKey<String> TENANT = ContextKey.of("acme.tenant", String.class);

    /** The deadline current inside a scope that binds {@code bound} within a scope whose deadline is {@code current}. */
    private static Deadline boundWithin(Deadline current, Deadline bound) {
        List<Deadline> inside = new ArrayList<>();
        Context.with(BuiltInKeys.DEADLINE, current).run(
                () -> Context.with(BuiltInKeys.DEADLINE, bound).run(() -> inside.add(BuiltInKeys.DEADLINE.get())));
        return inside.getFirst();
    }

    @Test
    @DisplayName("DL-10 binding a later deadline keeps the current one")
    void bindingALaterDeadlineKeepsTheCurrentOne() {
        Deadline current = Deadline.after(Duration.ofHours(1));
        Deadline later = Deadline.after(Duration.ofHours(2));

        assertThat(boundWithin(current, later)).isSameAs(current);
    }

    @Test
    @DisplayName("DL-10 binding an earlier deadline shortens it")
    void bindingAnEarlierDeadlineShortensIt() {
        Deadline current = Deadline.after(Duration.ofHours(2));
        Deadline earlier = Deadline.after(Duration.ofHours(1));

        assertThat(boundWithin(current, earlier)).isSameAs(earlier);
    }

    @Test
    @DisplayName("DL-10 binding a deadline with none current binds it")
    void bindingADeadlineWithNoneCurrentBindsIt() {
        Deadline deadline = Deadline.after(Duration.ofHours(1));
        List<Deadline> inside = new ArrayList<>();

        Context.with(BuiltInKeys.DEADLINE, deadline).run(() -> inside.add(BuiltInKeys.DEADLINE.get()));

        assertThat(inside).containsExactly(deadline);
    }

    @Test
    @DisplayName("DL-10 only the last change to DEADLINE in a binding applies")
    void onlyTheLastChangeToDeadlineInABindingApplies() {
        Deadline current = Deadline.after(Duration.ofHours(3));
        Deadline a = Deadline.after(Duration.ofHours(1));
        Deadline b = Deadline.after(Duration.ofHours(2));
        ContextBinding binding = Context.with(BuiltInKeys.DEADLINE, a).with(BuiltInKeys.DEADLINE, b);
        List<Deadline> withCurrent = new ArrayList<>();
        List<Deadline> withNoneCurrent = new ArrayList<>();

        Context.with(BuiltInKeys.DEADLINE, current).run(() -> binding.run(() -> withCurrent.add(BuiltInKeys.DEADLINE.get())));
        binding.run(() -> withNoneCurrent.add(BuiltInKeys.DEADLINE.get()));

        assertThat(withCurrent.getFirst()).isSameAs(current.earliest(b)).isSameAs(b);
        assertThat(withNoneCurrent.getFirst()).isSameAs(b);
    }

    @Test
    @DisplayName("DL-11 Context.without(DEADLINE) throws IllegalArgumentException")
    void contextWithoutDeadlineThrows() {
        assertThat(Deadlines.current()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> Context.without(BuiltInKeys.DEADLINE));

        List<Throwable> inside = new ArrayList<>();
        Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofHours(1))).run(() -> {
            try {
                Context.without(BuiltInKeys.DEADLINE);
            } catch (IllegalArgumentException expected) {
                inside.add(expected);
            }
        });
        assertThat(inside).hasSize(1);
    }

    @Test
    @DisplayName("DL-11 ContextBinding.without(DEADLINE) throws IllegalArgumentException")
    void contextBindingWithoutDeadlineThrows() {
        ContextBinding binding = Context.with(TENANT, "t1");

        assertThat(Deadlines.current()).isEmpty();
        assertThatIllegalArgumentException().isThrownBy(() -> binding.without(BuiltInKeys.DEADLINE));

        List<Throwable> inside = new ArrayList<>();
        Context.with(BuiltInKeys.DEADLINE, Deadline.after(Duration.ofHours(1))).run(() -> {
            try {
                binding.without(BuiltInKeys.DEADLINE);
            } catch (IllegalArgumentException expected) {
                inside.add(expected);
            }
        });
        assertThat(inside).hasSize(1);
    }
}
