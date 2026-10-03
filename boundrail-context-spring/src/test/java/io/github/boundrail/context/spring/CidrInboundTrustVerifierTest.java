package io.github.boundrail.context.spring;

import static io.github.boundrail.context.spring.InboundTrustVerifier.Trust.TRUSTED_INTERNAL;
import static io.github.boundrail.context.spring.InboundTrustVerifier.Trust.UNTRUSTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import io.github.boundrail.context.spring.InboundTrustVerifier.Trust;
import io.github.boundrail.context.spring.support.RecordingResolverProvider;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.LazyInitializationBeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.BindException;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.core.env.MapPropertySource;
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
    @DisplayName("IN-08 a hex-form IPv4-mapped remote address is matched as IPv4")
    void aHexFormIpv4MappedRemoteAddressIsMatchedAsIpv4() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=10.0.0.0/8")
                .run(context -> assertThat(verdict(context, "::ffff:a00:1")).isEqualTo(TRUSTED_INTERNAL));
    }

    @Test
    @DisplayName("IN-08 IPv4-compatible and IPv4-translated entries are plain IPv6 entries")
    void ipv4CompatibleAndIpv4TranslatedEntriesArePlainIpv6Entries() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=::10.0.0.1,::ffff:0:10.0.0.1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(verdict(context, "10.0.0.1")).isEqualTo(UNTRUSTED);
            assertThat(verdict(context, "::10.0.0.1")).isEqualTo(TRUSTED_INTERNAL);
        });
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
        String probe = RecordingResolverProvider.probeName();
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=127.0.0.0/8").run(context -> {
            assertThat(verdict(context, "127.0.0.1")).as("the loopback literal is trusted").isEqualTo(TRUSTED_INTERNAL);
            assertThat(verdict(context, probe)).isEqualTo(UNTRUSTED);
        });
        assertThat(RecordingResolverProvider.wasLookedUp(probe)).as("the remote address was looked up").isFalse();
        assertProbeLookupsAreRecorded(probe);
    }

    @Test
    @DisplayName("IN-08 an over-long remote address is UNTRUSTED without costly parsing")
    void anOverLongRemoteAddressIsUntrustedWithoutCostlyParsing() {
        String overLong = ":".repeat(65535) + "%";
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

    @Test
    @DisplayName("IN-08 a bare address is a single host")
    void aBareAddressIsASingleHost() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=10.1.2.3,fd00::1").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(verdict(context, "10.1.2.3")).isEqualTo(TRUSTED_INTERNAL);
            assertThat(verdict(context, "10.1.2.4")).isEqualTo(UNTRUSTED);
            assertThat(verdict(context, "fd00::1")).isEqualTo(TRUSTED_INTERNAL);
            assertThat(verdict(context, "fd00::2")).isEqualTo(UNTRUSTED);
        });
    }

    @Test
    @DisplayName("IN-08 prefix lengths are 0 to 32 for IPv4 and 0 to 128 for IPv6")
    void prefixLengthsAre0To32ForIpv4And0To128ForIpv6() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=0.0.0.0/0,::/0").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(verdict(context, "203.0.113.5")).isEqualTo(TRUSTED_INTERNAL);
            assertThat(verdict(context, "2001:db8::1")).isEqualTo(TRUSTED_INTERNAL);
        });
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=10.0.0.1/32,fd00::1/128").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(verdict(context, "10.0.0.1")).isEqualTo(TRUSTED_INTERNAL);
            assertThat(verdict(context, "fd00::1")).isEqualTo(TRUSTED_INTERNAL);
        });
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @ValueSource(strings = {"10.0.0.1/8", "10.0.0.0/33", "fd00::/129", "10.0.0.0/-1", "10.0.0.0/", "::ffff:10.0.0.0/104", "::ffff:10.0.0.1",
            "fe80::1%eth0", "localhost", "orders.svc.test", "10.0.0", "[2001:db8::1]", "[2001:db8::]/32", "010.0.0.1", "10.0.0.256",
            "::ffff:10.0.0.01", "::10.0.0.01/128", "10.0.0.0/08", "10.0.0.0/+8",
            "::ffff:a00:1", "0:0:0:0:0:ffff:a00:1"})
    @DisplayName("IN-08 an invalid entry fails startup")
    void anInvalidEntryFailsStartup(String entry) {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=" + entry).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class);
        });
    }

    @Test
    @DisplayName("IN-08 entries are parsed without DNS lookups")
    void entriesAreParsedWithoutDnsLookups() {
        String probe = RecordingResolverProvider.probeName();
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=" + probe).run(context -> {
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class);
        });
        assertThat(RecordingResolverProvider.wasLookedUp(probe)).as("the entry was looked up").isFalse();
        assertProbeLookupsAreRecorded(probe);
    }

    @Test
    @DisplayName("IN-08 invalid entries are ignored when inbound.trust is not cidr")
    void invalidEntriesAreIgnoredWhenInboundTrustIsNotCidr() {
        runner.withPropertyValues("boundrail.context.inbound.trust=none", "boundrail.context.inbound.trusted-cidrs=10.0.0.1/8,localhost")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(verdict(context, "10.0.0.1")).isEqualTo(UNTRUSTED);
                });
    }

    @Test
    @DisplayName("IN-08 an invalid CIDR entry fails startup with lazy initialization")
    void anInvalidCidrEntryFailsStartupWithLazyInitialization() {
        runner.withPropertyValues("spring.main.lazy-initialization=true", "boundrail.context.inbound.trusted-cidrs=10.0.0.1/8")
                .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()))
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).rootCause().isInstanceOf(IllegalArgumentException.class);
                });
    }

    @Test
    @DisplayName("IN-08 one enclosing pair of square brackets is removed from the remote address")
    void oneEnclosingPairOfSquareBracketsIsRemovedFromTheRemoteAddress() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=2001:db8::/32").run(context -> {
            assertThat(verdict(context, "[2001:db8::1]")).isEqualTo(TRUSTED_INTERNAL);
            assertThat(verdict(context, "[[2001:db8::1]]")).isEqualTo(UNTRUSTED);
            assertThat(verdict(context, "[2001:db8::1")).isEqualTo(UNTRUSTED);
        });
    }

    @Test
    @DisplayName("IN-08 a bracketed IPv4 remote address is unbracketed")
    void aBracketedIpv4RemoteAddressIsUnbracketed() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=10.0.0.0/8")
                .run(context -> assertThat(verdict(context, "[10.0.0.1]")).isEqualTo(TRUSTED_INTERNAL));
    }

    @Test
    @DisplayName("IN-08 a remote address whose IPv4 part breaks the decimal form is UNTRUSTED")
    void aRemoteAddressWhoseIpv4PartBreaksTheDecimalFormIsUntrusted() {
        runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=0.0.0.0/0,::/0").run(context -> {
            assertThat(verdict(context, "10.0.0.1")).isEqualTo(TRUSTED_INTERNAL);
            assertThat(verdict(context, "010.0.0.1")).isEqualTo(UNTRUSTED);
            assertThat(verdict(context, "10.0.0.256")).isEqualTo(UNTRUSTED);
            assertThat(verdict(context, "::ffff:10.0.0.01")).isEqualTo(UNTRUSTED);
            assertThat(verdict(context, "::10.0.0.01")).isEqualTo(UNTRUSTED);
        });
    }

    @Test
    @DisplayName("IN-03 inbound.trust is matched with relaxed binding")
    void inboundTrustIsMatchedWithRelaxedBinding() {
        WebApplicationContextRunner cidrs = runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=10.0.0.0/8");
        cidrs.withPropertyValues("boundrail.context.inbound.trust=CIDR").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(verdict(context, "10.1.2.3")).isEqualTo(TRUSTED_INTERNAL);
        });
        cidrs.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("trust", Map.of("boundrail.context.inbound.trust", " Cidr "))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(verdict(context, "10.1.2.3")).isEqualTo(TRUSTED_INTERNAL);
                });
    }

    @Test
    @DisplayName("IN-03 an empty or blank inbound.trust means none")
    void anEmptyOrBlankInboundTrustMeansNone() {
        WebApplicationContextRunner cidrs = runner.withPropertyValues("boundrail.context.inbound.trusted-cidrs=0.0.0.0/0");
        cidrs.withPropertyValues("boundrail.context.inbound.trust=").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(verdict(context, "10.1.2.3")).isEqualTo(UNTRUSTED);
        });
        cidrs.withInitializer(context -> context.getEnvironment().getPropertySources()
                        .addFirst(new MapPropertySource("trust", Map.of("boundrail.context.inbound.trust", " "))))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(verdict(context, "10.1.2.3")).isEqualTo(UNTRUSTED);
                });
    }

    enum Startup { EAGER, LAZY, WITH_VERIFIER_BEAN }

    @ParameterizedTest(name = "{0}")
    @EnumSource(Startup.class)
    @DisplayName("IN-03 an invalid inbound.trust fails startup with a binding error naming the property")
    void anInvalidInboundTrustFailsStartupWithABindingErrorNamingTheProperty(Startup startup) {
        WebApplicationContextRunner configured = runner.withPropertyValues("boundrail.context.inbound.trust=bogus");
        configured = switch (startup) {
            case EAGER -> configured;
            case LAZY -> configured.withPropertyValues("spring.main.lazy-initialization=true")
                    .withInitializer(context -> context.addBeanFactoryPostProcessor(new LazyInitializationBeanFactoryPostProcessor()));
            case WITH_VERIFIER_BEAN -> configured.withBean(InboundTrustVerifier.class, () -> request -> UNTRUSTED);
        };
        configured.run(context -> {
            assertThat(context).hasFailed();
            List<String> boundNames = new ArrayList<>();
            for (Throwable cause = context.getStartupFailure(); cause != null; cause = cause.getCause()) {
                if (cause instanceof BindException bind) {
                    boundNames.add(bind.getName().toString());
                }
            }
            assertThat(boundNames).contains("boundrail.context.inbound.trust");
        });
    }

    /** The positive control: a direct lookup of the probe name fails and is recorded, so the provider is the JVM's resolver. */
    private static void assertProbeLookupsAreRecorded(String probe) {
        assertThatExceptionOfType(UnknownHostException.class).isThrownBy(() -> InetAddress.getByName(probe));
        assertThat(RecordingResolverProvider.wasLookedUp(probe)).as("the positive control was recorded").isTrue();
    }

    private static Trust verdict(AssertableWebApplicationContext context, String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return context.getBean(InboundTrustVerifier.class).verify(request);
    }
}
