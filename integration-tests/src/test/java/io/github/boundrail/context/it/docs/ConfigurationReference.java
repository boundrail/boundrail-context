package io.github.boundrail.context.it.docs;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Renders {@code docs/configuration.md} from the Configuration tables of the feature specs (REL-03). */
public final class ConfigurationReference {

    private static final String TABLE_HEADER = "| Property | Default | Meaning |";

    private ConfigurationReference() {}

    /** Renders the reference from spec texts keyed by feature folder name, such as {@code 005-deadlines}. */
    public static String render(Map<String, String> specsByFeature) {
        StringBuilder out = new StringBuilder("""
                # Configuration reference

                Generated from the Configuration tables in `specs/`; do not edit by hand. \
                Regenerate with `./gradlew :integration-tests:generateConfigurationReference`.
                """);
        new TreeMap<>(specsByFeature).values().forEach(spec -> {
            List<String> lines = spec.lines().toList();
            out.append("\n## ").append(title(lines)).append("\n\n");
            table(lines).forEach(line -> out.append(line).append('\n'));
        });
        return out.toString();
    }

    private static String title(List<String> lines) {
        String heading = lines.stream().filter(line -> line.startsWith("# ")).findFirst().orElseThrow();
        return heading.substring(2).replaceFirst("^Feature Specification: ", "");
    }

    /** The table, with each property's full name; only rows start with a code span, not the header or the separator. */
    private static List<String> table(List<String> lines) {
        return lines.stream()
                .dropWhile(line -> !line.equals(TABLE_HEADER))
                .takeWhile(line -> line.startsWith("|"))
                .map(line -> line.replaceFirst("^\\| `", "| `boundrail.context."))
                .toList();
    }
}
