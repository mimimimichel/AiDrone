plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Clé d'app DJI (developer.dji.com), liée au nom de paquet com.bebopia.app.
// Fournie par la propriété Gradle djiApiKey ou la variable d'environnement DJI_API_KEY (secret GitHub).
val djiApiKey: String = (project.findProperty("djiApiKey") as String?)?.takeIf { it.isNotBlank() }
    ?: System.getenv("DJI_API_KEY")?.takeIf { it.isNotBlank() }
    ?: ""

android {
    namespace = "com.bebopia.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bebopia.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 27
        versionName = "2.1"
        manifestPlaceholders["DJI_API_KEY"] = djiApiKey
        // Le kit DJI n'existe que pour les processeurs ARM (tous les téléphones récents).
        // -PonlyArm64=true : APK plus léger, pour les téléphones 64 bits (presque tous depuis 2017).
        ndk {
            abiFilters += if (project.findProperty("onlyArm64") == "true") listOf("arm64-v8a")
            else listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    useLibrary("org.apache.http.legacy")

    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += listOf(
                "*/*/libdjivideo.so", "*/*/libSDKRelativeJNI.so", "*/*/libFlyForbid.so", "*/*/libduml_vision_bokeh.so",
                "*/*/libyuv2.so", "*/*/libGroudStation.so", "*/*/libFRCorkscrew.so", "*/*/libUpgradeVerify.so",
                "*/*/libFR.so", "*/*/libDJIFlySafeCore.so", "*/*/libdjifs_jni.so", "*/*/libsfjni.so",
                "*/*/libDJICommonJNI.so", "*/*/libDJICSDKCommon.so", "*/*/libDJIUpgradeCore.so",
                "*/*/libDJIUpgradeJNI.so", "*/*/libDJIWaypointV2Core.so", "*/*/libDJIMOP.so", "*/*/libDJISDKLOGJNI.so",
            )
        }
        resources {
            excludes += listOf("META-INF/rxjava.properties", "assets/location_map_gps_locked.png", "assets/location_map_gps_3d.png")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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
    androidResources {
        noCompress += "tflite"
    }
}

dependencies {
    // Détecteur d'objets sur le téléphone (sans Internet)
    implementation("com.google.mediapipe:tasks-vision:0.10.20")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    // Kit DJI (Mavic Mini, Mini 2, Mini SE…) : connexion par la radiocommande en USB
    implementation("com.dji:dji-sdk:4.18") {
        exclude(module = "library-anti-distortion") // seulement pour les Mavic 2
        // fly-safe-database gardée : sans elle, DJI peut refuser le décollage tant qu'il ne l'a pas téléchargée
    }
    compileOnly("com.dji:dji-sdk-provided:4.18")
    // Utilisées par les écrans du kit DJI sans qu'il les déclare
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // WebSocket pour la conversation en direct avec Gemini Live
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
}
