# Native libraries call back into these Java classes by name (JNI), so R8 must keep them whole.

# MuPDF
-keep class com.artifex.mupdf.fitz.** { *; }

# OpenCV
-keep class org.opencv.** { *; }

# LiteRT (TensorFlow Lite)
-keep class org.tensorflow.lite.** { *; }
-dontwarn org.tensorflow.lite.**

# Tesseract (Tesseract4Android)
-keep class com.googlecode.tesseract.android.** { *; }
-keep class com.googlecode.leptonica.android.** { *; }

# Keep line numbers in crash stack traces readable.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
