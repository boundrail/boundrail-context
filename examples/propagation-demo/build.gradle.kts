plugins {
    id("boundrail.java-conventions")
    application
}

application {
    mainClass = "io.github.boundrail.context.demo.DemoClient"
}

dependencies {
    implementation(platform(libs.spring.boot.bom))
    implementation(project(":boundrail-context-spring"))
    implementation(project(":boundrail-context-kafka"))
    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.restclient)
    implementation(libs.spring.boot.starter.kafka)

    testImplementation(project(":boundrail-context-test"))
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.kafka)
}

tasks.test {
    testLogging {
        showStandardStreams = true
    }
}
