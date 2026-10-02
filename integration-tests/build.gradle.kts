plugins {
    id("boundrail.integration-tests")
}

dependencies {
    testImplementation(project(":boundrail-context-core"))
    testImplementation(project(":boundrail-context-spring"))
    testImplementation(project(":boundrail-context-kafka"))
    testImplementation(project(":boundrail-context-test"))

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.webmvc)
    testImplementation(libs.spring.boot.starter.restclient)
    testImplementation(libs.spring.boot.starter.security.oauth2.resource.server)
    testImplementation(libs.spring.kafka)
    testImplementation(libs.micrometer.core)
    testImplementation(libs.testcontainers.kafka)
}
