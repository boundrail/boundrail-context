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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** A JDK {@link HttpServer} on a free loopback port that records every request and answers each with status 200 and a fixed body. */
public final class RecordingServer implements AutoCloseable {

    /**
     * One recorded request.
     *
     * @param method the request method
     * @param uri the request URI, as received
     * @param headers the request headers, by name as the JDK server reports them
     */
    public record Request(String method, URI uri, Map<String, List<String>> headers) {
    }

    private final HttpServer server;
    private final List<Request> requests = new CopyOnWriteArrayList<>();

    private RecordingServer(String responseBody) {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        server.createContext("/", exchange -> {
            try (exchange; InputStream in = exchange.getRequestBody()) {
                in.readAllBytes();
                requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI(), Map.copyOf(exchange.getRequestHeaders())));
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=UTF-8");
                exchange.sendResponseHeaders(200, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        server.start();
    }

    /** {@return a started server answering every request with {@code responseBody}} */
    public static RecordingServer start(String responseBody) {
        return new RecordingServer(responseBody);
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
    }
}
