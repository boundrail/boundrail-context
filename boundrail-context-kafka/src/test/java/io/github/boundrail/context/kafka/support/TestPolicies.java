package io.github.boundrail.context.kafka.support;

import static io.github.boundrail.context.Boundary.INTERNAL_CALL;
import static io.github.boundrail.context.Boundary.MESSAGE;

import io.github.boundrail.context.Codecs;
import io.github.boundrail.context.ContextCodec;
import io.github.boundrail.context.ContextKey;
import io.github.boundrail.context.ContextPolicy;

/**
 * The test policy of 007's tasks: {@code tenant} (string codec, {@code Tenant-Id}, propagates to {@code MESSAGE} and {@code INTERNAL_CALL}),
 * {@code order-count} (long codec, {@code Order-Count}, {@code MESSAGE}) and {@code priority} (long codec, {@code Priority},
 * {@code INTERNAL_CALL} only).
 */
public final class TestPolicies {

    public static final ContextKey<String> TENANT = ContextKey.of("tenant", String.class);
    public static final ContextKey<Long> ORDER_COUNT = ContextKey.of("order-count", Long.class);
    public static final ContextKey<Long> PRIORITY = ContextKey.of("priority", Long.class);

    /** {@return the test policy} */
    public static ContextPolicy policy() {
        return withTenantCodec(Codecs.string());
    }

    /** {@return the test policy with {@code tenant} decoded by {@code codec}, such as a counting or a throwing codec} */
    public static ContextPolicy withTenantCodec(ContextCodec<String> codec) {
        return ContextPolicy.builder()
                .key(TENANT, rules -> rules.codec(codec).wireName("Tenant-Id").propagateTo(MESSAGE, INTERNAL_CALL))
                .key(ORDER_COUNT, rules -> rules.codec(Codecs.longValue()).wireName("Order-Count").propagateTo(MESSAGE))
                .key(PRIORITY, rules -> rules.codec(Codecs.longValue()).wireName("Priority").propagateTo(INTERNAL_CALL))
                .build();
    }

    private TestPolicies() {
    }
}
