plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "org.szabadpdf.core.pdf"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
}

dependencies {
    // MuPDF stays an implementation detail: nothing outside this module sees its types.
    implementation(libs.mupdf.fitz)
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
