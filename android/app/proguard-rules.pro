# UniFFI: keep the generated bindings and the JNA types they use.
-keep class uniffi.stream_ffi.** { *; }
-keepclassmembers class uniffi.stream_ffi.** { *; }
-keep,allowobfuscation interface uniffi.stream_ffi.** { *; }

# JNA
-dontwarn java.awt.**
-keep class com.sun.jna.** { *; }
-keepclassmembers class * extends com.sun.jna.Structure { *; }
