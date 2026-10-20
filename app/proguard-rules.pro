# JNI entry points are looked up by name from native code.
-keep class app.cactusai.core.engine.NativeLib { *; }
-keepclasseswithmembernames class * { native <methods>; }
