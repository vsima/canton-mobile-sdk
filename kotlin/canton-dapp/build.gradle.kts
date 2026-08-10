plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

description = "CIP-0103 dApp API for Canton: protocol types, JSON-RPC codec, and dApp-side client"

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Deliberately NOT canton-sdk. A dApp links this module to talk to a
    // wallet; it has no business pulling in the Ledger API stubs, signing
    // drivers or the token standard. The wallet-side engine lives in
    // :canton-dapp-wallet, which is where that dependency belongs.
    //
    // Both of these are `api`: JsonElement appears in the public surface
    // (Daml commands, ledgerApi results) and Flow carries the event stream.
    // kotlinx-serialization is used via its JsonElement API only — no
    // compiler plugin, no reflection, R8-safe — as in :canton-wallet-sdk.
    api(libs.kotlinx.serialization.json)
    api(libs.coroutines.core)

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "canton-dapp", version.toString())
    pom {
        name = "Canton dApp API"
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
