package io.github.boundrail.context.spring;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.AuthorityPropagator;
import io.github.boundrail.context.Boundary;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.Deadline;
import io.github.boundrail.context.DeadlineExceededException;
import io.github.boundrail.context.Deadlines;
import io.github.boundrail.context.Destination;
import io.github.boundrail.context.Violation;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The built-in {@code exchange} propagator: trades the bound authority's credential for a token scoped to the destination's service name,
 * with an RFC 8693 token exchange (ID-04, ID-11). The exchange request goes through its own JDK {@code HttpClient}, never through a Spring
 * client, so the library's interceptor never sees it. The only class of the library that names a Jackson 3 type (D65).
 */
final class ExchangeAuthorityPropagator implements AuthorityPropagator, AutoCloseable {

    private static final String GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange";
    private static final String ACCESS_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token";
    private static final int CACHE_CAPACITY = 10_000;
    private static final long CACHE_MARGIN_SECONDS = 30;
    /** The request timeout with no deadline bound; fixed, with no property (ID-12). */
    private static final Duration NO_DEADLINE_TIMEOUT = Duration.ofSeconds(10);

    private final URI tokenUri;
    private final String basicCredentials;
    private final HttpClient client = HttpClient.newHttpClient();

    private final ViolationReporter reporter;
    private final DeadlineCounter counter;
    private final Clock clock;
    private final Duration noDeadlineTimeout;

    /**
     * Its own mapper, never the application's, whose customisation could make parsing lenient (D65). Numbers are read exactly, as
     * {@code BigInteger} or {@code BigDecimal}, never through a {@code double}, and a repeated key makes the response invalid (ID-11).
     */
    private final JsonMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS, DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build();

    /** Exchanged tokens in the order they were added, evicting the earliest added beyond the capacity (ID-11); guarded by itself. */
    private final Map<CacheKey, CachedToken> cache = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<CacheKey, CachedToken> eldest) {
            return size() > CACHE_CAPACITY;
        }
    };

    ExchangeAuthorityPropagator(URI tokenUri, String clientId, String clientSecret, ViolationReporter reporter, DeadlineCounter counter) {
        this(tokenUri, clientId, clientSecret, reporter, counter, Clock.systemUTC(), NO_DEADLINE_TIMEOUT);
    }

    ExchangeAuthorityPropagator(URI tokenUri, String clientId, String clientSecret, ViolationReporter reporter, Clock clock) {
        this(tokenUri, clientId, clientSecret, reporter, stage -> { }, clock, NO_DEADLINE_TIMEOUT);
    }

    ExchangeAuthorityPropagator(URI tokenUri, String clientId, String clientSecret, ViolationReporter reporter, DeadlineCounter counter,
            Clock clock, Duration noDeadlineTimeout) {
        this.tokenUri = tokenUri;
        this.reporter = reporter;
        this.counter = counter;
        this.clock = clock;
        this.noDeadlineTimeout = noDeadlineTimeout;
        this.basicCredentials = Base64.getEncoder().encodeToString((formEncode(clientId) + ":" + formEncode(clientSecret))
                .getBytes(StandardCharsets.US_ASCII));
    }

    /**
     * {@return a token exchanged for the destination's service name} Throws {@code NullPointerException} for a {@code null} argument
     * (CORE-16). For a destination without a service name, returns empty without calling the token endpoint and reports one
     * {@code MISSING_AUDIENCE} (ID-05).
     */
    @Override
    public Optional<String> outboundCredential(Authority authority, Destination destination) {
        Objects.requireNonNull(authority, "authority");
        Objects.requireNonNull(destination, "destination");
        Optional<String> service = destination.service();
        if (service.isEmpty()) {
            reporter.report(reporter.violation(Violation.Kind.MISSING_AUDIENCE, BuiltInKeys.AUTHORITY.name(), Boundary.INTERNAL_CALL,
                    Optional.of(destination)));
            return Optional.empty();
        }
        String audience = service.get();
        CacheKey key = new CacheKey(sha256Hex(authority.credential()), audience);
        synchronized (cache) {
            CachedToken cached = cache.get(key);
            if (cached != null) {
                if (clock.instant().isBefore(cached.end())) {
                    return Optional.of(cached.token());
                }
                // Removed, so the entry that replaces it counts as added last.
                cache.remove(key);
            }
        }
        // The remaining time of a bound deadline, else the fixed timeout (ID-12).
        Optional<Deadline> deadline = Deadlines.current();
        Duration timeout = deadline.map(Deadline::remaining).orElse(noDeadlineTimeout);
        if (!timeout.isPositive()) {
            // Checked before timeout(...), which rejects zero (Q2): the exchange request is not sent.
            counter.increment(DeadlineExceededException.Stage.TIMEOUT);
            throw new DeadlineExceededException(DeadlineExceededException.Stage.TIMEOUT);
        }
        String form = "grant_type=" + formEncode(GRANT_TYPE)
                + "&subject_token=" + formEncode(authority.credential())
                + "&subject_token_type=" + formEncode(ACCESS_TOKEN_TYPE)
                + "&audience=" + formEncode(audience);
        HttpRequest request = HttpRequest.newBuilder(tokenUri)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + basicCredentials)
                .header("Accept", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (HttpTimeoutException e) {
            // Includes HttpConnectTimeoutException: the request timeout covers the connect.
            if (deadline.isPresent()) {
                counter.increment(DeadlineExceededException.Stage.TIMEOUT);
                throw new DeadlineExceededException(DeadlineExceededException.Stage.TIMEOUT, e);
            }
            throw new TokenExchangeException(audience, "timed out", e);
        } catch (IOException e) {
            throw new TokenExchangeException(audience, "endpoint unreachable", e);
        } catch (InterruptedException e) {
            // The JDK client clears the interrupt status (ID-12).
            Thread.currentThread().interrupt();
            throw new TokenExchangeException(audience, "interrupted", e);
        }
        if (response.statusCode() != 200) {
            throw new TokenExchangeException(audience, response.statusCode());
        }
        Object body;
        try {
            body = mapper.readValue(response.body(), Object.class);
        } catch (JacksonException | NumberFormatException e) {
            // Neither the cause nor part of the message: a parser's message can echo the body (ID-12).
            body = null;
        }
        if (!(body instanceof Map<?, ?> object)
                || !(object.get("access_token") instanceof String token) || token.isEmpty()
                || object.containsKey("expires_in") && !isWholeAndNotNegative(object.get("expires_in"))) {
            throw new TokenExchangeException(audience, response.statusCode(), "no valid response");
        }
        Instant received = clock.instant();
        if (object.get("expires_in") instanceof Number expiresIn) {
            Instant end = cacheEnd(received, expiresIn, authority);
            if (end.isAfter(received)) {
                synchronized (cache) {
                    cache.put(key, new CachedToken(token, end));
                }
            }
        }
        return Optional.of(token);
    }

    /** {@return whether an {@code expires_in} read exactly is a JSON number with a whole value of zero or more} (ID-11) */
    private static boolean isWholeAndNotNegative(Object expiresIn) {
        return switch (expiresIn) {
            case BigInteger integer -> integer.signum() >= 0;
            case BigDecimal decimal -> decimal.signum() >= 0 && decimal.stripTrailingZeros().scale() <= 0;
            case null, default -> false;
        };
    }

    /**
     * {@return when a token received at {@code received} stops being used} 30 seconds before it expires, never after the source
     * credential's expiry, and at most {@code Instant.MAX} for an {@code expires_in} too large for the clock (ID-04, ID-11).
     */
    private static Instant cacheEnd(Instant received, Number expiresIn, Authority authority) {
        // Compared with the cap before any conversion: converting a value such as 1e999999999 overflows or takes minutes.
        BigDecimal seconds = expiresIn instanceof BigDecimal decimal ? decimal : new BigDecimal((BigInteger) expiresIn);
        BigDecimal cap = BigDecimal.valueOf(Instant.MAX.getEpochSecond() - received.getEpochSecond() + CACHE_MARGIN_SECONDS);
        Instant end = seconds.compareTo(cap) >= 0 ? Instant.MAX : received.plusSeconds(seconds.longValue() - CACHE_MARGIN_SECONDS);
        return authority.expiresAt().filter(end::isAfter).orElse(end);
    }

    /** Closes the HTTP client; called by Spring's inferred destroy method when the context closes (RT-09). */
    @Override
    public void close() {
        client.close();
    }

    /** {@return the request timeout with no deadline bound} For tests. */
    Duration noDeadlineTimeout() {
        return noDeadlineTimeout;
    }

    /** {@return the client the exchange requests go through} For tests. */
    HttpClient httpClient() {
        return client;
    }

    /** {@return the cache's keys, in the order their entries were added} For tests. */
    List<CacheKey> cacheKeys() {
        synchronized (cache) {
            return List.copyOf(cache.keySet());
        }
    }

    /** {@return the lower-case hex SHA-256 hash of the credential's UTF-8 bytes} */
    private static String sha256Hex(String credential) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(credential.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every JDK", e);
        }
    }

    /** A cached exchanged token and the instant from which it is no longer used. */
    private record CachedToken(String token, Instant end) {
    }

    /** A cache key: the hex SHA-256 hash of the source credential, never the credential, and the audience (ID-04, ID-11). */
    record CacheKey(String credentialHash, String audience) {
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
