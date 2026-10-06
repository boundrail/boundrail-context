package io.github.boundrail.context.spring;

import org.apache.commons.logging.Log;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.scheduling.annotation.SchedulingConfiguration;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

/**
 * Replaces Spring's own scheduled-annotation post-processor definition, registered by {@code @EnableScheduling}, with
 * {@link ContextScheduledAnnotationBeanPostProcessor} under the same name (EXE-06). A definition under that name that is not Spring's own is
 * left as registered, with one WARN entry; with no such definition, nothing is done.
 */
final class ScheduledContextRegistrar implements BeanDefinitionRegistryPostProcessor {

    private static final String PROCESSOR = TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME;
    private static final String FACTORY_METHOD = "scheduledAnnotationProcessor";

    private final Log log;

    ScheduledContextRegistrar(Log log) {
        this.log = log;
    }

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) {
        if (!registry.containsBeanDefinition(PROCESSOR)) {
            return;
        }
        if (!isSpringsOwn(registry, registry.getBeanDefinition(PROCESSOR))) {
            log.warn("The bean definition " + PROCESSOR + " is not Spring's own, so it is left as registered and @Scheduled methods are not "
                    + "given a system context (EXE-06)");
            return;
        }
        RootBeanDefinition replacement = new RootBeanDefinition(ContextScheduledAnnotationBeanPostProcessor.class);
        replacement.setRole(BeanDefinition.ROLE_INFRASTRUCTURE);
        registry.removeBeanDefinition(PROCESSOR);
        registry.registerBeanDefinition(PROCESSOR, replacement);
    }

    /** {@return whether the definition is the factory method {@code scheduledAnnotationProcessor} of Spring's {@code SchedulingConfiguration}} */
    private static boolean isSpringsOwn(BeanDefinitionRegistry registry, BeanDefinition definition) {
        String factoryBean = definition.getFactoryBeanName();
        return FACTORY_METHOD.equals(definition.getFactoryMethodName()) && factoryBean != null
                && registry.containsBeanDefinition(factoryBean)
                && SchedulingConfiguration.class.getName().equals(registry.getBeanDefinition(factoryBean).getBeanClassName());
    }
}
