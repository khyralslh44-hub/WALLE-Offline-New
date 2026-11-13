# JNI-driven wrappers are discovered by the native sherpa-onnx runtime.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# LiteRT-LM JNI bridge resolves runtime classes and methods reflectively.
-keep class com.google.ai.edge.litertlm.** { *; }

# Preserve model/runtime metadata used by Android task factories and native bindings.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod

# EVE calls this implementation through its local-language interface and runtime selection.
# Keep this adapter in the minified release so the LiteRT-LM backend remains available.
-keep class com.eve.robot.EveLiteRtLanguageModel { *; }
