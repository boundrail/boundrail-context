package io.github.boundrail.context.demo;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The demo's services on random loopback ports, talking real HTTP, as Docker Compose runs them under fixed names. */
final class DemoServices implements AutoCloseable {

    private final ConfigurableApplicationContext orders;
    private final ConfigurableApplicationContext partner;
    private final ConfigurableApplicationContext edge;

    private DemoServices(String[] ordersArguments, Class<?>[] edgeOverrides) {
        orders = start(new SpringApplicationBuilder(OrdersService.class).properties("boundrail.context.inbound.trusted-cidrs=127.0.0.1/32"),
                ordersArguments);
        try {
            partner = start(new SpringApplicationBuilder(PartnerService.class));
        } catch (RuntimeException e) {
            orders.close();
            throw e;
        }
        try {
            edge = start(new SpringApplicationBuilder(EdgeService.class).sources(edgeOverrides)
                    .properties("demo.orders-url=" + uri(orders), "demo.partner-url=" + uri(partner),
                            "spring.main.allow-bean-definition-overriding=" + (edgeOverrides.length > 0)));
        } catch (RuntimeException e) {
            partner.close();
            orders.close();
            throw e;
        }
    }

    /**
     * Starts the services.
     *
     * @param edgeOverrides configuration classes whose beans replace {@code edge}'s beans of the same name
     */
    static DemoServices start(Class<?>... edgeOverrides) {
        return new DemoServices(new String[0], edgeOverrides);
    }

    /**
     * Starts the services.
     *
     * @param ordersArguments command-line arguments for {@code orders}; {@code .properties(...)} are defaults, which rank below
     *     {@code @PropertySource}
     */
    static DemoServices startWithOrders(String... ordersArguments) {
        return new DemoServices(ordersArguments, new Class<?>[0]);
    }

    URI edge() {
        return uri(edge);
    }

    @Override
    public void close() {
        edge.close();
        partner.close();
        orders.close();
    }

    private static ConfigurableApplicationContext start(SpringApplicationBuilder service, String... arguments) {
        return service.properties("server.address=127.0.0.1", "server.port=0", "spring.main.banner-mode=off").run(arguments);
    }

    private static URI uri(ConfigurableApplicationContext service) {
        return URI.create("http://127.0.0.1:" + service.getEnvironment().getRequiredProperty("local.server.port"));
    }

    /** Adds a filter to {@code edge} that answers the first request to each path with a 504, as a cold JVM that uses up the budget does. */
    @Configuration(proxyBeanMethods = false)
    static class FirstCallTimesOut {

        @Bean
        static Filter firstCallTimesOut() {
            Set<String> called = ConcurrentHashMap.newKeySet();
            return (request, response, chain) -> {
                if (called.add(((HttpServletRequest) request).getRequestURI())) {
                    ((HttpServletResponse) response).sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT);
                } else {
                    chain.doFilter(request, response);
                }
            };
        }
    }
}
