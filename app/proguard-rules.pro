# Error code names are looked up by reflection for the dev tools.
-keepclassmembers class com.google.mlkit.genai.common.GenAiException$ErrorCode { public static final int *; }

# genai-prompt 1.0.0-beta4 ships incomplete consumer rules: R8 strips the ML Kit component registrars it
# finds by reflection, and Generation.getClient() crashes. Keep ML Kit whole.
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_** { *; }
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }

# LiteRT-LM calls back into its Kotlin classes from JNI (callbacks, BenchmarkInfo, SamplerConfig fields).
-keep class com.google.ai.edge.litertlm.** { *; }
