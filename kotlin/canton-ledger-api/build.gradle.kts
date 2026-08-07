import com.google.protobuf.gradle.id
import com.google.protobuf.gradle.proto

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.protobuf)
    alias(libs.plugins.maven.publish)
}

description = "Generated gRPC bindings for the Canton Ledger API (com.daml.ledger.api.v2)"

kotlin {
    jvmToolchain(17)
}

dependencies {
    // Lite protobuf runtime: Android-safe (the full protobuf-java runtime
    // conflicts with the protobuf-javalite that DataStore/Firebase pull in).
    api(libs.grpc.protobuf.lite)
    api(libs.grpc.stub)
    api(libs.grpc.kotlin.stub)
    api(libs.protobuf.kotlin.lite)
    api(libs.coroutines.core)
}

sourceSets {
    main {
        proto {
            srcDir("../../proto/ledger-api")
            srcDir("../../proto/ledger-api-value")
            // google.rpc types referenced by the Ledger API, compiled lite
            // into this artifact (proto-google-common-protos is full-only).
            srcDir("../../proto/googleapis")
        }
    }
}

protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    plugins {
        id("grpc") {
            artifact = libs.grpc.generator.java.get().toString()
        }
        id("grpckt") {
            artifact = libs.grpc.generator.kotlin.get().toString() + ":jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc") { option("lite") }
                id("grpckt") { option("lite") }
            }
            task.builtins {
                getByName("java") { option("lite") }
                id("kotlin") { option("lite") }
            }
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    coordinates(group.toString(), "canton-ledger-api", version.toString())
    pom {
        name = "Canton Ledger API bindings"
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
