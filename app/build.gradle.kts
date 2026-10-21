plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }

val geckoVersion = "140.0.20250707120347"
// -PtestAbi=x86_64 adds an emulator-testable split
val extraAbi = (project.findProperty("testAbi") as String?)

android {
    namespace = "com.fullfind.browser"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.fullfind.browser"
        minSdk = 30; targetSdk = 34
        versionCode = 4; versionName = "2.0"
        resourceConfigurations += listOf("en")
    }
    splits {
        abi {
            isEnable = true; reset()
            include("arm64-v8a", "armeabi-v7a", *listOfNotNull(extraAbi).toTypedArray())
            isUniversalApk = false
        }
    }
    signingConfigs {
        create("ff") {
            storeFile = file("/workspace/keys/fullfind.keystore"); storePassword = "android"
            keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    buildTypes {
        getByName("debug") { signingConfig = signingConfigs.getByName("ff") }
        getByName("release") {
            isMinifyEnabled = true; isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("ff")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
    packaging {
        resources.excludes += listOf("META-INF/*.version", "META-INF/*.kotlin_module", "DebugProbesKt.bin")
        jniLibs.useLegacyPackaging = true
    }
}

dependencies {
    implementation("org.mozilla.geckoview:geckoview:$geckoVersion")
    testImplementation("junit:junit:4.13.2")
}
