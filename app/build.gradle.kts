plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.lumiscan.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.lumiscan.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "1.1.4"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        val apiBase = (project.findProperty("lumiscan.apiBase") as String?) ?: "https://134.185.119.124"
        buildConfigField("String", "API_BASE", "\"$apiBase\"")
    }

    splits {
        abi {
            // Per-ABI APKs for distribution (~half the size) plus a universal one.
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a")
            isUniversalApk = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += setOf(
                "META-INF/DEPENDENCIES", "META-INF/LICENSE*", "META-INF/NOTICE*",
                // Post-quantum tables pulled in via PdfBox's BouncyCastle; PDF AES encryption doesn't use them.
                "org/bouncycastle/pqc/**",
            )
        }
        jniLibs {
            useLegacyPackaging = true
            // Phones are ARM; dropping x86 keeps the universal APK small.
            excludes += setOf("lib/x86/**", "lib/x86_64/**")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.exifinterface:exifinterface:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // Camera
    val camerax = "1.5.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // Image processing: edge detection, perspective, filters, inpainting, counting
    // 4.10 predates the bundled KleidiCV HAL, whose SVE2 paths SIGILL on hosts that advertise
    // but cannot execute SVE (e.g. Apple-silicon emulators).
    implementation("org.opencv:opencv:4.10.0")

    // On-device ML (bundled models, no Google Play Services required)
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")
    implementation("com.google.mlkit:segmentation-selfie:16.0.0-beta6")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mlkit:translate:17.0.3")

    // PDF manipulation (merge / split / encrypt / compress / watermark / sign / text)
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.test:core:1.7.0")
}
