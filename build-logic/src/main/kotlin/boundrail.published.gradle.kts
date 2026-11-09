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
