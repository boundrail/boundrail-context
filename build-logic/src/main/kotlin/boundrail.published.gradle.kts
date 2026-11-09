import com.vanniktech.maven.publish.DeploymentValidation
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar

// Applied only in the four published projects (REL-04, D85).
plugins {
    `java-library`
    id("com.vanniktech.maven.publish")
}

// Gradle's own Javadoc JAR, so the module metadata has a javadocElements variant; the plugin's default attaches it to the POM only.
java {
    withJavadocJar()
}

mavenPublishing {
    configure(JavaLibrary(JavadocJar.None(), SourcesJar.Sources()))

    // Manual release through the Central Portal: the upload is validated, then waits there to be published by hand (REL-04, D85).
    publishToMavenCentral(false, DeploymentValidation.VALIDATED)
    // The key comes from Gradle properties outside the repository; check never signs, so it needs no key (D85).
    signAllPublications()

    // The metadata Central requires; each project sets its own description (REL-04).
    pom {
        name.set(project.name)
        description.set(provider { project.description })
        url.set("https://github.com/boundrail/boundrail-context")
        licenses {
            license {
                name.set("Apache-2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0")
            }
        }
        scm {
            url.set("https://github.com/boundrail/boundrail-context")
            connection.set("scm:git:https://github.com/boundrail/boundrail-context.git")
            developerConnection.set("scm:git:ssh://git@github.com/boundrail/boundrail-context.git")
        }
        // No email: a released POM cannot be removed from Central (REL-04, D85).
        developers {
            developer {
                id.set("jordi1st")
                name.set("jordi1st")
                url.set("https://github.com/jordi1st")
            }
        }
    }
}

// Resolved versions in the POM, so dependencies whose version comes from a platform still carry one (D85).
publishing {
    publications.withType<MavenPublication>().configureEach {
        versionMapping {
            allVariants {
                fromResolutionResult()
            }
        }
    }
}
