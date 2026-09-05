# 端侧大模型基座 App (Android Native) 架构与实施方案

## 1. 核心定位
本 App 定位为**“端侧 AI 基础设施”**，即运行在 Android 手机/平板上的无头（弱 UI）服务应用。它的核心任务是将设备芯片算力转化为标准的 OpenAPI 接口，供同设备的其他应用通过 IPC/HTTP 进行调用。

首期支持：**Qwen2.5-0.5B (GGUF 格式)**
未来扩展预留：端侧 ASR (如 Whisper.cpp) 与 端侧 TTS (如 Piper)。

---

## 2. 技术栈选型

既然退回原生 Android 开发，为了保证性能和现代化，我们采用以下技术栈：

* **开发语言**: Kotlin (主流、安全，协程非常适合处理流式生成)
* **UI 框架**: Jetpack Compose (轻量化构建控制台界面)
* **本地服务网关**: **Ktor Server (CIO 引擎)**。这是一个极致轻量级的纯 Kotlin HTTP 框架，可以轻松在 Android App 内部拉起一个占用极小内存的 Web Server。
* **大模型推理层**: **Llama.cpp + NDK/JNI**。直接使用 C++ 编译 llama.cpp，通过 JNI (Java Native Interface) 暴露 `generate_text` 等方法给 Kotlin 侧。

---

## 3. 全局架构设计

整体 App 分为四层，自下而上依次为：

### 层级一：C++ 推理层 (Native C/C++)
* **LLM 模块**: 集成 `llama.cpp`，负责读取 GGUF 模型，加载进内存，执行 Token 预测。暴露 JNI 接口如 `loadModel(path)` 和 `streamGenerate(prompt)`。
* **预留设计**: 定义统一的 C++ 抽象类基类，未来集成 `whisper.cpp` 时可平行扩展。

### 层级二：本地网关层 (Ktor Server)
* 在 Android 的后台线程（Coroutine IO Dispatcher）中启动 Ktor HTTP Server，监听 `127.0.0.1:8080`。
* **路由配置**: 
  * `POST /v1/chat/completions`: 接收标准 OpenAI 格式的 JSON，调用 C++ 推理层，并将结果以 `Server-Sent Events (SSE)` 流式返回。

### 层级三：资源管家 (Storage & Networking)
负责 0.5B 模型的获取与存储：
* **方式 A (极客路线)**: 提供清晰的文件路径提示，允许开发者通过 `adb push` 直接将几十兆/几百兆的 GGUF 文件推送到 App 私有目录，如：
  `adb push qwen2.5.gguf /sdcard/Android/data/com.tangren.sense/files/models/`
* **方式 B (傻瓜路线)**: 利用 Kotlin `OkHttp`，在 App 内部提供一个直连 HuggingFace / ModelScope 的下载进度条。

### 层级四：控制台 UI (Jetpack Compose)
极简的运维面板，包含：
1. **模型状态区**: 显示 GGUF 文件是否存在、模型占用内存大小。
2. **服务控制区**: [启动 Server] / [停止 Server] 按钮。
3. **接口日志区**: 实时滚动显示类似 `[GET /v1/models 200 OK]` 的网络访问日志。
4. **内部 Demo 区**: 预留一个简单的对话气泡界面，直接请求本机的 8080 端口验证模型能力。

---

## 4. 首个版本的开发实施步骤 (Roadmap)

### 第一阶段：工程搭建与 C++ 环境跑通 (最硬核的部分)
1. 在 Android Studio 创建包含 **C++ Support (Native)** 的项目。
2. 将 `llama.cpp` 的核心源码（如 `llama.cpp`, `ggml.c`）拷贝进 `app/src/main/cpp`。
3. 编写 `CMakeLists.txt`，将 llama 编译为 `libllama.so`。
4. 编写 Kotlin JNI 接口，实现 `initContext()` 和简单的字符串传入传出。

### 第二阶段：模型下发与加载测试
1. 设计一个简单的界面，允许用户一键从远端下载测试用的 `qwen2.5-0.5B-q4.gguf`。
2. 将模型路径传递给 JNI，在 C++ 层成功 `llama_load_model_from_file` 并打印加载耗时。

### 第三阶段：嵌入式网关 Ktor 接入
1. 引入 Ktor Server 依赖。
2. 在 App 启动时，开启服务监听：
```kotlin
embeddedServer(CIO, port = 8080, host = "127.0.0.1") {
    routing {
        post("/v1/chat/completions") {
            // 调用 JNI，组装流式响应返回给其他 App
        }
    }
}.start(wait = false)
```

### 第四阶段：其他 App IPC 验证
在同一台手机上，使用一个完全无关联的第三方 HTTP 请求工具（如纯 Web 页面、甚至其他小程序），向 `http://127.0.0.1:8080` 发起网络请求，验证能否成功唤起底层 C++ 推理并拿到文本流。

---

## 5. 对后续 ASR / TTS 的预留建议

为了后续无缝接入语音，我们在 Kotlin 层定义多态引擎接口：

```kotlin
interface TangRenEngine {
    fun initialize(modelPath: String): Boolean
    fun release()
}

// 文本大模型
class LLMEngine : TangRenEngine { ... }

// 未来加入：语音识别
class ASREngine : TangRenEngine {
    fun transcribeAudio(pcmData: ByteArray): String
}

// 未来加入：语音合成
class TTSEngine : TangRenEngine {
    fun synthesizeText(text: String): ByteArray
}
```

通过 Ktor Router 分别将它们挂载在：
* `/v1/audio/transcriptions` -> `ASREngine`
* `/v1/audio/speech` -> `TTSEngine`
从而在端侧形成一个微缩版的“全模态 AI 云”。
