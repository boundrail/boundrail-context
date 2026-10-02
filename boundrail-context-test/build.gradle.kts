plugins {
    id("boundrail.java-conventions")
    `java-library`
}

dependencies {
    api(project(":boundrail-context-core"))
    compileOnly(platform(libs.spring.boot.bom))
    compileOnly(project(":boundrail-context-spring"))
    compileOnly(libs.spring.web)
    compileOnly(libs.jakarta.servlet.api)

    testImplementation(project(":boundrail-context-spring"))
    testImplementation(libs.spring.web)
    testImplementation(libs.jakarta.servlet.api)
    testImplementation(libs.spring.boot.restclient)
}
