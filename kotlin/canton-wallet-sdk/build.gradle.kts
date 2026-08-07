plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

description = "Wallet-grade Kotlin SDK for the Canton Network: external signing, party onboarding, token standard"

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":canton-sdk"))
    // Registry (off-ledger) HTTP APIs: OkHttp is already on the classpath
    // transitively via grpc-okhttp; kotlinx-serialization-json is used via
    // its JsonElement API only (no compiler plugin required).
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.grpc.okhttp)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "canton-wallet-sdk", version.toString())
    pom {
        name = "Canton Kotlin Wallet SDK"
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
