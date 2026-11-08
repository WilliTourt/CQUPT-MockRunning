/*
 * 校园跑——重邮智慧体育打卡工具（单机版）
 * 作者：蓝色大肥鱼🐟
 */
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.whale.campusrun"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.whale.campusrun"
        minSdk = 24
        targetSdk = 34
        // 版本号直接写在这里，不依赖任何分支。规则与清单见 AGENT.md §5.1 / §5.2
        // versionCode = MAJOR*10000 + MINOR*100 + PATCH
        versionCode = 11100
        versionName = "1.11.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 自用，直接用 debug 签名，免得配 keystore
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
        buildConfig = true      // 让 MainActivity 能读到 BuildConfig.VERSION_NAME
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")

    // TrackGen 是纯 JVM 逻辑，直接用单元测试验证配速标定与围栏判定
    testImplementation("junit:junit:4.13.2")
}



