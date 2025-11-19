# Placeholder for project-specific keep rules.

# Telemetry payload is instantiated from JNI via FindClass and must keep its
# canonical name and members when R8 is enabled.
-keep class com.kotopogoda.uploader.feature.viewer.enhance.NativeRunTelemetry { *; }
