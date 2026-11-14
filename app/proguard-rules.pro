# Yumenode AI · ProGuard rules
# Mantener clases nativas JNI intactas
-keepclasseswithmembernames class * {
    native <methods>;
}

# LiteRT-LM
-keep class com.google.ai.edge.litertlm.** { *; }
-dontwarn com.google.ai.edge.litertlm.**

# llama.cpp JNI
-keep class net.ladenthin.llama.** { *; }
-dontwarn net.ladenthin.llama.**

# ONNX Runtime
-keep class ai.onnxruntime.** { *; }
-dontwarn ai.onnxruntime.**

# PDFBox
-keep class org.apache.pdfbox.** { *; }
-dontwarn org.apache.pdfbox.**

# Markwon
-keep class io.noties.markwon.** { *; }
-dontwarn io.noties.markwon.**

# PdfBox JPX / JP2 decoder (auto-generated missing class)
-dontwarn com.gemalto.jp2.JP2Decoder

# Establecer recursos (no eliminar strings / layouts / drawables)
-keepclassmembers class **.R$* {
    public static <fields>;
}

# Establecer nombres de clases que usan reflexión (SessionManager, ChatManager, etc.)
-keep class com.yumenode.ai.SessionManager { *; }
-keep class com.yumenode.ai.ChatManager { *; }
-keep class com.yumenode.ai.ChatParams { *; }
-keep class com.yumenode.ai.ChatParams { *; }
-keep class com.yumenode.ai.AttachmentHandler { *; }

# Serializable / Parcelable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Enums
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# Anotaciones
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keepattributes Signature
-keepattributes Exceptions
-keepattributes InnerClasses