# Release builds currently ship with isMinifyEnabled = false.
# If you enable R8 later, keep the Pleos SDK public API and its serialized data classes.
-keep class ai.pleos.playground.** { *; }
-dontwarn ai.pleos.playground.**
-dontwarn android.car.**
