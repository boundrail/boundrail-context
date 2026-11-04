package io.github.boundrail.context.spring;

import io.github.boundrail.context.Destination;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import org.springframework.web.client.RestClient;

/**
 * Declares the destination of every request made through the configured client (OUT-03). If both methods are applied to one
 * builder, the last one applied wins. No effect on a builder without the library's interceptor, i.e. one that is not
 * auto-configured, such as {@code RestClient.builder()}.
 *
 * <h2>Deadlines and timeouts (DL-07)</h2>
 * While a deadline is bound, the library's interceptor arms a timer for the remaining time on every client, for any destination, and
 * interrupts the calling thread if the time runs out before the response headers arrive; the call then fails with
 * {@code DeadlineExceededException} (stage {@code TIMEOUT}). Timeout capping is guaranteed only with the JDK {@code HttpClient} request
 * factory ({@code spring.http.clients.imperative.factory=jdk}). Other factories are cut off only where they honour the interrupt: Jetty
 * (which only gives up the wait; the exchange continues), Reactor Netty, and the Simple and Apache HttpComponents factories on virtual
 * threads; the Simple and Apache HttpComponents factories on platform threads are not capped. A timeout while reading the response body,
 * and a shorter timeout the client is configured with, surface as the client's own exceptions.
 * <ul>
 *   <li>At startup the library logs one WARN when the request factory builder Spring Boot auto-configures is not the JDK one. It reads
 *       that builder only, so the WARN can be wrong when an application customizer wraps Boot's builder or the application defines its
 *       own builder for the JDK factory, and a client given its own request factory in code is not checked.</li>
 *   <li>An application interrupt that arrives at the moment the timer fires is cleared too. An interrupt already set before the call is
 *       never cleared: the JDK client then fails at once and its exception propagates unchanged.</li>
 *   <li>A call made while or after the application context closes, when the timer has shut down, runs without the cap;
 *       {@code Boundrail-Deadline-Ms} and the expiry check before sending still apply.</li>
 * </ul>
 *
 * <h2>Expired deadlines in request handlers (DL-08)</h2>
 * A {@code DeadlineExceededException} escaping a handler is mapped to {@code boundrail.context.deadline.expired-status} by a resolver the
 * library appends to Spring MVC's own resolver chain. MVC configured by {@code WebMvcConfigurationSupport}, as {@code @EnableWebMvc} and
 * Spring Boot's MVC auto-configuration do, gets it; a {@code DispatcherServlet} without it does not.
 *
 * <h2>Redirects (OUT-05)</h2>
 * A 3xx redirect that the HTTP client follows is not classified again: the redirected request may carry the context headers written for
 * the original destination. Turn off redirect following on clients declared {@link #internal(String) internal} and on clients that reach
 * hosts matched by an internal host pattern.
 */
public final class BoundrailClients {

    /** The request attribute carrying the declared {@link Destination}, read by {@link ContextClientInterceptor}. */
    static final String DESTINATION_ATTRIBUTE = BoundrailClients.class.getName() + ".destination";

    private BoundrailClients() {
    }

    /**
     * {@return a builder customizer declaring {@code Destination(INTERNAL, service)} for every request}
     *
     * @param service the name of the destination service
     */
    public static Consumer<RestClient.Builder> internal(String service) {
        return declare(new Destination(Destination.Kind.INTERNAL, Optional.of(Objects.requireNonNull(service, "service"))));
    }

    /** {@return a builder customizer declaring {@code Destination(EXTERNAL, empty)} for every request} */
    public static Consumer<RestClient.Builder> external() {
        return declare(new Destination(Destination.Kind.EXTERNAL, Optional.empty()));
    }

    private static Consumer<RestClient.Builder> declare(Destination destination) {
        return builder -> builder.defaultRequest(spec -> spec.attribute(DESTINATION_ATTRIBUTE, destination));
    }
}
