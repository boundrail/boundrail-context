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

// ConfigurationReferenceIT compares docs/configuration.md with the Configuration tables in specs/*/spec.md (REL-03). Both are inputs,
// so a change to either reruns the test instead of reporting it up to date.
val repositoryRoot = rootProject.layout.projectDirectory
val specs = repositoryRoot.dir("specs")
val configurationReference = repositoryRoot.file("docs/configuration.md")

tasks.withType<Test>().configureEach {
    inputs.files(fileTree(specs) { include("*/spec.md") }).withPropertyName("specs").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.files(configurationReference).withPropertyName("configurationReference").withPathSensitivity(PathSensitivity.NONE)
    systemProperty("boundrail.it.repository-root", repositoryRoot.asFile.absolutePath)
}

tasks.register<JavaExec>("generateConfigurationReference") {
    group = "documentation"
    description = "Regenerates docs/configuration.md from the Configuration tables in specs/."
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "io.github.boundrail.context.it.docs.ConfigurationReference"
    args(repositoryRoot.asFile.absolutePath)
}
