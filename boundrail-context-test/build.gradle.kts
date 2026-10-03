plugins {
    id("boundrail.java-conventions")
    `java-library`
    // Generates the POM and module metadata that BuildSmokeIT checks (TEST-01); nothing is published.
    `maven-publish`
}

dependencies {
    api(project(":boundrail-context-core"))
    compileOnly(platform(libs.spring.boot.bom))
    api(project(":boundrail-context-spring"))
    compileOnly(libs.spring.web)
    compileOnly(libs.jakarta.servlet.api)

    testImplementation(libs.spring.web)
    testImplementation(libs.jakarta.servlet.api)
    testImplementation(libs.spring.boot.restclient)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
