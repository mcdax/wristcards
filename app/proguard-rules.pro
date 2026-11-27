# Anki-Kern (rsdroid): JNI ruft Klassen und Methoden über ihren Namen auf.
-keep class net.ankiweb.rsdroid.** { *; }
# Protobuf-Lite liest die Felder der erzeugten Nachrichten per Reflexion.
-keep class * extends com.google.protobuf.GeneratedMessageLite { *; }
-keep class anki.** { *; }
# AndroidSVG nutzt optional Klassen, die es auf Android nicht gibt.
-dontwarn com.caverock.androidsvg.**
# Der Kern bringt slf4j ohne Implementierung mit; die fehlt auf Android bewusst.
-dontwarn org.slf4j.impl.**
