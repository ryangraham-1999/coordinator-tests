// Built-in Kotlin (AGP 9) compiles the app module. The Kotlin version used everywhere
// is the one pinned in gradle/libs.versions.toml (designs/stage-1-foundation.md 4.1).
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// The check (designs/stage-1-foundation.md 4.5) picks one seed per run and passes it as -Psmj.seed;
// every test task gets it as system property smj.seed. smj.checkDir is where tests write
// build/check/timings.txt (4.15).
subprojects {
    tasks.withType<Test>().configureEach {
        providers.gradleProperty("smj.seed").orNull?.let { systemProperty("smj.seed", it) }
        systemProperty(
            "smj.checkDir",
            providers.gradleProperty("smj.checkDir").orNull ?: rootProject.layout.buildDirectory.dir("check").get().asFile.absolutePath,
        )
    }
}
