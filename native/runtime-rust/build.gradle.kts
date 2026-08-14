plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
}

android {
    namespace = "com.mtzallqmy.aiagent.native_runtime"
    compileSdk = 34
    ndkVersion = "26.3.11579264"

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { aidl = true }

    sourceSets.getByName("main").jniLibs.srcDir(layout.buildDirectory.dir("rust-jni"))
}

val rustOutput = layout.buildDirectory.dir("rust-jni")
val rustTarget = layout.buildDirectory.dir("cargo-target")

val buildRustRuntime by tasks.registering(Exec::class) {
    inputs.files(fileTree("rust") { include("**/*.rs", "Cargo.toml", "Cargo.lock") })
    outputs.dir(rustOutput)
    workingDir(project.file("rust"))
    environment("CARGO_TARGET_DIR", rustTarget.get().asFile.absolutePath)
    commandLine(
        "cargo", "ndk",
        "-t", "arm64-v8a",
        "-t", "armeabi-v7a",
        "-t", "x86_64",
        "--platform", "26",
        "-o", rustOutput.get().asFile.absolutePath,
        "build", "--release", "--locked",
    )
}

tasks.named("preBuild").configure { dependsOn(buildRustRuntime) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
}
