plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.github.lmq00.swipeclean"
    // libxposed 的 service/interface 制品要求 compileSdk 36（targetSdk 仍为 35）。
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.lmq00.swipeclean"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.2"
    }

    signingConfigs {
        create("release") {
            // 签名密钥不入库：CI 从 GitHub Secrets 解码到 KEYSTORE_PATH 后注入。
            System.getenv("KEYSTORE_PATH")?.let { path ->
                val keyFile = file(path)
                if (keyFile.exists()) {
                    storeFile = keyFile
                    storePassword = System.getenv("KEYSTORE_PASSWORD")
                    keyAlias = System.getenv("KEY_ALIAS")
                    keyPassword = System.getenv("KEY_PASSWORD")
                }
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 有密钥就用固定签名（各次构建可互相覆盖安装）；没有则退回 debug 签名，
            // 保证克隆仓库的人无需私钥也能构建出可安装的 APK。
            val releaseSigning = signingConfigs.getByName("release")
            signingConfig = if (releaseSigning.storeFile != null) {
                releaseSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            // META-INF/xposed/* carries the libxposed entry point declaration.
            merges += "META-INF/xposed/*"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // Provided by LSPosed at runtime; never bundled into the APK.
    compileOnly("io.github.libxposed:api:101.0.1")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
}