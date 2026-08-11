plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.vsima.canton.sample"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.vsima.canton.sample"
        // 26, not 21: the SDK's JVM modules use java.time and
        // java.util.Base64, which are API 26 on Android and not desugared.
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release {
            // Exercises R8 shrinking against the SDK — this is the check that
            // catches missing keep rules before a consumer's release build does.
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
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
    implementation("io.github.vsima.canton:canton-sdk:0.1.0-SNAPSHOT")
    // The CIP-0103 dApp layer: the sample exercises an in-process
    // client<->session round trip, which keeps the R8 release build covering
    // these modules on Android.
    implementation("io.github.vsima.canton:canton-dapp:0.1.0-SNAPSHOT")
    implementation("io.github.vsima.canton:canton-dapp-wallet:0.1.0-SNAPSHOT")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
