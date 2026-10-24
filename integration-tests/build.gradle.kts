plugins {
    id("boundrail.integration-tests")
}

dependencies {
    testImplementation(project(":boundrail-context-core"))
    testImplementation(project(":boundrail-context-spring"))
    testImplementation(project(":boundrail-context-kafka"))
    testImplementation(project(":boundrail-context-test"))

    testImplementation(platform(libs.spring.boot.bom))
    testImplementation(libs.spring.boot.starter.webmvc)
    testImplementation(libs.spring.boot.starter.restclient)
    testImplementation(libs.spring.boot.starter.security.oauth2.resource.server)
    testImplementation(libs.spring.boot.test)
    testImplementation(libs.spring.kafka)
    testImplementation(libs.spring.boot.kafka)
    testImplementation(libs.micrometer.core)
    testImplementation(libs.micrometer.context.propagation)
    testImplementation(libs.testcontainers.kafka)
}

// BuildSmokeIT checks the generated POM and module metadata of boundrail-context-test (TEST-01).
val testModulePublication = rootProject.layout.projectDirectory.dir("boundrail-context-test/build/publications/maven")
val testModulePom = testModulePublication.file("pom-default.xml")
val testModuleMetadata = testModulePublication.file("module.json")

tasks.withType<Test>().configureEach {
    dependsOn(
        ":boundrail-context-test:generatePomFileForMavenPublication",
        ":boundrail-context-test:generateMetadataFileForMavenPublication",
    )
    inputs.file(testModulePom).withPropertyName("testModulePom").withPathSensitivity(PathSensitivity.NONE)
    inputs.file(testModuleMetadata).withPropertyName("testModuleMetadata").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("boundrail.it.test-module.pom", testModulePom.asFile.absolutePath)
    systemProperty("boundrail.it.test-module.metadata", testModuleMetadata.asFile.absolutePath)
}
