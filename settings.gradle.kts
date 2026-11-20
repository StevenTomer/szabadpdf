pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // MuPDF (Artifex). Restricted to its own group so nothing else resolves from here.
        maven("https://maven.ghostscript.com") {
            content { includeGroup("com.artifex.mupdf") }
        }
        // Tesseract OCR for Android (Apache-2.0), only published on JitPack.
        maven("https://jitpack.io") {
            content { includeGroup("cz.adaptech.tesseract4android") }
        }
    }
}

rootProject.name = "szabadpdf"
include(":app", ":core:pdf", ":feature:viewer", ":feature:organize", ":core:scan", ":feature:scan")
