// The app: Activity, navigation, AppGraph wiring, widgets, Quick Settings tile, share target, shortcuts.
import java.util.Properties
import java.util.Random

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
}

// One place for the version: version.properties at the repo root.
val versionProps = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }

android {
    namespace = "com.nihars.mnemos"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.nihars.mnemos"
        minSdk = 31
        targetSdk = 36
        versionCode = versionProps.getProperty("versionCode").toInt()
        versionName = versionProps.getProperty("versionName")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Signing comes from the environment (build.sh reads keystore.env); never from the repo.
    signingConfigs {
        create("release") {
            val store = System.getenv("MNEMOS_KEYSTORE")
            if (store != null) {
                storeFile = file(store)
                storePassword = System.getenv("MNEMOS_STORE_PASSWORD")
                keyAlias = System.getenv("MNEMOS_KEY_ALIAS") ?: "mnemos"
                keyPassword = System.getenv("MNEMOS_KEY_PASSWORD") ?: System.getenv("MNEMOS_STORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (System.getenv("MNEMOS_KEYSTORE") != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "/META-INF/*.version", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }

    lint {
        warningsAsErrors = true
        abortOnError = true
        checkDependencies = true
        lintConfig = rootProject.file("config/lint.xml")
    }

    sourceSets["main"].assets.srcDir(layout.buildDirectory.dir("generated/mnemosAssets"))
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    implementation(project(":core:data"))
    implementation(project(":feature:signin"))
    implementation(project(":feature:tree"))
    implementation(project(":feature:editor"))
    implementation(project(":feature:table"))
    implementation(project(":feature:viewer"))
    implementation(project(":feature:search"))
    implementation(project(":feature:conflicts"))
    implementation(project(":feature:recent"))
    implementation(project(":feature:settings"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.profileinstaller)
    "baselineProfile"(project(":benchmark"))
    debugImplementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
}

// Assets made at build time: the demo vault (with the big generated files), the changelog shown by
// the updater, and the licence list.
val generateAssets by tasks.registering(GenerateAssetsTask::class) {
    demoSource.set(rootProject.layout.projectDirectory.dir("demo-vault"))
    changelog.set(rootProject.layout.projectDirectory.file("CHANGELOG.md"))
    licences.set(rootProject.layout.projectDirectory.file("config/licenses.txt"))
    outputDir.set(layout.buildDirectory.dir("generated/mnemosAssets"))
}
tasks.named("preBuild") { dependsOn(generateAssets) }

abstract class GenerateAssetsTask : DefaultTask() {
    @get:InputDirectory abstract val demoSource: DirectoryProperty
    @get:InputFile abstract val changelog: RegularFileProperty
    @get:InputFile abstract val licences: RegularFileProperty
    @get:OutputDirectory abstract val outputDir: DirectoryProperty

    @TaskAction
    fun run() {
        val out = outputDir.get().asFile
        out.deleteRecursively()
        val demo = File(out, "demo")
        demoSource.get().asFile.copyRecursively(demo, overwrite = true)
        changelog.get().asFile.copyTo(File(out, "CHANGELOG.md"), overwrite = true)
        licences.get().asFile.copyTo(File(out, "licenses.txt"), overwrite = true)
        // Deterministic big files so the repo stays small: a ~2 MB log and a 500-row CSV.
        val rnd = Random(42)
        val levels = listOf("INFO", "INFO", "INFO", "DEBUG", "WARN", "ERROR")
        val logLines = StringBuilder()
        var i = 0
        while (logLines.length < 2_100_000) {
            val lvl = levels[rnd.nextInt(levels.size)]
            logLines.append("2026-10-%02d %02d:%02d:%02d %-5s sync[%d] ".format(1 + i / 40000, (i / 1500) % 24, (i / 25) % 60, i % 60, lvl, 1000 + rnd.nextInt(9000)))
            logLines.append(if (lvl == "ERROR") "failed to sync folder notes: connection reset (attempt ${i % 7})" else "scanned ${rnd.nextInt(5000)} files in ${rnd.nextInt(900)} ms")
            logLines.append('\n')
            i++
        }
        File(demo, "logs").mkdirs()
        File(demo, "logs/syncthing.log").writeText(logLines.toString())
        val csv = StringBuilder("date,item,category,amount,paid_by,note\n")
        val items = listOf("Tea", "Rice, basmati", "Bus pass", "Running shoes", "Coffee beans", "Books", "Vegetables", "Internet")
        val cats = listOf("Food", "Travel", "Fitness", "Home", "Learning")
        for (r in 1..500) {
            val item = items[rnd.nextInt(items.size)]
            val quoted = if (item.contains(',')) "\"$item\"" else item
            csv.append("2026-%02d-%02d,%s,%s,%d.%02d,%s,%s\n".format(1 + r % 12, 1 + r % 28, quoted, cats[rnd.nextInt(cats.size)], rnd.nextInt(2000), rnd.nextInt(100), if (r % 3 == 0) "card" else "cash", if (r % 50 == 0) "\"bulk \"\"deal\"\"\"" else ""))
        }
        File(demo, "tables").mkdirs()
        File(demo, "tables/expenses.csv").writeText(csv.toString())
    }
}
