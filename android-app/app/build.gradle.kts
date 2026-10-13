/*
 * 鏍″洯璺?鈥斺€?閲嶉偖鏅烘収浣撹偛鎵撳崱宸ュ叿锛堝崟鏈虹増锛? * 浣滆€咃細钃濊壊澶ц偉楸?馃悷
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
        versionCode = 8
        versionName = "1.8"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 鑷敤锛岀洿鎺ョ敤 debug 绛惧悕锛屽厤寰楅厤 keystore
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
        buildConfig = true      // 璁?MainActivity 鑳借鍒?BuildConfig.VERSION_NAME
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")

    // TrackGen 鏄函 JVM 閫昏緫锛岀洿鎺ョ敤鍗曞厓娴嬭瘯楠岃瘉閰嶉€熸爣瀹氫笌鍥存爮鍒ゅ畾
    testImplementation("junit:junit:4.13.2")
}



