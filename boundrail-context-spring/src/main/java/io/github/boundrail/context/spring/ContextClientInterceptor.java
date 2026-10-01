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
import java.io.IOException;
import java.util.Optional;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

/** Around-style interceptor installed on every auto-configured {@code RestClient.Builder} and {@code RestTemplateBuilder} (OUT-01). */
final class ContextClientInterceptor implements ClientHttpRequestInterceptor {

    private final DestinationClassifier classifier;
    private final ContextPolicy policy;

    ContextClientInterceptor(DestinationClassifier classifier, EffectivePolicy policy) {
        this.classifier = classifier;
        this.policy = policy.policy();
    }

    /**
     * Classifies the request URI and the client's declaration, if any (OUT-02), writes the wire headers of the bound keys that propagate
     * to the boundary (OUT-04), then executes the request once.
     */
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        Optional<Destination> declared = Optional.ofNullable((Destination) request.getAttributes().get(BoundrailClients.DESTINATION_ATTRIBUTE));
        Destination destination = classifier.classify(new OutboundRequest(request.getURI(), declared));
        // DEST-03: UNKNOWN is treated exactly like EXTERNAL.
        Boundary boundary = destination.kind() == Destination.Kind.INTERNAL ? Boundary.INTERNAL_CALL : Boundary.EXTERNAL_CALL;
        writeHeaders(request, policy.filter(Context.capture(), boundary));
        return execution.execute(request, body);
    }

    /**
     * Writes each key's wire header, except {@code AUTHORITY} (ID-03) and {@code DEADLINE} (DL-05), and except a header application code
     * already set; {@code HttpHeaders} compares names ignoring case (WIRE-06). An exception from {@code encode} propagates (WIRE-05).
     */
    private void writeHeaders(HttpRequest request, ContextSnapshot snapshot) {
        for (ContextKey<?> key : snapshot.keys()) {
            if (key == BuiltInKeys.AUTHORITY || key == BuiltInKeys.DEADLINE) {
                continue;
            }
            policy.wireName(key)
                    .filter(wireName -> !request.getHeaders().containsHeader(wireName))
                    .ifPresent(wireName -> request.getHeaders().set(wireName, encode(key, snapshot)));
        }
    }

    private <T> String encode(ContextKey<T> key, ContextSnapshot snapshot) {
        T value = snapshot.find(key).orElseThrow();
        if (key == BuiltInKeys.INITIATOR) {
            return BuiltInKeys.INITIATOR.type().cast(value).value();
        }
        return policy.codec(key).orElseThrow().encode(value);
    }
}
