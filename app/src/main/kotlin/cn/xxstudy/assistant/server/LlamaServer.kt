package cn.xxstudy.assistant.server

import cn.xxstudy.assistant.repository.LlamaRepository
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.cors.routing.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.launch
import org.json.JSONObject

object LlamaServer {
    private var server: ApplicationEngine? = null
    
    // 启动本地服务端
    fun start(repository: LlamaRepository, port: Int = 8989) {
        if (server != null) return

        server = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            // 允许跨域请求
            install(CORS) {
                anyHost()
                allowHeader(HttpHeaders.ContentType)
                allowMethod(HttpMethod.Options)
                allowMethod(HttpMethod.Post)
                allowMethod(HttpMethod.Get)
            }
            
            routing {
                // 健康检查接口
                get("/ping") {
                    call.respondText("pong", ContentType.Text.Plain)
                }

                // 核心对话接口，模拟类似 OpenAI 的简单格式
                post("/v1/chat/completions") {
                    try {
                        val body = call.receiveText()
                        val json = try { JSONObject(body) } catch (e: Exception) { JSONObject() }
                        val prompt = json.optString("prompt", "你好")
                        val isStream = json.optBoolean("stream", false)
                        
                        if (isStream) {
                            // 开启流式通道
                            val channel = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
                            
                            // 启动后台协程生成 token
                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                val rawResult = repository.generateText(prompt) { token ->
                                    if (token.isNotEmpty()) {
                                        channel.trySend(token)
                                    }
                                }
                                // 推理完全结束后，把性能指标也送进去
                                val metrics = rawResult.split("<|metrics|>").getOrNull(1)
                                if (metrics != null) {
                                    channel.trySend("___METRICS___$metrics")
                                }
                                channel.close()
                            }
                            
                            // 以 Server-Sent Events (SSE) 返回
                            call.respondBytesWriter(ContentType.Text.EventStream) {
                                for (token in channel) {
                                    val isMetrics = token.startsWith("___METRICS___")
                                    val responseJson = JSONObject().apply {
                                        put("id", "chatcmpl-local")
                                        put("object", "chat.completion.chunk")
                                        put("model", "qwen2.5-0.5b-instruct-gguf")
                                        if (isMetrics) {
                                            val metricsRaw = token.removePrefix("___METRICS___")
                                            val metricsJson = try { JSONObject(metricsRaw) } catch(e: Exception) { metricsRaw }
                                            put("metrics", metricsJson)
                                        } else {
                                            val delta = JSONObject().apply { put("content", token) }
                                            put("choices", org.json.JSONArray().put(JSONObject().apply { put("delta", delta) }))
                                        }
                                    }
                                    writeStringUtf8("data: ${responseJson}\n\n")
                                    flush()
                                }
                                writeStringUtf8("data: [DONE]\n\n")
                                flush()
                            }
                        } else {
                            // 非流式阻塞等待
                            val rawResult = repository.generateText(prompt) {}
                            
                            val parts = rawResult.split("<|metrics|>")
                            val cleanText = parts.firstOrNull() ?: ""
                            val metricsText = parts.getOrNull(1)

                            // 构造 JSON 返回体
                            val responseJson = JSONObject().apply {
                                put("id", "chatcmpl-local")
                                put("object", "chat.completion")
                                put("model", "qwen2.5-0.5b-instruct-gguf")
                                if (metricsText != null) {
                                    val metricsJson = try { JSONObject(metricsText) } catch(e: Exception) { metricsText }
                                    put("metrics", metricsJson)
                                }
                                val message = JSONObject().apply {
                                    put("role", "assistant")
                                    put("content", cleanText)
                                }
                                put("choices", org.json.JSONArray().put(JSONObject().apply { put("message", message) }))
                            }
                            call.respondText(responseJson.toString(), ContentType.Application.Json)
                        }
                    } catch (e: Exception) {
                        e.printStackTrace()
                        call.respondText("{\"error\": \"${e.message}\"}", ContentType.Application.Json, HttpStatusCode.InternalServerError)
                    }
                }
            }
        }
        server?.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }
}
