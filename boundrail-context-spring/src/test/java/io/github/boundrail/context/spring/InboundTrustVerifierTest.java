package io.github.boundrail.context.spring;

import static io.github.boundrail.context.Boundary.ATTACHED;
import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

class InboundTrustVerifierTest {

    private static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);

    private static final ContextPolicy POLICY = ContextPolicy.builder()
            .key(TENANT, rules -> rules.codec(Codecs.string()).wireName("Tenant-Id").propagateTo(ATTACHED, INTERNAL_CALL))
            .build();

    private static final Consumer<MockHttpServletRequest> TENANT_HEADER = request -> request.addHeader("Tenant-Id", "acme");

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            .withBean(ContextPolicy.class, () -> POLICY);

    @Test
    @DisplayName("IN-03 the default verifier returns UNTRUSTED for every request")
    void theDefaultVerifierReturnsUntrustedForEveryRequest() {
        runner.run(context -> {
            assertThat(context.getBean(BoundrailContextProperties.class).inbound().trust())
                    .isEqualTo(BoundrailContextProperties.Inbound.Trust.NONE);
            assertUntrusted(context.getBean(InboundTrustVerifier.class));
            assertThat(ContextInboundFilterTest.observe(ContextInboundFilterTest.inboundRegistration(context).getFilter(),
                    TENANT, TENANT_HEADER)).as("the default verifier binds nothing").isEmpty();
        });
        runner.withPropertyValues("boundrail.context.inbound.trust=none").run(context -> {
            assertThat(context.getBean(BoundrailContextProperties.class).inbound().trust())
                    .isEqualTo(BoundrailContextProperties.Inbound.Trust.NONE);
            assertUntrusted(context.getBean(InboundTrustVerifier.class));
            assertThat(ContextInboundFilterTest.observe(ContextInboundFilterTest.inboundRegistration(context).getFilter(),
                    TENANT, TENANT_HEADER)).as("inbound.trust=none binds nothing").isEmpty();
        });
        runner.withBean(InboundTrustVerifier.class, () -> request -> InboundTrustVerifier.Trust.TRUSTED_INTERNAL).run(context ->
                assertThat(ContextInboundFilterTest.observe(ContextInboundFilterTest.inboundRegistration(context).getFilter(),
                        TENANT, TENANT_HEADER)).as("the same request through a trusting bean").contains("acme"));
    }

    @Test
    @DisplayName("CORE-13 the default verifier rejects a null request")
    void theDefaultVerifierRejectsANullRequest() {
        runner.run(context -> assertThatNullPointerException().isThrownBy(() -> context.getBean(InboundTrustVerifier.class).verify(null)));
    }

    private static void assertUntrusted(InboundTrustVerifier verifier) {
        MockHttpServletRequest plain = new MockHttpServletRequest();
        MockHttpServletRequest withHeaders = new MockHttpServletRequest();
        withHeaders.addHeader("Tenant-Id", "acme");
        withHeaders.addHeader("Boundrail-Initiator", "edge");
        MockHttpServletRequest loopback = new MockHttpServletRequest();
        loopback.setRemoteAddr("127.0.0.1");
        MockHttpServletRequest privateAddress = new MockHttpServletRequest();
        privateAddress.setRemoteAddr("10.1.2.3");

        assertThat(verifier.verify(plain)).isEqualTo(InboundTrustVerifier.Trust.UNTRUSTED);
        assertThat(verifier.verify(withHeaders)).isEqualTo(InboundTrustVerifier.Trust.UNTRUSTED);
        assertThat(verifier.verify(loopback)).isEqualTo(InboundTrustVerifier.Trust.UNTRUSTED);
        assertThat(verifier.verify(privateAddress)).isEqualTo(InboundTrustVerifier.Trust.UNTRUSTED);
    }
}
