package io.github.boundrail.context;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DeadlineTest {

    private static final Duration ONE_HOUR = Duration.ofHours(1);
    private static final Duration ONE_MINUTE = Duration.ofMinutes(1);
    private static final Duration MAX_BUDGET = Duration.ofNanos(4_611_686_018_427_387_903L);
    private static final Duration FOREVER = ChronoUnit.FOREVER.getDuration();

    @Test
    @DisplayName("DL-01 remaining is never negative")
    void remainingIsNeverNegative() {
        assertThat(Deadline.after(Duration.ZERO).remaining()).isEqualTo(Duration.ZERO);
        assertThat(Deadline.after(Duration.ofSeconds(-5)).remaining()).isEqualTo(Duration.ZERO);
        assertThat(Deadline.after(Duration.ofNanos(-1)).remaining()).isEqualTo(Duration.ZERO);
        assertThat(Deadline.after(ONE_HOUR).remaining()).isPositive().isLessThanOrEqualTo(ONE_HOUR);
    }

    @Test
    @DisplayName("DL-01 a zero or negative duration gives an already expired deadline")
    void aZeroOrNegativeDurationGivesAnAlreadyExpiredDeadline() {
        assertThat(Deadline.after(Duration.ZERO).isExpired()).isTrue();
        assertThat(Deadline.after(Duration.ofNanos(-1)).isExpired()).isTrue();
        assertThat(Deadline.after(Duration.ofHours(-1)).isExpired()).isTrue();

        Deadline longOne = Deadline.after(ONE_HOUR);
        assertThat(longOne.within(Duration.ZERO).isExpired()).isTrue();
        assertThat(longOne.within(Duration.ofNanos(-1)).isExpired()).isTrue();
        assertThat(longOne.within(Duration.ofHours(-1)).isExpired()).isTrue();
    }

    @Test
    @DisplayName("DL-01 a long budget is not expired")
    void aLongBudgetIsNotExpired() {
        assertThat(Deadline.after(ONE_HOUR).isExpired()).isFalse();
    }

    @Test
    @DisplayName("DL-01 check throws DeadlineExceededException with stage APPLICATION when expired")
    void checkThrowsWithStageApplicationWhenExpired() {
        Deadline expired = Deadline.after(Duration.ZERO);

        assertThatExceptionOfType(DeadlineExceededException.class)
                .isThrownBy(expired::check)
                .satisfies(e -> assertThat(e.stage()).isEqualTo(DeadlineExceededException.Stage.APPLICATION));
    }

    @Test
    @DisplayName("DL-01 check passes when not expired")
    void checkPassesWhenNotExpired() {
        assertThatCode(Deadline.after(ONE_HOUR)::check).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("DL-01 earliest returns the deadline that expires first")
    void earliestReturnsTheDeadlineThatExpiresFirst() {
        Deadline sooner = Deadline.after(ONE_MINUTE);
        Deadline later = Deadline.after(ONE_HOUR);

        assertThat(sooner.earliest(later)).isSameAs(sooner);
        assertThat(later.earliest(sooner)).isSameAs(sooner);
    }

    @Test
    @DisplayName("DL-01 within returns this when max ends later")
    void withinReturnsThisWhenMaxEndsLater() {
        Deadline deadline = Deadline.after(ONE_MINUTE);

        assertThat(deadline.within(ONE_HOUR)).isSameAs(deadline);
    }

    @Test
    @DisplayName("DL-01 within returns a deadline at most max away when max ends sooner")
    void withinReturnsADeadlineAtMostMaxAwayWhenMaxEndsSooner() {
        Deadline deadline = Deadline.after(ONE_HOUR);

        Deadline within = deadline.within(ONE_MINUTE);

        assertThat(within).isNotSameAs(deadline);
        assertThat(within.remaining()).isPositive().isLessThanOrEqualTo(ONE_MINUTE);
    }

    static Stream<Duration> budgetsAtOrAboveTheMaximum() {
        return Stream.of(Duration.ofNanos(Long.MAX_VALUE / 2), Duration.ofNanos(Long.MAX_VALUE), FOREVER);
    }

    @ParameterizedTest
    @MethodSource("budgetsAtOrAboveTheMaximum")
    @DisplayName("DL-01 after caps a budget at or above the maximum budget")
    void afterCapsABudgetAtOrAboveTheMaximumBudget(Duration budget) {
        Deadline deadline = Deadline.after(budget);

        assertThat(deadline.remaining())
                .isLessThanOrEqualTo(MAX_BUDGET)
                .isGreaterThanOrEqualTo(MAX_BUDGET.minus(ONE_MINUTE));
        assertThat(deadline.isExpired()).isFalse();
    }

    @Test
    @DisplayName("DL-01 within a budget at or above the maximum keeps the instant of this")
    void withinABudgetAtOrAboveTheMaximumKeepsTheInstantOfThis() {
        Deadline oneHour = Deadline.after(ONE_HOUR);
        assertThat(oneHour.within(FOREVER)).isSameAs(oneHour);

        Deadline capped = Deadline.after(FOREVER);
        Duration cappedRemaining = capped.remaining();
        Deadline within = capped.within(FOREVER);
        assertThat(within.remaining()).isLessThanOrEqualTo(cappedRemaining);
    }

    @Test
    @DisplayName("DL-01 a negative budget whose nanoseconds overflow gives an expired deadline")
    void aNegativeBudgetWhoseNanosecondsOverflowGivesAnExpiredDeadline() {
        Deadline viaAfter = Deadline.after(FOREVER.negated());
        assertThat(viaAfter.isExpired()).isTrue();
        assertThat(viaAfter.remaining()).isEqualTo(Duration.ZERO);

        Deadline viaWithin = Deadline.after(ONE_HOUR).within(FOREVER.negated());
        assertThat(viaWithin.isExpired()).isTrue();
        assertThat(viaWithin.remaining()).isEqualTo(Duration.ZERO);
    }

    @Test
    @DisplayName("CORE-03 after, earliest and within reject null with NullPointerException")
    void afterEarliestAndWithinRejectNull() {
        Deadline deadline = Deadline.after(ONE_HOUR);

        assertThatNullPointerException().isThrownBy(() -> Deadline.after(null));
        assertThatNullPointerException().isThrownBy(() -> deadline.earliest(null));
        assertThatNullPointerException().isThrownBy(() -> deadline.within(null));
    }
}
