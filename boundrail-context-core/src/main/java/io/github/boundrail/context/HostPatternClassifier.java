package io.github.boundrail.context;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Classifies a request by its declared destination, else by its host against internal, then external host patterns (DEST-02).
 */
public final class HostPatternClassifier implements DestinationClassifier {

    /** An RFC 1123 label: 1 - 63 ASCII letters, digits and hyphens, not starting or ending with a hyphen (DEST-04). */
    private static final Pattern LABEL = Pattern.compile("[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?");

    private final List<String> internalHosts;
    private final List<String> externalHosts;

    private HostPatternClassifier(List<String> internalHosts, List<String> externalHosts) {
        this.internalHosts = internalHosts;
        this.externalHosts = externalHosts;
    }

    /**
     * {@return a classifier with copies of {@code internalHosts} and {@code externalHosts}}
     *
     * @throws IllegalArgumentException if a host pattern is not valid (DEST-04)
     */
    public static HostPatternClassifier of(List<String> internalHosts, List<String> externalHosts) {
        List<String> internal = List.copyOf(internalHosts);
        List<String> external = List.copyOf(externalHosts);
        internal.forEach(HostPatternClassifier::requireValid);
        external.forEach(HostPatternClassifier::requireValid);
        return new HostPatternClassifier(lowerCase(internal), lowerCase(external));
    }

    private static List<String> lowerCase(List<String> patterns) {
        return patterns.stream().map(pattern -> pattern.toLowerCase(Locale.ROOT)).toList();
    }

    /** Accepts an exact name, or {@code *.} followed by one, of at most 253 characters with no all-digit last label (DEST-04). */
    private static void requireValid(String pattern) {
        String name = pattern.startsWith("*.") ? pattern.substring(2) : pattern;
        String[] labels = name.split("\\.", -1);
        boolean valid = name.length() <= 253
                && Arrays.stream(labels).allMatch(label -> LABEL.matcher(label).matches())
                && !labels[labels.length - 1].matches("[0-9]+");
        if (!valid) {
            throw new IllegalArgumentException("Invalid host pattern: " + pattern);
        }
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
            boolean matches = pattern.startsWith("*.")
                    ? lowerHost.endsWith(pattern.substring(1))
                    : lowerHost.equals(pattern);
            if (matches) {
                return true;
            }
        }
        return false;
    }
}
