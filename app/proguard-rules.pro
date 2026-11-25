-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
# Métodos llamados desde Rust por JNI
-keep class app.tinyl.engine.NativeEngine { *; }
