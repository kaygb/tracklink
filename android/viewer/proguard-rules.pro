# 保留 osmdroid 内部通过反射/序列化的类型
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# 高德 SDK：内部大量反射，混淆会导致切换高德底图时崩溃
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
