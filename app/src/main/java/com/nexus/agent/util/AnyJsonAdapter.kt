package com.nexus.agent.util

import com.squareup.moshi.FromJson
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.ToJson

/**
 * 处理 `Map<String, Any>` 中 Any 值的序列化。
 *
 * Moshi 默认不支持 Any/Object 类型，这里覆盖 JSON 兼容的基本类型
 * （字符串 / 数字 / 布尔 / null / 数组 / 对象）。供 MacroStore、GameProfileStore
 * 等需要序列化 `Map<String, Any>` 的存储复用。
 */
class AnyJsonAdapter : JsonAdapter<Any?>() {

    @FromJson
    override fun fromJson(reader: JsonReader): Any? = readValue(reader)

    @ToJson
    override fun toJson(writer: JsonWriter, value: Any?) = writeValue(writer, value)

    private fun readValue(reader: JsonReader): Any? {
        return when (reader.peek()) {
            JsonReader.Token.STRING -> reader.nextString()
            JsonReader.Token.NUMBER -> {
                val d = reader.nextDouble()
                if (d == Math.floor(d) && !d.isInfinite() && d in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
                    d.toLong()
                } else {
                    d
                }
            }
            JsonReader.Token.BOOLEAN -> reader.nextBoolean()
            JsonReader.Token.NULL -> {
                reader.nextNull<Unit>()
                null
            }
            JsonReader.Token.BEGIN_ARRAY -> {
                val list = mutableListOf<Any?>()
                reader.beginArray()
                while (reader.hasNext()) list.add(readValue(reader))
                reader.endArray()
                list
            }
            JsonReader.Token.BEGIN_OBJECT -> {
                val map = linkedMapOf<String, Any?>()
                reader.beginObject()
                while (reader.hasNext()) map[reader.nextName()] = readValue(reader)
                reader.endObject()
                map
            }
            else -> null
        }
    }

    private fun writeValue(writer: JsonWriter, value: Any?) {
        when (value) {
            null -> writer.nullValue()
            is String -> writer.value(value)
            is Boolean -> writer.value(value)
            is Int -> writer.value(value)
            is Long -> writer.value(value)
            is Double -> writer.value(value)
            is Float -> writer.value(value.toDouble())
            is Map<*, *> -> {
                writer.beginObject()
                for ((k, v) in value) {
                    writer.name(k.toString())
                    writeValue(writer, v)
                }
                writer.endObject()
            }
            is List<*> -> {
                writer.beginArray()
                for (v in value) writeValue(writer, v)
                writer.endArray()
            }
            else -> writer.value(value.toString())
        }
    }
}
