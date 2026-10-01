plugins {
    id("boundrail.java-conventions")
    `java-library`
}

dependencies {
    api(project(":boundrail-context-core"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.boot.autoconfigure)
    compileOnly(libs.spring.security.web)
    compileOnly(libs.micrometer.core)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.micrometer.core)
}
