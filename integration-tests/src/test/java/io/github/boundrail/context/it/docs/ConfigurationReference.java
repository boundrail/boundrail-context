package io.github.boundrail.context.it.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Renders {@code docs/configuration.md} from the Configuration tables of the feature specs (REL-03). */
public final class ConfigurationReference {

    private static final String SECTION = "### Configuration";
    private static final String TABLE_HEADER = "| Property | Default | Meaning |";

    private ConfigurationReference() {}

    /** Writes {@code docs/configuration.md} under the repository root given as the only argument. */
    public static void main(String[] args) throws IOException {
        Path root = Path.of(args[0]);
        Files.writeString(root.resolve("docs/configuration.md"), generate(root));
    }

    /** Renders the reference from the {@code spec.md} of every feature folder under {@code specs/}. */
    public static String generate(Path repositoryRoot) throws IOException {
        try (Stream<Path> features = Files.list(repositoryRoot.resolve("specs"))) {
            Map<String, String> specs = features
                    .filter(feature -> Files.isRegularFile(feature.resolve("spec.md")))
                    .collect(Collectors.toMap(feature -> feature.getFileName().toString(), ConfigurationReference::readSpec));
            return render(specs);
        }
    }

    private static String readSpec(Path feature) {
        try {
            return Files.readString(feature.resolve("spec.md"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Renders the reference from spec texts keyed by feature folder name, such as {@code 005-deadlines}. */
    public static String render(Map<String, String> specsByFeature) {
        StringBuilder out = new StringBuilder("""
                # Configuration reference

                Generated from the Configuration tables in `specs/`; do not edit by hand.
                Regenerate with `./gradlew :integration-tests:generateConfigurationReference`.
                Property names in the Meaning column are relative to `boundrail.context.`.
                """);
        new TreeMap<>(specsByFeature).forEach((feature, spec) -> {
            List<String> lines = spec.lines().toList();
            if (!lines.contains(SECTION)) {
                return;
            }
            List<String> table = table(lines);
            if (table.isEmpty()) {
                throw new IllegalArgumentException(
                        "specs/" + feature + "/spec.md has a Configuration section but no " + TABLE_HEADER + " table");
            }
            out.append("\n## ").append(title(lines)).append("\n\n");
            table.forEach(line -> out.append(line).append('\n'));
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
