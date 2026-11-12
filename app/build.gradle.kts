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
    // 本机 C 盘 SDK 里的 NDK 被清理工具删残过（只剩 toolchains），
    // 允许用 luna.ndkPath 指向一份完整的 NDK 副本，不影响其他机器。
    providers.gradleProperty("luna.ndkPath").orNull
        ?.takeIf { it.isNotBlank() }
        ?.let { ndkPath = it }

    defaultConfig {
        applicationId = "com.nexus.agent"
        minSdk = 24
        targetSdk = 36
        versionCode = 120
        versionName = "0.9.22"

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
        /**
         * java.time 脱糖（0.9.20 批次 D）。
         *
         * 作息引擎要判「现在几点」（[com.nexus.agent.ui.room.TimeRoutineManager]），
         * 而 `java.time.LocalTime` 是 API 26 才进系统的 —— minSdk 是 24，
         * 不开脱糖的话在 Android 7 上 `LocalTime.now()` 会直接
         * `NoClassDefFoundError`（不是返回 0 点，是崩）。
         *
         * 替代方案是退回 `java.util.Calendar`（FlexiMem 当年就是这么绕的），
         * 但那样「23:00 跨零点」这类判断要手写月份/闰年边界，容易写错。
         * 脱糖只多几百 KB，换一套能单测的纯函数 API，值得。
         */
        isCoreLibraryDesugaringEnabled = true
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
    // java.time 脱糖运行时（见 compileOptions 里的说明）
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.4")

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
    // 0.9.17 赛博房间：灯光/距离/姿态全靠 animateColorAsState + animateFloatAsState
    // 做过渡。这个模块由 BOM 定版本，不写死 —— 否则和 compose-bom 打架。
    implementation("androidx.compose.animation:animation")

    // 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // 网络请求（LLM API 调用）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.moshi:moshi:1.15.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")

    // 基础依赖
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")

    // WorkManager（0.9.2 批次二）：主动问候心跳。
    // 用 2.9+ 的合并版 —— CoroutineWorker 已经自带，不需要再引 work-runtime-ktx。
    implementation("androidx.work:work-runtime:2.10.0")

    // Room：人格档案 / 对话历史 / 长期记忆
    // 之前所有持久化都是 SharedPreferences（14 个命名空间），存不下结构化历史，
    // 也做不了「最近 N 轮」「按权重取记忆」这类查询。
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // 纯逻辑单元测试（定时任务的时刻推算、自然语言解析）
    testImplementation("junit:junit:4.13.2")
}
