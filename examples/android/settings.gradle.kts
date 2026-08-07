pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "canton-android-sample"

// Composite build: substitutes io.github.vsima.canton:* dependencies with
// the SDK modules from source, so the sample always tracks the working tree.
includeBuild("../../kotlin")

include(":app")
