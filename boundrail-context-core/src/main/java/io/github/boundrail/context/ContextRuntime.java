package io.github.boundrail.context;

import io.github.boundrail.context.internal.CaptureSiteProperties;
import io.github.boundrail.context.internal.ContextSlot;
import io.github.boundrail.context.internal.SlotFactory;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/** The runtime that holds the current snapshot in a single slot per thread of execution (RT-01). */
public final class ContextRuntime {

    /** The backends that can hold the current snapshot (RT-02). */
    public enum Backend {
        /** A {@link ThreadLocal} backend. */
        THREAD_LOCAL,
        /** A {@code ScopedValue} backend. */
        SCOPED_VALUE
    }

    /** When snapshots record the site where they were bound (DIAG-05, RT-08). */
    public enum CaptureSites {
        /** No capture sites are recorded. */
        OFF,
        /** Every binding records its capture site. */
        ON,
        /** A sampled fraction of bindings records its capture site. */
        SAMPLED
    }

    static final ContextSlot SLOT = SlotFactory.create();

    private static final CopyOnWriteArrayList<ScopeListener> LISTENERS = new CopyOnWriteArrayList<>();

    private static final System.Logger LOGGER = System.getLogger("io.github.boundrail.context");

    /** The capture-site mode and its sample rate, held together so that they always change together (RT-08). */
    private record CaptureSiteMode(CaptureSites mode, double sampleRate) {
    }

    private static volatile CaptureSiteMode captureSiteMode =
            new CaptureSiteMode(CaptureSiteProperties.mode(), CaptureSiteProperties.sampleRate());

    /** A restorer kept with the listener that returned it, so a failing restorer can be reported by its listener. */
    private record Entered(ScopeListener listener, ScopeListener.Restorer restorer) {
    }

    private ContextRuntime() {
    }

    /** {@return the active backend (RT-05)} */
    public static Backend backend() {
        return SLOT.backend();
    }

    /**
     * Changes the capture-site mode and its sample rate, JVM-wide (RT-08).
     *
     * @param mode the mode
     * @param sampleRate the fraction of bindings that record their capture site when the mode is {@code SAMPLED}, from 0.0 to 1.0
     * @throws NullPointerException if the mode is {@code null}
     * @throws IllegalArgumentException if the sample rate is NaN or outside 0.0 – 1.0; the mode is then unchanged
     */
    public static void captureSites(CaptureSites mode, double sampleRate) {
        Objects.requireNonNull(mode, "mode");
        if (!(sampleRate >= 0.0 && sampleRate <= 1.0)) {
            throw new IllegalArgumentException("sampleRate must be from 0.0 to 1.0: " + sampleRate);
        }
        captureSiteMode = new CaptureSiteMode(mode, sampleRate);
    }

    /** {@return the current capture-site mode (RT-08)} */
    public static CaptureSites captureSites() {
        return captureSiteMode.mode();
    }

    /** {@return the current sample rate (RT-08)} */
    public static double sampleRate() {
        return captureSiteMode.sampleRate();
    }

    /**
     * Registers a listener that is called whenever a scope is entered (RT-07). A listener that is already registered stays registered once,
     * in its original position.
     *
     * @param listener the listener
     * @throws NullPointerException if the listener is {@code null}
     */
    public static void addScopeListener(ScopeListener listener) {
        Objects.requireNonNull(listener, "listener");
        LISTENERS.addIfAbsent(listener);
    }

    /**
     * Removes a registered listener (RT-07). Removing a listener that is not registered does nothing.
     *
     * @param listener the listener
     * @throws NullPointerException if the listener is {@code null}
     */
    public static void removeScopeListener(ScopeListener listener) {
        Objects.requireNonNull(listener, "listener");
        LISTENERS.remove(listener);
    }

    /**
     * Calls an operation with a snapshot current, the single entry point for every scope (RT-07). Once the snapshot is current, each
     * listener is entered in registration order; when the operation returns or throws, their restorers run in reverse order, before the
     * previous snapshot is restored. A {@code RuntimeException} from a listener or a restorer is logged, naming the listener, and the next
     * one runs, so the operation's outcome does not change. A {@code null} restorer, or an {@code Error} from a listener, stops the entry: the
     * operation and later listeners do not run, the restorers already returned run in reverse order, and {@code NullPointerException} is
     * thrown or the {@code Error} propagates. An {@code Error} from a restorer propagates at once and the remaining restorers are skipped.
     */
    static <R, X extends Throwable> R enter(ContextSnapshot snapshot, ContextCallable<R, X> op) throws X {
        return SLOT.call(snapshot, () -> {
            if (LISTENERS.isEmpty()) {
                return op.call();
            }
            List<Entered> entered = new ArrayList<>();
            try {
                for (ScopeListener listener : LISTENERS) {
                    ScopeListener.Restorer restorer;
                    try {
                        restorer = listener.onEnter(snapshot);
                    } catch (RuntimeException e) {
                        LOGGER.log(System.Logger.Level.WARNING, "Scope listener " + listener.getClass().getName() + " failed on entry", e);
                        continue;
                    }
                    if (restorer == null) {
                        throw new NullPointerException("Scope listener " + listener.getClass().getName() + " returned a null restorer");
                    }
                    entered.add(new Entered(listener, restorer));
                }
                return op.call();
            } finally {
                for (int i = entered.size() - 1; i >= 0; i--) {
                    Entered scope = entered.get(i);
                    try {
                        scope.restorer().restore();
                    } catch (RuntimeException e) {
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Restorer of scope listener " + scope.listener().getClass().getName() + " failed", e);
                    }
                }
            }
        });
    }
}
