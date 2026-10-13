// Pure Kotlin CSV/TSV round-trip parser and writer.
plugins {
    alias(libs.plugins.kotlin.jvm)
    // Lets Android lint analyse this plain-Kotlin module too.
    alias(libs.plugins.android.lint)
}
// Builds on whatever JDK runs Gradle (21 in the Docker image) but emits Java 17 bytecode.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
lint {
    warningsAsErrors = true
    abortOnError = true
    lintConfig = rootProject.file("config/lint.xml")
}
dependencies {
    api(project(":core:text"))
    testImplementation(libs.junit)
}
