plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.samrat.cardboardhands"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.samrat.cardboardhands"
        minSdk = 29
        targetSdk = 35
        versionCode = 14
        versionName = "2.0.1"
        // PhoneXR itself runs 64-bit; this keeps OpenCV and MediaPipe for other ABIs out of the APK.
        ndk { abiFilters += listOf("arm64-v8a") }
    }

    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
        // The editions give the app its own name through a generated string.
        resValues = true
    }

    /**
     * Two editions of PhoneXR. "Full" is everything. "Lite" is for a phone that cannot spare the
     * work: no face, no depth network, no voice assistant, no ARCore — hands, Joy-Con, games,
     * cinema and the home stay. The two install side by side, so a weaker phone can carry Lite
     * while the same account keeps the full one elsewhere.
     */
    flavorDimensions += "edition"
    productFlavors {
        create("full") {
            dimension = "edition"
            buildConfigField("boolean", "LITE", "false")
            buildConfigField("boolean", "BE", "false")
            resValue("string", "app_label", "PhoneXR")
        }
        create("lite") {
            dimension = "edition"
            applicationIdSuffix = ".lite"
            versionNameSuffix = "-lite"
            buildConfigField("boolean", "LITE", "true")
            buildConfigField("boolean", "BE", "false")
            resValue("string", "app_label", "PhoneXR Lite")
        }
        // BE: no camera at all. A dot in the middle of the view aims, a tap on the screen clicks,
        // the room is black; no game patching, no friends — VR for watching and browsing.
        create("be") {
            dimension = "edition"
            applicationIdSuffix = ".be"
            versionNameSuffix = "-be"
            buildConfigField("boolean", "LITE", "true")
            buildConfigField("boolean", "BE", "true")
            resValue("string", "app_label", "PhoneXR BE")
        }
    }

    // One PhoneXR key everywhere (local builds, CI, patched games): games signed with it may start
    // hand tracking through the signature permission START_HAND_TRACKING.
    signingConfigs {
        getByName("debug") {
            storeFile = file("src/main/assets/phonexr-signing.p12")
            storeType = "pkcs12"
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    // libvrapi.so of the Gear VR adapter, built by gearvr-shim (see gearvr-shim/STATUS.txt).
    // A local build wins; the checked-in copy lets CI (and a fresh clone) package the adapter.
    val shimBuild = rootProject.file("gearvr-shim/build/assets")
    sourceSets.getByName("main").assets.srcDir(if (shimBuild.isDirectory) shimBuild else rootProject.file("gearvr-shim/prebuilt"))

    // Models are memory-mapped straight from the APK (YOLO11, MediaPipe), so they stay uncompressed.
    androidResources {
        noCompress += listOf("tflite", "task")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")

    val cameraX = "1.4.1"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("androidx.camera:camera-mlkit-vision:$cameraX")

    // OrangeHanding: YOLO11 + MediaPipe (hands and body), brings tasks-vision and LiteRT along.
    implementation(project(":orangehanding"))
    // Speech vs. other sounds for the face's mouth (YAMNet audio classifier).
    implementation("com.google.mediapipe:tasks-audio:0.10.35")
    // 6DoF in the VR home: ARCore tracks where the headset is in the room.
    implementation("com.google.ar:core:1.47.0")
    // Bundled QR recognizer: viewer profiles scan even when the optional Google Play
    // Code Scanner module is missing (common on custom ROMs and older phones).
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    // Calls: Supabase Realtime over a WebSocket.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.android.tools.build:apksig:8.7.3")
    implementation("com.google.android.material:material:1.12.0")
    // Нейросеть глубины (MiDaS) для 3D из обычного фото; сама модель скачивается по запросу.
    // LiteRT (the new TensorFlow Lite) comes with :tracking; the same Interpreter API runs MiDaS.
    // ArUco markers on the Joy-Con for camera tracking; Lite goes without it (24 МБ библиотеки).
    "fullImplementation"("org.opencv:opencv:4.14.0")
    // Shizuku runs the cinema display service as the shell user (virtual display + input for other apps).
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // Интерфейс в стиле Apple HIG: https://github.com/ienground/compose-hig
    implementation("zone.ien.hig:hig:1.4.1")
    // Второй вид интерфейса — Material You (цвета из обоев системы), переключается в настройках.
    implementation("androidx.compose.material3:material3:1.5.0-alpha24")
    // Material You icons in the VR home (Horizon-style library and dock); R8 keeps only the used ones.
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    // hig отдаёт их только в runtime; экраны используют их напрямую.
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("io.github.kyant0:backdrop:2.0.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    // WebXR in the built-in browser: the polyfill must run before the page's own scripts.
    implementation("androidx.webkit:webkit:1.14.0")

    testImplementation("junit:junit:4.13.2")
}
