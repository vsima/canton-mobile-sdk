plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.maven.publish) apply false
}

allprojects {
    group = "io.github.vsima.canton"
    version = "0.5.0-SNAPSHOT"
}
