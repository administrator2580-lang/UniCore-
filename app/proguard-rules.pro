# Keep Kotlin & Firestore annotations and model reflection safe in release builds
-keepattributes Signature
-keepattributes *Annotation*
-keepclassmembers class * {
    @com.google.firebase.firestore.DocumentId *;
    @com.google.firebase.firestore.ServerTimestamp *;
    @com.google.firebase.firestore.PropertyName *;
}
# Strip verbose debug logs in release R8 builds
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
    public static int i(...);
}
