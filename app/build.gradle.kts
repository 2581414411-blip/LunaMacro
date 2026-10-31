import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.nexus.agent"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.nexus.agent"
        minSdk = 24
        targetSdk = 36
        versionCode = 72
        versionName = "0.8.7"

        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++11"
            }
        }
    }

    signingConfigs {
        create("release") {
            val props = Properties().apply {
                val f = rootProject.file("keystore.properties")
                if (f.exists()) load(FileInputStream(f))
            }
            if (props.isNotEmpty()) {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    /**
     * 纯 JVM 单测里 android.jar 的方法都是抛 `Stub!` 的空实现，
     * 只要被测代码顺手写了句 Log.i 就会整组测试失败（0.7.11 踩过）。
     * 打开这个开关后未实现的 Android API 返回默认值，测试聚焦在真实逻辑上。
     */
    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        aidl = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.5"
        }
    }

    androidResources {
        noCompress += listOf("mnn", "weight", "mtok", "json")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

// Room 导出 schema：以后加字段/改表时靠它写 Migration，
// 没这个就无法安全升级（只能 destructive 掉用户数据）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Shizuku（系统级输入注入）
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // ONNX Runtime 由 sherpa-onnx 自带（libonnxruntime.so 在 jniLibs），无需 Maven 依赖

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.activity:activity-compose:1.9.0")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // 网络请求（LLM API 调用）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")

    // 基础依赖
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // Room：人格档案 / 对话历史 / 长期记忆
    // 之前所有持久化都是 SharedPreferences（14 个命名空间），存不下结构化历史，
    // 也做不了「最近 N 轮」「按权重取记忆」这类查询。
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 纯逻辑单元测试（定时任务的时刻推算、自然语言解析）
    testImplementation("junit:junit:4.13.2")
}
