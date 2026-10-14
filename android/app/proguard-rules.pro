# Room Browser — R8/ProGuard rules

# --- Kotlin / coroutines ---
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod
-dontwarn kotlinx.coroutines.**

# --- kotlinx.serialization ---
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** { kotlinx.serialization.KSerializer serializer(...); }
-keep,includedescriptorclasses class com.roombrowser.**$$serializer { *; }
-keepclassmembers class com.roombrowser.** { *** Companion; }
-keepclasseswithmembers class com.roombrowser.** { kotlinx.serialization.KSerializer serializer(...); }

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-dontwarn androidx.room.paging.**

# --- OkHttp / DoH ---
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# --- ZXing ---
-keep class com.google.zxing.** { *; }

# --- WebView JS interfaces (none registered, keep safe anyway) ---
-keepattributes JavascriptInterface
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- Biometric ---
-keep class androidx.biometric.** { *; }

# --- CameraX ---
-keep class androidx.camera.** { *; }

# On-device llama.cpp engine (JNI): keep the bridge class and its native methods.
-keepclasseswithmembernames class com.roombrowser.localai.engine.LlamaBridge { native <methods>; }
-keep class com.roombrowser.localai.engine.LlamaBridge { *; }
-keep interface com.roombrowser.localai.engine.LlamaEngineApi { *; }
-keep class com.roombrowser.localai.engine.LlamaEngine { *; }

# Embedded Node runtime (JNI): the native method is found by its mangled name,
# so the class and the method both have to survive shrinking.
-keepclasseswithmembernames class com.roombrowser.node.NodeBridge { native <methods>; }
-keep class com.roombrowser.node.NodeBridge { *; }

# --- Multi-chain wallet (:core:wallet) — web3j-crypto + jackson + tuweni ---
# First exercised by the R8 release build (CI release job). The crypto paths
# are invoked directly, but web3j/jackson reference optional deps (slf4j,
# rxjava, spring, error-prone/checker annotations) that are NOT on the
# Android classpath — R8 "missing class" errors are silenced per family, and
# the reflection-touched families (EIP-712 structured-data mappers, jackson
# databind serializers, web3j ABI value tuples) are kept whole.
-dontwarn org.web3j.**
-dontwarn org.slf4j.**
# tuweni-bytes' pom drags in org.connid:framework(-internal) (a provisioning
# framework nothing at runtime ever calls; R8 prunes it). Its script adapter
# references groovy.lang.GroovyShell/Script, which are NOT on the Android
# classpath — CI 22f601d quality job caught exactly this one missing class.
-dontwarn groovy.lang.**
-dontwarn org.identityconnectors.**
-dontwarn io.reactivex.**
-dontwarn com.fasterxml.jackson.**
-dontwarn org.apache.tuweni.**
-dontwarn org.bouncycastle.**
-dontwarn org.springframework.**
-dontwarn javax.annotation.**
-dontwarn javax.inject.**
-dontwarn com.google.errorprone.**
-dontwarn org.checkerframework.**
-dontwarn org.jetbrains.annotations.**
-keep class org.web3j.crypto.** { *; }
-keep class org.web3j.abi.datatypes.** { *; }
-keep class org.web3j.tuples.** { *; }
-keep class com.fasterxml.jackson.databind.** { *; }
