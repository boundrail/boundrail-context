plugins {
    // Applied here so its release 21 is always configured before the release 25 below overrides it for java25.
    id("boundrail.java-conventions")
}

val java25 = sourceSets.create("java25") {
    java.setSrcDirs(listOf("src/main/java25"))
    compileClasspath += sourceSets.main.get().output
}

tasks.named<JavaCompile>(java25.compileJavaTaskName) {
    options.release = 25
}

tasks.jar {
    into("META-INF/versions/25") {
        from(java25.output)
    }
    manifest {
        attributes("Multi-Release" to "true")
    }
}

// The sources JAR mirrors the binary layout, so a debugger on JDK 25 finds the java25 sources (REL-04). Javadoc stays main only.
tasks.withType<Jar>().matching { it.name == "sourcesJar" }.configureEach {
    into("META-INF/versions/25") {
        from(java25.allSource)
    }
}
