package com.nexus.agent.game

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * SFT 数据集导出器。
 *
 * 把「采集 + 标注」的数据导出为 Qwen2-VL SFT 格式：
 * - dataset.jsonl（每行一条 {image, conversations}）
 * - images/ 子目录（对应截图）
 * 打包成 zip 后通过系统分享面板分享。
 */
object DatasetExporter {

    private const val TAG = "DatasetExporter"

    private val moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
    private val analysisAdapter = moshi.adapter(ScreenAnalysis::class.java)

    /**
     * 导出数据集，返回打包好的 zip 文件（位于 cacheDir，便于 FileProvider 分享）。
     * 无已标注数据时返回 null。
     */
    fun export(context: Context): File? {
        val root = context.getExternalFilesDir("dataset") ?: return null
        val imagesDir = File(root, "images")
        val labelsDir = File(root, "labels")
        if (!labelsDir.exists()) return null

        val labelFiles = labelsDir.listFiles { f -> f.name.endsWith(".json") } ?: return null
        if (labelFiles.isEmpty()) {
            Log.w(TAG, "无已标注数据，无法导出")
            return null
        }

        val exportDir = File(root, "export").apply { mkdirs() }
        val exportImagesDir = File(exportDir, "images").apply { mkdirs() }

        val lines = mutableListOf<String>()
        var count = 0

        labelFiles.forEach { labelFile ->
            val base = labelFile.nameWithoutExtension
            val imgFile = File(imagesDir, "$base.jpg")
            if (!imgFile.exists()) return@forEach

            val analysis = analysisAdapter.fromJson(labelFile.readText()) ?: return@forEach

            // 复制图片到 export/images/
            imgFile.copyTo(File(exportImagesDir, "$base.jpg"), overwrite = true)

            // 生成 assistant 文本
            val buttonStr = analysis.buttons.joinToString("、") {
                "${it.text}(${"%.2f".format(it.x)}, ${"%.2f".format(it.y)})"
            }
            val assistant = if (analysis.buttons.isEmpty()) {
                "界面类型：${analysis.screenType}。"
            } else {
                "界面类型：${analysis.screenType}。按钮：$buttonStr。"
            }

            val record = JSONObject().apply {
                put("image", "images/$base.jpg")
                put("conversations", JSONArray().apply {
                    put(JSONObject().apply {
                        put("from", "user")
                        put("value", "<image>\n请描述这张游戏截图中的界面类型和所有按钮。")
                    })
                    put(JSONObject().apply {
                        put("from", "assistant")
                        put("value", assistant)
                    })
                })
            }
            lines.add(record.toString())
            count++
        }

        if (lines.isEmpty()) {
            Log.w(TAG, "无有效已标注数据")
            return null
        }

        // 写 dataset.jsonl
        val jsonlFile = File(exportDir, "dataset.jsonl")
        jsonlFile.writeText(lines.joinToString("\n"))

        // 打包 zip 到 cacheDir（复用 FileProvider 的 cache-path 配置）
        val zipFile = File(context.cacheDir, "dataset_export_${System.currentTimeMillis()}.zip")
        ZipOutputStream(FileOutputStream(zipFile)).use { zos ->
            zos.putNextEntry(ZipEntry("dataset.jsonl"))
            zos.write(jsonlFile.readBytes())
            zos.closeEntry()
            exportImagesDir.listFiles()?.forEach { img ->
                zos.putNextEntry(ZipEntry("images/${img.name}"))
                zos.write(img.readBytes())
                zos.closeEntry()
            }
        }

        Log.i(TAG, "导出完成：$count 条数据 → ${zipFile.name}")
        return zipFile
    }

    /**
     * 通过系统分享面板分享 zip。
     */
    fun share(context: Context, zipFile: File) {
        val authority = "${context.packageName}.fileprovider"
        val uri = FileProvider.getUriForFile(context, authority, zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享数据集"))
    }
}
