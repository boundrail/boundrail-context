import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("boundrail.java-conventions")
}

val hostsFile = layout.projectDirectory.file("src/test/resources/hosts").asFile.absolutePath
val testSourceSet = sourceSets.test.get()

// Integration tests run only through the JDK-specific tasks below.
tasks.test {
    enabled = false
}

fun registerIntegrationTest(name: String, javaVersion: Int, configure: Test.() -> Unit = {}) =
    tasks.register<Test>(name) {
        group = "verification"
        description = "Runs the integration tests against the packaged JARs on JDK $javaVersion."
        testClassesDirs = testSourceSet.output.classesDirs
        classpath = testSourceSet.runtimeClasspath
        javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(javaVersion) }
        systemProperty("jdk.net.hosts.file", hostsFile)
        systemProperty("boundrail.it.task", name)
        testLogging {
            exceptionFormat = TestExceptionFormat.FULL
        }
        configure()
    }

val integrationTests = listOf(
    registerIntegrationTest("integrationTestJdk21", 21),
    registerIntegrationTest("integrationTestJdk25", 25),
    registerIntegrationTest("integrationTestJdk25ThreadLocal", 25) {
        systemProperty("boundrail.context.backend", "thread-local")
        filter {
            includeTestsMatching("*RuntimeConformanceIT")
        }
    },
)

tasks.check {
    dependsOn(integrationTests)
}
