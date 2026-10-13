// PIN hashing, wrong-PIN cooldown, Keystore-bound biometric unlock and the lock state machine.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.nihars.mnemos.core.security"
    compileSdk = 36
    defaultConfig { minSdk = 31 }
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
    implementation(libs.androidx.core.ktx)
    api(libs.androidx.biometric)
    api(libs.androidx.fragment)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit)
}
