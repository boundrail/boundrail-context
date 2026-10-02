package io.github.boundrail.context.it.topology;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import org.springframework.http.HttpHeaders;

/**
 * Shared probe: every service records what it observed for each request, keyed by service name. Observations are plain
 * {@code Map<String, String>} so the topology does not depend on the library API.
 */
public final class ObservedContext {

    /** Observation entry holding the {@code tenant} the handler found bound; absent if unbound. */
    public static final String TENANT = "ctx.tenant";
    /** Observation entry holding the {@code priority} the handler found bound; absent if unbound. */
    public static final String PRIORITY = "ctx.priority";

    private final Map<String, List<Map<String, String>>> observations = new ConcurrentHashMap<>();

    public void record(String service, Map<String, String> observation) {
        observations.computeIfAbsent(service, name -> new CopyOnWriteArrayList<>()).add(Map.copyOf(observation));
    }

    /** The observations of one service, oldest first. */
    public List<Map<String, String>> of(String service) {
        return List.copyOf(observations.getOrDefault(service, List.of()));
    }

    /** What a {@code /ping} handler observed: its request headers, and the {@code tenant} and {@code priority} it found bound. */
    static Map<String, String> ping(HttpHeaders headers) {
        Map<String, String> observation = new HashMap<>(headers(headers));
        AppKeys.TENANT.find().ifPresent(tenant -> observation.put(TENANT, tenant));
        AppKeys.PRIORITY.find().ifPresent(priority -> observation.put(PRIORITY, priority.toString()));
        return observation;
    }

    /** Request headers as an observation: lower-case names, multiple values joined with a comma. */
    static Map<String, String> headers(HttpHeaders headers) {
        return headers.headerSet().stream()
                .collect(Collectors.toMap(
                        header -> header.getKey().toLowerCase(Locale.ROOT),
                        header -> String.join(",", header.getValue()),
                        (first, second) -> first + "," + second));
    }
}
