package io.github.boundrail.context.it.topology;

import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextSnapshot;
import java.util.HashMap;
import java.util.Map;
import org.springframework.scheduling.annotation.Scheduled;

/** Edge's scheduled job (bean {@code cleanupJob}): each run records every key bound in its snapshot to {@link ObservedContext} (IT-1.7). */
public class CleanupJob {

    /** The service name its observations are recorded under. */
    public static final String OBSERVED_AS = "edge.cleanupJob";

    private final ObservedContext observed;

    public CleanupJob(ObservedContext observed) {
        this.observed = observed;
    }

    /** Records each bound key's name and value ({@code toString()}). */
    @Scheduled(fixedDelay = 200)
    public void run() {
        ContextSnapshot snapshot = Context.capture();
        Map<String, String> bound = new HashMap<>();
        for (ContextKey<?> key : snapshot.keys()) {
            bound.put(key.name(), snapshot.find(key).orElseThrow().toString());
        }
        observed.record(OBSERVED_AS, bound);
    }
}
