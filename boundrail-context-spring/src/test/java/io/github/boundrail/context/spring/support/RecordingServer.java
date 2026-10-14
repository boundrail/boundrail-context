package io.github.boundrail.context.spring.support;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A JDK {@link HttpServer} on a free loopback port that records every request and answers each with a fixed status, {@code Content-Type}
 * and body (by default 200 and {@code text/plain}), optionally after a delay before the headers or before the body.
 */
public final class RecordingServer implements AutoCloseable {

    /**
     * One recorded request.
     *
     * @param method the request method
     * @param uri the request URI, as received
     * @param headers the request headers, by name as the JDK server reports them
     * @param body the request body, decoded as UTF-8
     */
    public record Request(String method, URI uri, Map<String, List<String>> headers, String body) {
    }

    private static final String TEXT_PLAIN = "text/plain; charset=UTF-8";

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private final ExecutorService handlers = Executors.newCachedThreadPool(task -> {
        Thread thread = new Thread(task, "recording-server");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Records each request, waits {@code headerDelay}, sends the status and headers, waits {@code bodyDelay}, then sends the body. A
     * {@code null} {@code contentType} sends no {@code Content-Type}. Handlers run on their own threads, so a waiting response does not
     * block other requests, and {@link #close} interrupts them.
     */
    private RecordingServer(int status, String contentType, String responseBody, Duration headerDelay, Duration bodyDelay) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        server.setExecutor(handlers);
        server.createContext("/", exchange -> {
            try (exchange; InputStream in = exchange.getRequestBody()) {
                String requestBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI(), Map.copyOf(exchange.getRequestHeaders()),
                        requestBody));
                sleep(headerDelay);
                if (contentType != null) {
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                }
                exchange.sendResponseHeaders(status, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.flush();
                    sleep(bodyDelay);
                    out.write(body);
                }
            }
        });
        server.start();
    }

    private static void sleep(Duration delay) throws IOException {
        if (delay.isZero()) {
            return;
        }
        try {
            Thread.sleep(delay);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("closed", e);
        }
    }

    /** {@return a started server answering every request with {@code responseBody}} */
    public static RecordingServer start(String responseBody) {
        return new RecordingServer(200, TEXT_PLAIN, responseBody, Duration.ZERO, Duration.ZERO);
    }

    /**
     * {@return a started server answering every request with {@code status}, {@code contentType} and {@code responseBody}} A {@code null}
     * {@code contentType} sends no {@code Content-Type}.
     */
    public static RecordingServer start(int status, String contentType, String responseBody) {
        return new RecordingServer(status, contentType, responseBody, Duration.ZERO, Duration.ZERO);
    }

    /** {@return a started server answering every request with {@code responseBody}, after waiting {@code delay} before the headers} */
    public static RecordingServer startDelayed(String responseBody, Duration delay) {
        return new RecordingServer(200, TEXT_PLAIN, responseBody, delay, Duration.ZERO);
    }

    /** {@return a started server that sends the headers at once and {@code responseBody} after {@code bodyDelay}} */
    public static RecordingServer startWithSlowBody(String responseBody, Duration bodyDelay) {
        return new RecordingServer(200, TEXT_PLAIN, responseBody, Duration.ZERO, bodyDelay);
    }

    /** {@return the base URI, such as {@code http://127.0.0.1:54321}, with an IPv6 host in brackets} */
    public URI uri() {
        try {
            return new URI("http", null, server.getAddress().getHostString(), server.getAddress().getPort(), null, null, null);
        } catch (URISyntaxException e) {
            throw new IllegalStateException(e);
        }
    }

    /** {@return the requests received so far, in order} */
    public List<Request> requests() {
        return List.copyOf(requests);
    }

    @Override
    public void close() {
        server.stop(0);
        handlers.shutdownNow();
    }
}
