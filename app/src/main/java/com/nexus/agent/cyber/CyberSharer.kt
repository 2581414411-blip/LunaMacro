package com.nexus.agent.cyber

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 把电视上的内容分享到 QQ（0.9.26 批次 F）。
 *
 * ## 这是「无感」能达到的上限，我不再假装更多
 *
 * Android 没有让第三方 App 无界面写入另一个 App 的公开 API。`ACTION_SEND` +
 * `setPackage` 能做到的是**跳过应用选择器**，直接进 QQ 的分享页 —— 仅此而已。
 * 想让分享页「闪一下就自动关」，只能强杀 QQ，那会丢消息、会招风控，不做。
 *
 * 所以这里的定位很明确：**她挑好的东西，一键送到你面前**。你只需要点一下「发送」。
 * 相比「她自己偷偷刷抖音然后替你发出去」，这个交互其实更诚实 ——
 * 你知道她发了什么。
 *
 * ## 为什么先落盘再分享
 *
 * `EXTRA_STREAM` 要的是 `content://` URI，而通知/聊天里的图片在内存里。
 * `FileProvider` 只能分享**文件**，所以必须先写进 `cacheDir/cyber/`。
 * 缓存放 cache 而不是 files：系统清理时自动带走，不会越攒越多。
 */
object CyberSharer {

    private const val TAG = "CyberShare"

    /** QQ 主包名；分身的分享入口也是同一个包，user 不同 */
    private const val QQ_PACKAGE = "com.tencent.mobileqq"

    /** 分享出去时附在配文后面的来源，让人知道这是哪来的 */
    private const val TAIL = "\n\n—— 宏月在赛博房间看到的"

    /**
     * 分享一条内容。
     *
     * @param bitmap 有图时传缩略图；纯文字内容传 null，走纯文本分享
     * @return 是否成功拉起（不代表用户点了发送）
     */
    suspend fun share(
        context: Context,
        item: CyberItem,
        bitmap: Bitmap?,
        comment: String?
    ): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val text = buildString {
                if (!comment.isNullOrBlank()) append(comment.trim()).append("\n")
                append(item.title)
                item.pageUrl?.let { append("\n").append(it) }
                append(TAIL)
            }

            val intent = Intent(Intent.ACTION_SEND).apply {
                // NEW_TASK：从后台（服务/协程）拉起 Activity 必须加，否则会抛
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(QQ_PACKAGE)
            }

            if (bitmap != null) {
                val file = writeCache(context, bitmap, item.id)
                if (file != null) {
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    intent.type = "image/*"
                    intent.putExtra(Intent.EXTRA_STREAM, uri)
                    // 不加这句，QQ 读不到我们的 private 缓存文件，分享页会是空白图
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                } else {
                    intent.type = "text/plain"
                }
            } else {
                intent.type = "text/plain"
            }
            intent.putExtra(Intent.EXTRA_TEXT, text)

            context.startActivity(intent)
            Log.i(TAG, "已拉起 QQ 分享：${item.title.take(24)}（有图=${bitmap != null}）")
            true
        }.getOrElse {
            Log.w(TAG, "分享失败：${it.javaClass.simpleName} ${it.message}")
            false
        }
    }

    /** 只分享链接（多用在热搜这类没有图的条目） */
    suspend fun shareLink(context: Context, item: CyberItem, comment: String?): Boolean =
        share(context, item, null, comment)

    /**
     * 写进 cacheDir/cyber/。
     *
     * 文件名用 item.id 的 hashCode：同一条内容反复分享会覆盖同一个文件，
     * 不会在缓存里堆出十几份一模一样的图。
     */
    private fun writeCache(context: Context, bitmap: Bitmap, itemId: String): File? = runCatching {
        val dir = File(context.cacheDir, "cyber").apply { if (!exists()) mkdirs() }
        val file = File(dir, "share_${itemId.hashCode()}.jpg")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
        }
        file
    }.onFailure { Log.w(TAG, "写缓存失败：${it.message}") }.getOrNull()

    /** 当前能不能分享（QQ 装没装）。装不上时 UI 应该禁用按钮而不是点了没反应 */
    fun canShare(context: Context): Boolean = runCatching {
        context.packageManager.getLaunchIntentForPackage(QQ_PACKAGE) != null ||
            context.packageManager.getPackageInfo(QQ_PACKAGE, 0) != null
    }.getOrDefault(false)

    /** 给「打开原文」用的 URI */
    fun uriOf(item: CyberItem): Uri? =
        item.pageUrl?.takeIf { it.startsWith("http") }?.let { Uri.parse(it) }
}
