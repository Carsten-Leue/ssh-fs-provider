# JSch – keep all algorithm/channel implementations loaded via reflection
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**

# Apache Commons Compress
-keep class org.apache.commons.compress.** { *; }
-dontwarn org.apache.commons.compress.**

# Tink (used internally by security-crypto EncryptedFile)
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**

# security-crypto
-keep class androidx.security.crypto.** { *; }
