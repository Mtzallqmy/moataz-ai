# Aegis application keep rules.
# Native JNI entry points use name-based lookup, so these bridge class/method names
# must remain stable if release minification is enabled in a future verified build.
-keep class com.mtzallqmy.aiagent.native_runtime.RustRuntimeNative { *; }
-keep class com.mtzallqmy.aiagent.local_llm.internal.LlamaCppJniBridge { *; }

# Keep native methods on any additional bridge that may be added by tool packs.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
