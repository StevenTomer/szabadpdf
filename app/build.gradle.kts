import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "org.szabadpdf"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "org.szabadpdf"
        minSdk = 26
        targetSdk = 37
        versionCode = 11
        versionName = "0.6.4"
    }
    buildFeatures { compose = true }
    // The APK is downloaded directly, so its native libraries are stored compressed: a much
    // smaller download, unpacked once at install.
    packaging { jniLibs { useLegacyPackaging = true } }
    // MuPDF, OpenCV and LiteRT are native libraries; one APK per processor type keeps each small.
    // (installDebug picks the right one for the connected device.)
    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "armeabi-v7a", "x86_64", "x86")
            isUniversalApk = false
        }
    }
    // Release signing: the key lives outside the repository. Its details are read from
    // ~/.android-keys/szabadpdf-release.properties (or the file named by the Gradle property
    // szabadpdf.signing). Without it, release builds are simply left unsigned.
    val signingFile = (findProperty("szabadpdf.signing") as String?)?.let(::file)
        ?: file("${System.getProperty("user.home")}/.android-keys/szabadpdf-release.properties")
    val signing = Properties().apply {
        if (signingFile.exists()) signingFile.inputStream().use(::load)
    }
    signingConfigs {
        if (signing.isNotEmpty()) {
            create("release") {
                storeFile = file(signing.getProperty("storeFile"))
                storePassword = signing.getProperty("storePassword")
                keyAlias = signing.getProperty("keyAlias")
                keyPassword = signing.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        debug { applicationIdSuffix = ".debug" }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
    }
}

dependencies {
    implementation(project(":core:ui"))
    implementation(project(":core:pdf"))
    implementation(project(":feature:viewer"))
    implementation(project(":feature:organize"))
    implementation(project(":feature:scan"))
    implementation(platform(libs.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.material.icons.extended)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.kotlinx.coroutines.android)
    debugImplementation(libs.compose.ui.tooling)
}
