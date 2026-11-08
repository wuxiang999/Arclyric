import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名信息不进仓库：优先取 -P 参数，其次取 android/keystore.properties，
// 都没有就构建未签名包。参考 keystore.properties.example。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

fun signingValue(key: String, fallback: String = ""): String =
    (project.findProperty(key) as String?) ?: keystoreProps.getProperty(key) ?: fallback

val arclyricStore = rootProject.file(signingValue("storeFile", "arclyric.keystore"))
val hasArclyricSigning = arclyricStore.exists() && signingValue("storePassword").isNotEmpty()
val builtinStore = rootProject.file("builtin.keystore")
val hasBuiltinSigning = builtinStore.exists() && signingValue("storePassword").isNotEmpty()

android {
    namespace = "com.vela.lyricplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vela.lyricplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.0.1"
    }

    signingConfigs {
        if (hasArclyricSigning) {
            create("arclyric") {
                storeFile = arclyricStore
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("keyAlias", "arclyric")
                keyPassword = signingValue("keyPassword", signingValue("storePassword"))
            }
        }
        // 和 aiot 内置调试证书（CN=localhost）一致，用于与内置证书签名的调试 RPK 联调。
        if (hasBuiltinSigning) {
            create("builtin") {
                storeFile = builtinStore
                storePassword = signingValue("storePassword")
                keyAlias = signingValue("builtinAlias", "builtin")
                keyPassword = signingValue("builtinPassword", signingValue("storePassword"))
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = if (project.hasProperty("builtinSign")) {
                signingConfigs.findByName("builtin") ?: signingConfigs.findByName("arclyric")
            } else {
                signingConfigs.findByName("arclyric")
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("arclyric")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))
}
