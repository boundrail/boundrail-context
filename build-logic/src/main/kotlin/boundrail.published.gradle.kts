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
}
