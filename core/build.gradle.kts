plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

val seedFile = rootProject.file("app/src/main/assets/seed/lists.json")
val drawingFile = rootProject.file("app/src/main/assets/drawing/car-top.json")
val linksFile = rootProject.file("app/src/main/assets/drawing/links.json")

// The check's seed phase (designs/stage-1-foundation.md 4.5) runs this and reads build/check/seed-result.txt.
// From S1.26 it validates the seed, then the drawing and the link file (4.20, 4.22).
tasks.register<JavaExec>("validateSeed") {
    group = "verification"
    description = "Validates the seed, the drawing and the link file under app/src/main/assets; writes build/check/seed-result.txt."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.streamlinemyjob.inspect.core.ValidateSeedKt")
    args(seedFile.absolutePath, drawingFile.absolutePath, linksFile.absolutePath, rootProject.layout.buildDirectory.file("check/seed-result.txt").get().asFile.absolutePath)
    isIgnoreExitValue = false
}

tasks.withType<Test>().configureEach {
    systemProperty("smj.seedFile", seedFile.absolutePath)
    systemProperty("smj.drawingFile", drawingFile.absolutePath)
    systemProperty("smj.linksFile", linksFile.absolutePath)
}
