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
}
