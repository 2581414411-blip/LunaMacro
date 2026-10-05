package com.nexus.agent.game

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File

/**
 * SFT 数据集手动标注器（数据层）。
 *
 * 从 `getExternalFilesDir("dataset")/images/` 加载截图，筛选出没有对应 label 的，
 * 提供 label 的读写。标注 UI 见 [com.nexus.agent.ui.DatasetAnnotatorScreen]。
 */
object DatasetAnnotator {

    private const val TAG = "DatasetAnnotator"

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val analysisAdapter = moshi.adapter(ScreenAnalysis::class.java)

    private fun root(context: Context): File = context.getExternalFilesDir("dataset") ?: File(context.filesDir, "dataset")

    private fun imagesDir(context: Context): File = File(root(context), "images").apply { mkdirs() }

    private fun labelsDir(context: Context): File = File(root(context), "labels").apply { mkdirs() }

    /**
     * 列出所有未标注的截图文件名（images/ 下没有对应 labels/{name}.json 的）。
     */
    fun listUnannotated(context: Context): List<String> {
        val imgs = imagesDir(context)
        val labels = labelsDir(context)
        if (!imgs.exists()) return emptyList()
        val files = imgs.listFiles { f -> f.name.endsWith(".jpg") } ?: return emptyList()
        return files
            .filter { img -> !File(labels, "${img.nameWithoutExtension}.json").exists() }
            .map { it.name }
            .sorted()
    }

    /**
     * 加载截图 Bitmap。
     */
    fun loadImage(context: Context, filename: String): Bitmap? {
        val f = File(imagesDir(context), filename)
        return if (f.exists()) {
            try {
                BitmapFactory.decodeFile(f.absolutePath)
            } catch (e: Throwable) {
                Log.e(TAG, "加载图片失败 $filename", e)
                null
            }
        } else null
    }

    /**
     * 加载已有标注（无标注返回 null）。
     */
    fun loadAnalysis(context: Context, filename: String): ScreenAnalysis? {
        val base = filename.substringBeforeLast('.')
        val f = File(labelsDir(context), "$base.json")
        if (!f.exists()) return null
        return try {
            analysisAdapter.fromJson(f.readText())
        } catch (e: Throwable) {
            Log.e(TAG, "解析标注失败 $filename", e)
            null
        }
    }

    /**
     * 保存标注。
     */
    fun saveAnalysis(context: Context, filename: String, analysis: ScreenAnalysis) {
        val base = filename.substringBeforeLast('.')
        val f = File(labelsDir(context), "$base.json")
        f.writeText(analysisAdapter.toJson(analysis))
        Log.i(TAG, "已保存标注 $base")
    }
}
