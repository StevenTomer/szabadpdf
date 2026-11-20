import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

/**
 * Fetches FairScan's document segmentation model (GPL-3.0) at build time and checks it against
 * a known SHA-256, so a changed or corrupted download fails the build instead of shipping.
 */
abstract class DownloadModelTask : DefaultTask() {
    @get:Input
    abstract val url: Property<String>

    @get:Input
    abstract val sha256: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun download() {
        val file = outputDirectory.file(MODEL_FILE).get().asFile
        if (file.exists() && hash(file) == sha256.get()) return
        file.parentFile.mkdirs()
        val partial = File(file.parentFile, "$MODEL_FILE.part")
        URI(url.get()).toURL().openStream().use { input -> partial.outputStream().use { input.copyTo(it) } }
        val actual = hash(partial)
        if (actual != sha256.get()) {
            partial.delete()
            throw GradleException("Model checksum mismatch: expected ${sha256.get()}, got $actual")
        }
        partial.renameTo(file)
    }

    private fun hash(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }

    companion object {
        const val MODEL_FILE = "fairscan-segmentation-model.tflite"
    }
}

val downloadModel = tasks.register<DownloadModelTask>("downloadSegmentationModel") {
    url.set("https://github.com/pynicolas/fairscan-segmentation-model/releases/download/v1.2.0/fairscan-segmentation-model.tflite")
    sha256.set("96e14d7e610dd0c27b768b228fbc553b4ec119ebe68f3a3594029a25400691d2")
    outputDirectory.set(layout.buildDirectory.dir("downloads/model"))
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
        variant.sources.assets?.addGeneratedSourceDirectory(downloadModel, DownloadModelTask::outputDirectory)
    }
}

dependencies {
    api(libs.opencv)
    implementation(libs.litert)
    implementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.kotlinx.coroutines.android)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
