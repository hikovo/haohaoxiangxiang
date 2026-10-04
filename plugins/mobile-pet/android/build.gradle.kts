plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.hikovo.mobilepet"
    compileSdk = 36
    // One source of policy for Android and the standalone JVM chat service.
    sourceSets.getByName("main").java.apply {
        srcDir("../../../shared/chat-engine/src/main/kotlin")
        exclude("**/OfflineDeepSeek.kt", "**/LocalChatModel.kt", "**/LegacyLiteRtProvider.kt", "**/OverlayChat.kt", "**/BoundedOfflineWriter.kt", "**/OfflinePersonaPrompt.kt")
    }
    // Retired model contract tests remain in the workspace, not this app's build.
    sourceSets.getByName("test").java.exclude("**/OfflineWriterContractTest.kt")

    defaultConfig {
        minSdk = 26

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    packaging {
        jniLibs.excludes += setOf("**/libsanhao_llama.so", "**/libc++_shared.so")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    androidResources {
        ignoreAssetsPattern = "*.litertlm:*.gguf:chat-rules.json"
    }
}

android {
    kotlinOptions {
        jvmTarget = "1.8"
        freeCompilerArgs += "-Xskip-metadata-version-check"
    }
}

// Kotlin collects its own source trees, so Java source filters alone are not enough.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    exclude("**/OfflineDeepSeek.kt", "**/LocalChatModel.kt", "**/LegacyLiteRtProvider.kt", "**/OverlayChat.kt", "**/BoundedOfflineWriter.kt", "**/OfflinePersonaPrompt.kt", "**/OfflineWriterContractTest.kt")
}

dependencies {

    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.appcompat:appcompat:1.6.0")
    implementation("com.google.android.material:material:1.7.0")
    // 2.9.x is the newest line compatible with this project's Kotlin 1.9 toolchain.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    implementation(project(":tauri-android"))
}
