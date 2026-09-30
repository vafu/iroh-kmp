plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
}

group = "io.github.vafu"
version = "0.1.0-SNAPSHOT"

val repositoryRoot = rootProject.projectDir
val isMacOs = System.getProperty("os.name").startsWith("Mac", ignoreCase = true)

kotlin {
    android {
        namespace = "io.github.vafu.iroh.native"
        compileSdk = 35
        minSdk = 26

        withHostTest {}
    }

    val iosTargets = listOf(
        iosArm64() to "aarch64-apple-ios",
        iosSimulatorArm64() to "aarch64-apple-ios-sim",
        iosX64() to "x86_64-apple-ios",
    )
    iosTargets.forEach { (target, rustTarget) ->
        val nativeLibrary = repositoryRoot.resolve(
            "target/$rustTarget/release/libiroh_kmp_native.a",
        )
        val buildNative = tasks.register<Exec>(
            "buildIrohNative${target.name.replaceFirstChar(Char::uppercase)}",
        ) {
            group = "build"
            description = "Builds the Rust Iroh facade for ${target.name}"
            workingDir(repositoryRoot)
            commandLine(
                "cargo", "build", "--package", "iroh-kmp-native",
                "--release", "--target", rustTarget,
            )
            inputs.files(
                repositoryRoot.resolve("Cargo.toml"),
                repositoryRoot.resolve("Cargo.lock"),
            )
            inputs.dir(repositoryRoot.resolve("native/iroh-kmp-native/src"))
            outputs.file(nativeLibrary)
            enabled = isMacOs
        }
        val interop = target.compilations.getByName("main").cinterops.create("iroh_kmp") {
            includeDirs(repositoryRoot.resolve("native/iroh-kmp-native"))
            extraOpts(
                "-libraryPath", nativeLibrary.parent,
                "-staticLibrary", nativeLibrary.name,
            )
        }
        tasks.named(interop.interopProcessingTaskName).configure { dependsOn(buildNative) }
    }

    sourceSets {
        commonMain.dependencies {
            api(project(":iroh-api"))
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
