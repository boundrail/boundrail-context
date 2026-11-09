plugins {
    id("boundrail.java-conventions")
    `java-library`
    id("boundrail.published")
}

// The Contents cell of design.md §12 (REL-04).
description = "JUnit Jupiter extension, assertions, test trust verifier"

dependencies {
    api(project(":boundrail-context-core"))
    compileOnly(platform(libs.spring.boot.bom))
    api(project(":boundrail-context-spring"))
    // Consumers of the assertions receive JUnit Jupiter and AssertJ transitively (TEST-04).
    api(libs.junit.jupiter.api)
    api(libs.assertj.core)
    compileOnly(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.web)
    compileOnly(libs.jakarta.servlet.api)

    testImplementation(libs.spring.web)
    testImplementation(libs.jakarta.servlet.api)
    testImplementation(libs.spring.boot.restclient)
    testImplementation(libs.spring.boot.autoconfigure)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.junit.platform.testkit)
}
