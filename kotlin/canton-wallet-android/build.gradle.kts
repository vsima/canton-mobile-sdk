plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

description = "Android Keystore signing driver and encrypted wallet store for the Canton wallet stack"

android {
    namespace = "io.github.vsima.canton.wallet.android"
    compileSdk = 36

    defaultConfig {
        // API 26 — and it is the JVM modules that set the floor, not this
        // one. canton-sdk and canton-wallet-sdk use java.time and
        // java.util.Base64 (both API 26 on Android) in the token provider,
        // the Daml value builders, and the registry/scan/token-standard
        // clients. Desugaring would paper over it; we deliberately don't
        // enable it, so 26 is the honest floor. This module's own
        // constraint, KeyGenParameterSpec, needs only 23.
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":canton-wallet-sdk"))
    // JsonElement API only, as in canton-wallet-sdk: no compiler plugin, no
    // reflection, nothing for R8 to keep.
    implementation(libs.kotlinx.serialization.json)
    // Jetpack DataStore owns the file: atomic writes, one writer per file,
    // reads off the main thread. The wallet store adds encryption on top.
    implementation(libs.androidx.datastore)
    // @RequiresApi only — CLASS retention, so consumers need nothing at runtime.
    compileOnly(libs.androidx.annotation)

    testImplementation(libs.kotlin.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)
}

mavenPublishing {
    configure(
        com.vanniktech.maven.publish.AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = true,
            publishJavadocJar = true,
        )
    )
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "canton-wallet-android", version.toString())
    pom {
        name = "Canton Android Keystore Driver"
        description = project.description
        url = "https://github.com/vsima/canton-mobile-sdk"
        licenses {
            license {
                name = "Apache-2.0"
                url = "https://www.apache.org/licenses/LICENSE-2.0"
            }
        }
        developers {
            developer {
                id = "vsima"
                name = "Victor Sima"
            }
        }
        scm {
            url = "https://github.com/vsima/canton-mobile-sdk"
        }
    }
}
