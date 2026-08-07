# Consumer rules — picked up automatically by AGP/R8 from this artifact.
#
# protobuf-javalite parses messages through schema strings that reference
# generated message fields reflectively; R8 must not strip or rename them.
# Without this rule, minified apps fail at runtime with errors like
# "CANCELLED: Failed to read message" when decoding responses.
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite {
    <fields>;
}
