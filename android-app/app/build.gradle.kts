/*
 * 校园跑——重邮智慧体育打卡工具（单机版）
 * 作者：蓝色大肥鱼🐟
 */
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 签名配置从 keystore.properties 读 —— 那个文件在 .gitignore 里，不进仓库
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        FileInputStream(keystorePropsFile).use { load(it) }
    }
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
        versionCode = 11102
        versionName = "1.11.2"
    }

    signingConfigs {
        // 没有 keystore.properties 就不注册，下面 release 也不会分到它
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 有 keystore.properties 就用正式 key 签；没有就**不签**
            // （产出的是 app-release-unsigned.apk，装不上，不会有人误当官方包）
            //
            // 绝不回落到 debug key —— 那个 keystore 全网通用
            // （~/.android/debug.keystore，密码 android），谁都能编一个
            // 签名相同、能原地覆盖安装的包，签名校验等于没做。
            signingConfig = signingConfigs.findByName("release")
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



