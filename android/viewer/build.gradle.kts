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
val tdtKey = localProps.getProperty("TRACKLINK_TDT_KEY") ?: ""
val defaultServer = localProps.getProperty("TRACKLINK_DEFAULT_SERVER") ?: ""
val updateBase = localProps.getProperty("TRACKLINK_UPDATE_BASE") ?: ""

val appVersionName = "1.7.10"
val appVersionCode = 20
android {
    namespace = "com.kezez.tracklink.viewer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kezez.tracklink.viewer"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        manifestPlaceholders["AMAP_KEY"] = amapKey
        buildConfigField("String", "TDT_KEY", "\"$tdtKey\"")
        buildConfigField("String", "DEFAULT_SERVER", "\"$defaultServer\"")
        buildConfigField("String", "UPDATE_BASE", "\"$updateBase\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // OSM 地图（免 Key）
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // 高德 3D 地图 SDK（设置里选「高德」时启用；Key 在 AndroidManifest meta-data）
    implementation("com.amap.api:3dmap:10.+")
}

extensions.configure<org.gradle.api.plugins.BasePluginExtension>("base") {
    archivesName.set("TrackLink-Viewer-v$appVersionName")
}
