plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

description = "Wallet-side CIP-0103 provider: request dispatch, per-peer grants, and approval seams"

kotlin {
    jvmToolchain(17)
}

dependencies {
    // The protocol surface is part of this module's API: a host implementing
    // DappApprovalDelegate handles DappWallet, PrepareSubmission and friends.
    api(project(":canton-dapp"))
    // Signing and submission live here, which is exactly why the dApp-side
    // module does not depend on canton-wallet-sdk.
    api(project(":canton-wallet-sdk"))

    testImplementation(libs.kotlin.test)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "canton-dapp-wallet", version.toString())
    pom {
        name = "Canton dApp Provider"
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
