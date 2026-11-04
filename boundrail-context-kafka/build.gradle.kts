plugins {
    id("boundrail.java-conventions")
    `java-library`
}

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
