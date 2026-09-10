plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.maven.publish) apply false
    alias(libs.plugins.android.library) apply false
    // API reference: `./gradlew dokkaGenerate` aggregates every library
    // module into build/dokka/html (the Pages workflow publishes it).
    alias(libs.plugins.dokka)
}

allprojects {
    group = "io.github.vsima.canton"
    version = "0.7.0"
}

dokka {
    moduleName.set("canton-mobile-sdk (Kotlin)")
}

// canton-ledger-api is left out on purpose: its public surface is generated
// gRPC/protobuf stubs (216 MB of HTML that document nothing a reader wants).
dependencies {
    dokka(project(":canton-sdk"))
    dokka(project(":canton-wallet-sdk"))
    dokka(project(":canton-wallet-android"))
    dokka(project(":canton-dapp"))
    dokka(project(":canton-dapp-wallet"))
    dokka(project(":canton-dapp-lan"))
    dokka(project(":canton-dapp-wc"))
}
