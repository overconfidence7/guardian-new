# kotlinx.serialization: keep generated serializers for the config/event models.
-keepattributes *Annotation*, InnerClasses
-keepclassmembers class app.guardianmode.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class app.guardianmode.**$$serializer { *; }
