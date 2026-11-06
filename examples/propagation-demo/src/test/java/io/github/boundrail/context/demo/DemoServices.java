package io.github.boundrail.context.demo;

import java.net.URI;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;

/** The demo's services on random loopback ports, talking real HTTP, as Docker Compose runs them under fixed names. */
final class DemoServices implements AutoCloseable {

    private final ConfigurableApplicationContext partner;
    private final ConfigurableApplicationContext edge;

    private DemoServices(Class<?>... edgeOverrides) {
        partner = start(new SpringApplicationBuilder(PartnerService.class));
        try {
            edge = start(new SpringApplicationBuilder(EdgeService.class).sources(edgeOverrides)
                    .properties("demo.partner-url=" + uri(partner),
                            "spring.main.allow-bean-definition-overriding=" + (edgeOverrides.length > 0)));
        } catch (RuntimeException e) {
            partner.close();
            throw e;
        }
    }

    /**
     * Starts the services.
     *
     * @param edgeOverrides configuration classes whose beans replace {@code edge}'s beans of the same name
     */
    static DemoServices start(Class<?>... edgeOverrides) {
        return new DemoServices(edgeOverrides);
    }

    URI edge() {
        return uri(edge);
    }

    @Override
    public void close() {
        edge.close();
        partner.close();
    }

    private static ConfigurableApplicationContext start(SpringApplicationBuilder service) {
        return service.properties("server.address=127.0.0.1", "server.port=0", "spring.main.banner-mode=off").run();
    }

    private static URI uri(ConfigurableApplicationContext service) {
        return URI.create("http://127.0.0.1:" + service.getEnvironment().getRequiredProperty("local.server.port"));
    }
}
