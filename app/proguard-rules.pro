# JNI callbacks resolve these upstream classes/methods by their original names.
# Preserve the complete media bindings, including video APIs.
-keep class org.videolan.libvlc.** { *; }
-keep class com.arthenica.ffmpegkit.** { *; }
-keep class me.zhanghai.android.libarchive.** { *; }

# The offline WebView calls the bridge through its annotated methods.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod
