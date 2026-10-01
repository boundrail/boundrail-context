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
 */
public final class BoundrailClients {

    /** The request attribute carrying the declared {@link Destination}, read by {@link ContextClientInterceptor}. */
    static final String DESTINATION_ATTRIBUTE = BoundrailClients.class.getName() + ".destination";

    private BoundrailClients() {
    }

    /** {@return a builder customizer declaring {@code Destination(INTERNAL, service)} for every request} */
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
