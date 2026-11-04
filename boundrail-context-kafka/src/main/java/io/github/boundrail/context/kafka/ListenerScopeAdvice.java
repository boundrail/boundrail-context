package io.github.boundrail.context.kafka;

import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.listener.BatchMessageListener;

/**
 * Runs a record listener's {@code onMessage} in its record's snapshot, replacing the current one, around-style (KAF-02, KAF-03, DESIGN
 * D72). Batch listeners and every other method pass through unscoped (KAF-05).
 */
final class ListenerScopeAdvice implements MethodInterceptor {

    private final KafkaContext context;

    ListenerScopeAdvice(KafkaContext context) {
        this.context = context;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Object[] arguments = invocation.getArguments();
        if (invocation.getThis() instanceof BatchMessageListener
                || !invocation.getMethod().getName().equals("onMessage")
                || arguments.length == 0
                || !(arguments[0] instanceof ConsumerRecord<?, ?> record)) {
            return invocation.proceed();
        }
        return context.snapshot(record).call(invocation::proceed);
    }
}
