// Headless/overlay CLI: `ows run app.ows` and `ows run overlay app.ows`.
// Plain Kotlin/JVM, same as ows-core — this module has no Android
// dependency either, which is the whole point: it can run on Linux,
// Windows, or macOS (or inside Termux/PRoot on Android, correctly
// identifying itself as a Linux host rather than pretending to be one —
// see JvmHostManager / AndroidHostManager kdoc in ows-core).
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir("src/main/java")
    }
}

application {
    mainClass.set("com.owsowd.cli.Main")
}

dependencies {
    implementation(project(":ows-core"))
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
}
