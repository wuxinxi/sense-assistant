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

# 获取本地文件真实字节大小（处理 macOS 与 Linux 差异，并追踪软链接）
get_local_file_size() {
    local target="$1"
    if [ "$(uname)" = "Darwin" ]; then
        stat -L -f %z "$target" 2>/dev/null || stat -f %z "$target" 2>/dev/null
    else
        stat -L -c %s "$target" 2>/dev/null || stat -c %s "$target" 2>/dev/null
    fi
}

# 获取远端 Android 文件字节大小
get_remote_file_size() {
    local remote_path="$1"
    local size
    size=$(adb shell "stat -c %s '$remote_path' 2>/dev/null" < /dev/null 2>/dev/null | tr -d '\r\n[:space:]')
    if [[ "$size" =~ ^[0-9]+$ ]]; then
        echo "$size"
    else
        echo "0"
    fi
}

# 3. 解析自动化标志
AUTO_YES=false
for arg in "$@"; do
    if [ "$arg" = "--yes" ] || [ "$arg" = "-y" ] || [ "$arg" = "--all" ]; then
        AUTO_YES=true
        break
    fi
done

# 4. 创建目标沙盒目录（无需 root 权限）
echo "📁 正在创建应用私有沙盒目录..."
adb shell "mkdir -p $TARGET_DIR"

ask_push() {
    local prompt_msg="$1"
    if [ "$AUTO_YES" = true ]; then
        return 0
    fi
    read -p "❓ 是否推送 $prompt_msg ? [Y/n]: " ans
    ans=$(echo "$ans" | tr '[:upper:]' '[:lower:]' | xargs)
    if [ "$ans" = "n" ]; then
        echo "⏭️ 已跳过 $prompt_msg"
        return 1
    fi
    return 0
}

# 5. 推送 SenseVoice ASR 模型
SENSE_VOICE_DIR="$PROJECT_ROOT/sense-voice-int8"
if [ -d "$SENSE_VOICE_DIR" ] && [ -f "$SENSE_VOICE_DIR/model.int8.onnx" ] && [ -f "$SENSE_VOICE_DIR/tokens.txt" ]; then
    if ask_push "SenseVoice 语音识别模型"; then
        LOCAL_ONNX_SIZE=$(get_local_file_size "$SENSE_VOICE_DIR/model.int8.onnx")
        REMOTE_ONNX_SIZE=$(get_remote_file_size "$TARGET_DIR/sense-voice-int8/model.int8.onnx")
        LOCAL_TOKENS_SIZE=$(get_local_file_size "$SENSE_VOICE_DIR/tokens.txt")
        REMOTE_TOKENS_SIZE=$(get_remote_file_size "$TARGET_DIR/sense-voice-int8/tokens.txt")

        if [ "$REMOTE_ONNX_SIZE" = "$LOCAL_ONNX_SIZE" ] && [ "$REMOTE_TOKENS_SIZE" = "$LOCAL_TOKENS_SIZE" ]; then
            echo "⚡ 远端已存在完整 SenseVoice 模型 ($((LOCAL_ONNX_SIZE / 1024 / 1024))MB)，大小一致，跳过推送。"
        else
            echo "🚀 正在推送 SenseVoice 离线语音识别模型到手机..."
            adb push "$SENSE_VOICE_DIR" "$TARGET_DIR/"
            echo "✅ SenseVoice 模型推送完成！"
        fi
    fi
else
    echo "⚠️ 未在本地检测到完整的 sense-voice-int8 模型，请先运行: bash Script/download_all.sh"
fi

# 6. 推送 VITS MeloTTS 中英双语超清离线语音合成模型 (优先) 或 AISHELL-3
MELO_TTS_DIR="$PROJECT_ROOT/vits-melo-tts-zh_en"
VITS_TTS_DIR="$PROJECT_ROOT/vits-zh-aishell3"
if [ -d "$MELO_TTS_DIR" ] && { [ -f "$MELO_TTS_DIR/model.int8.onnx" ] || [ -f "$MELO_TTS_DIR/model.onnx" ]; }; then
    if ask_push "VITS MeloTTS 语音合成模型"; then
        MELO_MODEL_FILE="$MELO_TTS_DIR/model.int8.onnx"
        [ ! -f "$MELO_MODEL_FILE" ] && MELO_MODEL_FILE="$MELO_TTS_DIR/model.onnx"
        LOCAL_MELO_SIZE=$(get_local_file_size "$MELO_MODEL_FILE")
        REMOTE_MELO_SIZE=$(get_remote_file_size "$TARGET_DIR/vits-melo-tts-zh_en/$(basename "$MELO_MODEL_FILE")")

        if [ "$REMOTE_MELO_SIZE" = "$LOCAL_MELO_SIZE" ] && [ "$LOCAL_MELO_SIZE" -gt 0 ]; then
            echo "⚡ 远端已存在完整 VITS MeloTTS 模型 ($((LOCAL_MELO_SIZE / 1024 / 1024))MB)，大小一致，跳过推送。"
        else
            echo "🚀 正在推送 VITS MeloTTS (44.1kHz 中英双语超清) 离线语音合成模型到手机..."
            adb push "$MELO_TTS_DIR" "$TARGET_DIR/"
            echo "✅ VITS MeloTTS 模型推送完成！"
        fi
    fi
elif [ -d "$VITS_TTS_DIR" ] && [ -f "$VITS_TTS_DIR/vits-aishell3.int8.onnx" ]; then
    if ask_push "VITS AISHELL-3 备用语音合成模型"; then
        LOCAL_VITS_SIZE=$(get_local_file_size "$VITS_TTS_DIR/vits-aishell3.int8.onnx")
        REMOTE_VITS_SIZE=$(get_remote_file_size "$TARGET_DIR/vits-zh-aishell3/vits-aishell3.int8.onnx")
        if [ "$REMOTE_VITS_SIZE" = "$LOCAL_VITS_SIZE" ] && [ "$LOCAL_VITS_SIZE" -gt 0 ]; then
            echo "⚡ 远端已存在完整 VITS AISHELL-3 模型 ($((LOCAL_VITS_SIZE / 1024 / 1024))MB)，大小一致，跳过推送。"
        else
            echo "🚀 正在推送备用 VITS AISHELL-3 离线语音合成模型到手机..."
            adb push "$VITS_TTS_DIR" "$TARGET_DIR/"
            echo "✅ VITS-TTS 模型推送完成！"
        fi
    fi
else
    echo "⚠️ 未在本地检测到完整的 TTS 模型，请先运行: python3 Script/download_models.py"
fi

# 7. 推送 LLM 大语言模型权重
GGUF_FILES=()
while IFS= read -r file; do
    [ -n "$file" ] && GGUF_FILES+=("$file")
done < <(find "$PROJECT_ROOT" -maxdepth 2 -name "*.gguf")

if [ ${#GGUF_FILES[@]} -eq 0 ]; then
    echo "⚠️ 未在本地检测到任何 .gguf 模型文件。"
else
    for GGUF_FILE in "${GGUF_FILES[@]}"; do
        if [ -f "$GGUF_FILE" ]; then
            FILE_NAME="$(basename "$GGUF_FILE")"
            if ask_push "大语言模型 $FILE_NAME"; then
                LOCAL_SIZE=$(get_local_file_size "$GGUF_FILE")
                REMOTE_FILE="/sdcard/Android/data/${PACKAGE_NAME}/files/${FILE_NAME}"
                REMOTE_SIZE=$(get_remote_file_size "$REMOTE_FILE")

                LOCAL_MB=$((LOCAL_SIZE / 1024 / 1024))
                REMOTE_MB=$((REMOTE_SIZE / 1024 / 1024))

                if [ "$REMOTE_SIZE" = "$LOCAL_SIZE" ] && [ "$LOCAL_SIZE" -gt 0 ]; then
                    echo "⚡ 远端已存在完整模型 $FILE_NAME (${REMOTE_MB}MB / ${LOCAL_SIZE} 字节)，大小一致，跳过推送。"
                else
                    if [ "$REMOTE_SIZE" -gt 0 ]; then
                        echo "🔄 远端模型 $FILE_NAME 不完整 (远端: ${REMOTE_MB}MB, 本地: ${LOCAL_MB}MB)，开始重新推送..."
                    else
                        echo "🚀 正在推送大模型: $FILE_NAME (${LOCAL_MB}MB) 到手机..."
                    fi
                    adb push "$GGUF_FILE" "/sdcard/Android/data/${PACKAGE_NAME}/files/"
                    echo "✅ 模型 $FILE_NAME 推送完成！"
                fi
            fi
        fi
    done
fi

# 8. 关键：修复 Android Linux 权限，确保 App 独立 UID 进程拥有完整读取和进入权限
echo "🛡️ 正在授予应用私有沙盒完整读写权限..."
adb shell "chmod -R 777 /sdcard/Android/data/${PACKAGE_NAME}/files/ 2>/dev/null || true"

echo "============================================================"
echo "🎉 所有模型推送与权限配置完毕！可以在手机上打开 App 体验纯离线语音与大模型交互了。"
echo "============================================================"
