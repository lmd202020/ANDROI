# MINHDUC ELITE - R8 / PROGUARD
# Keep runtime-visible metadata used by reflection and annotations.
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# Keep line information for useful crash reports while allowing class/member obfuscation.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# Remove verbose/debug Android logging from optimized release builds.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}

# Avoid broad -keep rules: they can disable useful shrinking and obfuscation.
# Add narrowly scoped keep rules only if a library uses reflection or JNI.

# Shizuku provider is instantiated by Android from the manifest.
-keep class rikka.shizuku.** { *; }

# Shizuku loads this class by its runtime class name in a privileged process.
-keep class com.minhduc.elite.PrivilegedPerformanceService { *; }
-keep interface com.minhduc.elite.IPerformanceService { *; }
-keep class com.minhduc.elite.IPerformanceService$Stub { *; }
