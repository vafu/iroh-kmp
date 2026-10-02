plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

val repositoryRoot = rootProject.projectDir
val nativeOutput = layout.buildDirectory.dir("native/android")
val androidSdk = providers.environmentVariable("ANDROID_HOME")
    .orElse("${System.getProperty("user.home")}/Android/Sdk")
val androidNdk = androidSdk.map { "$it/ndk/30.0.16248370" }

val buildIrohNative by tasks.registering(Exec::class) {
    group = "build"
    description = "Builds the Rust Iroh facade for Android arm64 and x86-64"
    workingDir(repositoryRoot)
    environment("ANDROID_HOME", androidSdk.get())
    environment("ANDROID_NDK_HOME", androidNdk.get())
    commandLine(
        "cargo", "ndk",
        "--target", "arm64-v8a",
        "--target", "x86_64",
        "--platform", "26",
        "--output-dir", nativeOutput.get().asFile,
        "build", "--release", "--package", "iroh-kmp-native",
    )
    inputs.files(repositoryRoot.resolve("Cargo.toml"), repositoryRoot.resolve("Cargo.lock"))
    inputs.dir(repositoryRoot.resolve("native/iroh-kmp-native/src"))
    outputs.files(
        nativeOutput.map { it.file("arm64-v8a/libiroh_kmp_native.so") },
        nativeOutput.map { it.file("x86_64/libiroh_kmp_native.so") },
    )
}

android {
    namespace = "io.github.vafu.iroh.sample"
    compileSdk = 35
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "io.github.vafu.iroh.sample"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures.compose = true
    sourceSets.getByName("main").jniLibs.directories.add(nativeOutput.get().asFile)
}

tasks.named("preBuild").configure { dependsOn(buildIrohNative) }

dependencies {
    implementation(project(":iroh-native"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
}
