plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
}

tasks.withType<Test>().configureEach {
    // Tests read the confirmed seed's list names from the file itself (designs/stage-1-foundation.md 9).
    systemProperty("smj.seedFile", rootProject.file("app/src/main/assets/seed/lists.json").absolutePath)
    systemProperty("smj.drawingFile", rootProject.file("app/src/main/assets/drawing/car-top.json").absolutePath)
    systemProperty("smj.linksFile", rootProject.file("app/src/main/assets/drawing/links.json").absolutePath)
}
