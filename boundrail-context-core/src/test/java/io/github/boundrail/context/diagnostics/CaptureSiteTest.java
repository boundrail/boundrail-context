package io.github.boundrail.context.diagnostics;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextRuntime;
import io.github.boundrail.context.ContextRuntime.CaptureSites;
import io.github.boundrail.context.ContextSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Lives outside the library's two packages, so its frames are the first ones DIAG-10 does not skip. */
class CaptureSiteTest {

    private static final ContextKey<String> TENANT = ContextKey.of("acme.tenant", String.class);

    /** A site in StackTraceElement.toString() form for a frame of this class: class.method(File.java:line). */
    private static final String SITE_OF_THIS_CLASS =
            "io\\.github\\.boundrail\\.context\\.diagnostics\\.CaptureSiteTest\\.[\\w$]+\\(CaptureSiteTest\\.java:\\d+\\)";

    private CaptureSites savedMode;
    private double savedRate;

    @BeforeEach
    void saveMode() {
        savedMode = ContextRuntime.captureSites();
        savedRate = ContextRuntime.sampleRate();
    }

    @AfterEach
    void restoreMode() {
        ContextRuntime.captureSites(savedMode, savedRate);
    }

    private static ContextSnapshot bindTenant() {
        return Context.with(TENANT, "t1").call(Context::capture);
    }

    @Test
    @DisplayName("DIAG-10 a binding under on records the first frame outside the library, and EMPTY has none")
    void aBindingUnderOnRecordsTheFirstFrameOutsideTheLibrary() {
        ContextRuntime.captureSites(CaptureSites.ON, savedRate);

        ContextSnapshot direct = bindTenant();
        Supplier<ContextSnapshot> lambda = () -> Context.with(TENANT, "t2").call(Context::capture);
        ContextSnapshot inLambda = lambda.get();

        assertThat(direct.captureSite()).hasValueSatisfying(site -> assertThat(site).matches(SITE_OF_THIS_CLASS).contains(".bindTenant("));
        assertThat(inLambda.captureSite()).hasValueSatisfying(site -> assertThat(site).matches(SITE_OF_THIS_CLASS).contains(".lambda$"));
        assertThat(ContextSnapshot.EMPTY.captureSite()).isEmpty();
    }

    @Test
    @DisplayName("DIAG-10 filtering, running and wrapping keep the capture site")
    void filteringRunningAndWrappingKeepTheCaptureSite() throws Exception {
        ContextRuntime.captureSites(CaptureSites.ON, savedRate);
        ContextSnapshot recorded = bindTenant();
        Optional<String> site = recorded.captureSite();
        ContextPolicy policy = ContextPolicy.builder().key(TENANT, rules -> rules.propagateTo(Boundary.ATTACHED)).build();
        List<Optional<String>> inside = new ArrayList<>();

        recorded.run(() -> inside.add(Context.capture().captureSite()));
        inside.add(recorded.call(() -> Context.capture().captureSite()));
        recorded.wrap(() -> {
            inside.add(Context.capture().captureSite());
        }).run();
        inside.add(recorded.wrap(() -> Context.capture().captureSite()).call());

        assertThat(site).isPresent();
        assertThat(policy.filter(recorded, Boundary.ATTACHED).captureSite()).isEqualTo(site);
        assertThat(inside).containsExactly(site, site, site, site);
    }

    @Test
    @DisplayName("DIAG-05 under off no capture site is recorded")
    void underOffNoCaptureSiteIsRecorded() {
        ContextRuntime.captureSites(CaptureSites.OFF, savedRate);

        assertThat(bindTenant().captureSite()).isEmpty();

        ContextRuntime.captureSites(CaptureSites.ON, savedRate);

        assertThat(bindTenant().captureSite()).as("the same binding under on").isPresent();
    }

    /** {@return how many of n bindings recorded a capture site} */
    private static long recordedOf(int n) {
        long recorded = 0;
        for (int i = 0; i < n; i++) {
            if (bindTenant().captureSite().isPresent()) {
                recorded++;
            }
        }
        return recorded;
    }

    @Test
    @DisplayName("DIAG-05 sampled at rate 1.0 records every snapshot and at 0.0 none")
    void sampledAtRate1RecordsEverySnapshotAndAt0None() {
        ContextRuntime.captureSites(CaptureSites.SAMPLED, 1.0);

        assertThat(recordedOf(100)).as("rate 1.0").isEqualTo(100);

        ContextRuntime.captureSites(CaptureSites.SAMPLED, 0.0);

        assertThat(recordedOf(100)).as("rate 0.0").isZero();
    }

    @Test
    @DisplayName("DIAG-05 sampled at 0.5 records some but not all of 1000 snapshots")
    void sampledAtHalfRecordsSomeButNotAllOf1000Snapshots() {
        ContextRuntime.captureSites(CaptureSites.SAMPLED, 0.5);

        assertThat(recordedOf(1000)).isBetween(300L, 700L);
    }
}
