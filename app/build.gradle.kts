plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.fullfind.browser"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.fullfind.browser"
        minSdk = 30; targetSdk = 34
        versionCode = 1; versionName = "1.0"
        resourceConfigurations += listOf("en")
    }
    signingConfigs {
        create("localDebug") {
            storeFile = file("../debug.keystore"); storePassword = "android"
            keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("localDebug") }
        getByName("release") {
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("localDebug")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    packaging { resources.excludes += listOf("META-INF/**", "kotlin/**", "DebugProbesKt.bin") }
}
