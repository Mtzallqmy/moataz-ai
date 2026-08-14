plugins {
    id("com.android.library") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
    
}

android {
    namespace = "com.mtzallqmy.aiagent.core.memory"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.room.runtime)
    testImplementation(libs.junit)
    implementation(libs.kotlinx.coroutines.core)
    implementation("org.jetbrains.kotlin:kotlin-test:1.9.24")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    implementation(project(":core:database"))
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:network"))
    implementation(libs.squareup.okhttp)
    implementation(libs.kotlinx.serialization.json)

}
