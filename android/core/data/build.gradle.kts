// App wiring shared by every feature: AppGraph, per-user sessions, settings, SAF access, watcher, demo vault.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.nihars.mnemos.core.data"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        warningsAsErrors = true
        abortOnError = true
        lintConfig = rootProject.file("config/lint.xml")
    }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    api(project(":core:text"))
    api(project(":core:csv"))
    api(project(":core:vaultfs"))
    api(project(":core:markdown"))
    api(project(":core:index"))
    api(project(":core:security"))
    api(project(":core:design"))
    api(libs.androidx.datastore)
    api(libs.coroutines.android)
    api(libs.androidx.lifecycle.viewmodel)
    api(libs.androidx.lifecycle.runtime)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.runtime)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
}
