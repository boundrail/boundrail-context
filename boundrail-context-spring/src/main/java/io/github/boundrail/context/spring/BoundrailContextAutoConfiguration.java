package io.github.boundrail.context.spring;

import io.github.boundrail.context.Authority;
import io.github.boundrail.context.AuthorityPropagator;
import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.DestinationClassifier;
import io.github.boundrail.context.HostPatternClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import java.net.URI;
import java.time.Clock;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.logging.LogFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.BeanFactoryUtils;
import org.springframework.beans.factory.ListableBeanFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.boot.restclient.RestTemplateCustomizer;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.AbstractOAuth2TokenAuthenticationToken;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerAdapter;

/** Spring Boot auto-configuration for Boundrail Context. */
@AutoConfiguration
@EnableConfigurationProperties(BoundrailContextProperties.class)
public class BoundrailContextAutoConfiguration {

    /** The built-in policy merged, in bean order, with every {@code ContextPolicy} bean; a conflict fails startup (POL-07, POL-05). */
    @Bean
    @Lazy(false)
    EffectivePolicy boundrailEffectivePolicy(ObjectProvider<ContextPolicy> policies) {
        ContextPolicy effective = BuiltInKeys.defaultPolicy();
        for (ContextPolicy policy : policies.orderedStream().toList()) {
            effective = effective.merge(policy);
        }
        return new EffectivePolicy(effective);
    }

    /** Logs violations and, with Micrometer, counts them (DIAG-03). */
    @Bean
    @Lazy(false)
    ViolationReporter boundrailViolationReporter(EffectivePolicy policy, BoundrailContextProperties properties,
            ObjectProvider<ViolationReporter.Counter> counter, ObjectProvider<ViolationListener> listeners,
            ConfigurableListableBeanFactory beanFactory, ExecutorTable executorTable) {
        Set<ViolationListener> ancestors = ancestorListeners(beanFactory);
        return new ViolationReporter(policy, properties.violations().logInterval(), counter.getIfAvailable(() -> violation -> { }),
                () -> listeners.orderedStream().filter(listener -> !ancestors.contains(listener)), executorTable::evidence,
                LogFactory.getLog(ViolationReporter.class), Clock.systemUTC());
    }

    /**
     * {@return the singleton listener beans of the ancestor contexts, by identity} The provider's ordered stream also holds them, so the
     * reporter drops them and calls only this context's listeners, prototype-scoped ones included (DIAG-11). The ancestors are refreshed
     * before this context, so they are collected once.
     */
    private static Set<ViolationListener> ancestorListeners(ConfigurableListableBeanFactory beanFactory) {
        Set<ViolationListener> ancestors = Collections.newSetFromMap(new IdentityHashMap<>());
        if (beanFactory.getParentBeanFactory() instanceof ListableBeanFactory parent) {
            ancestors.addAll(BeanFactoryUtils.beansOfTypeIncludingAncestors(parent, ViolationListener.class, false, false).values());
        }
        return ancestors;
    }

    /** The context's executor beans by thread-name prefix, built once the context has refreshed, for executor evidence (DIAG-04, DIAG-12). */
    @Bean
    @Lazy(false)
    ExecutorTable boundrailExecutorTable() {
        return new ExecutorTable();
    }

    /**
     * Decorates Spring's task executor and scheduler beans (EXE-03). {@code static}, so this configuration class is not created early, and
     * the effective policy is resolved only when a task is first decorated.
     */
    @Bean
    static ExecutorDecoratingPostProcessor boundrailExecutorDecoratingPostProcessor(ObjectProvider<EffectivePolicy> policy,
            Environment environment) {
        return new ExecutorDecoratingPostProcessor(policy, environment);
    }

    /**
     * Audits the executor beans when the application is ready, unless {@code audit.enabled=false} (DIAG-06). The flags are bound as Boot
     * booleans, so an invalid value fails startup.
     */
    @Bean
    @Lazy(false)
    ExecutorAudit boundrailExecutorAudit(ExecutorTable executorTable, BoundrailContextProperties properties) {
        return new ExecutorAudit(executorTable, properties.executors().modes().keySet(), properties.audit().enabled(),
                properties.audit().failOnGap(), LogFactory.getLog(ExecutorAudit.class));
    }

    /** Applies the capture-site properties that are set, JVM-wide, at startup, also under lazy initialization (DIAG-05). */
    @Bean
    @Lazy(false)
    CaptureSiteSettings boundrailCaptureSiteSettings(Environment environment) {
        return new CaptureSiteSettings(environment);
    }

    /**
     * Gives {@code @Scheduled} methods a fresh system context by replacing Spring's own scheduled-annotation post-processor definition
     * (EXE-06). {@code static}, as a bean definition registry post-processor must be.
     */
    @Bean
    static ScheduledContextRegistrar boundrailScheduledContextRegistrar() {
        return new ScheduledContextRegistrar(LogFactory.getLog(ScheduledContextRegistrar.class));
    }

    /** Classifies destinations by the {@code destinations.*} host patterns; an invalid pattern fails startup (OUT-03, DEST-04). */
    @Bean
    @Lazy(false)
    @ConditionalOnMissingBean(DestinationClassifier.class)
    HostPatternClassifier boundrailDestinationClassifier(BoundrailContextProperties properties) {
        return HostPatternClassifier.of(properties.destinations().internalHosts(), properties.destinations().externalHosts());
    }

    /**
     * The built-in propagator selected by {@code authority.propagation}, unless an {@code AuthorityPropagator} bean replaces it (ID-04).
     * {@code @Lazy(false)}, so that the built-in {@code exchange} propagator's settings are validated at startup, also under lazy
     * initialization (ID-11).
     */
    @Bean
    @Lazy(false)
    @ConditionalOnMissingBean(AuthorityPropagator.class)
    AuthorityPropagator boundrailAuthorityPropagator(BoundrailContextProperties properties) {
        BoundrailContextProperties.Authority authority = properties.authority();
        return switch (authority.propagation()) {
            case FORWARD -> new ForwardAuthorityPropagator();
            case EXCHANGE -> new ExchangeAuthorityPropagator(URI.create(authority.exchange().tokenUri()), authority.exchange().clientId(),
                    authority.exchange().clientSecret());
        };
    }

    /** Mirrors loggable keys into SLF4J's MDC for each scope, where SLF4J is present (LOG-01, LOG-02). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MDC.class)
    static class MdcConfiguration {

        @Bean
        @Lazy(false)
        MdcScopeListener boundrailMdcScopeListener(EffectivePolicy policy) {
            return new MdcScopeListener(policy);
        }
    }

    /**
     * Runs a handler's {@code Callable}, or a {@code WebAsyncTask} without an executor of its own, in the request's snapshot filtered for
     * {@code ATTACHED}, where Spring MVC is present (EXE-09), and refuses it if its deadline has expired when it would start (EXE-08).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RequestMappingHandlerAdapter.class)
    static class MvcAsyncConfiguration {

        /** {@code static}, so the effective policy is resolved only when a task is first submitted. */
        @Bean
        static MvcAsyncExecutorPostProcessor boundrailMvcAsyncExecutorPostProcessor(ObjectProvider<EffectivePolicy> policy) {
            return new MvcAsyncExecutorPostProcessor(policy);
        }

        /** Refuses a {@code Callable} whose captured deadline has expired when it would start; registered last (EXE-08). */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(WebMvcConfigurer.class)
        static class DeadlineCallableConfiguration {

            @Bean
            DeadlineCallableInterceptor.Registration boundrailDeadlineCallableRegistration(ObjectProvider<DeadlineCounter> counters) {
                return new DeadlineCallableInterceptor.Registration(counters.getIfAvailable(() -> stage -> { }));
            }
        }
    }

    /**
     * Maps an escaping {@code DeadlineExceededException} to {@code expired-status} in a servlet application with Spring MVC (DL-08). The
     * resolver is appended to the resolvers MVC configures, after the application's {@code @ExceptionHandler} methods, and is never a
     * {@code HandlerExceptionResolver} bean, which would stop {@code DispatcherServlet} from installing its default resolvers.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    @ConditionalOnClass(WebMvcConfigurer.class)
    static class DeadlineExceptionConfiguration {

        /** Inert when {@code map-exception} is off: read from the bound properties, so Boot's boolean forms such as {@code yes} work. */
        @Bean
        WebMvcConfigurer boundrailDeadlineExceptionMvcConfigurer(BoundrailContextProperties properties,
                ObjectProvider<DeadlineCounter> counters) {
            DeadlineExceptionResolver resolver = new DeadlineExceptionResolver(properties.deadline(), counters.getIfAvailable(() -> stage -> { }));
            return new WebMvcConfigurer() {
                @Override
                public void extendHandlerExceptionResolvers(List<HandlerExceptionResolver> resolvers) {
                    resolvers.add(resolver);
                }
            };
        }
    }

    /** Counts violations in the application's unique or primary {@code MeterRegistry} bean, never the global registry (DIAG-03). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MicrometerConfiguration {

        /** Counts deadline expiries by stage, in the unique or primary registry, never the global one (DL-09). */
        @Bean
        DeadlineCounter boundrailDeadlineCounter(ObjectProvider<MeterRegistry> registries) {
            return stage -> {
                MeterRegistry registry = registries.getIfUnique();
                if (registry != null) {
                    registry.counter("boundrail.context.deadline.exceeded", "stage", stage.name().toLowerCase(Locale.ROOT)).increment();
                }
            };
        }

        @Bean
        ViolationReporter.Counter boundrailViolationCounter(ObjectProvider<MeterRegistry> registries) {
            return violation -> {
                MeterRegistry registry = registries.getIfUnique();
                if (registry != null) {
                    registry.counter("boundrail.context.violations",
                            "kind", violation.kind().name().toLowerCase(Locale.ROOT),
                            "key", violation.key().toLowerCase(Locale.ROOT),
                            "boundary", violation.boundary().name().toLowerCase(Locale.ROOT)).increment();
                }
            };
        }
    }

    /**
     * Adds the library's interceptor to every auto-configured {@code RestClient.Builder} and {@code RestTemplateBuilder}, at most once per
     * client (OUT-01).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass({ClientHttpRequestInterceptor.class, RestClientCustomizer.class, RestTemplateCustomizer.class})
    static class ClientConfiguration {

        /** Warns once at startup when Boot's auto-configured request factory is not the JDK {@code HttpClient} one (DL-07). */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(ClientHttpRequestFactoryBuilder.class)
        static class RequestFactoryCheckConfiguration {

            @Bean
            @Lazy(false)
            RequestFactoryCheck boundrailRequestFactoryCheck(ObjectProvider<ClientHttpRequestFactoryBuilder<?>> builders) {
                return new RequestFactoryCheck(builders.getIfUnique(), LogFactory.getLog(RequestFactoryCheck.class));
            }
        }

        /** The timer that stops calls whose deadline runs out (DL-07); one per context, shut down when it closes (RT-09). */
        @Bean(destroyMethod = "close")
        DeadlineTimer boundrailDeadlineTimer(ObjectProvider<DeadlineCounter> counters) {
            return new DeadlineTimer(counters.getIfAvailable(() -> stage -> { }));
        }

        @Bean
        @Lazy(false)
        RestClientCustomizer boundrailRestClientCustomizer(DestinationClassifier classifier, EffectivePolicy policy,
                ViolationReporter reporter, BoundrailContextProperties properties, DeadlineTimer timer,
                ObjectProvider<DeadlineCounter> counters, AuthorityPropagator propagator) {
            DeadlineCounter counter = counters.getIfAvailable(() -> stage -> { });
            return builder -> builder.requestInterceptors(interceptors -> {
                if (interceptors.stream().noneMatch(ContextClientInterceptor.class::isInstance)) {
                    interceptors.add(new ContextClientInterceptor(classifier, policy, reporter,
                            properties.violations().mode(), properties.wire().maxValueLength(), timer, counter, propagator));
                }
            });
        }

        @Bean
        @Lazy(false)
        RestTemplateCustomizer boundrailRestTemplateCustomizer(DestinationClassifier classifier, EffectivePolicy policy,
                ViolationReporter reporter, BoundrailContextProperties properties, DeadlineTimer timer,
                ObjectProvider<DeadlineCounter> counters, AuthorityPropagator propagator) {
            DeadlineCounter counter = counters.getIfAvailable(() -> stage -> { });
            return restTemplate -> {
                if (restTemplate.getInterceptors().stream().noneMatch(ContextClientInterceptor.class::isInstance)) {
                    restTemplate.getInterceptors().add(new ContextClientInterceptor(classifier, policy, reporter,
                            properties.violations().mode(), properties.wire().maxValueLength(), timer, counter, propagator));
                }
            };
        }
    }

    /** Registers the inbound filter before Spring Security's filter chain, with the built-in verifier unless a bean replaces it (IN-01, IN-03). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
    static class InboundConfiguration {

        /**
         * The built-in verifier selected by {@code inbound.trust}: {@code none} trusts no caller, {@code cidr} trusts callers whose remote
         * address is inside one of {@code inbound.trusted-cidrs}, whose entries are parsed at startup, also under lazy initialization
         * (IN-03, IN-08).
         */
        @Bean
        @Lazy(false)
        @ConditionalOnMissingBean(InboundTrustVerifier.class)
        InboundTrustVerifier boundrailInboundTrustVerifier(BoundrailContextProperties properties) {
            return switch (properties.inbound().trust()) {
                case CIDR -> new CidrInboundTrustVerifier(properties.inbound().trustedCidrs());
                case NONE -> request -> {
                    Objects.requireNonNull(request, "request");
                    return InboundTrustVerifier.Trust.UNTRUSTED;
                };
            };
        }

        /**
         * Orders the filter one below {@code spring.security.filter.order}; a value that is not an integer, or is {@code Integer.MIN_VALUE},
         * fails startup, also under lazy initialization (IN-01).
         */
        @Bean
        @Lazy(false)
        FilterRegistrationBean<ContextInboundFilter> boundrailInboundFilter(InboundTrustVerifier verifier, EffectivePolicy policy,
                ViolationReporter reporter, BoundrailContextProperties properties, Environment environment,
                ObjectProvider<DeadlineCounter> counters) {
            FilterRegistrationBean<ContextInboundFilter> registration = new FilterRegistrationBean<>(new ContextInboundFilter(verifier, policy,
                    reporter, properties.wire().maxValueLength(), properties.deadline(), counters.getIfAvailable(() -> stage -> { })));
            int securityOrder = environment.getProperty("spring.security.filter.order", Integer.class,
                    ContextInboundFilter.SECURITY_FILTER_ORDER_DEFAULT);
            if (securityOrder == Integer.MIN_VALUE) {
                throw new IllegalArgumentException("spring.security.filter.order must be greater than Integer.MIN_VALUE");
            }
            registration.setOrder(securityOrder - 1);
            registration.setDispatcherTypes(DispatcherType.REQUEST, DispatcherType.ASYNC);
            return registration;
        }

        /** Registers the authentication filter after Spring Security's filter chain, where Spring Security is present (IN-04). */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(SecurityContextHolder.class)
        static class AuthenticationConfiguration {

            /**
             * Orders the filter one above the configured {@code spring.security.filter.order}, so it runs inside Spring Security's filter
             * chain's call to the rest of the chain, once the {@code SecurityContext} is populated; {@code Integer.MAX_VALUE} fails startup,
             * also under lazy initialization (IN-04).
             */
            @Bean
            @Lazy(false)
            FilterRegistrationBean<ContextAuthenticationFilter> boundrailAuthenticationFilter(
                    ObjectProvider<ContextContributor> contributors, ObjectProvider<AuthorityExtraction> extraction,
                    ViolationReporter reporter, Environment environment) {
                FilterRegistrationBean<ContextAuthenticationFilter> registration = new FilterRegistrationBean<>(
                        new ContextAuthenticationFilter(contributors, extraction.getIfAvailable(() -> authentication -> Optional.empty()),
                                reporter));
                int securityOrder = environment.getProperty("spring.security.filter.order", Integer.class,
                        ContextInboundFilter.SECURITY_FILTER_ORDER_DEFAULT);
                if (securityOrder == Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("spring.security.filter.order must be less than Integer.MAX_VALUE");
                }
                registration.setOrder(securityOrder + 1);
                return registration;
            }

            /** Extracts the authority from an OAuth2 token authentication, where Spring Security's resource server is present (ID-02). */
            @Configuration(proxyBeanMethods = false)
            @ConditionalOnClass(AbstractOAuth2TokenAuthenticationToken.class)
            static class OAuth2AuthorityConfiguration {

                /**
                 * {@code Authority.of(getName(), token value, expiry)} for an {@code AbstractOAuth2TokenAuthenticationToken}, without the expiry
                 * when the token has none (ID-10), and with an empty subject when the name is {@code null} (ID-02); empty for any other
                 * authentication.
                 */
                @Bean
                AuthorityExtraction boundrailAuthorityExtraction() {
                    return authentication -> {
                        if (!(authentication instanceof AbstractOAuth2TokenAuthenticationToken<?> oauth2)) {
                            return Optional.empty();
                        }
                        String subject = Objects.requireNonNullElse(oauth2.getName(), "");
                        String credential = oauth2.getToken().getTokenValue();
                        return Optional.of(oauth2.getToken().getExpiresAt() == null
                                ? Authority.of(subject, credential)
                                : Authority.of(subject, credential, oauth2.getToken().getExpiresAt()));
                    };
                }
            }
        }
    }
}
