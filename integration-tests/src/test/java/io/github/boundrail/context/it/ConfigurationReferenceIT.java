package io.github.boundrail.context.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.boundrail.context.it.docs.ConfigurationReference;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ConfigurationReferenceIT {

    private static final String DEADLINES_SPEC = """
            # Feature Specification: Deadlines

            **Feature Branch**: `005-deadlines`

            ## Requirements

            **DL-03** Prose that mentions `deadline.max-inbound` and is not a table row.

            ### Configuration

            Properties use the prefix `boundrail.context.`.

            Every startup failure caused by invalid configuration also occurs under lazy initialization.

            | Property | Default | Meaning |
            |---|---|---|
            | `deadline.max-inbound` | `30s` | Cap on trusted inbound deadlines (DL-03) |
            | `deadline.default-inbound` | unset | Budget applied when no trusted deadline is present (DL-03) |

            ## Success Criteria

            - **SC-001**: Text after the table.
            """;

    private static final String CORE_SPEC = """
            # Feature Specification: Core and Runtimes

            ### Configuration

            Properties use the prefix `boundrail.context.`.

            | Property | Default | Meaning |
            |---|---|---|
            | `backend` (JVM system property) | `auto` | `auto` or `thread-local` (RT-04) |
            | `violations.mode` | `fail` | `fail` or `report` (DIAG-02) |

            ## Assumptions

            - Text after the table.
            """;

    @Test
    @DisplayName("REL-03 the reference has one section per feature in number order with full property names")
    void referenceHasOneSectionPerFeatureInNumberOrderWithFullPropertyNames() {
        Map<String, String> specs = new LinkedHashMap<>();
        specs.put("005-deadlines", DEADLINES_SPEC);
        specs.put("002-core-runtime", CORE_SPEC);

        String reference = ConfigurationReference.render(specs);

        assertThat(reference).isEqualTo("""
                # Configuration reference

                Generated from the Configuration tables in `specs/`; do not edit by hand.
                Regenerate with `./gradlew :integration-tests:generateConfigurationReference`.
                Property names in the Meaning column are relative to `boundrail.context.`.

                ## Core and Runtimes

                | Property | Default | Meaning |
                |---|---|---|
                | `boundrail.context.backend` (JVM system property) | `auto` | `auto` or `thread-local` (RT-04) |
                | `boundrail.context.violations.mode` | `fail` | `fail` or `report` (DIAG-02) |

                ## Deadlines

                | Property | Default | Meaning |
                |---|---|---|
                | `boundrail.context.deadline.max-inbound` | `30s` | Cap on trusted inbound deadlines (DL-03) |
                | `boundrail.context.deadline.default-inbound` | unset | Budget applied when no trusted deadline is present (DL-03) |
                """);
    }

    @Test
    @DisplayName("REL-03 a spec without a Configuration section is left out")
    void specWithoutConfigurationSectionIsLeftOut() {
        Map<String, String> specs = new LinkedHashMap<>();
        specs.put("001-foundation", """
                # Feature Specification: Foundation

                ## Requirements

                | Module | Purpose |
                |---|---|
                | `core` | JDK only |
                """);
        specs.put("002-core-runtime", CORE_SPEC);

        String reference = ConfigurationReference.render(specs);

        assertThat(reference).doesNotContain("Foundation", "| `core` |").contains("## Core and Runtimes");
    }

    @Test
    @DisplayName("REL-03 a spec with a Configuration section but no table fails generation, naming the spec")
    void specWithConfigurationSectionButNoTableFailsGeneration() {
        Map<String, String> specs = new LinkedHashMap<>();
        specs.put("002-core-runtime", CORE_SPEC);
        specs.put("005-deadlines", DEADLINES_SPEC.replace("| Property | Default | Meaning |", "| Name | Default | Meaning |"));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ConfigurationReference.render(specs))
                .withMessageContaining("specs/005-deadlines/spec.md");
    }

    @Test
    @DisplayName("REL-03 the committed docs/configuration.md matches the Configuration tables of specs 002 – 007")
    void committedReferenceMatchesTheSpecs() throws IOException {
        Path root = Path.of(System.getProperty("boundrail.it.repository-root"));
        Path reference = root.resolve("docs/configuration.md");
        String stale = "docs/configuration.md is not up to date with specs/; "
                + "regenerate it with ./gradlew :integration-tests:generateConfigurationReference";

        assertThat(reference).as(stale).exists();
        assertThat(reference).as(stale).hasContent(ConfigurationReference.generate(root));
    }
}
