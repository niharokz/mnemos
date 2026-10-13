// Mnemos for Android: module list and repositories.
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "mnemos-android"

include(
    ":app",
    ":core:text", ":core:csv", ":core:vaultfs", ":core:markdown",
    ":core:security", ":core:index", ":core:design", ":core:data",
    ":feature:signin", ":feature:tree", ":feature:editor", ":feature:table",
    ":feature:viewer", ":feature:search", ":feature:conflicts",
    ":feature:recent", ":feature:settings",
    ":benchmark",
)
