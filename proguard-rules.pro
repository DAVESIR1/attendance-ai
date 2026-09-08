/*
 * Attendance AI — R8/ProGuard keep rules.
 *
 * Release minification is currently disabled (minifyEnabled false); these
 * rules keep the reflection/JNI-facing surfaces safe if it gets enabled.
 */
# MediaPipe Tasks loads graph models + native code reflectively.
-keep class com.google.mediapipe.** { *; }
# TensorFlow Lite uses JNI to bridge the interpreter into these classes.
-keep class org.tensorflow.lite.** { *; }
