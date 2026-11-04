package io.github.boundrail.context.it.topology;

import io.github.boundrail.context.Violation;
import io.github.boundrail.context.test.TestInboundTrustVerifier;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ExtensionContext.Namespace;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.LoggerFactory;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.kafka.KafkaContainer;

/**
 * JUnit extension that starts the test topology once per test run: a Testcontainers Kafka broker and the five services,
 * each a separate Spring Boot application context on a random loopback port. Tests receive it as a {@link Running}
 * parameter.
 */
public final class Topology implements BeforeAllCallback, ParameterResolver {

    /** The topology's services, with the hosts-file name clients address them by. */
    public enum Service {
        EDGE("edge", "edge.svc.test", EdgeApp.class),
        ORDERS("orders", "orders.svc.test", OrdersApp.class),
        BILLING("billing", "billing.svc.test", BillingApp.class),
        PARTNER("partner", "partner.example.test", PartnerApp.class),
        TOKEN_EXCHANGE("token-exchange", "token-exchange.svc.test", TokenExchangeApp.class);

        private final String serviceName;
        private final String host;
        private final Class<?> application;

        Service(String serviceName, String host, Class<?> application) {
            this.serviceName = serviceName;
            this.host = host;
            this.application = application;
        }
    }

    private static final Namespace NAMESPACE = Namespace.create(Topology.class);

    /** Same Kafka version as the kafka-clients managed by the Spring Boot BOM. */
    private static final String KAFKA_IMAGE = "apache/kafka:4.2.1";

    /** Only edge is secured; the other services run without Spring Security's auto-configuration. */
    private static final String SECURITY_AUTO_CONFIGURATIONS = String.join(",",
            "org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
            "org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration");

    @Override
    public void beforeAll(ExtensionContext context) {
        running(context);
    }

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == Running.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        return running(context);
    }

    private static Running running(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE).computeIfAbsent(Running.class, type -> Running.start(), Running.class);
    }

    /** The started topology; closed by JUnit when the test run ends. */
    public static final class Running implements AutoCloseable {

        private final KafkaContainer kafka =
                new KafkaContainer(KAFKA_IMAGE).withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("kafka")));
        private final ObservedContext observed = new ObservedContext();
        private final TestJwts jwts = new TestJwts();
        private final Map<Service, ConfigurableApplicationContext> contexts = new EnumMap<>(Service.class);

        private Running() {}

        private static Running start() {
            Running running = new Running();
            try {
                running.kafka.start();
                for (Service service : Service.values()) {
                    running.contexts.put(service, running.startService(service));
                }
                return running;
            } catch (RuntimeException e) {
                running.close();
                throw e;
            }
        }

        /**
         * Starts an extra edge instance with the common properties plus the given ones, for a scenario that needs other configuration or
         * asserts edge's startup log entries. The caller closes it.
         */
        public ConfigurableApplicationContext startEdge(String... properties) {
            return startService(Service.EDGE, properties);
        }

        /** The base URI of an extra edge instance started with {@link #startEdge}. */
        public URI uri(ConfigurableApplicationContext edge) {
            int port = edge.getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            return URI.create("http://" + Service.EDGE.host + ":" + port);
        }

        private ConfigurableApplicationContext startService(Service service, String... extraProperties) {
            List<String> properties = new ArrayList<>(List.of(
                    "spring.application.name=" + service.serviceName,
                    "server.address=127.0.0.1",
                    "server.port=0",
                    "boundrail.context.destinations.internal-hosts=*.svc.test",
                    "spring.main.banner-mode=off",
                    // Logging is initialised once per JVM, so the first service's name would label every log line.
                    "logging.include-application-name=false",
                    // Every log line shows the MDC's tenant (LOG-01, IT-1.6).
                    "logging.pattern.console=%d{yyyy-MM-dd'T'HH:mm:ss.SSSXXX} %5p [%t] %logger{40} tenant=%X{tenant} : %m%n",
                    "spring.kafka.bootstrap-servers=" + kafka.getBootstrapServers()));
            if (service != Service.EDGE) {
                properties.add("spring.autoconfigure.exclude=" + SECURITY_AUTO_CONFIGURATIONS);
            } else {
                // Boot applies Micrometer's context-propagating decorator to edge's taskExecutor (IT-1.1).
                properties.add("spring.task.execution.propagate-context=true");
            }
            if (service == Service.BILLING) {
                // IT-3.2: billing's clients use the JDK HttpClient request factory with a configured read timeout of 10s.
                properties.add("spring.http.clients.imperative.factory=jdk");
                properties.add("spring.http.clients.read-timeout=10s");
                // IT-3.8, IT-4.9: billing's listener reads orders.events from the start, so a record published before its partitions are
                // assigned is not lost; the broker creates the topic on first use.
                properties.add("spring.kafka.consumer.group-id=billing");
                properties.add("spring.kafka.consumer.auto-offset-reset=earliest");
            }
            properties.addAll(List.of(extraProperties));
            return new SpringApplicationBuilder(service.application)
                    .properties(properties.toArray(String[]::new))
                    .initializers(context -> {
                        context.getBeanFactory().registerSingleton("observedContext", observed);
                        context.getBeanFactory().registerSingleton("testJwts", jwts);
                        context.getBeanFactory().registerSingleton("topologyPolicy", TopologyPolicy.policy());
                        // Each service counts its own violations (DIAG-03).
                        context.getBeanFactory().registerSingleton("meterRegistry", new SimpleMeterRegistry());
                        // Every service trusts callers whose client applies TestInboundTrustVerifier.peer() (TEST-01).
                        context.getBeanFactory().registerSingleton("inboundTrustVerifier", new TestInboundTrustVerifier());
                        if (service == Service.EDGE || service == Service.ORDERS || service == Service.BILLING) {
                            // These services resolve the other services' URIs per request: those start later, on random ports.
                            context.getBeanFactory().registerSingleton("topology", this);
                        }
                    })
                    .run();
        }

        /** The base URI of a service, addressed by its hosts-file name. */
        public URI uri(Service service) {
            int port = contexts.get(service).getEnvironment().getRequiredProperty("local.server.port", Integer.class);
            return URI.create("http://" + service.host + ":" + port);
        }

        public ObservedContext observed() {
            return observed;
        }

        public TestJwts jwts() {
            return jwts;
        }

        public String kafkaBootstrapServers() {
            return kafka.getBootstrapServers();
        }

        /** How many times token-exchange's token endpoint has been called in this run. */
        public int tokenExchangeCalls() {
            return contexts.get(Service.TOKEN_EXCHANGE).getBean(TokenExchangeApp.class).calls();
        }

        /** How many violations of this kind a service has counted in this run, summed over keys and boundaries. */
        public double violations(Service service, Violation.Kind kind) {
            return contexts.get(service).getBean(MeterRegistry.class).find("boundrail.context.violations")
                    .tag("kind", kind.name().toLowerCase(Locale.ROOT))
                    .counters().stream()
                    .mapToDouble(Counter::count)
                    .sum();
        }

        /** How many violations of this kind a service has counted in this run for the key, summed over boundaries. */
        public double violations(Service service, Violation.Kind kind, String key) {
            return contexts.get(service).getBean(MeterRegistry.class).find("boundrail.context.violations")
                    .tag("kind", kind.name().toLowerCase(Locale.ROOT))
                    .tag("key", key.toLowerCase(Locale.ROOT))
                    .counters().stream()
                    .mapToDouble(Counter::count)
                    .sum();
        }

        /** The tag values of every violation counter a service has registered in this run. */
        public List<String> violationTagValues(Service service) {
            return contexts.get(service).getBean(MeterRegistry.class).find("boundrail.context.violations")
                    .counters().stream()
                    .flatMap(counter -> counter.getId().getTags().stream())
                    .map(Tag::getValue)
                    .toList();
        }

        @Override
        public void close() {
            try {
                for (ConfigurableApplicationContext context : contexts.values()) {
                    try {
                        context.close();
                    } catch (RuntimeException e) {
                        System.getLogger(Topology.class.getName())
                                .log(System.Logger.Level.WARNING, "Failed to close a topology service", e);
                    }
                }
            } finally {
                kafka.stop();
            }
        }
    }
}
