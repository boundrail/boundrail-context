package io.github.boundrail.context.spring;

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

    ContextClientInterceptor(DestinationClassifier classifier) {
        this.classifier = classifier;
    }

    /** Classifies the request URI and the client's declaration, if any (OUT-02), then executes the request once. */
    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution) throws IOException {
        Optional<Destination> declared = Optional.ofNullable((Destination) request.getAttributes().get(BoundrailClients.DESTINATION_ATTRIBUTE));
        classifier.classify(new OutboundRequest(request.getURI(), declared));
        return execution.execute(request, body);
    }
}
