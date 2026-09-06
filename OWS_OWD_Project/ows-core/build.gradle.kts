// ows-core is the OWS/OWD language runtime: lexer, parser, compiler,
// bytecode, VM, OWD scene graph, and the HostManager contract. It has zero
// Android dependencies (verified: the one file that used to need
// android.graphics — CanvasRenderer — moved to :app, since a concrete
// Canvas-based renderer is host-specific GUI code, not core language
// runtime). That's what lets it build as a plain Kotlin/JVM module instead
// of an Android library: :app (Android) and :ows-cli (plain JVM CLI) both
// depend on the exact same jar, with no Android classes anywhere in it.
plugins {
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    jvmToolchain(17)
    sourceSets.main {
        // Existing code lives under the Android-convention src/main/java
        // path (kept as-is to minimize churn); the Kotlin/JVM plugin's
        // default source set only assumes src/main/kotlin, so this is
        // spelled out explicitly rather than relying on an implicit dual
        // default.
        kotlin.srcDir("src/main/java")
    }
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
}
