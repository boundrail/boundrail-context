plugins {
    id("boundrail.java-conventions")
    `java-library`
}

dependencies {
    api(project(":boundrail-context-core"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.security.web)
    compileOnly(libs.spring.security.oauth2.resource.server)
    compileOnly(libs.spring.security.oauth2.jose)
    compileOnly(libs.micrometer.core)
    compileOnly(libs.spring.web)
    compileOnly(libs.spring.boot.restclient)
    compileOnly(libs.jakarta.servlet.api)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.micrometer.core)
    testImplementation(libs.micrometer.context.propagation)
    testImplementation(libs.spring.web)
    testImplementation(libs.spring.boot.restclient)
    testImplementation(libs.jakarta.servlet.api)
    testImplementation(libs.spring.boot.security)
    testImplementation(libs.spring.security.web)
    testImplementation(libs.spring.security.oauth2.resource.server)
    testImplementation(libs.spring.security.oauth2.jose)
}
