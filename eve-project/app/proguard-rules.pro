# JNI-driven wrappers are discovered by the native sherpa-onnx runtime.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# LiteRT-LM JNI bridge resolves runtime classes and methods reflectively.
-keep class com.google.ai.edge.litertlm.** { *; }

# Preserve model/runtime metadata used by Android task factories and native bindings.
-keepattributes *Annotation*,InnerClasses,EnclosingMethod
