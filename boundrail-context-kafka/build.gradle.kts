plugins {
    id("boundrail.java-conventions")
    `java-library`
    id("boundrail.published")
}

// The Contents cell of design.md §12 (REL-04).
description = "Spring Kafka producer and consumer adapters"

dependencies {
    api(project(":boundrail-context-core"))
    api(project(":boundrail-context-spring"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.kafka)
    implementation(libs.spring.boot.autoconfigure)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.micrometer.core)
    testImplementation(libs.spring.boot.kafka)
    testImplementation(libs.testcontainers.kafka)
}
