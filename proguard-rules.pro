# Attendance AI — R8/ProGuard keep rules.
#
# Release builds run with minifyEnabled true (assembleRelease), so everything
# below has to survive shrinking + obfuscation. Keep this list in sync whenever
# a new reflective/JNI-backed library is added — and always verify the release
# APK on the phone (enrol + recognise), not just `assembleDebug`.

# --- MediaPipe Tasks: native libs + graph/task-bundle loading -----------------
-keep class com.google.mediapipe.** { *; }
-dontwarn com.google.mediapipe.**

# --- TensorFlow Lite: JNI bridges the interpreter into these classes ----------
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.api.** { *; }
-dontwarn org.tensorflow.lite.**

# --- Protobuf (bundled with MediaPipe/TFLite): descriptor-driven reflection ---
-keep class com.google.protobuf.** { *; }
-dontwarn com.google.protobuf.**

# --- Room: generated *_Impl classes are instantiated reflectively -------------
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# --- SQLCipher / net.zetetic: JNI + reflective SQLite factory loading ---------
-keep class net.sqlcipher.** { *; }
-keep class net.zetetic.** { *; }
-dontwarn net.sqlcipher.**
-dontwarn net.zetetic.**

# --- EncryptedSharedPreferences (androidx.security) uses Tink templates -------
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# --- JNI: keep classes declaring native methods, and their method names -------
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# --- Components named from AndroidManifest.xml (belt and braces) --------------
-keep class org.attendanceai.ui.AttendanceActivity { *; }
-keep class org.attendanceai.presentation.lockscreen.LockScreenActivity { *; }
-keep class org.attendanceai.presentation.lockscreen.LockScreenViewModel { *; }

# --- Keep annotations/signatures (Room, Compose and kotlinx rely on them) -----
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

