plugins {
    id("com.android.application")
}

android {
    namespace = "com.ino.speedtracer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ino.speedtracer"
        minSdk = 26
        targetSdk = 36
        // Play に出すたびに versionCode を1つ上げる(同じ番号は二度と使えない)
        versionCode = 1
        versionName = "1.0.0"
    }

    // アップロード鍵(Play アプリ署名の「アップロード鍵」)。
    // 公開リポジトリに置いているが、Play Console へのログインなしでは更新を出せない。
    // 漏れて困る場合は Play Console の「アップロード鍵のリセット」で差し替えられる。
    signingConfigs {
        create("upload") {
            storeFile = file("upload.keystore")
            storePassword = "speedtracer"
            keyAlias = "upload"
            keyPassword = "speedtracer"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("upload")
        }
        release {
            signingConfig = signingConfigs.getByName("upload")
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
