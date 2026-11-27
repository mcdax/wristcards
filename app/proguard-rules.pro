# Anki core (rsdroid): JNI calls classes and methods by name.
-keep class net.ankiweb.rsdroid.** { *; }
# Protobuf-Lite reads the fields of the generated messages via reflection.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class anki.** { *; }
# AndroidSVG optionally uses classes that don't exist on Android.
-dontwarn com.caverock.androidsvg.**
# The core ships slf4j without an implementation; it is deliberately missing on Android.
-dontwarn org.slf4j.impl.**
