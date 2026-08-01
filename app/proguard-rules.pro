-keep class com.retrsoft.bilisponsorskip.XposedInit { *; }
-keep class org.luckypray.dexkit.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}
