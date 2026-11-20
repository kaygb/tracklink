import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 密钥从 local.properties 注入（不入库），源码中不留真实值
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val amapKey = localProps.getProperty("TRACKLINK_AMAP_KEY") ?: ""
val uploadKey = localProps.getProperty("TRACKLINK_UPLOAD_KEY") ?: ""
val defaultServer = localProps.getProperty("TRACKLINK_DEFAULT_SERVER") ?: ""
val updateBase = localProps.getProperty("TRACKLINK_UPDATE_BASE") ?: ""

val appVersionName = "1.7.10"
val appVersionCode = 20
android {
    namespace = "com.kezez.tracklink.tracker"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kezez.tracklink.tracker"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        manifestPlaceholders["AMAP_KEY"] = amapKey
        buildConfigField("String", "UPLOAD_KEY", "\"$uploadKey\"")
        buildConfigField("String", "DEFAULT_SERVER", "\"$defaultServer\"")
        buildConfigField("String", "UPDATE_BASE", "\"$updateBase\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 用调试签名打包，方便直接安装体验（正式发布请替换为自己的 keystore）
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // 高德定位 SDK：无 GMS 设备的系统网络定位不可用，用它做 WiFi/基站混合定位兕底
    implementation("com.amap.api:location:6.+")
}

extensions.configure<org.gradle.api.plugins.BasePluginExtension>("base") {
    archivesName.set("TrackLink-Tracker-v$appVersionName")
}
