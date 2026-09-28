pluginManagement {
    includeBuild("build-logic")
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
    }
}

rootProject.name = "boundrail-context"

include(
    "boundrail-context-core",
    "boundrail-context-spring",
    "boundrail-context-kafka",
    "boundrail-context-test",
    "integration-tests",
    "examples:propagation-demo",
)
