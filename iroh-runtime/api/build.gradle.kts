plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

group = "io.github.vafu"
version = "0.1.0-SNAPSHOT"

kotlin {
    android {
        namespace = "io.github.vafu.iroh.api"
        compileSdk = 35
        minSdk = 26

        withHostTest {}
    }

    iosArm64()
    iosSimulatorArm64()
    iosX64()

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
