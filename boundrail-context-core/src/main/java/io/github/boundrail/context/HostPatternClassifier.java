package io.github.boundrail.context;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Classifies a request by its declared destination, else by its host against internal, then external host patterns (DEST-02).
 */
public final class HostPatternClassifier implements DestinationClassifier {

    private final List<String> internalHosts;
    private final List<String> externalHosts;

    private HostPatternClassifier(List<String> internalHosts, List<String> externalHosts) {
        this.internalHosts = internalHosts;
        this.externalHosts = externalHosts;
    }

    /** {@return a classifier with copies of {@code internalHosts} and {@code externalHosts}} */
    public static HostPatternClassifier of(List<String> internalHosts, List<String> externalHosts) {
        return new HostPatternClassifier(List.copyOf(internalHosts), List.copyOf(externalHosts));
    }

    /**
     * {@return the declared destination if present, else {@code INTERNAL} on an internal match, else {@code EXTERNAL} on an
     * external match, else {@code UNKNOWN}; a host-pattern match has no service name}
     */
    @Override
    public Destination classify(OutboundRequest request) {
        Objects.requireNonNull(request, "request");
        if (request.declared().isPresent()) {
            return request.declared().get();
        }
        String host = request.uri().getHost();
        if (host == null) {
            return new Destination(Destination.Kind.UNKNOWN, Optional.empty());
        }
        if (matchesAny(internalHosts, host)) {
            return new Destination(Destination.Kind.INTERNAL, Optional.empty());
        }
        if (matchesAny(externalHosts, host)) {
            return new Destination(Destination.Kind.EXTERNAL, Optional.empty());
        }
        return new Destination(Destination.Kind.UNKNOWN, Optional.empty());
    }

    /**
     * {@return whether {@code host} equals a pattern, or ends with {@code .suffix} for a {@code *.suffix} pattern, ignoring case;
     * a non-null URI host never starts with a dot, so a suffix match has at least one label before it}
     */
    private static boolean matchesAny(List<String> patterns, String host) {
        String lowerHost = host.toLowerCase(Locale.ROOT);
        for (String pattern : patterns) {
            String lowerPattern = pattern.toLowerCase(Locale.ROOT);
            boolean matches = lowerPattern.startsWith("*.")
                    ? lowerHost.endsWith(lowerPattern.substring(1))
                    : lowerHost.equals(lowerPattern);
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
