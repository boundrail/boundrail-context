plugins {
    id("boundrail.integration-tests")
}

dependencies {
    testImplementation(project(":boundrail-context-core"))
    testImplementation(project(":boundrail-context-spring"))
    testImplementation(project(":boundrail-context-kafka"))
    testImplementation(project(":boundrail-context-test"))
}
