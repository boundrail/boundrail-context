package io.github.boundrail.context.spring;

import io.github.boundrail.context.BuiltInKeys;
import io.github.boundrail.context.ContextPolicy;
import io.github.boundrail.context.DestinationClassifier;
import io.github.boundrail.context.HostPatternClassifier;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.DispatcherType;
import java.time.Clock;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Objects;
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
     * {@code ATTACHED}, where Spring MVC is present (EXE-09).
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(RequestMappingHandlerAdapter.class)
    static class MvcAsyncConfiguration {

        /** {@code static}, so the effective policy is resolved only when a task is first submitted. */
        @Bean
        static MvcAsyncExecutorPostProcessor boundrailMvcAsyncExecutorPostProcessor(ObjectProvider<EffectivePolicy> policy) {
            return new MvcAsyncExecutorPostProcessor(policy);
        }
    }

    /** Counts violations in the application's unique or primary {@code MeterRegistry} bean, never the global registry (DIAG-03). */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(MeterRegistry.class)
    static class MicrometerConfiguration {

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

        @Bean
        @Lazy(false)
        RestClientCustomizer boundrailRestClientCustomizer(DestinationClassifier classifier, EffectivePolicy policy,
                ViolationReporter reporter, BoundrailContextProperties properties) {
            return builder -> builder.requestInterceptors(interceptors -> {
                if (interceptors.stream().noneMatch(ContextClientInterceptor.class::isInstance)) {
                    interceptors.add(new ContextClientInterceptor(classifier, policy, reporter,
                            properties.violations().mode(), properties.wire().maxValueLength()));
                }
            });
        }

        @Bean
        @Lazy(false)
        RestTemplateCustomizer boundrailRestTemplateCustomizer(DestinationClassifier classifier, EffectivePolicy policy,
                ViolationReporter reporter, BoundrailContextProperties properties) {
            return restTemplate -> {
                if (restTemplate.getInterceptors().stream().noneMatch(ContextClientInterceptor.class::isInstance)) {
                    restTemplate.getInterceptors().add(new ContextClientInterceptor(classifier, policy, reporter,
                            properties.violations().mode(), properties.wire().maxValueLength()));
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
                ViolationReporter reporter, BoundrailContextProperties properties, Environment environment) {
            FilterRegistrationBean<ContextInboundFilter> registration = new FilterRegistrationBean<>(
                    new ContextInboundFilter(verifier, policy, reporter, properties.wire().maxValueLength()));
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
                    ObjectProvider<ContextContributor> contributors, ViolationReporter reporter, Environment environment) {
                FilterRegistrationBean<ContextAuthenticationFilter> registration =
                        new FilterRegistrationBean<>(new ContextAuthenticationFilter(contributors, reporter));
                int securityOrder = environment.getProperty("spring.security.filter.order", Integer.class,
                        ContextInboundFilter.SECURITY_FILTER_ORDER_DEFAULT);
                if (securityOrder == Integer.MAX_VALUE) {
                    throw new IllegalArgumentException("spring.security.filter.order must be less than Integer.MAX_VALUE");
                }
                registration.setOrder(securityOrder + 1);
                return registration;
            }
        }
    }
}
