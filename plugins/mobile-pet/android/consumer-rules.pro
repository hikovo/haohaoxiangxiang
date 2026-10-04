# Public consumer ProGuard rules for the Android overlay plugin.
# Tauri resolves the plugin and invocation argument fields through reflection.
# WorkManager also recreates reminder workers by their persisted class names.
-keep class com.hikovo.mobilepet.** { *; }
