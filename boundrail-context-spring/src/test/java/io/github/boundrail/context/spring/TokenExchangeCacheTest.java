package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Destination.Kind.INTERNAL;
import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import io.github.boundrail.context.Authority;
import io.github.boundrail.context.Destination;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The built-in {@code exchange} propagator's cache of exchanged tokens (ID-04, ID-11), called directly with a clock the test advances and a
 * token endpoint that counts the exchanges it answers.
 */
class TokenExchangeCacheTest {

    private static final Instant START = Instant.parse("2026-10-18T10:00:00Z");
    private static final Authority ALICE = Authority.of("alice", "alice-token");
    private static final Destination ORDERS = internal("orders");

    private final MutableClock clock = new MutableClock(START);
    private final CountingTokenEndpoint endpoint = new CountingTokenEndpoint();
    private final ExchangeAuthorityPropagator propagator = new ExchangeAuthorityPropagator(endpoint.tokenUri(), "edge", "edge-secret",
            clock);

    @AfterEach
    void close() {
        propagator.close();
        endpoint.close();
    }

    @Test
    @DisplayName("ID-04 a second call for the same credential and audience uses the cached token, and another audience or credential "
            + "exchanges")
    void aSecondCallForTheSameCredentialAndAudienceUsesTheCachedToken() {
        endpoint.expiresIn("3600");

        Optional<String> first = propagator.outboundCredential(ALICE, ORDERS);
        Optional<String> second = propagator.outboundCredential(ALICE, ORDERS);
        assertThat(endpoint.calls()).isEqualTo(1);
        assertThat(second).isEqualTo(first);

        propagator.outboundCredential(ALICE, internal("billing"));
        assertThat(endpoint.calls()).as("another audience").isEqualTo(2);

        propagator.outboundCredential(Authority.of("alice", "alice-other-token"), ORDERS);
        assertThat(endpoint.calls()).as("another credential of the same subject").isEqualTo(3);
    }

    @Test
    @DisplayName("ID-04 a cached token is used until 30 seconds before it expires")
    void aCachedTokenIsUsedUntil30SecondsBeforeItExpires() {
        endpoint.expiresIn("3600");
        propagator.outboundCredential(ALICE, ORDERS);

        clock.set(START.plusSeconds(3569));
        propagator.outboundCredential(ALICE, ORDERS);
        assertThat(endpoint.calls()).as("cached at +3569s").isEqualTo(1);

        clock.set(START.plusSeconds(3570));
        propagator.outboundCredential(ALICE, ORDERS);
        assertThat(endpoint.calls()).as("exchanged again at +3570s").isEqualTo(2);
    }

    @Test
    @DisplayName("ID-04 a cached token is never used beyond the source credential's expiry")
    void aCachedTokenIsNeverUsedBeyondTheSourceCredentialsExpiry() {
        endpoint.expiresIn("3600");
        Authority expiring = Authority.of("alice", "alice-expiring-token", START.plusSeconds(60));
        propagator.outboundCredential(expiring, ORDERS);

        clock.set(START.plusSeconds(59));
        propagator.outboundCredential(expiring, ORDERS);
        assertThat(endpoint.calls()).as("cached at +59s").isEqualTo(1);

        clock.set(START.plusSeconds(60));
        propagator.outboundCredential(expiring, ORDERS);
        assertThat(endpoint.calls()).as("exchanged again at +60s, the source credential's expiry").isEqualTo(2);

        // ID-10: an authority without expiry is limited by expires_in only.
        propagator.outboundCredential(ALICE, ORDERS);
        clock.set(START.plusSeconds(60 + 3569));
        propagator.outboundCredential(ALICE, ORDERS);
        assertThat(endpoint.calls()).as("without expiry, cached until expires_in minus 30s").isEqualTo(3);
        clock.set(START.plusSeconds(60 + 3570));
        propagator.outboundCredential(ALICE, ORDERS);
        assertThat(endpoint.calls()).as("without expiry, exchanged again at expires_in minus 30s").isEqualTo(4);
    }

    static Stream<Arguments> uncachedResponses() {
        return Stream.of(
                Arguments.of("expires_in absent", null, ALICE),
                Arguments.of("expires_in 30", "30", ALICE),
                Arguments.of("expires_in 0", "0", ALICE),
                Arguments.of("source expiry already passed", "3600", Authority.of("alice", "alice-expired-token", START.minusSeconds(1))));
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("uncachedResponses")
    @DisplayName("ID-11 a response without expires_in, or one whose lifetime is 30 seconds or less, is used and not cached")
    void aResponseWithoutExpiresInOrWithALifetimeOf30SecondsOrLessIsUsedAndNotCached(String name, String expiresIn, Authority authority) {
        endpoint.expiresIn(expiresIn);

        Optional<String> first = propagator.outboundCredential(authority, ORDERS);
        Optional<String> second = propagator.outboundCredential(authority, ORDERS);

        assertThat(first).as("the token is used").hasValue("token-1");
        assertThat(second).as("the token is used").hasValue("token-2");
        assertThat(endpoint.calls()).as("each call exchanges").isEqualTo(2);
        assertThat(propagator.cacheKeys()).isEmpty();
    }

    @ParameterizedTest(name = "[{index}] expires_in {0}")
    @ValueSource(strings = {"1e400", "99999999999999999999", "1e999999999"})
    @DisplayName("ID-11 an expires_in too large for the clock is valid and the cache end is capped")
    void anExpiresInTooLargeForTheClockIsValidAndTheCacheEndIsCapped(String expiresIn) {
        endpoint.expiresIn(expiresIn);
        Authority expiring = Authority.of("alice", "alice-expiring-token", START.plusSeconds(60));

        assertThat(propagator.outboundCredential(expiring, ORDERS)).hasValue("token-1");
        clock.set(START.plusSeconds(59));
        assertThat(propagator.outboundCredential(expiring, ORDERS)).as("cached at +59s").hasValue("token-1");
        clock.set(START.plusSeconds(60));
        assertThat(propagator.outboundCredential(expiring, ORDERS)).as("exchanged again at the source expiry").hasValue("token-2");

        // Without a source expiry the cache end is the largest instant (Instant.MAX), and nothing overflows.
        assertThat(propagator.outboundCredential(ALICE, ORDERS)).hasValue("token-3");
        clock.set(START.plus(Duration.ofDays(365 * 1000L)));
        assertThat(propagator.outboundCredential(ALICE, ORDERS)).as("cached a thousand years on").hasValue("token-3");
        assertThat(endpoint.calls()).isEqualTo(3);
    }

    @Test
    @DisplayName("ID-11 the cache holds at most 10,000 entries and evicts the entry added earliest")
    void theCacheHoldsAtMost10000EntriesAndEvictsTheEntryAddedEarliest() {
        endpoint.expiresIn("3600");
        for (int i = 0; i < 10_001; i++) {
            propagator.outboundCredential(ALICE, internal("audience-" + i));
        }
        assertThat(endpoint.calls()).isEqualTo(10_001);
        assertThat(propagator.cacheKeys()).hasSize(10_000);

        propagator.outboundCredential(ALICE, internal("audience-1"));
        assertThat(endpoint.calls()).as("the second is still cached").isEqualTo(10_001);
        propagator.outboundCredential(ALICE, internal("audience-0"));
        assertThat(endpoint.calls()).as("the first was evicted").isEqualTo(10_002);
        propagator.outboundCredential(ALICE, internal("audience-1"));
        assertThat(endpoint.calls()).as("re-adding audience-0 evicted audience-1, added earliest, although it was used last")
                .isEqualTo(10_003);

        // An expired entry replaced by a new exchange counts as added last.
        clock.set(START);
        try (ExchangeAuthorityPropagator fresh = new ExchangeAuthorityPropagator(endpoint.tokenUri(), "edge", "edge-secret", clock)) {
            endpoint.expiresIn("100");
            fresh.outboundCredential(ALICE, internal("audience-0"));
            endpoint.expiresIn("3600");
            for (int i = 1; i < 10_000; i++) {
                fresh.outboundCredential(ALICE, internal("audience-" + i));
            }
            int before = endpoint.calls();

            clock.set(START.plusSeconds(70));
            fresh.outboundCredential(ALICE, internal("audience-0"));
            assertThat(endpoint.calls()).as("audience-0 expired at +70s").isEqualTo(before + 1);
            fresh.outboundCredential(ALICE, internal("audience-10000"));
            assertThat(endpoint.calls()).isEqualTo(before + 2);

            fresh.outboundCredential(ALICE, internal("audience-0"));
            assertThat(endpoint.calls()).as("the replaced entry counts as added last, so it is still cached").isEqualTo(before + 2);
            fresh.outboundCredential(ALICE, internal("audience-1"));
            assertThat(endpoint.calls()).as("audience-1 was now the entry added earliest, so it was evicted").isEqualTo(before + 3);
        }
    }

    @Test
    @DisplayName("ID-11 cache keys hold the SHA-256 hash of the credential, never the credential")
    void cacheKeysHoldTheSha256HashOfTheCredentialNeverTheCredential() throws NoSuchAlgorithmException {
        endpoint.expiresIn("3600");

        propagator.outboundCredential(ALICE, ORDERS);

        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                "alice-token".getBytes(StandardCharsets.UTF_8)));
        assertThat(propagator.cacheKeys()).singleElement().satisfies(key -> {
            assertThat(key.credentialHash()).isEqualTo(hash);
            assertThat(key.audience()).isEqualTo("orders");
            assertThat(key.toString()).doesNotContain("alice-token");
        });
    }

    private static Destination internal(String service) {
        return new Destination(INTERNAL, Optional.of(service));
    }

    /** A clock that stays at the instant the test sets. */
    private static final class MutableClock extends Clock {

        private volatile Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant now) {
            this.now = now;
        }

        @Override
        public Instant instant() {
            return now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    /**
     * A token endpoint on a free loopback port that answers each request with status 200 and {@code {"access_token":"token-<n>"}}, where
     * {@code n} counts the requests, plus {@code "expires_in":<raw JSON>} when one is set.
     */
    private static final class CountingTokenEndpoint implements AutoCloseable {

        private final HttpServer server;
        private final AtomicInteger calls = new AtomicInteger();
        private volatile String expiresIn;

        CountingTokenEndpoint() {
            try {
                server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            server.createContext("/token", exchange -> {
                try (exchange; InputStream in = exchange.getRequestBody()) {
                    in.readAllBytes();
                    String raw = expiresIn;
                    String body = "{\"access_token\":\"token-" + calls.incrementAndGet() + "\""
                            + (raw == null ? "" : ",\"expires_in\":" + raw) + "}";
                    byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length);
                    try (OutputStream out = exchange.getResponseBody()) {
                        out.write(bytes);
                    }
                }
            });
            server.start();
        }

        /** Sets the raw JSON of {@code expires_in} in later answers; {@code null} leaves it out. */
        void expiresIn(String rawJson) {
            this.expiresIn = rawJson;
        }

        int calls() {
            return calls.get();
        }

        URI tokenUri() {
            return URI.create("http://" + server.getAddress().getHostString() + ":" + server.getAddress().getPort() + "/token");
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
