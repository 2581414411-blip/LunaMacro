package com.nexus.agent.system

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream

/**
 * Stellar 一键安装器。
 *
 * 把 Stellar APK 内嵌到 Nexus Agent 的 assets（`stellar.apk`），
 * 检测到未安装时，提取到 cacheDir 并跳转系统安装器——测试者无需
 * 自己去 GitHub 下载，只需点一次「安装」。
 */
object StellarInstaller {

    private const val TAG = "StellarInstaller"
    private const val ASSET_NAME = "stellar.apk"
    private const val APK_FILE_NAME = "stellar.apk"

    /** Stellar 包名 */
    const val STELLAR_PACKAGE = "roro.stellar.manager"

    /** 是否已安装 Stellar */
    fun isInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo(STELLAR_PACKAGE, 0)
            true
        } catch (e: Throwable) {
            false
        }
    }

    /** 是否有「安装未知应用」权限（Android 8+） */
    fun canInstall(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.packageManager.canRequestPackageInstalls()
        } else {
            true
        }
    }

    /** 跳转「安装未知应用」授权页 */
    fun openInstallPermissionSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Throwable) {
            Log.w(TAG, "openInstallPermissionSettings failed", e)
        }
    }

    /**
     * 提取内嵌 APK 并跳转系统安装器。
     *
     * @return true 成功跳转；false 提取或跳转失败
     */
    fun install(context: Context): Boolean {
        return try {
            val apkFile = File(context.cacheDir, APK_FILE_NAME)
            if (!apkFile.exists() || apkFile.length() == 0L) {
                context.assets.open(ASSET_NAME).use { input ->
                    FileOutputStream(apkFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Log.i(TAG, "提取 Stellar APK 到 ${apkFile.absolutePath}（${apkFile.length()} 字节）")
            }
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", apkFile)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (e: Throwable) {
            Log.e(TAG, "install failed", e)
            false
        }
    }
}
