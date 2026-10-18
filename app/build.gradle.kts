plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "cn.weibo.filedownloader"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.weibo.filedownloader"
        minSdk = 24
        targetSdk = 35
        versionCode = 10041
        versionName = "0.0.51"
        testInstrumentationRunner = "cn.weibo.filedownloader.DownloadRefreshInstrumentation"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        getByName("release") {
            // Temporary: keep update compatibility with the APKs already distributed.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
            isUniversalApk = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    implementation(project(":wei-carousel"))
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.work:work-runtime:2.10.1")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("io.coil-kt:coil:2.7.0")
    implementation("io.coil-kt:coil-video:2.7.0")
    implementation("androidx.compose.ui:ui:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview:1.7.8")
    implementation("androidx.compose.material3:material3:1.3.2")
    debugImplementation("androidx.compose.ui:ui-tooling:1.7.8")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
