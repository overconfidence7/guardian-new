plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    `java-library`
}

group = "app.guardianmode"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    testImplementation(kotlin("test"))
    testImplementation("org.junit.jupiter:junit-jupiter:6.1.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets["test"].resources.srcDir("../shared/fixtures")

tasks.test {
    // Tests tagged "known-bug" describe behavior the app doesn't have yet. Pull-request CI
    // leaves them out; the daily run passes -PincludeKnownBugs=true (see qa/daily/README.md).
    useJUnitPlatform {
        if (!providers.gradleProperty("includeKnownBugs").isPresent) excludeTags("known-bug")
    }
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
