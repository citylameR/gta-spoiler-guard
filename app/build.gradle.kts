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
        versionCode = 2
        versionName = "0.2"
    }

    // Постоянный ключ, лежит в репозитории (личное приложение, репо приватный).
    // Благодаря ему каждая новая сборка из CI ставится поверх старой без удаления.
    signingConfigs {
        create("personal") {
            storeFile = file("personal.keystore")
            storePassword = "spoilerguard"
            keyAlias = "personal"
            keyPassword = "spoilerguard"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("personal")
        }
        debug {
            signingConfig = signingConfigs.getByName("personal")
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
