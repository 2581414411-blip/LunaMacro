package com.nexus.agent.copilot

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * 聊天里的图片：选图 → 缩放 → 落盘 → 读回来显示（0.9.6）。
 *
 * ## 为什么要自己存一份
 *
 * 用户从相册选来的 `content://` Uri 只有**临时读权限**，App 重启后大概率读不到；
 * 而且系统相册随时可能被清理。聊天记录是要长期留着的，所以选完就复制一份到
 * `filesDir/persona_media/`，聊天流里只记本地路径。
 *
 * ## 为什么要限制尺寸
 *
 * 视觉模型长边压到 640（[com.nexus.agent.game.VisionHub.MAX_EDGE]），
 * 原图动辄 4000×3000、一张十几 MB，直接解码就是几十 MB 的 ARGB 位图，
 * 连着发几张就可能被 lmkd 收走。这里统一 [MAX_STORE_EDGE]（1280）落盘，
 * 显示再按屏幕需要缩小解码。
 *
 * ## 为什么单独处理旋转
 *
 * 手机拍的照片大多是「横着存、EXIF 里写个 90 度」。`BitmapFactory` 不看 EXIF，
 * 存下来就会变成躺着的猫 —— 这是发图功能最常见的「看起来像坏了」。
 */
object ChatMedia {

    private const val TAG = "ChatMedia"

    /** 落盘时的长边上限：够视觉模型看，也不至于把内存撑爆 */
    const val MAX_STORE_EDGE = 1280

    /** 聊天里显示用的长边上限（再大就是浪费） */
    const val MAX_SHOW_EDGE = 720

    /** 最多留多少个文件；超了删最旧的。聊天流本身也只留最近几十条 */
    private const val MAX_FILES = 60

    private const val JPEG_QUALITY = 88

    /** 图片存放目录（`filesDir/persona_media`） */
    fun dir(context: Context): File =
        File(context.applicationContext.filesDir, "persona_media").apply {
            if (!exists()) runCatching { mkdirs() }
        }

    /**
     * 把位图存成聊天用的 JPEG。
     *
     * @return 绝对路径；失败返回 null（调用方应当提示，而不是发一条空消息）
     */
    fun save(context: Context, bitmap: Bitmap, prefix: String = "img"): String? {
        if (bitmap.isRecycled) return null
        val file = File(dir(context), "${prefix}_${System.currentTimeMillis()}.jpg")
        return runCatching {
            val scaled = scaleDown(bitmap, MAX_STORE_EDGE)
            FileOutputStream(file).use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            if (scaled !== bitmap) scaled.recycle()
            prune(context)
            file.absolutePath
        }.onFailure {
            Log.w(TAG, "存图失败：${it.javaClass.simpleName} ${it.message}")
            runCatching { file.delete() }
        }.getOrNull()
    }

    /**
     * 从相册/相机 Uri 解码成位图（按需采样 + 纠正 EXIF 旋转）。
     *
     * 采样在**解码前**做：先只读图片头拿宽高，再按 2 的幂降采样，
     * 这样 4000×3000 的原图不会被整张读进内存。
     */
    fun decodeUri(context: Context, uri: Uri, maxEdge: Int = MAX_STORE_EDGE): Bitmap? = runCatching {
        val cr = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            Log.w(TAG, "读不到图片尺寸：$uri")
            return null
        }
        val opts = BitmapFactory.Options().apply {
            inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val raw = cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: return null
        val degrees = readRotation(cr.openInputStream(uri))
        val fixed = applyRotation(raw, degrees)
        val out = scaleDown(fixed, maxEdge)
        // 中间产物是我们的，用完就还；入参位图（save 那条路）绝不在这里回收
        if (out !== fixed) runCatching { fixed.recycle() }
        out
    }.onFailure { Log.w(TAG, "解码失败：${it.javaClass.simpleName} ${it.message}") }.getOrNull()

    /**
     * 视频取一帧。
     *
     * 用户发的视频多半是几秒的片段；整段交给视觉模型是不可能的（模型只看图），
     * 所以取**首帧**当封面看。为什么是首帧而不是中间帧：首帧通常还没切镜头，
     * 最能代表「这段视频是什么」；中间帧偶尔会卡在过场黑屏上，模型只能说「一片黑」。
     */
    fun videoFrame(context: Context, uri: Uri, maxEdge: Int = MAX_STORE_EDGE): Bitmap? = runCatching {
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(context, uri)
            val frame = mmr.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                ?: mmr.getFrameAtTime(-1)
            if (frame == null) return null
            val out = scaleDown(frame, maxEdge)
            if (out !== frame) runCatching { frame.recycle() }
            out
        } finally {
            runCatching { mmr.release() }
        }
    }.onFailure { Log.w(TAG, "视频取帧失败：${it.javaClass.simpleName} ${it.message}") }.getOrNull()

    /** 读本地文件做缩略图；文件没了返回 null（旧记录里的图被清理掉时不该崩） */
    fun decodeFile(path: String?, maxEdge: Int = MAX_SHOW_EDGE): Bitmap? {
        if (path.isNullOrBlank()) return null
        val f = File(path)
        if (!f.exists()) return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            }
            val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
            val out = scaleDown(decoded, maxEdge)
            if (out !== decoded) runCatching { decoded.recycle() }
            out
        }.getOrNull()
    }

    /** 采样率：2 的幂，保证解码后的图不小于 [maxEdge]（缩太多会糊） */
    internal fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        while (longest / 2 >= maxEdge) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    /**
     * 等比缩到长边 [maxEdge]；已经够小就原样返回。
     *
     * **不回收入参**：调用方可能还要拿原图去推理（发图链路就是「先存一份、再喂给模型」）。
     * 中间产物的回收由调用方自己负责，谁 created 谁 recycle。
     */
    internal fun scaleDown(src: Bitmap, maxEdge: Int): Bitmap {
        val longest = maxOf(src.width, src.height)
        if (longest <= maxEdge || longest <= 0) return src
        val ratio = maxEdge.toFloat() / longest
        val w = (src.width * ratio).toInt().coerceAtLeast(1)
        val h = (src.height * ratio).toInt().coerceAtLeast(1)
        return Bitmap.createScaledBitmap(src, w, h, true)
    }

    private fun applyRotation(src: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return src
        val m = Matrix().apply { postRotate(degrees.toFloat()) }
        return runCatching {
            val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            if (out !== src) runCatching { src.recycle() }
            out
        }.getOrDefault(src)
    }

    private fun readRotation(input: java.io.InputStream?): Int = runCatching {
        if (input == null) return 0
        val exif = input.use { ExifInterface(it) }
        when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90
            ExifInterface.ORIENTATION_ROTATE_180 -> 180
            ExifInterface.ORIENTATION_ROTATE_270 -> 270
            else -> 0
        }
    }.getOrDefault(0)

    /** 只留最近 [MAX_FILES] 个文件，防止一年后占了几个 G */
    private fun prune(context: Context) {
        runCatching {
            val files = dir(context).listFiles()?.filter { it.isFile } ?: return
            if (files.size <= MAX_FILES) return
            files.sortedBy { it.lastModified() }
                .take(files.size - MAX_FILES)
                .forEach { it.delete() }
        }
    }
}
