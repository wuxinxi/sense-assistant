package cn.xxstudy.assistant.intent

import org.json.JSONArray
import org.json.JSONObject

/**
 * 通用意图解析器：将大模型输出的原始文本安全提取解析为结构化 [ActionRequest] 列表
 */
object ActionParser {

    /**
     * 将文本解析为 ActionRequest 列表，若非有效指令 JSON 则返回 null
     */
    fun parse(text: String): List<ActionRequest>? {
        if (text.isBlank()) return null

        var raw = text.trim()
        if (raw.contains("```json")) {
            raw = raw.substringAfter("```json").substringBefore("```").trim()
        } else if (raw.contains("```")) {
            raw = raw.substringAfter("```").substringBefore("```").trim()
        }

        if (!raw.startsWith("[") && !raw.startsWith("{")) return null

        return try {
            val jsonArray = if (raw.startsWith("[")) {
                JSONArray(raw)
            } else {
                JSONArray().put(JSONObject(raw))
            }

            if (jsonArray.length() == 0) return null

            val list = mutableListOf<ActionRequest>()
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.optJSONObject(i) ?: continue
                val action = obj.optString("action").takeIf { it.isNotBlank() } ?: continue

                val map = mutableMapOf<String, Any?>()
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.opt(k)
                    if (v != null && v != JSONObject.NULL) {
                        map[k] = v
                        // 自动建立 snake_case 与 camelCase 的双向映射，增加容错性
                        if (k.contains("_")) {
                            val camel = snakeToCamel(k)
                            map.putIfAbsent(camel, v)
                        } else {
                            val snake = camelToSnake(k)
                            map.putIfAbsent(snake, v)
                        }
                    }
                }

                list.add(
                    ActionRequest(
                        action = action,
                        params = map,
                        rawJson = obj.toString(2)
                    )
                )
            }
            if (list.isNotEmpty()) list else null
        } catch (e: Exception) {
            null
        }
    }

    private fun snakeToCamel(str: String): String {
        return str.split("_").mapIndexed { index, s ->
            if (index == 0) s else s.replaceFirstChar { it.uppercase() }
        }.joinToString("")
    }

    private fun camelToSnake(str: String): String {
        return str.replace(Regex("([a-z])([A-Z]+)"), "$1_$2").lowercase()
    }
}
