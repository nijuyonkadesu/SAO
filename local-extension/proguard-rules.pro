# AndroidX and ML Kit supply their consumer rules.
# ML Kit discovers these classes by manifest metadata and invokes their no-arg constructors.
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    public <init>();
}
