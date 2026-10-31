// Nexus Agent 根构建文件
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    // KSP 版本必须与 Kotlin 版本严格对应（2.0.21 → 2.0.21-1.0.28），
    // 错一位就报 "This version of KSP is not compatible with Kotlin ..."
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
