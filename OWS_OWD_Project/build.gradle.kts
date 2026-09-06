// Top-level build file for OWS/OWD ecosystem
plugins {
    id("com.android.application") version "8.2.2" apply false
    id("com.android.library") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    // ows-core is now a pure Kotlin/JVM module (no Android dependencies —
    // see ows-core/build.gradle.kts) so it can be shared by both the
    // Android :app module and the plain-JVM :ows-cli module. This plugin
    // id is what makes a `kotlin("jvm")` / `id("org.jetbrains.kotlin.jvm")`
    // module possible in the same multi-project build as the Android ones.
    id("org.jetbrains.kotlin.jvm") version "1.9.22" apply false
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
