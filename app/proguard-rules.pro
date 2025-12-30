# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# --- Kotlin Serialization (防止 JSON 解析失败) ---
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.SerializationKt
-keep class kotlinx.serialization.** { *; }
-keepclassmembers class kotlinx.serialization.** { *; }

# --- Ktor (防止网络请求库崩溃) ---
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# --- 你的数据模型 (防止数据类被改名导致无法解析) ---
-keep class com.antgskds.calendarassistant.model.** { *; }

# --- Jetpack Compose ---
-keep class androidx.compose.** { *; }