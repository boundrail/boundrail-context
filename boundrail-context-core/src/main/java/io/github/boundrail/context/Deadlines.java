package io.github.boundrail.context;

import java.util.Optional;

/** Access to the current deadline (DL-02). */
public final class Deadlines {

    private Deadlines() {
    }

    /** {@return the current deadline, equivalent to {@code BuiltInKeys.DEADLINE.find()} (DL-02)} */
    public static Optional<Deadline> current() {
        return BuiltInKeys.DEADLINE.find();
    }
}
