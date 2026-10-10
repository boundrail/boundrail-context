package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.ContextViolationException;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.Deadlines;
import io.github.boundrail.context.Destination;
import io.github.boundrail.context.DestinationClassifier;
import io.github.boundrail.context.OutboundRequest;
import io.github.boundrail.context.Violation;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Around-style interceptor installed on every auto-configured {@code RestClient.Builder} and {@code RestTemplateBuilder} (OUT-01). */
final class ContextClientInterceptor implements ClientHttpRequestInterceptor {

    private final DestinationClassifier classifier;
    private final ContextPolicy policy;
    private final ViolationReporter reporter;
    private final BoundrailContextProperties.Violations.Mode mode;
    private final int maxValueLength;

    ContextClientInterceptor(DestinationClassifier classifier, EffectivePolicy policy, ViolationReporter reporter,
            BoundrailContextProperties.Violations.Mode mode, int maxValueLength) {
        this.classifier = classifier;
        this.policy = policy.policy();
        this.reporter = reporter;
        this.mode = mode;
        this.maxValueLength = maxValueLength;
    }

    /**
     * Classifies the request URI and the client's declaration, if any (OUT-02), checks the required keys (POL-08), writes the wire headers
     * of the bound keys that propagate to the boundary (OUT-04), strips forbidden headers from an external or unknown request (WIRE-07),
     * writes the remaining time of a bound deadline to an internal request (DL-05), then executes the request once.
     */
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        Optional<Destination> declared = Optional.ofNullable((Destination) request.getAttributes().get(BoundrailClients.DESTINATION_ATTRIBUTE));
        Destination destination = classifier.classify(new OutboundRequest(request.getURI(), declared));
        // DEST-03: UNKNOWN is treated exactly like EXTERNAL.
        Boundary boundary = destination.kind() == Destination.Kind.INTERNAL ? Boundary.INTERNAL_CALL : Boundary.EXTERNAL_CALL;
        checkRequiredKeys(boundary, destination);
        writeHeaders(request, policy.filter(Context.capture(), boundary), boundary, destination);
        if (boundary == Boundary.EXTERNAL_CALL) {
            stripForbiddenHeaders(request, destination);
        } else {
            Deadlines.current().ifPresent(deadline -> writeDeadline(request, deadline, destination));
        }
        return execution.execute(request, body);
    }

    /**
     * Sets {@code Boundrail-Deadline-Ms} on an internal call to the deadline's remaining time in whole milliseconds, read last, or to an
     * application-set value that is one header value of ASCII digits and smaller (DL-05, an exception to OUT-04). A value longer than
     * {@code maxValueLength} is not written, any application-set value is removed too, and one {@code OVERSIZED_VALUE} is reported, so the
     * receiver never gets a longer budget than the sender's (WIRE-04, DESIGN D50, D57).
     */
    private void writeDeadline(HttpRequest request, Deadline deadline, Destination destination) {
        String wireName = policy.wireName(BuiltInKeys.DEADLINE).orElseThrow();
        List<String> application = request.getHeaders().getOrEmpty(wireName);
        String value = headerValue(deadline.remaining());
        if (application.size() == 1 && ContextInboundFilter.isDigits(application.getFirst()) && isSmaller(application.getFirst(), value)) {
            value = application.getFirst();
        }
        if (value.length() > maxValueLength) {
            request.getHeaders().remove(wireName);
            reporter.report(reporter.violation(Violation.Kind.OVERSIZED_VALUE, BuiltInKeys.DEADLINE.name(), Boundary.INTERNAL_CALL,
                    Optional.of(destination)));
            return;
        }
        request.getHeaders().set(wireName, value);
    }

    /** {@return the remaining time in whole milliseconds, rounded down} (DL-05) */
    static String headerValue(Duration remaining) {
        return Long.toString(remaining.toMillis());
    }

    /** {@return whether the digits {@code candidate} are a smaller number than the digits {@code remaining}} */
    private static boolean isSmaller(String candidate, String remaining) {
        String digits = candidate.replaceFirst("^0+(?=.)", "");
        return digits.length() != remaining.length() ? digits.length() < remaining.length() : digits.compareTo(remaining) < 0;
    }

    /**
     * Reports one {@code MISSING_REQUIRED} per required key that propagates to the boundary and is not bound, a default not counting, in
     * ascending order of key name (POL-08, DIAG-02). It reads the context, not the request headers. In {@code FAIL} mode it then throws one
     * {@code ContextViolationException} carrying the first, so no header is written and the request is not sent.
     */
    private void checkRequiredKeys(Boundary boundary, Destination destination) {
        List<Violation> missing = policy.keys().stream()
                .filter(key -> policy.isRequired(key) && policy.propagates(key, boundary) && !key.isBound())
                .map(ContextKey::name)
                .sorted()
                .map(name -> reporter.violation(Violation.Kind.MISSING_REQUIRED, name, boundary, Optional.of(destination)))
                .toList();
        missing.forEach(reporter::report);
        if (!missing.isEmpty() && mode == BoundrailContextProperties.Violations.Mode.FAIL) {
            throw new ContextViolationException(missing.getFirst());
        }
    }

    /**
     * Removes every header named after the wire name of a policy key that does not propagate to {@code EXTERNAL_CALL}, and reports one
     * {@code FORBIDDEN_HEADER} per removed name that had at least one value, with the key's name (WIRE-07, DIAG-01). {@code HttpHeaders}
     * holds every value of a name under one entry, compared ignoring case (WIRE-06). {@code Authorization} is never a policy wire name.
     */
    private void stripForbiddenHeaders(HttpRequest request, Destination destination) {
        for (ContextKey<?> key : policy.keys()) {
            if (policy.propagates(key, Boundary.EXTERNAL_CALL)) {
                continue;
            }
            Optional<String> wireName = policy.wireName(key);
            if (wireName.isEmpty()) {
                continue;
            }
            List<String> removed = request.getHeaders().remove(wireName.get());
            if (removed != null && !removed.isEmpty()) {
                reporter.report(reporter.violation(Violation.Kind.FORBIDDEN_HEADER, key.name(), Boundary.EXTERNAL_CALL,
                        Optional.of(destination)));
            }
        }
    }

    /**
     * Writes each key's wire header, except {@code AUTHORITY} (ID-03) and {@code DEADLINE} (DL-05), and except a header application code
     * already set with at least one value, whose context value is neither encoded nor checked (OUT-04); {@code HttpHeaders} compares names
     * ignoring case (WIRE-06). An exception from {@code encode} propagates (WIRE-05). An encoding longer than {@code maxValueLength} is not
     * written and is reported as {@code OVERSIZED_VALUE} (WIRE-04); otherwise an encoding that is not a valid field value is not written and
     * is reported as {@code INVALID_VALUE} (WIRE-08).
     */
    private void writeHeaders(HttpRequest request, ContextSnapshot snapshot, Boundary boundary, Destination destination) {
        for (ContextKey<?> key : snapshot.keys()) {
            if (key == BuiltInKeys.AUTHORITY || key == BuiltInKeys.DEADLINE) {
                continue;
            }
            Optional<String> wireName = policy.wireName(key).filter(name -> request.getHeaders().getOrEmpty(name).isEmpty());
            if (wireName.isEmpty()) {
                continue;
            }
            String encoded = encode(key, snapshot);
            if (encoded.length() > maxValueLength) {
                reporter.report(reporter.violation(Violation.Kind.OVERSIZED_VALUE, key.name(), boundary, Optional.of(destination)));
                continue;
            }
            if (!isFieldValue(encoded)) {
                reporter.report(reporter.violation(Violation.Kind.INVALID_VALUE, key.name(), boundary, Optional.of(destination)));
                continue;
            }
            request.getHeaders().set(wireName.get(), encoded);
        }
    }

    private <T> String encode(ContextKey<T> key, ContextSnapshot snapshot) {
        T value = snapshot.find(key).orElseThrow();
        if (key == BuiltInKeys.INITIATOR) {
            return BuiltInKeys.INITIATOR.type().cast(value).value();
        }
        return policy.codec(key).orElseThrow().encode(value);
    }

    /**
     * {@return whether the value is empty, or starts and ends with a visible US-ASCII character and contains only visible US-ASCII
     * characters, spaces and horizontal tabs} (WIRE-08)
     */
    private static boolean isFieldValue(String value) {
        if (value.isEmpty()) {
            return true;
        }
        if (!isVisible(value.charAt(0)) || !isVisible(value.charAt(value.length() - 1))) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!isVisible(c) && c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static boolean isVisible(char c) {
        return c >= '\u0021' && c <= '\u007E';
    }
}
