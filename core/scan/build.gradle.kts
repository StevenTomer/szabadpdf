import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

/**
 * Fetches a data file at build time and checks it against a known SHA-256, so a changed or
 * corrupted download fails the build instead of shipping. The file lands at [assetPath] inside
 * [outputDirectory], which is added to the app's assets.
 */
abstract class DownloadAssetTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:Input
    abstract val assetPath: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun download() {
        val file = outputDirectory.file(assetPath.get()).get().asFile
        if (file.exists() && hash(file) == sha256.get()) return
        file.parentFile.mkdirs()
        val partial = File(file.parentFile, "${file.name}.part")
        URI(url.get()).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        val actual = hash(partial)
        if (actual != sha256.get()) {
            partial.delete()
            throw GradleException("Checksum mismatch for ${assetPath.get()}: expected ${sha256.get()}, got $actual")
        }
        partial.renameTo(file)
    }

    private fun hash(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}

// FairScan's document segmentation model (GPL-3.0).
val downloadModel = tasks.register<DownloadAssetTask>("downloadSegmentationModel") {
    url.set("https://github.com/pynicolas/fairscan-segmentation-model/releases/download/v1.2.0/fairscan-segmentation-model.tflite")
    sha256.set("96e14d7e610dd0c27b768b228fbc553b4ec119ebe68f3a3594029a25400691d2")
    assetPath.set("fairscan-segmentation-model.tflite")
    outputDirectory.set(layout.buildDirectory.dir("downloads/model"))
}

// Tesseract's English recognition data, "fast" variant (Apache-2.0), pinned to a commit.
val downloadOcrData = tasks.register<DownloadAssetTask>("downloadOcrData") {
    url.set("https://raw.githubusercontent.com/tesseract-ocr/tessdata_fast/87416418657359cb625c412a48b6e1d6d41c29bd/eng.traineddata")
    sha256.set("7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2")
    assetPath.set("tessdata/eng.traineddata")
    outputDirectory.set(layout.buildDirectory.dir("downloads/ocr"))
}

android {
    namespace = "org.szabadpdf.core.scan"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    // The model is memory-mapped straight out of the APK.
    androidResources { noCompress += "tflite" }
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(downloadModel, DownloadAssetTask::outputDirectory)
        variant.sources.assets?.addGeneratedSourceDirectory(downloadOcrData, DownloadAssetTask::outputDirectory)
    }
}

dependencies {
    api(libs.opencv)
    implementation(libs.litert)
    implementation(libs.tesseract4android)
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
