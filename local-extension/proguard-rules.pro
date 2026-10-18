# AndroidX and ML Kit supply their consumer rules.
# ML Kit discovers these classes by manifest metadata and invokes their no-arg constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
-keep class one.njk.sao.extension.local.VectorMath { *; }
-keep class one.njk.sao.extension.local.ModelMemory { *; }
# ONNX Runtime's JNI looks up tensor types/constructors by name; its AAR rules do not retain them.
# Required by https://onnxruntime.ai/docs/build/android.html#note-proguard-rules-for-r8-minimization-android-app-builds-to-work
-keep class ai.onnxruntime.** { *; }
