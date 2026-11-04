package io.github.boundrail.context.kafka;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Configuration properties under {@code boundrail.context.kafka}.
 *
 * @param trustHeaders whether record headers are trusted and decoded; with {@code false}, nothing is bound from them and each policy wire
 *     header present is reported (KAF-04)
 */
@ConfigurationProperties("boundrail.context.kafka")
public record BoundrailKafkaProperties(@DefaultValue("true") boolean trustHeaders) {
}
