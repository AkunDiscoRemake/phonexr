plugins {
    id("com.android.library")
}

// OrangeHanding: YOLO11 finds people and wrists, MediaPipe reads the fine landmarks inside them.
android {
    namespace = "com.samrat.orangehanding"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The .tflite and .task models are memory-mapped, so they must stay uncompressed in the APK.
    androidResources {
        noCompress += listOf("tflite", "task")
    }
}

dependencies {
    api("com.google.mediapipe:tasks-vision:0.10.35")
    api("com.google.ai.edge.litert:litert:1.4.0")
}
