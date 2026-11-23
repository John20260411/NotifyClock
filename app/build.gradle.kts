plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// C 盘空间紧张：设置 NOTIALARM_BUILD_DIR 后把编译产物放到 D 盘。
System.getenv("NOTIALARM_BUILD_DIR")?.takeIf { it.isNotBlank() }?.let {
    layout.buildDirectory.set(file(it))
}

android {
    namespace = "cn.notialarm"
    compileSdk = 35

    defaultConfig {
        applicationId = "cn.notialarm"
        minSdk = 26
        targetSdk = 34
        versionCode = 15
        versionName = "0.3.9"
    }

    signingConfigs {
        if (!System.getenv("RELEASE_STORE_FILE").isNullOrBlank()) {
            create("release") {
                storeFile = file(System.getenv("RELEASE_STORE_FILE"))
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (!System.getenv("RELEASE_STORE_FILE").isNullOrBlank()) {
                signingConfigs.getByName("release")
            } else {
                null
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.12.0")
    testImplementation("junit:junit:4.13.2")
}
