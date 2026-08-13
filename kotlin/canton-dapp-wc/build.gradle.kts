plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

description = "WalletConnect transport for CIP-0103: carries JSON-RPC frames over a WalletConnect session"

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Only canton-dapp — like the LAN transport, this carries the protocol and
    // routes into a DappRequestHandler (which DappSession satisfies). It has NO
    // dependency on a WalletConnect client library: the Reown WalletKit binding
    // is an Android/app concern that drives this adapter through its two public
    // touch-points (sessionNamespaces + handle). That keeps the transport pure
    // JVM and unit-testable against the real engine with no relay.
    api(project(":canton-dapp"))

    testImplementation(libs.kotlin.test)
    // The wallet-side engine, to prove the adapter drives a real DappSession.
    testImplementation(project(":canton-dapp-wallet"))
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "canton-dapp-wc", version.toString())
    pom {
        name = "Canton dApp WalletConnect transport"
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
