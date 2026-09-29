package io.github.boundrail.context.it.topology;

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

    private final Map<String, List<Map<String, String>>> observations = new ConcurrentHashMap<>();

    public void record(String service, Map<String, String> observation) {
        observations.computeIfAbsent(service, name -> new CopyOnWriteArrayList<>()).add(Map.copyOf(observation));
    }

    /** The observations of one service, oldest first. */
    public List<Map<String, String>> of(String service) {
        return List.copyOf(observations.getOrDefault(service, List.of()));
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
