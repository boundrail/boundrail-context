package io.github.boundrail.context.demo;

import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.net.URI;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.kafka.KafkaContainer;

/**
 * The demo's services on random loopback ports, talking real HTTP and a real broker, as Docker Compose runs them under fixed names. Every run
 * gets the broker, so {@code edge}'s listener runs as it does in Compose; one container per test JVM, started on first use and stopped by
 * Testcontainers when the JVM exits.
 */
final class DemoServices implements AutoCloseable {

    /** Same Kafka version as the kafka-clients managed by the Spring Boot BOM, as in the topology. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.2.1";

    private static KafkaContainer broker;

    private final ConfigurableApplicationContext orders;
    private final ConfigurableApplicationContext partner;
    private final ConfigurableApplicationContext edge;

    private DemoServices(String[] ordersArguments, String[] edgeArguments, Class<?>[] edgeOverrides) {
        String brokerArgument = "--spring.kafka.bootstrap-servers=" + bootstrapServers();
        orders = start(new SpringApplicationBuilder(OrdersService.class).properties("boundrail.context.inbound.trusted-cidrs=127.0.0.1/32"),
                with(brokerArgument, ordersArguments));
        try {
            partner = start(new SpringApplicationBuilder(PartnerService.class));
        } catch (RuntimeException e) {
            orders.close();
            throw e;
        }
        try {
            edge = start(new SpringApplicationBuilder(EdgeService.class).sources(edgeOverrides)
                    .properties("demo.orders-url=" + uri(orders), "demo.partner-url=" + uri(partner),
                            "spring.main.allow-bean-definition-overriding=" + (edgeOverrides.length > 0)),
                    with(brokerArgument, edgeArguments));
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
        return new DemoServices(new String[0], new String[0], edgeOverrides);
    }

    /**
     * Starts the services.
     *
     * @param ordersArguments command-line arguments for {@code orders}; {@code .properties(...)} are defaults, which rank below
     *     {@code @PropertySource}
     */
    static DemoServices startWithOrders(String... ordersArguments) {
        return new DemoServices(ordersArguments, new String[0], new Class<?>[0]);
    }

    /**
     * Starts the services.
     *
     * @param edgeArguments command-line arguments for {@code edge}; {@code .properties(...)} are defaults, which rank below {@code @PropertySource}
     */
    static DemoServices startWithEdge(String... edgeArguments) {
        return new DemoServices(new String[0], edgeArguments, new Class<?>[0]);
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

    private static synchronized String bootstrapServers() {
        if (broker == null) {
            KafkaContainer started = new KafkaContainer(KAFKA_IMAGE);
            started.start();
            broker = started;
        }
        return broker.getBootstrapServers();
    }

    private static String[] with(String argument, String[] arguments) {
        return Stream.concat(Stream.of(argument), Stream.of(arguments)).toArray(String[]::new);
    }

    private static ConfigurableApplicationContext start(SpringApplicationBuilder service, String... arguments) {
        return service.properties("server.address=127.0.0.1", "server.port=0", "server.shutdown=immediate", "spring.main.banner-mode=off")
                .run(arguments);
    }

    private static URI uri(ConfigurableApplicationContext service) {
        return URI.create("http://127.0.0.1:" + service.getEnvironment().getRequiredProperty("local.server.port"));
    }

    /** Adds a filter to {@code edge} that answers the first request to each flow with a 504, as a cold JVM that uses up the budget does. */
    @Configuration(proxyBeanMethods = false)
    static class FirstCallTimesOut {

        @Bean
        static Filter firstCallTimesOut() {
            Set<String> called = ConcurrentHashMap.newKeySet();
            return (request, response, chain) -> {
                String path = ((HttpServletRequest) request).getRequestURI();
                if (Set.of("/deadline", "/external-call").contains(path) && called.add(path)) {
                    ((HttpServletResponse) response).sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT);
                } else {
                    chain.doFilter(request, response);
                }
            };
        }
    }
}
