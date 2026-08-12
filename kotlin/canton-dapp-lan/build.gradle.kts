plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

description = "LAN gRPC transport for CIP-0103: JSON-RPC frames over a bidirectional stream"

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Only canton-dapp — this transport carries both roles (a dApp dials, a
    // wallet listens), so it must not pull in the wallet stack. The provider
    // side routes into a DappRequestHandler, which DappSession satisfies.
    api(project(":canton-dapp"))
    // The tunnel is a hand-registered bidi method with a raw-byte marshaller;
    // no .proto, no codegen (see DappTunnel). grpc-okhttp brings the channel
    // and server builders and runs on Android; grpc-stub brings the
    // Server/ClientCalls streaming helpers.
    implementation(libs.grpc.okhttp)
    implementation(libs.grpc.stub)

    testImplementation(libs.kotlin.test)
    testImplementation(project(":canton-dapp-wallet"))
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "canton-dapp-lan", version.toString())
    pom {
        name = "Canton dApp LAN transport"
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
