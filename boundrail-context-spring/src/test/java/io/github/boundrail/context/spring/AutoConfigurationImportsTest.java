package io.github.boundrail.context.spring;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.annotation.ImportCandidates;

class AutoConfigurationImportsTest {

    @Test
    @DisplayName("POL-07 the auto-configuration is registered for Spring Boot")
    void theAutoConfigurationIsRegisteredForSpringBoot() {
        ImportCandidates candidates = ImportCandidates.load(AutoConfiguration.class, getClass().getClassLoader());

        assertThat(candidates.getCandidates()).contains(BoundrailContextAutoConfiguration.class.getName());
    }
}
