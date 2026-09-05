#!/usr/bin/env bash
set -e

# 获取脚本目录
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
PACKAGE_NAME="cn.xxstudy.assistant"
TARGET_DIR="/sdcard/Android/data/${PACKAGE_NAME}/files/models"

echo "============================================================"
echo "📱 SenseAssistant 模型一键推送与授权工具 (ADB)"
echo "📌 目标包名: $PACKAGE_NAME"
echo "📂 沙盒目标路径: $TARGET_DIR"
echo "============================================================"

# 1. 检查 ADB 命令
if ! command -v adb &> /dev/null; then
    echo "❌ 未检测到 adb 命令，请确保已安装 Android SDK Platform-Tools 并配置好 PATH。"
    exit 1
fi

# 2. 检查设备连接
echo "🔍 正在检测 USB 连接设备..."
DEVICE_COUNT=$(adb devices | grep -v "List" | grep "device$" | wc -l | tr -d ' ')

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "⚠️ 未检测到已授权的 Android 设备，请确保："
    echo "   1. 手机已开启【开发者选项】和【USB 调试】"
    echo "   2. 手机屏幕弹出【允许 USB 调试】时点击了确认"
    exit 1
fi

echo "✅ 已连接 $DEVICE_COUNT 台设备。"

# 3. 创建目标沙盒目录（无需 root 权限）
echo "📁 正在创建应用私有沙盒目录..."
adb shell "mkdir -p $TARGET_DIR"

# 4. 推送 SenseVoice ASR 模型
SENSE_VOICE_DIR="$PROJECT_ROOT/sense-voice-int8"
if [ -d "$SENSE_VOICE_DIR" ] && [ -f "$SENSE_VOICE_DIR/model.int8.onnx" ] && [ -f "$SENSE_VOICE_DIR/tokens.txt" ]; then
    echo "🚀 正在推送 SenseVoice 离线语音识别模型到手机..."
    adb push "$SENSE_VOICE_DIR" "$TARGET_DIR/"
    echo "✅ SenseVoice 模型推送完成！"
else
    echo "⚠️ 未在本地检测到完整的 sense-voice-int8 模型，请先运行: bash Script/download_all.sh"
fi

# 5. 推送 VITS MeloTTS 中英双语超清离线语音合成模型 (优先) 或 AISHELL-3
MELO_TTS_DIR="$PROJECT_ROOT/vits-melo-tts-zh_en"
VITS_TTS_DIR="$PROJECT_ROOT/vits-zh-aishell3"
if [ -d "$MELO_TTS_DIR" ] && { [ -f "$MELO_TTS_DIR/model.int8.onnx" ] || [ -f "$MELO_TTS_DIR/model.onnx" ]; }; then
    echo "🚀 正在推送 VITS MeloTTS (44.1kHz 中英双语超清) 离线语音合成模型到手机..."
    adb push "$MELO_TTS_DIR" "$TARGET_DIR/"
    echo "✅ VITS MeloTTS 模型推送完成！"
elif [ -d "$VITS_TTS_DIR" ] && [ -f "$VITS_TTS_DIR/vits-aishell3.int8.onnx" ]; then
    echo "🚀 正在推送备用 VITS AISHELL-3 离线语音合成模型到手机..."
    adb push "$VITS_TTS_DIR" "$TARGET_DIR/"
    echo "✅ VITS-TTS 模型推送完成！"
else
    echo "⚠️ 未在本地检测到完整的 TTS 模型，请先运行: python3 Script/download_models.py"
fi

# 6. 推送 Qwen 大模型（若存在）
QWEN_GGUF=$(find "$PROJECT_ROOT" -maxdepth 2 -name "*qwen*.gguf" | head -n 1)
if [ -n "$QWEN_GGUF" ] && [ -f "$QWEN_GGUF" ]; then
    echo "🚀 正在推送 LLM 大模型权重: $(basename "$QWEN_GGUF") ..."
    adb push "$QWEN_GGUF" "/sdcard/Android/data/${PACKAGE_NAME}/files/"
    echo "✅ LLM 模型推送完成！"
fi

# 7. 关键：修复 Android Linux 权限，确保 App 独立 UID 进程拥有完整读取和进入权限
echo "🛡️ 正在授予应用私有沙盒完整读写权限..."
adb shell "chmod -R 777 $TARGET_DIR 2>/dev/null || true"

echo "============================================================"
echo "🎉 所有模型推送与权限配置完毕！可以在手机上打开 App 体验纯离线语音识别了。"
echo "============================================================"
