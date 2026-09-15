import re

with open('/Users/sensiwu/LovelyCoder/TangRen/Note/tangren-note/公众号/文章/2026-09/edge-ai-assistant/index.md', 'r') as f:
    content = f.read()

new_content = """### 2.4 全双工电话模式：端侧的终极突围

解决了基础的打断后，我们把目光投向了目前端侧 AI 应用最高规格的体验——像豆包、ChatGPT 一样的**全双工电话模式**（Full-duplex Barge-in）。

什么叫全双工？

就是 AI 在口若悬河巴拉巴拉说话的同时，你的麦克风也处于“全时段监听”状态。
你只要一开口，它立刻闭嘴，倾听你的新指令。

要在云端服务器上实现这个，算力管够，各路降噪算法随便堆。
但在我们这台断网的安卓旧机上，简直是地狱难度。

最绝望的，是**AI 会陷入“我打断我自己”的死循环**。

进入电话模式后，手机外放喇叭在最大音量播放 AI 的声音。
而同时，为了随时听你插话，麦克风又是全开的。
这意味着麦克风会把 AI 自己的声音原封不动地录进去。
于是搞笑的一幕出现了：AI 一开口，VAD 语音检测引擎立刻激动地判定“有人说话！”，然后无情地把 AI 自己的发音给打断了。

云端可以跑庞大的软件 AEC（回声消除）大模型。
但端侧的 2G 可用内存根本吃不消。

我们不得不硬着头皮，深入 Android 音频系统最底层。
我们果断抛弃了常规的 `MIC` 录音通道，强制改写为 VoIP 专属的 `VOICE_COMMUNICATION` 模式，硬生生激活了手机硬件 DSP 芯片自带的 Acoustic Echo Canceler。

从此，麦克风对 AI 自己的声音充耳不闻，只对你的声音敏感。

硬件回声消除搞定后，真正的**毫秒级三连斩**登场。

我们引入了轻量级的端侧人声检测模型 **Silero VAD v5** 作为门神。
为了达到和真人聊天一样的顺滑，只要 VAD 的 `onVoiceStart` 监听到你嘴巴发出声音的瞬间，底层的 ViewModel 会立刻执行无情的“三连斩”：

第一斩，强制 Flush 并瞬间清空 TTS 引擎播放队列。
第二斩，一键 Cancel 掉 C++ 层的 LLM 推理协程，绝不浪费 CPU 一丝算力。
第三斩，在屏幕的聊天气泡中优雅地打上一个 `（已打断）` 标识，并无缝切入新一轮录音。

不仅如此。
我们还用纯 Compose 手搓了一个极简的深色沉浸式通话界面。
屏幕中央那颗代表 AI 的科技蓝能量球，会跟随着你的真实分贝值（RMS）实时膨胀、收缩。

当它因为你的插话而瞬间闭嘴倾听时。
那种仿佛有生命的反应速度，已经逼近了真实的跨时空人类对谈。

"""

content = content.replace("## 结语", new_content + "## 结语")

with open('/Users/sensiwu/LovelyCoder/TangRen/Note/tangren-note/公众号/文章/2026-09/edge-ai-assistant/index.md', 'w') as f:
    f.write(content)
print("Article patched successfully.")
