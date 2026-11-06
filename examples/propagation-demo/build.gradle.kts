plugins {
    id("boundrail.java-conventions")
    application
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":boundrail-context-spring"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.restclient)

    testImplementation(project(":boundrail-context-test"))
    testImplementation(libs.spring.boot.starter.test)
}

tasks.test {
    testLogging {
        showStandardStreams = true
    }
}
