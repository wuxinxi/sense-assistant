# Ace2 端侧推理实测与构建

验证日期：2026-10-04。设备：一加 Ace2 / SM8475 / Adreno 730，Android 16。
模型：现有 `MiniCPM5-2B-Q4_K_M.gguf`，1,561,318,368 字节，实际约 2.52B 参数；未替换或重新量化权重。

## 结论与测量口径

原 APK 只打包 CPU 后端，没有 Vulkan/OpenCL，确实没有使用 GPU。
Gradle 的 `-O3` 只编译 JNI 桥，不会重新优化已经预编译的 llama/ggml 库。
重建核心库并启用 ARM FP16/DotProd 后，CPU 解码改善；GPU 不是这份模型在此设备上的最快路径。

同设备、4 线程、普通 attention、两次重复的独立短基准（pp128 / tg32）：

| 路径 | 提示词处理 token/s | 生成 token/s |
| --- | ---: | ---: |
| 原 APK 的 CPU 核心库 | 16.50 | 11.85 |
| 新 Release CPU（FP16 + DotProd，无 OpenMP） | 57.72 | 18.67 |
| OpenCL GPU（请求全层 offload） | 52.95 | 4.54 |

GPU 值来自接入前的同版本独立构建；CPU 最终值来自下述可复现脚本的干净固定版本构建。
基准按生成 token 数计量，不是汉字数。CPU 基准必须同时使用 `-dev none -nopo 1`；仅 `-ngl 0` 仍可能把部分操作交给 GPU。

应用内 CPU 模式发送 `Hive`，开启既有知识库：从 18,679 个分块中命中 2 条并注入提示词，原生生成 158 tokens，首 token 9,251ms，生成调用总时长 23,692ms，解码速度 **10.9 token/s**；正文正常输出。检索时间不包含在原生生成计时中。此结果不能与短基准直接比较，也不是对所有问题、温度和上下文长度的速度保证。

## 第二轮：复用 CPU 工作线程

用户随后观察到应用内约 8 token/s；本轮读取到界面记录为 8.6 token/s。独立基准与 JNI 存在重要差异：`llama-bench` 挂接了持久线程池，而原 JNI 未挂接。固定版本的 CPU 后端在没有线程池时，会为每次 graph compute 创建并销毁临时线程；因此独立基准不能直接代表应用内吞吐。

无侵入对照保留现有模型、4 线程、普通 attention：

| 单项配置（1,024 token 深度，tg32） | token/s |
| --- | ---: |
| 普通 attention，4 线程 | 15.17 ± 0.21 |
| Flash Attention，4 线程 | 14.83 ± 0.06 |
| 普通 attention，3 线程 | 13.95 ± 0.03 |
| 普通 attention，5 线程 | 13.63 ± 0.11 |

这些结果不支持默认开启 CPU Flash Attention 或增加线程数。大核掩码 `f0` 的一次对照为 15.65 ± 0.01，而默认调度对照有明显波动；没有把固定核心编号硬编码到应用，以免误用于其他设备或牺牲系统交互。

新增 `Script/native_decode_probe.cpp` / `Script/probe_decode_workers.sh` 使用同一模型、1,024 token 的固定资料输入、64 个 teacher-forced tokens，按临时 / 持久 / 持久 / 临时的 ABBA 顺序比较。每轮重新创建上下文，持久路径直接使用生产代码 `CpuWorkerPool`；同时比较所有步骤的完整 logits，不仅判断是否跑完。

首轮直接 API 对照：临时线程 12.40 / 12.76，持久线程 15.40 / 15.16 token/s，耗时比 **1.215**，最大 logits 差 **0.000000**。生产辅助类对照：临时线程 12.36 / 12.25，持久线程 15.02 / 14.90 token/s，耗时比 **1.216**，最大 logits 差 **0.000000**。这些是解码子系统结果，不是对应用内 8 token/s 自动变为 15 token/s 的保证。

最终完整脚本回归（`MIN_SPEED_RATIO=1.15`）：临时线程 12.86 / 12.36，持久线程 15.04 / 14.47 token/s，耗时比 **1.171**，最大 logits 差 **0.000000**，脚本输出 `PASS`、退出码 0。本轮对照提升约 17%–22%，存在设备状态波动。25 项 JVM 测试重新执行通过，APK 构建及 JNI 动态依赖检查通过。

JNI 的 CPU 生成上下文已挂接持久 4 线程池；重载、释放和初始化失败都先销毁上下文再释放线程池。注意：`llama_detach_threadpool` 只修改上下文的指针，后端直到下一次 compute 才更新缓存指针；测试中不能在保留旧上下文时释放其旧线程池。

本轮保持 GPU 默认关闭、CPU 普通 attention、模型权重、知识库和语音设置不变。增加不包含提示词内容的生成分阶段日志：prompt tokens、已有 KV 长度、prefill / decode / sample / callback 时间。非 RAG 回答可能同时运行 4 线程 VITS 语音合成；这只是待验证的竞争因素，尚未声称已定位为应用内剩余差距的原因。

复现（Android NDK 环境变量同下文）：

```sh
MIN_SPEED_RATIO=1.15 bash Script/probe_decode_workers.sh
```

脚本不安装、不重启应用；必须保持其他推理任务空闲。退出码 3 表示 logits 差异超过 0.001，4 表示未达到指定速度提升，超时也失败。速度阈值用于此设备对照，不是其他设备的通用承诺。

当时测试版 APK 已构建但未安装；随后安装状态及应用内回归见第三轮。JVM 测试不能代替 JNI 的真机性能/线程生命周期回归。

## 第三轮：应用问答、思考预算与质量验证

检查设备 APK 内 JNI、CPU、llama 三个库的 SHA-256，均与第二轮持久线程池版本一致；应用确实已经运行该优化版本。本轮未覆盖安装或重启应用，合成测试消息追加到现有聊天，未清空聊天、模型或知识库。

同一 `Hive` 输入、2 条知识库命中、354 prompt tokens、KV 起点 0：

| 设置 | 首 token ms | 总时长 ms | sampled tokens | 解码 token/s |
| --- | ---: | ---: | ---: | ---: |
| 自动朗读开启 | 6633 | 25305 | 258 | 13.8 |
| 自动朗读关闭 | 6521 | 23566 | 242 | 14.1 |

两轮 callback 总耗时分别 63.4 / 61.2ms，sampling 为 27.2 / 24.8ms，不支持“UI 回调直接阻塞导致主要性能损失”的假设。吞吐差约 2%，样本太少，不能宣称关闭语音必然提速；RAG 路径本身也不在推理期间流式合成语音。另两轮 Hive 解码均为 14.4 token/s，正文可见。知识库命中并注入已验证，但不代表回答逐句准确。

非 RAG 的 `unicorn`：思考开启、自动朗读关闭时，512 tokens 用尽，总时长 49,812ms、10.3 token/s，界面出现“思考已达生成上限，尚未输出正文”。`probe_app_generation.py --check-last --require-answer` 对此真实失败退出。关闭思考后另一轮为 137 tokens、9,932ms、14.1 token/s，有正文；KV 长度 595 vs 526、输出长度不同，不能将全部速度变化归因于开关。后续语音开启一轮为 13.5 token/s，但模型受之前 Hive 上下文影响输出了代码，应用抑制代码朗读，不能作为有效的流式 TTS 竞争对照。

新增修复（当前分支的最新版 APK，**尚未安装到应用**）：

- 读取实际 GGUF 模板：禁用思考时预填 `<think>\n\n</think>\n\n`，而不是仅靠提示词或单 token 禁止。
- 多 token 标签不能只禁第一片，避免把普通 `<` 误禁，破坏代码类型/标记输出。
- 512-token 总预算中，思考约占最多 128 tokens；仍处于思考时，向 KV 和流式回调写入对应结束标记，剩余预算用于正文及控制 token。强制结束不是正文完整或准确的保证。
- 性能计数重置之前同步上一轮排队计算，避免把空闲时长算入新请求的 prefill。旧版一轮 prefill 日志为 705,745.5ms，而实际首 token 6,651ms；该阶段日志无效，独立墙钟 TTFT/总时长仍有效。

`Script/test_generation_policy.cpp` 在旧预算策略下失败，修复后通过；25 项 JVM 测试无失败，APK 构建和 JNI 依赖检查通过。

`Script/probe_jni_generation.sh` 提取新 APK 的真实库，在独立 Dalvik 进程加载手机现有模型，不安装应用、不运行 instrumentation、不清空数据。长推理余数题在日志中实际触发 `thought_limit=128 forced_think_end=1 control_tokens=3`，随后输出非空正文（31,868ms、16.9 token/s）；另一次 `unicorn` 也触发强制闭合并输出正文。以上只验证思考到正文的协议路径，**未验证长题数学答案正确**。

严格质量回归不能宣称全绿：同一明确 Dart 类型题曾正确输出 `List<String>`，重复时输出 `String<>`；早先“原样输出 List<String>”还被理解成水果列表。最新独立进程四项测试中，长思考/普通思考/数学短题通过，Dart 类型题失败，脚本退出码 1。尖括号可以生成、禁用模式未泄露思考，但 2.52B 模型的指令遵循及专业知识仍不稳定；这与“没有正文”的协议故障应分开处理。

复现需让应用及其他推理任务空闲：

```sh
c++ -std=c++17 -Iapp/src/main/cpp Script/test_generation_policy.cpp -o /tmp/tangren-generation-policy
/tmp/tangren-generation-policy
ANDROID_SDK_ROOT=/absolute/path/to/sdk bash Script/probe_jni_generation.sh
# 应用主聊天页、CPU 模式：追加合成问题；不会修改设置。
python3 Script/probe_app_generation.py --prompt Hive --require-answer
```

独立 VM 的解码与应用内完整 RAG 问答不可直接等同；短答案只有一至几个 token，速度尤其不稳定。原自动朗读和思考设置在测试后恢复为开启，GPU 继续关闭、知识库继续开启。最新版的应用 UI/语音全链路回归仍需获得允许重装和重启后补做。

## GPU 取舍

Vulkan 独立实验在该系统驱动上有两类失败：设备函数指针为空；修正设备分派及扩展加载后，量化矩阵乘法的计算管线仍以 `ErrorUnknown` 失败。禁用 FP16 也没有解决管线错误。因此 APK 不打包这条未验证成功的 Vulkan 路径。

OpenCL 可以执行现有 Q4_K_M 模型，但本次全层 GPU 解码比 CPU 慢。设置页提供默认关闭的“GPU 推理 (OpenCL · 实验)”开关；切换会重载模型并重置 KV 上下文。首页显示实际后端，而非“请求 GPU”就宣称正在使用 GPU。

应用内 GPU 模式也成功初始化，首页显示 `GPU OpenCL`；但本轮 `Hive` 问答提前结束（首 token 3,820ms、总计 6,489ms、短输出速度 1.9 token/s），显示的完整资料来自既有 RAG 兜底，**不能视为 GPU 已通过回答质量验证**。这个过短输出的速度不适合与 CPU 的完整回答作直接对比。实验开关明确提示可能生成异常，Ace2 本模型建议关闭；最终设备恢复 CPU。后续 GPU 调优必须补充确定性 CPU/GPU 输出或 logits 差分验证，不能只看 benchmark 是否跑完。

CPU/OpenCL 都作为动态插件加载；JNI 不直接链接插件。OpenCL 插件依赖系统公开的 `libOpenCL.so`，Manifest 使用 `required=false`，APK 不包含厂商驱动或 ICD loader。缺失插件/驱动或模型/上下文初始化返回失败时可回退 CPU；**厂商驱动内部 abort/SIGSEGV 不能靠这一回退捕获**，因此 GPU 保持实验性、明确 opt-in。

知识库 embedding 始终使用 CPU，且显式关闭 GPU 操作和 KV offload，避免切换 GPU 后意外改变检索计算路径。

## 复现

依赖：Git、Python 3、CMake、Ninja、Android NDK 28.2.13676358、ADB、ripgrep。macOS 默认 NDK host tag 为 `darwin-x86_64`；Linux 设置 `NDK_HOST_TAG=linux-x86_64`。

```sh
export ANDROID_NDK_HOME=/absolute/path/to/ndk/28.2.13676358
# CMake/Ninja 不在 PATH 时也可设置 CMAKE_BIN 和 NINJA_BIN 为绝对路径。
bash Script/build_native_runtime.sh
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

脚本固定 llama.cpp `f95b0d95394d5e311ba8228689972843178c5e28`、OpenCL Headers 和 ICD loader 的提交，开启 Release `-O3 -DNDEBUG`、`armv8.2-a+fp16+dotprod`、嵌入 OpenCL kernels、动态后端，关闭 OpenMP；剥离发行二进制符号，并同步匹配的头文件和许可证。CPU 指令集延续项目针对现代 ARM 设备的假设，不是面向所有历史 arm64 手机的兼容版本。

关闭应用及其他推理任务后，顺序运行，避免并行推理造成干扰：

```sh
MIN_DECODE_TPS=12 bash Script/benchmark_native_runtime.sh cpu
bash Script/benchmark_native_runtime.sh gpu
```

两条命令不会安装、卸载或清空应用。CPU 脚本在本设备输出 `decode: 18.67 token/s`、`PASS`；阈值只适合此设备回归验证，其他设备按实际基线设置。

还应检查构建出的 JNI ELF，防止 CMake 把没有 SONAME 的 MODULE 插件的开发机绝对路径写入依赖：

```sh
bash Script/check_native_dependencies.sh /absolute/path/to/built/libsense_assistant.so
```

真机覆盖安装仅使用 `adb install -r -t`，不卸载应用、不运行会移除目标应用的 instrumentation runner。本轮安装前已备份私有数据库/设置，确认既有 1,418 篇笔记、18,679 分块与 GGUF 权重保留。

性能看板改用公开的进程 CPU 时间及本进程 PSS API，不再读取受限的 `/proc/stat`，也不再用会返回旧样本的跨进程内存查询。CPU 百分比按整机逻辑核总容量归一化，8 核手机占满 4 核约为 50%，不是单核占用率。

上游资料：[固定版本的 OpenCL 后端说明](https://github.com/ggml-org/llama.cpp/blob/f95b0d95394d5e311ba8228689972843178c5e28/docs/backend/OPENCL.md)。
