# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

-dontwarn org.jboss.netty.**
-dontwarn org.bouncycastle.**
-dontwarn org.slf4j.**

#-keepclassmembers class org.jboss.netty.** { *;}

#Some important internal fields that where removed
-keep class org.jboss.netty.channel.DefaultChannelPipeline{volatile <fields>;}

#A Factory which has a static factory implementation selector which is pruned
-keep class org.jboss.netty.util.internal.QueueFactory{static <fields>;}

#Some fields whose names need to be maintained because they are accessed using inflection
-keepclassmembernames class org.jboss.netty.util.internal.**{*;}