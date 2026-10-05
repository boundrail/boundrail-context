package io.github.boundrail.context.test;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Turns capture sites {@code on} for the annotated test class and restores the previous mode and sample rate after its last test, and
 * records every violation reported in any application context in the JVM during each test method, exposed as a {@link RecordedViolations}
 * parameter (TEST-02). It does not change the violation mode, which stays each application's {@code boundrail.context.violations.mode}.
 *
 * <p>The recorder is JVM-wide, so running {@code @BoundrailTest} test methods in parallel is not supported.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@ExtendWith(BoundrailTestExtension.class)
public @interface BoundrailTest {
}
