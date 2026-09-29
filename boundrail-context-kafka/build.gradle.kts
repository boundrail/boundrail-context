plugins {
    id("boundrail.java-conventions")
    `java-library`
}

dependencies {
    api(project(":boundrail-context-core"))
    implementation(platform(libs.spring.boot.bom))
    implementation(libs.spring.kafka)
}
