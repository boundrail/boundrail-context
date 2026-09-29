plugins {
    // Applied here so its release 21 is always configured before the release 25 below overrides it for java25.
    id("boundrail.java-conventions")
}

val java25 = sourceSets.create("java25") {
    java.setSrcDirs(listOf("src/main/java25"))
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
