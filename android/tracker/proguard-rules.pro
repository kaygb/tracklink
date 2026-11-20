# 高德定位 SDK：反射调用 keep 全量
-keep class com.amap.api.location.** { *; }
-keep class com.amap.api.fence.** { *; }
-keep class com.autonavi.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
-keep class com.loc.** { *; }
-keepclassmembers class ** { void onLocationChanged(...); }
-dontwarn org.jetbrains.annotations.**
