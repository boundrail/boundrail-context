package io.github.boundrail.context.spring;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.AuthorityPropagator;
import io.github.boundrail.context.Destination;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.databind.json.JsonMapper;

/**
 * The built-in {@code exchange} propagator: trades the bound authority's credential for a token scoped to the destination's service name,
 * with an RFC 8693 token exchange (ID-04, ID-11). The exchange request goes through its own JDK {@code HttpClient}, never through a Spring
 * client, so the library's interceptor never sees it. The only class of the library that names a Jackson 3 type (D65).
 */
final class ExchangeAuthorityPropagator implements AuthorityPropagator, AutoCloseable {

    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";

    private final URI tokenUri;
    private final String basicCredentials;
    private final HttpClient client = HttpClient.newHttpClient();

    /** Its own mapper, never the application's, whose customisation could make parsing lenient (D65). */
    private final JsonMapper mapper = JsonMapper.builder().build();

    ExchangeAuthorityPropagator(URI tokenUri, String clientId, String clientSecret) {
        this.tokenUri = tokenUri;
        this.basicCredentials = Base64.getEncoder().encodeToString((formEncode(clientId) + ":" + formEncode(clientSecret))
                .getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * {@return a token exchanged for the destination's service name} Throws {@code NullPointerException} for a {@code null} argument
     * (CORE-16).
     */
    @Override
    public Optional<String> outboundCredential(Authority authority, Destination destination) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(destination, "destination");
        String audience = destination.service().orElseThrow();
        String form = "grant_type=" + formEncode(GRANT_TYPE)
                + "&subject_token=" + formEncode(authority.credential())
                + "&subject_token_type=" + formEncode(ACCESS_TOKEN_TYPE)
                + "&audience=" + formEncode(audience);
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basicCredentials)
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            throw new IllegalStateException("Token exchange for audience " + audience + " failed", e);
        }
        if (!(mapper.readValue(response.body(), Object.class) instanceof Map<?, ?> object)) {
            throw new IllegalStateException("Token exchange for audience " + audience + " failed: no valid response");
        }
        return Optional.of((String) object.get("access_token"));
    }

    /** Closes the HTTP client; called by Spring's inferred destroy method when the context closes (RT-09). */
    @Override
    public void close() {
        client.close();
    }

    /** {@return the client the exchange requests go through} For tests. */
    HttpClient httpClient() {
        return client;
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
