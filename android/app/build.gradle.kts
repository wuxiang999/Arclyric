plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.vela.lyricplayer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vela.lyricplayer"
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "1.0.3"
    }

    signingConfigs {
        create("arclyric") {
            storeFile = rootProject.file("arclyric.keystore")
            storePassword = "arclyric"
            keyAlias = "arclyric"
            keyPassword = "arclyric"
        }
        // 与 aiot 内置调试证书（CN=localhost）一致，用于和内置证书签名的调试 RPK 联调
        create("builtin") {
            storeFile = rootProject.file("builtin.keystore")
            storePassword = "arclyric"
            keyAlias = "builtin"
            keyPassword = "arclyric"
        }
    }

    buildTypes {
        debug {
            signingConfig = if (project.hasProperty("builtinSign")) {
                signingConfigs.getByName("builtin")
            } else {
                signingConfigs.getByName("arclyric")
            }
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("arclyric")
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
