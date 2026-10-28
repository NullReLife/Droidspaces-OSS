import java.util.Locale

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Every commit gets its own build number, so CI APKs between version bumps
// still install over each other. Needs full history: a shallow clone counts 1,
// and a tarball without .git counts 0. Both still sort above the old scheme.
val gitCommitCount = runCatching {
    providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }
        .standardOutput.asText.get().trim().toInt()
}.getOrDefault(0)

// One version for the app and the backend it ships: DS_VERSION from the C header
val dsVersionName = Regex("#define\\s+DS_VERSION\\s+\"([^\"]+)\"")
    .find(file("../../src/include/droidspace.h").readText())?.groupValues?.get(1) ?: "1.0.0"
// ponytail: 10000 commits per release before the suffix spills into the next slot; still monotonic, just ugly
val dsVersionCodeVal = dsVersionName.split(".").let { parts ->
    try {
        val major = parts.getOrNull(0)?.toInt() ?: 1
        val minor = parts.getOrNull(1)?.toInt() ?: 0
        val patch = parts.getOrNull(2)?.toInt() ?: 0
        major * 1000 + minor * 100 + patch * 10
    } catch (e: Exception) {
        1
    }
} * 10000 + gitCommitCount

android {
    namespace = "com.droidspaces.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.droidspaces.app"
        minSdk = 26
        targetSdk = 34
        versionCode = dsVersionCodeVal
        versionName = dsVersionName
    }

    signingConfigs {
        // droidspaces.keystore signs debug and release alike. The passwords come
        // from -P, gradle.properties or ~/.gradle/gradle.properties.
        val keystoreFile = file("../../droidspaces.keystore")
        val keystorePassword = providers.gradleProperty("KEYSTORE_PASSWORD").orNull ?: ""
        if (keystorePassword.isEmpty() &&
            rootProject.file("local.properties").let { it.exists() && it.readText().contains("KEYSTORE_PASSWORD") }
        ) logger.warn("KEYSTORE_PASSWORD in local.properties is no longer read: move it, KEY_ALIAS and KEY_PASSWORD to ~/.gradle/gradle.properties or pass them with -P, or the release APK comes out unsigned")
        val keyAliasName = providers.gradleProperty("KEY_ALIAS").orNull ?: "droidspaces"
        val actualKeyPassword = providers.gradleProperty("KEY_PASSWORD").orNull ?: keystorePassword

        if (keystoreFile.exists() && keystorePassword.isNotEmpty()) {
            getByName("debug") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = actualKeyPassword
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
            create("release") {
                storeFile = keystoreFile
                storePassword = keystorePassword
                keyAlias = keyAliasName
                keyPassword = actualKeyPassword
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        // No release key (a fork, a pull request): the release build type signs with
        // the debug config below. AGP creates ~/.android/debug.keystore when that
        // config's file is missing, as on a fresh runner, but only for the config
        // named "debug", not for a copy of it under another name.
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xno-param-assertions", // Remove parameter assertions in release builds
            "-Xno-call-assertions", // Remove call assertions in release builds
            "-Xno-receiver-assertions", // Remove receiver assertions in release builds
            "-Xbackend-threads=0" // Use all available threads for compilation
        )
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// ---------------------------------------------------------------------------
// Auto-generate language list from res/values-* directories.
//
// This task runs before every build and writes two files under build/:
//   1. assets/supported_locales.txt  — read by LocaleHelper at runtime to
//      build the in-app language picker dynamically.
//   2. res/xml/locales_config.xml    — keeps the Android 13+ per-app
//      language system setting in sync automatically.
//
// Detection rule: a values-XX directory is a translation if it contains a
// strings.xml file (case-insensitive). This cleanly excludes non-language
// qualifiers like values-night, values-v31, values-v33, etc., which never
// carry strings.xml. No hardcoded exclusion list is needed.
//
// Adding a new language via Weblate: just merge the PR — the next build
// picks it up with zero manual changes required.
// ---------------------------------------------------------------------------
// Build-generated assets and resources live here, registered as extra source
// roots, so nothing the build rewrites ever shows up as a diff.
val generatedDir = layout.buildDirectory.dir("generated/droidspaces").get()
android.sourceSets["main"].assets.srcDir(generatedDir.dir("assets"))
android.sourceSets["main"].res.srcDir(generatedDir.dir("res"))

tasks.register("generateSupportedLocalesList") {
    val resDir = file("src/main/res")
    val localesAsset = generatedDir.file("assets/supported_locales.txt").asFile
    val localesConfig = generatedDir.file("res/xml/locales_config.xml").asFile

    inputs.dir(resDir)
    outputs.files(localesAsset, localesConfig)

    doLast {
        localesAsset.parentFile.mkdirs()
        localesConfig.parentFile.mkdirs()

        // Scan values-XX dirs; include only those with a strings.xml (any case)
        val localeCodes = resDir.listFiles()
            .orEmpty()
            .filter { dir ->
                dir.isDirectory &&
                dir.name.startsWith("values-") &&
                dir.listFiles().orEmpty().any { f ->
                    f.name.equals("strings.xml", ignoreCase = true)
                }
            }
            .mapNotNull { dir ->
                val suffix = dir.name.removePrefix("values-")
                // Convert Android qualifier format (pt-rBR) → BCP 47 (pt-BR)
                // so java.util.Locale can validate it.
                val bcp47 = suffix.replace(Regex("-r([A-Z])"), "-$1")
                val locale = Locale.forLanguageTag(bcp47)
                // Reject anything that doesn't parse as a real language subtag
                if (locale.language.isNotEmpty()) suffix else null
            }
            .sorted()

        // --- 1. assets/supported_locales.txt ---
        localesAsset.writeText(localeCodes.joinToString("\n") + "\n")
        println("generateSupportedLocalesList: wrote ${localeCodes.size} locales → $localeCodes")

        // --- 2. res/xml/locales_config.xml ---
        val sb = StringBuilder()
        sb.appendLine("""<?xml version="1.0" encoding="utf-8"?>""")
        sb.appendLine("""<locale-config xmlns:android="http://schemas.android.com/apk/res/android">""")
        sb.appendLine("""    <locale android:name="en" />""") // default / fallback
        localeCodes.forEach { code ->
            val bcp47 = code.replace("-r", "-")
            sb.appendLine("""    <locale android:name="$bcp47" />""")
        }
        sb.appendLine("""</locale-config>""")
        localesConfig.writeText(sb.toString())
    }
}

val repo = rootProject.layout.projectDirectory.dir("..")

// boot-module/module.prop carries the backend's version; the template lives
// outside assets so the per-commit versionCode never shows up as a diff. The
// autoboot script is the one init/ ships, so the two boot paths run one file.
tasks.register<Copy>("generateBootModule") {
    from("src/main/boot-module/module.prop") {
        expand(mapOf("version" to dsVersionName, "versionCode" to dsVersionCodeVal))
    }
    from(repo.file("init/android-service/vendor/bin/droidspaces_autoboot.sh"))
    into(generatedDir.dir("assets/boot-module"))
    inputs.property("version", dsVersionName)
    inputs.property("versionCode", dsVersionCodeVal)
}

// The backend the APK ships. make is incremental, so an unchanged src/ costs one no-op per arch
val phoneArches = listOf("aarch64", "armhf", "x86_64", "x86")
val buildBackend = tasks.register<Exec>("buildBackend") {
    workingDir(repo)
    commandLine(listOf("make") + phoneArches)
    inputs.dir(repo.dir("src"))
    inputs.file(repo.file("Makefile"))
    outputs.files(phoneArches.map { repo.file("output/droidspaces-$it") })
}
val backendAssets = tasks.register<Sync>("backendAssets") {
    from(buildBackend)
    into(generatedDir.dir("assets/binaries"))
}

// Run before every build variant
tasks.configureEach {
    if (name.startsWith("pre") && name.endsWith("Build")) {
        dependsOn("generateSupportedLocalesList")
        dependsOn("generateBootModule")
        dependsOn(backendAssets)
    }
}

dependencies {
    // Compose BOM
    // 2026.06.01 is the last BOM on the Compose 1.11 line; 1.12 wants compileSdk 37 and AGP 9.
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    // material3 1.4.0 stable stripped every Expressive API (wavy progress, LoadingIndicator,
    // ButtonGroup). 1.5.0-alpha18 is the alpha that promoted the wavy indicator to stable
    // and the last one built against Compose 1.11; alpha20 onward needs Compose 1.12.
    implementation("androidx.compose.material3:material3:1.5.0-alpha18")
    implementation("androidx.compose.material:material-icons-extended")

    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-process:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.11.0")

    // Removed App Startup library - direct initialization in Application.onCreate() is faster
    // Eliminates ContentProvider overhead (~5-10ms saved)

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Drag-and-drop reordering for Compose lists (Auto boot priority screen)
    // Every 2.x/3.x release pins Compose 1.7; the BOM above resolves it upward.
    implementation("sh.calvin.reorderable:reorderable:3.1.0")

    // ViewModel
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Root execution - libsu
    implementation("com.github.topjohnwu.libsu:core:5.2.1")
    implementation("com.github.topjohnwu.libsu:io:5.2.1")

    // Coroutines - latest version for better performance
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Terminal emulator (ported from LXC-Manager / termux-app)
    // 0.118.3 consumes unsupported CSI private-parameter sequences (ESC [ = / ESC [ <)
    // instead of leaking them to the screen — fish 4.x probes with ESC[=5u.
    implementation("com.github.termux.termux-app:terminal-view:0.118.3")
    implementation("com.github.termux.termux-app:terminal-emulator:0.118.3")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
}
