plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.chickenbuter.spoilerguard"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.chickenbuter.spoilerguard"
        minSdk = 31          // Android 12+
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        // Личное приложение: release подписываем debug-ключом, чтобы APK сразу ставился.
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Никаких зависимостей кроме Kotlin stdlib: только Android framework.
