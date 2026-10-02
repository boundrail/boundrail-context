package io.github.boundrail.context.spring;

import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Context;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.ContextSnapshot;
import io.github.boundrail.context.Destination;
import io.github.boundrail.context.DestinationClassifier;
import io.github.boundrail.context.OutboundRequest;
import io.github.boundrail.context.Violation;
import java.io.IOException;
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
    private final int maxValueLength;

    ContextClientInterceptor(DestinationClassifier classifier, EffectivePolicy policy, ViolationReporter reporter, int maxValueLength) {
        this.classifier = classifier;
        this.policy = policy.policy();
        this.reporter = reporter;
        this.maxValueLength = maxValueLength;
    }

    /**
     * Classifies the request URI and the client's declaration, if any (OUT-02), writes the wire headers of the bound keys that propagate
     * to the boundary (OUT-04), strips forbidden headers from an external or unknown request (WIRE-07), then executes the request once.
     */
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        Optional<Destination> declared = Optional.ofNullable((Destination) request.getAttributes().get(BoundrailClients.DESTINATION_ATTRIBUTE));
        Destination destination = classifier.classify(new OutboundRequest(request.getURI(), declared));
        // DEST-03: UNKNOWN is treated exactly like EXTERNAL.
        Boundary boundary = destination.kind() == Destination.Kind.INTERNAL ? Boundary.INTERNAL_CALL : Boundary.EXTERNAL_CALL;
        writeHeaders(request, policy.filter(Context.capture(), boundary), boundary, destination);
        if (boundary == Boundary.EXTERNAL_CALL) {
            stripForbiddenHeaders(request, destination);
        }
        return execution.execute(request, body);
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
                reporter.report(new Violation(Violation.Kind.FORBIDDEN_HEADER, key.name(), Boundary.EXTERNAL_CALL, Optional.of(destination),
                        Thread.currentThread().getName(), Optional.empty(), Optional.empty()));
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
                reporter.report(new Violation(Violation.Kind.OVERSIZED_VALUE, key.name(), boundary, Optional.of(destination),
                        Thread.currentThread().getName(), Optional.empty(), Optional.empty()));
                continue;
            }
            if (!isFieldValue(encoded)) {
                reporter.report(new Violation(Violation.Kind.INVALID_VALUE, key.name(), boundary, Optional.of(destination),
                        Thread.currentThread().getName(), Optional.empty(), Optional.empty()));
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
