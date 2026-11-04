package io.github.boundrail.context;

import java.time.Duration;
import java.util.Objects;

/**
 * An immutable point in time on the monotonic clock ({@link System#nanoTime()}) by which work should finish (DL-01).
 *
 * <p>Budgets are capped at {@code Duration.ofNanos(Long.MAX_VALUE / 2)}, so the difference between any two instants or
 * {@code nanoTime()} readings fits in a {@code long}. Instants are only compared through their difference, which stays
 * correct when {@code now + budget} wraps past {@link Long#MAX_VALUE}.
 */
public final class Deadline {

    private static final long MAX_BUDGET_NANOS = Long.MAX_VALUE / 2;
    private static final Duration MAX_BUDGET = Duration.ofNanos(MAX_BUDGET_NANOS);

    private final long instant;

    private Deadline(long instant) {
        this.instant = instant;
    }

    /**
     * {@return a deadline {@code budget} from now; zero or negative is already expired, and the budget is capped}
     *
     * @param budget the time from now
     */
    public static Deadline after(Duration budget) {
        Objects.requireNonNull(budget, "budget");
        long nanos;
        if (budget.isNegative() || budget.isZero()) {
            nanos = 0;
        } else if (budget.compareTo(MAX_BUDGET) >= 0) {
            nanos = MAX_BUDGET_NANOS;
        } else {
            nanos = budget.toNanos();
        }
        return new Deadline(System.nanoTime() + nanos);
    }

    /** {@return the time left, never negative} */
    public Duration remaining() {
        return Duration.ofNanos(Math.max(0, instant - System.nanoTime()));
    }

    /** {@return whether no time is left} */
    public boolean isExpired() {
        return instant - System.nanoTime() <= 0;
    }

    /** Throws {@link DeadlineExceededException} with stage {@code APPLICATION} if this deadline has expired. */
    public void check() {
        if (isExpired()) {
            throw new DeadlineExceededException(DeadlineExceededException.Stage.APPLICATION);
        }
    }

    /**
     * {@return whichever of this and {@code other} expires first; this one on a tie}
     *
     * @param other the deadline to compare with
     */
    public Deadline earliest(Deadline other) {
        Objects.requireNonNull(other, "other");
        return other.instant - instant < 0 ? other : this;
    }

    /**
     * {@return {@code earliest(Deadline.after(max))}}
     *
     * @param max the longest time from now
     */
    public Deadline within(Duration max) {
        Objects.requireNonNull(max, "max");
        return earliest(after(max));
    }
}
