import com.android.build.api.artifact.SingleArtifact
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release key (designs/stage-1-foundation.md 4.2): SMJ_KEYSTORE_PROPERTIES first, then
// <home>/.smj/keystore.properties. Made once on the main PC by scripts/make-key (S1.21).
// With no file found, the release build type has no signing config: configuring the project and the
// export tasks below still work, and any task that packages the release fails with a clear message.
val smjHome: String = System.getProperty("user.home")
val releaseKeyProperties: File =
    providers.environmentVariable("SMJ_KEYSTORE_PROPERTIES").orNull?.takeIf { it.isNotBlank() }?.let { File(it) }
        ?: File(smjHome, ".smj/keystore.properties")
val releaseKey: Properties? =
    releaseKeyProperties.takeIf { it.isFile }?.let { f -> Properties().apply { f.inputStream().use { load(it) } } }

android {
    namespace = "com.streamlinemyjob.inspect"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.streamlinemyjob.inspect"
        minSdk = 31
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Throwaway key for test builds of the .debug app only, committed on purpose so every test build
        // installs over the last one. It has nothing to do with the release key.
        getByName("debug") {
            storeFile = file("debug-key/test-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appName"] = "Coordinator (debug)"
        }
        release {
            isMinifyEnabled = false
            manifestPlaceholders["appName"] = "Coordinator"
            if (releaseKey != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
    }

    // Test code built into both the JVM tests and the instrumented tests (designs/stage-1-foundation.md 3, 4.15):
    // RepositoryAdapter, SetClock and Snapshot.read. Never part of main, so nothing in it ships.
    sourceSets {
        getByName("test").kotlin.srcDir("src/sharedTest/kotlin")
        getByName("androidTest").kotlin.srcDir("src/sharedTest/kotlin")
        // The exported schema files, for the migration test (4.29). Unit tests only see the build's own
        // assets, so they go in the debug build's (never the release's); they are small JSON files.
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }

    testOptions {
        // Robolectric database tests (designs/stage-1-foundation.md 4.1; sdk=34 in robolectric.properties).
        unitTests.isIncludeAndroidResources = true
        managedDevices {
            localDevices {
                create("api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp"
                }
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}

// Room's exported schema, committed (designs/stage-1-foundation.md 3, 4.11).
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Tests that load the confirmed seed read it from the file itself (designs/stage-1-foundation.md 9).
tasks.withType<Test>().configureEach {
    systemProperty("smj.seedFile", file("src/main/assets/seed/lists.json").absolutePath)
    systemProperty("smj.drawingFile", file("src/main/assets/drawing/car-top.json").absolutePath)
    systemProperty("smj.linksFile", file("src/main/assets/drawing/links.json").absolutePath)
}

// Any task that packages or signs the release fails when no key was found (4.2).
if (releaseKey == null) {
    val missingKeyMessage = "Release key not found at ${releaseKeyProperties.absolutePath}. " +
        "Run scripts/make-key (once, on the main PC), then back up ${File(smjHome, ".smj").absolutePath}."
    tasks.configureEach {
        if (Regex("(package|sign)Release(Bundle)?").matches(name)) {
            doFirst { throw GradleException(missingKeyMessage) }
        }
    }
}

// Exports for the check (4.3, 4.4). They use only AGP's public variant API and resolved
// configurations, so they need no release key and depend on no packaging or signing task.
val smjCheckDir = rootProject.layout.buildDirectory.dir("check")

val smjExportManifests = tasks.register("smjExportManifests") {
    group = "verification"
    description = "Copies each variant's merged manifest to build/check/merged-manifest-<variant>.xml."
}

androidComponents {
    onVariants { variant ->
        val export = tasks.register<Copy>("smjExportManifest${variant.name.replaceFirstChar { it.uppercase() }}") {
            from(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
            into(smjCheckDir)
            rename { "merged-manifest-${variant.name}.xml" }
        }
        smjExportManifests.configure { dependsOn(export) }
    }
}

tasks.register("smjExportRuntimeDeps") {
    group = "verification"
    description = "Writes the resolved releaseRuntimeClasspath to build/check/release-runtime-deps.txt."
    val root = configurations.named("releaseRuntimeClasspath").flatMap { it.incoming.resolutionResult.rootComponent }
    val out = smjCheckDir.map { it.file("release-runtime-deps.txt") }
    outputs.file(out)
    outputs.upToDateWhen { false }
    doLast {
        val seen = HashSet<org.gradle.api.artifacts.result.ResolvedComponentResult>()
        val lines = sortedSetOf<String>()
        fun walk(c: org.gradle.api.artifacts.result.ResolvedComponentResult) {
            if (!seen.add(c)) return
            c.dependencies.filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>().forEach { d ->
                val sel = d.selected
                when (val id = sel.id) {
                    is org.gradle.api.artifacts.component.ModuleComponentIdentifier ->
                        lines += "${id.group}:${id.module}:${id.version}"
                    is org.gradle.api.artifacts.component.ProjectComponentIdentifier ->
                        lines += "project ${id.projectPath}"
                }
                walk(sel)
            }
        }
        walk(root.get())
        val f = out.get().asFile
        f.parentFile.mkdirs()
        f.writeText(lines.joinToString("\n", postfix = "\n"))
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)

    testImplementation(project(":testdata"))
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.kotlinx.serialization.json)

    androidTestImplementation(project(":testdata"))
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
