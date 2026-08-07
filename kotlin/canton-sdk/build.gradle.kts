plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.maven.publish)
}

description = "Ergonomic Kotlin SDK for the Canton Network Ledger API"

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":canton-ledger-api"))
    implementation(libs.grpc.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.grpc.inprocess)
}

tasks.test {
    useJUnitPlatform()
}

mavenPublishing {
    coordinates(group.toString(), "canton-sdk", version.toString())
    pom {
        name = "Canton Kotlin SDK"
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
