import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Compiles against the Java 17 API, not the JDK Gradle runs on: with a JDK 21 build, a call like List.removeFirst would
// otherwise bind to the Java 21 method, which Android before 15 lacks. Android lint does not check this module, so a Java 12-17
// API that Android 11 lacks still gets through; prefer Kotlin's own collection functions.
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
        freeCompilerArgs.add("-Xjdk-release=17")
    }
}

dependencies {
    testImplementation(libs.junit)
}

// The shared CI workflow and pre-commit hook run testDebugUnitTest, which a plain JVM module does not otherwise have.
// A Test task of its own, rather than an alias, so --tests filtering still works.
tasks.register<Test>("testDebugUnitTest") {
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
}
