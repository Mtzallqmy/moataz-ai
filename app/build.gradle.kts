import java.io.File

plugins {
    id("com.android.application") version "8.5.2"
    id("org.jetbrains.kotlin.android") version "1.9.24"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24"
}

android {
    namespace = "com.mtzallqmy.aiagent"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.mtzallqmy.aiagent"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 65
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE") ?: ""
            if (keystorePath.isNotEmpty()) {
                storeFile = File(keystorePath)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val keystorePath = (System.getenv("RELEASE_KEYSTORE") ?: "")
            if (keystorePath.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Application modules
    implementation(project(":core:model"))
    implementation(project(":core:common"))
    implementation(project(":core:database"))
    implementation(project(":core:datastore"))
    implementation(project(":core:network"))
    implementation(project(":core:security"))
    implementation(project(":core:permissions"))
    implementation(project(":core:ui"))
    implementation(project(":core:agent"))
    implementation(project(":core:tools"))
    implementation(project(":core:capabilities"))
    implementation(project(":core:memory"))
    implementation(project(":core:workspace"))
    implementation(project(":core:sandbox"))
    implementation(project(":core:workflow"))
    implementation(project(":native:runtime-rust"))
    implementation(project(":native:local-llm"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:providers"))
    implementation(project(":feature:device"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:sandbox"))
    implementation(project(":feature:files"))
    implementation(project(":feature:security"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:logs"))
    implementation(project(":feature:schedules"))
    implementation(project(":tool:android"))
    implementation(project(":tool:filesystem"))
    implementation(project(":tool:terminal"))
    implementation(project(":tool:http"))
    implementation(project(":tool:mcp"))
    implementation(project(":tool:clipboard"))
    implementation(project(":tool:ssh"))
    implementation(project(":provider:openai"))
    implementation(project(":provider:anthropic"))
    implementation(project(":provider:google"))
    implementation(project(":provider:openrouter"))
    implementation(project(":provider:openai-compatible"))
    implementation(project(":provider:local"))

    // Platform
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.work.runtime.ktx)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
