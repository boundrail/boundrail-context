package io.github.boundrail.context.test;

import io.github.boundrail.context.spring.ViolationListener;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Bean;

/**
 * The test module's only auto-configuration: a {@code ViolationListener} that hands every violation the application context reports to the
 * JVM-wide recorder of {@code @BoundrailTest} (TEST-02, DIAG-11). The test verifier and {@code peer()} are not auto-configured (TEST-01).
 */
@AutoConfiguration
class BoundrailTestAutoConfiguration {

    @Bean
    ViolationListener boundrailTestViolationRecorder() {
        return ViolationRecorder::record;
    }
}
