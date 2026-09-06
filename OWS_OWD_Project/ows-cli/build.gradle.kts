// Headless/overlay CLI: `ows run app.ows` and `ows run overlay app.ows`.
//
// :ows-core is an Android library module (see its own build.gradle.kts) —
// that's required for the existing CI pipeline (.github/workflows/build.yml),
// which assembles it into ows-core-release.aar / ows-core-debug.aar. A plain
// Kotlin/JVM module like this one can't cleanly consume an AAR as a project
// dependency, so instead of `implementation(project(":ows-core"))`, this
// compiles ows-core's actual source files directly as an extra source
// directory below. That's safe because ows-core's source has zero Android
// imports (verified) — it's already pure Kotlin/JVM code that just happens
// to be packaged as an Android library for CI's sake. This gives :ows-cli
// a real, independent JVM build of the exact same runtime, with zero changes
// to how :ows-core itself is built or packaged.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        kotlin.srcDir("src/main/java")
        kotlin.srcDir("../ows-core/src/main/java")
    }
}

application {
    mainClass.set("com.owsowd.cli.Main")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
}
