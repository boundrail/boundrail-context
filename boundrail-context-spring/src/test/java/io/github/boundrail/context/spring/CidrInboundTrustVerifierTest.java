package io.github.boundrail.context.spring;

import static io.github.boundrail.context.spring.InboundTrustVerifier.Trust.TRUSTED_INTERNAL;
import static io.github.boundrail.context.spring.InboundTrustVerifier.Trust.UNTRUSTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.boundrail.context.spring.InboundTrustVerifier.Trust;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.mock.web.MockHttpServletRequest;

class CidrInboundTrustVerifierTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(BoundrailContextAutoConfiguration.class))
            .withPropertyValues("boundrail.context.inbound.trust=cidr");

    @Test
    @DisplayName("IN-03 with inbound.trust=cidr a remote address inside a trusted CIDR is TRUSTED_INTERNAL")
    void withInboundTrustCidrARemoteAddressInsideATrustedCidrIsTrustedInternal() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=10.0.0.0/8,fd00::/8").run(context -> {
            assertThat(verdict(context, "10.1.2.3")).isEqualTo(TRUSTED_INTERNAL);
            assertThat(verdict(context, "fd00::1")).isEqualTo(TRUSTED_INTERNAL);
        });
    }

    @Test
    @DisplayName("IN-03 a remote address outside every trusted CIDR is UNTRUSTED")
    void aRemoteAddressOutsideEveryTrustedCidrIsUntrusted() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=10.0.0.0/8,fd00::/8").run(context -> {
            assertThat(verdict(context, "11.0.0.1")).isEqualTo(UNTRUSTED);
            assertThat(verdict(context, "192.168.1.1")).isEqualTo(UNTRUSTED);
            assertThat(verdict(context, "fe00::1")).isEqualTo(UNTRUSTED);
        });
    }

    @Test
    @DisplayName("IN-03 the checked address is getRemoteAddr()")
    void theCheckedAddressIsGetRemoteAddr() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=10.0.0.0/8").run(context -> {
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.setRemoteAddr("203.0.113.5");
            request.addHeader("X-Forwarded-For", "10.0.0.1");

            assertThat(context.getBean(InboundTrustVerifier.class).verify(request)).isEqualTo(UNTRUSTED);
        });
    }

    @Test
    @DisplayName("IN-03 trusted-cidrs defaults to empty, so cidr trusts nobody")
    void trustedCidrsDefaultsToEmptySoCidrTrustsNobody() {
        runner.run(context -> {
            assertThat(context.getBean(BoundrailContextProperties.class).inbound().trustedCidrs()).isEmpty();
            assertThat(verdict(context, "10.1.2.3")).isEqualTo(UNTRUSTED);
            assertThat(verdict(context, "127.0.0.1")).isEqualTo(UNTRUSTED);
            assertThat(verdict(context, "::1")).isEqualTo(UNTRUSTED);
        });
    }

    @Test
    @DisplayName("IN-03 a verifier bean wins over inbound.trust=cidr")
    void aVerifierBeanWinsOverInboundTrustCidr() {
        InboundTrustVerifier application = request -> UNTRUSTED;
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=0.0.0.0/0,::/0")
                .withBean(InboundTrustVerifier.class, () -> application)
                .run(context -> {
                    assertThat(context).hasSingleBean(InboundTrustVerifier.class);
                    assertThat(context.getBean(InboundTrustVerifier.class)).isSameAs(application);
                    assertThat(verdict(context, "10.1.2.3")).isEqualTo(UNTRUSTED);
                });
    }

    @Test
    @DisplayName("IN-08 an IPv4 remote address matches only IPv4 entries and an IPv6 address only IPv6 entries")
    void anIpv4RemoteAddressMatchesOnlyIpv4EntriesAndAnIpv6AddressOnlyIpv6Entries() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=::/0").run(context -> {
            assertThat(verdict(context, "fd00::1")).as("an IPv6 address matches ::/0").isEqualTo(TRUSTED_INTERNAL);
            assertThat(verdict(context, "10.1.2.3")).isEqualTo(UNTRUSTED);
        });
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=0.0.0.0/0").run(context -> {
            assertThat(verdict(context, "10.1.2.3")).as("an IPv4 address matches 0.0.0.0/0").isEqualTo(TRUSTED_INTERNAL);
            assertThat(verdict(context, "fd00::1")).isEqualTo(UNTRUSTED);
        });
    }

    @Test
    @DisplayName("IN-08 an IPv4-mapped remote address is matched as IPv4")
    void anIpv4MappedRemoteAddressIsMatchedAsIpv4() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=10.0.0.0/8")
                .run(context -> assertThat(verdict(context, "::ffff:10.1.2.3")).isEqualTo(TRUSTED_INTERNAL));
    }

    @Test
    @DisplayName("CORE-13 the CIDR verifier rejects a null request")
    void theCidrVerifierRejectsANullRequest() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=10.0.0.0/8").run(context -> {
            assertThat(verdict(context, "10.1.2.3")).as("the CIDR verifier is the bean").isEqualTo(TRUSTED_INTERNAL);
            assertThatNullPointerException().isThrownBy(() -> context.getBean(InboundTrustVerifier.class).verify(null));
        });
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"unknown", "orders.svc.test", ""})
    @DisplayName("IN-08 a remote address that is not an IP literal is UNTRUSTED")
    void aRemoteAddressThatIsNotAnIpLiteralIsUntrusted(String remoteAddress) {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=0.0.0.0/0,::/0").run(context -> {
            assertThat(verdict(context, "10.1.2.3")).as("a literal is trusted").isEqualTo(TRUSTED_INTERNAL);
            assertThat(verdict(context, remoteAddress)).isEqualTo(UNTRUSTED);
        });
    }

    @Test
    @DisplayName("IN-08 the remote address is parsed without DNS lookups")
    void theRemoteAddressIsParsedWithoutDnsLookups() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=127.0.0.0/8").run(context -> {
            assertThat(verdict(context, "127.0.0.1")).as("the loopback literal is trusted").isEqualTo(TRUSTED_INTERNAL);
            assertThat(verdict(context, "localhost")).isEqualTo(UNTRUSTED);
        });
    }

    @Test
    @DisplayName("IN-08 an over-long remote address is UNTRUSTED without costly parsing")
    void anOverLongRemoteAddressIsUntrustedWithoutCostlyParsing() {
        String overLong = ":".repeat(8191) + "%";
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=::/0").run(context -> {
            assertThat(verdict(context, "fd00:0000:0000:0000:0000:0000:0000:0001"))
                    .as("a 39-character IPv6 literal is trusted")
                    .isEqualTo(TRUSTED_INTERNAL);
            assertThat(assertTimeoutPreemptively(Duration.ofMillis(200), () -> verdict(context, overLong)))
                    .isEqualTo(UNTRUSTED);
        });
    }

    @Test
    @DisplayName("IN-08 an IPv6 remote address with a zone ID is UNTRUSTED")
    void anIpv6RemoteAddressWithAZoneIdIsUntrusted() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=fe80::/10").run(context -> {
            assertThat(verdict(context, "fe80::1%eth0")).isEqualTo(UNTRUSTED);
            assertThat(verdict(context, "fe80::1")).isEqualTo(TRUSTED_INTERNAL);
        });
    }

    private static Trust verdict(AssertableWebApplicationContext context, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return context.getBean(InboundTrustVerifier.class).verify(request);
    }
}
