package io.github.boundrail.context.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.boundrail.context.Violation;
import io.github.boundrail.context.it.topology.CleanupJob;
import io.github.boundrail.context.it.topology.EdgeApp;
import io.github.boundrail.context.it.topology.ObservedContext;
import io.github.boundrail.context.it.topology.Topology;
import io.github.boundrail.context.it.topology.Topology.Service;
import io.github.boundrail.context.test.BoundrailTest;
import io.github.boundrail.context.test.RecordedViolations;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Context propagation suite (IT-1): context follows async work and scheduled jobs into calls and logs, and lost context fails loudly.
 * Scenarios come from specs/004-async-diagnostics (IT-1.1 – IT-1.9); each body is written test-first when that feature enables it
 * (BLD-10).
 */
@ExtendWith(Topology.class)
@BoundrailTest
class ContextPropagationIT {

    private static final HttpClient HTTP = HttpClient.newHttpClient();

    @Test
    @DisplayName("IT-1.1 Decorated executor carries context")
    void it11DecoratedExecutorCarriesContext(Topology.Running topology) throws Exception {
        // IT-1.1: Given a public request authenticated as alice (tenant `acme`), when edge runs work through an `@Async` method on
        // `taskExecutor` (an auto-decorated `ThreadPoolTaskExecutor` in detached mode, with Micrometer's context-propagating decorator also
        // configured) and that work calls orders, then orders observes `tenant=acme`. *(EXE-02, EXE-03, EXE-05, OUT-04)*
        String marker = UUID.randomUUID().toString();

        HttpResponse<String> response = callEdge(topology, "/async/call-orders", marker);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).as("the work ran on taskExecutor").startsWith("taskExecutor-");
        assertThat(observed(topology, "orders", marker)).containsEntry(ObservedContext.TENANT, "acme");
    }

    @Test
    @DisplayName("IT-1.2 Undecorated executor fails loudly")
    void it12UndecoratedExecutorFailsLoudly(Topology.Running topology) throws Exception {
        // IT-1.2: Given violation mode `fail`, when edge runs the same work on `rawExecutor` (a plain `ExecutorService` bean whose threads
        // are named `rawExecutor-N`), then the work's future completes exceptionally with `ContextViolationException`, whose violation has
        // kind `MISSING_REQUIRED`, key `tenant`, boundary `INTERNAL_CALL`, and an `executorEvidence` that names `rawExecutor` with status
        // `GAP`. Orders receives no request. *(EXE-04, POL-08, DIAG-02, DIAG-04)*
        String marker = UUID.randomUUID().toString();

        HttpResponse<String> response = callEdge(topology, "/raw/call-orders", marker);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).as("the violation's kind, key, boundary, capture site and executor evidence").isNotEqualTo("sent");
        String[] violation = response.body().split("\\|");
        assertThat(violation).as(response.body()).hasSize(5);
        assertThat(List.of(violation[0], violation[1], violation[2], violation[4]))
                .containsExactly("MISSING_REQUIRED", "tenant", "INTERNAL_CALL", "rawExecutor (GAP)");
        assertThat(marked(topology, "orders", marker)).as("orders requests marked %s", marker).isEmpty();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("IT-1.3 Explicit wrapping fixes it")
    void it13ExplicitWrappingFixesIt(Topology.Running topology, CapturedOutput output) throws Exception {
        // IT-1.3: Given `boundExecutor`, an edge bean declared as `BoundExecutors.detached(...)` around a plain `ExecutorService`, when the
        // same work runs on it, then orders observes `tenant=acme` and the audit reports `boundExecutor` as `COVERED (detached)`.
        // *(EXE-04, DIAG-06)*
        String marker = UUID.randomUUID().toString();

        HttpResponse<String> response = callEdge(topology, "/bound/call-orders", marker);
        topology.startEdge().close();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("sent");
        assertThat(observed(topology, "orders", marker)).containsEntry(ObservedContext.TENANT, "acme");
        assertThat(output.getOut()).contains("Executor audit: boundExecutor COVERED (detached)");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("IT-1.4 Report mode continues")
    void it14ReportModeContinues(Topology.Running topology, CapturedOutput output) throws Exception {
        // IT-1.4: Given violation mode `report`, when the IT-1.2 work runs twice in a row, then orders receives both requests without a
        // `Tenant-Id` header, `boundrail.context.violations{kind=missing_required,key=tenant,boundary=internal_call}` increases by two, and
        // exactly one WARN entry is logged. *(DIAG-02, DIAG-03)*
        String marker = UUID.randomUUID().toString();
        try (ConfigurableApplicationContext edge = topology.startEdge("boundrail.context.violations.mode=report")) {
            double before = missingTenantOnInternalCalls(edge);

            HttpResponse<String> first = call(topology, topology.uri(edge), "/raw/call-orders", marker);
            HttpResponse<String> second = call(topology, topology.uri(edge), "/raw/call-orders", marker);

            assertThat(List.of(first.statusCode(), second.statusCode())).containsExactly(200, 200);
            assertThat(List.of(first.body(), second.body())).containsExactly("sent", "sent");
            assertThat(marked(topology, "orders", marker)).hasSize(2).allSatisfy(seen -> assertThat(seen).doesNotContainKey("tenant-id"));
            assertThat(missingTenantOnInternalCalls(edge)).isEqualTo(before + 2);
            assertThat(output.getOut().lines().filter(line -> line.contains(" WARN ")
                    && line.contains("Context violation MISSING_REQUIRED: key tenant, wire name Tenant-Id, boundary INTERNAL_CALL")))
                    .hasSize(1);
        }
    }

    /** The extra edge's {@code boundrail.context.violations{kind=missing_required,key=tenant,boundary=internal_call}} count. */
    private static double missingTenantOnInternalCalls(ConfigurableApplicationContext edge) {
        Counter counter = edge.getBean(MeterRegistry.class).find("boundrail.context.violations")
                .tags("kind", "missing_required", "key", "tenant", "boundary", "internal_call").counter();
        return counter == null ? 0 : counter.count();
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("IT-1.5 Startup audit")
    void it15StartupAudit(Topology.Running topology, CapturedOutput output) {
        // IT-1.5: The startup audit reports `taskExecutor` as `COVERED (detached)` and `rawExecutor` as `GAP`. With
        // `audit.fail-on-gap=true`, the edge context fails to start with an error that lists `rawExecutor`. *(DIAG-06)*
        topology.startEdge().close();
        Throwable failOnGap = catchThrowable(() -> topology.startEdge("boundrail.context.audit.fail-on-gap=true").close());

        assertThat(output.getOut()).contains("Executor audit: taskExecutor COVERED (detached)", "Executor audit: rawExecutor GAP");
        assertThat(failOnGap).as("startup with audit.fail-on-gap=true").isNotNull().hasMessageContaining("rawExecutor");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    @DisplayName("IT-1.6 MDC")
    void it16Mdc(Topology.Running topology, CapturedOutput output) throws Exception {
        // IT-1.6: A log line written inside the IT-1.1 async work contains `tenant=acme` through `%X{tenant}`, and a later task on the same
        // pooled thread without context logs no tenant. *(LOG-01, RT-03)*
        String marker = UUID.randomUUID().toString();
        String laterMarker = UUID.randomUUID().toString();

        HttpResponse<String> work = callEdge(topology, "/async/call-orders", marker);
        HttpResponse<String> later = callEdge(topology, "/async/log-without-context", laterMarker);

        assertThat(List.of(work.statusCode(), later.statusCode())).containsExactly(200, 200);
        assertThat(later.body()).as("the same pooled thread").isEqualTo(work.body());
        assertThat(logLine(output, marker)).contains("tenant=acme :");
        assertThat(logLine(output, laterMarker)).contains("tenant= :");
    }

    /** The single log line that mentions the marker. */
    private static String logLine(CapturedOutput output, String marker) {
        List<String> lines = output.getOut().lines().filter(line -> line.contains("marker " + marker)).toList();
        assertThat(lines).as("log lines with marker %s", marker).hasSize(1);
        return lines.getFirst();
    }

    @Test
    @DisplayName("IT-1.7 Scheduled jobs get a system context")
    void it17ScheduledJobsGetASystemContext(Topology.Running topology) throws InterruptedException {
        // IT-1.7: When a `@Scheduled` method on edge (bean `cleanupJob`, method `run`) runs, then the snapshot it records to
        // `ObservedContext` contains exactly `initiator=system:scheduled:cleanupJob.run`. *(EXE-06)*
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (topology.observed().of(CleanupJob.OBSERVED_AS).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }

        assertThat(topology.observed().of(CleanupJob.OBSERVED_AS)).as("cleanupJob.run's recorded snapshots").isNotEmpty()
                .allSatisfy(bound -> assertThat(bound).containsExactlyEntriesOf(Map.of("boundrail.initiator",
                        "system:scheduled:cleanupJob.run")));
    }

    @Test
    @DisplayName("IT-1.8 Capture sites are recorded in tests, and only where observed")
    void it18CaptureSitesAreRecordedInTestsAndOnlyWhereObserved(Topology.Running topology, RecordedViolations violations) throws Exception {
        // IT-1.8: Under `@BoundrailTest`, a `MISSING_REQUIRED` violation raised after `Context.without(tenant)` in an edge controller
        // carries a capture site in that controller class. The IT-1.2 violation, whose task ran with `EMPTY`, carries no capture site.
        // *(DIAG-04, DIAG-05, DIAG-10, TEST-02)*
        HttpResponse<String> withoutTenant = callEdge(topology, "/call-orders/without-tenant", UUID.randomUUID().toString());
        HttpResponse<String> raw = callEdge(topology, "/raw/call-orders", UUID.randomUUID().toString());

        assertThat(withoutTenant.body()).isEqualTo("MISSING_REQUIRED");
        assertThat(raw.body()).startsWith("MISSING_REQUIRED|");
        List<Violation> missing = violations.all().stream().filter(violation -> violation.kind() == Violation.Kind.MISSING_REQUIRED).toList();
        assertThat(missing).as("MISSING_REQUIRED violations recorded during the test").hasSize(2);
        assertThat(missing.get(0).captureSite()).as("capture site after Context.without(tenant)").hasValueSatisfying(
                site -> assertThat(site).startsWith("io.github.boundrail.context.it.topology.EdgeApp."));
        assertThat(missing.get(1).captureSite()).as("capture site of the task run with EMPTY").isEmpty();
    }

    @Test
    @DisplayName("IT-1.9 Spring MVC async processing keeps the request's context")
    void it19SpringMvcAsyncProcessingKeepsTheRequestsContext(Topology.Running topology) throws Exception {
        // IT-1.9: Given a public request authenticated as alice (tenant `acme`), when an edge handler returns a `Callable` that calls orders,
        // then orders observes `tenant=acme`. When an edge handler returns a `DeferredResult` that a thread with no context completes, then an
        // edge `HandlerInterceptor` that records the bound `tenant` in `postHandle` of the async dispatch observes `tenant=acme`.
        // *(IN-09, EXE-09)*
        String callableMarker = UUID.randomUUID().toString();

        HttpResponse<String> callable = callEdge(topology, "/mvc-async/callable", callableMarker);

        assertThat(callable.statusCode()).as(callable.body()).isEqualTo(200);
        assertThat(callable.body()).as("the Callable ran on MVC's internal default executor").startsWith("MvcAsync");
        assertThat(observed(topology, "orders", callableMarker)).containsEntry(ObservedContext.TENANT, "acme");

        String deferredMarker = UUID.randomUUID().toString();

        HttpResponse<String> deferred = callEdge(topology, "/mvc-async/deferred", deferredMarker);

        assertThat(deferred.statusCode()).as(deferred.body()).isEqualTo(200);
        // MVC writes the response before postHandle runs, so the client can see it before the interceptor has recorded.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (marked(topology, EdgeApp.ASYNC_DISPATCH, deferredMarker).isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(observed(topology, EdgeApp.ASYNC_DISPATCH, deferredMarker)).containsEntry(ObservedContext.TENANT, "acme");
    }

    /** Sends a GET to the shared edge with alice's JWT and the marker. */
    private static HttpResponse<String> callEdge(Topology.Running topology, String path, String marker) throws Exception {
        return call(topology, topology.uri(Service.EDGE), path, marker);
    }

    /** Sends a GET to an edge instance with alice's JWT and the marker. */
    private static HttpResponse<String> call(Topology.Running topology, URI edge, String path, String marker) throws Exception {
        URI uri = edge.resolve(path + "?marker=" + marker);
        return HTTP.send(HttpRequest.newBuilder(uri).header("Authorization", "Bearer " + topology.jwts().alice()).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** The single request a service observed with this marker. */
    private static Map<String, String> observed(Topology.Running topology, String service, String marker) {
        List<Map<String, String>> marked = marked(topology, service, marker);
        assertThat(marked).as("%s requests marked %s", service, marker).hasSize(1);
        return marked.getFirst();
    }

    /** Every request a service observed with this marker. */
    private static List<Map<String, String>> marked(Topology.Running topology, String service, String marker) {
        return topology.observed().of(service).stream()
                .filter(seen -> marker.equals(seen.get(EdgeApp.MARKER.toLowerCase(Locale.ROOT))))
                .toList();
    }
}
