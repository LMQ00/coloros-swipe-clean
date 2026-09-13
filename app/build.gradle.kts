plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.lmq00.swipeclean"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.lmq00.swipeclean"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file("keystore/swipeclean.jks")
            storePassword = "swipeclean"
            keyAlias = "swipeclean"
            keyPassword = "swipeclean"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 固定 keystore：CI 每次构建产出的 APK 签名一致，可直接覆盖安装。
            signingConfig = signingConfigs.getByName("release")
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
            // META-INF/xposed/* carries the libxposed entry point declaration.
            merges += "META-INF/xposed/*"
        }
    }
}

dependencies {
    // Provided by LSPosed at runtime; never bundled into the APK.
    compileOnly("io.github.libxposed:api:101.0.1")

    // 模块 App ↔ 框架的配置通道：提供 XposedProvider / XposedServiceHelper，
    // 其 manifest 会并入 XposedProvider（authority <applicationId>.XposedService）。
    implementation("io.github.libxposed:service:101.0.0")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}