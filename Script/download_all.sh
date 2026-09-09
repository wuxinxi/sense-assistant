#!/usr/bin/env bash
set -e

# 获取脚本所在目录绝对路径
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$SCRIPT_DIR"

VENV_DIR="$SCRIPT_DIR/.venv"

# 导出环境变量强制国内镜像并禁用国外 cas-server 直连
export HF_ENDPOINT="https://hf-mirror.com"
export HF_HUB_DISABLE_XET="1"
export HF_HUB_ENABLE_HF_TRANSFER="0"

echo "============================================================"
echo "🚀 启动 SenseAssistant 端侧模型自动化下载环境"
echo "📂 脚本目录: $SCRIPT_DIR"
echo "🌐 镜像端点: $HF_ENDPOINT"
echo "============================================================"

# 1. 检查 Python3
if ! command -v python3 &> /dev/null; then
    echo "❌ 错误: 未检测到 python3，请先安装 Python 3.8+ 环境。"
    exit 1
fi

# 2. 检查并创建专属虚拟环境 (避免全局 pip 限制)
if [ ! -d "$VENV_DIR" ]; then
    echo "📦 正在创建独立 Python 虚拟环境: $VENV_DIR ..."
    python3 -m venv "$VENV_DIR"
    echo "✅ 虚拟环境创建成功。"
else
    echo "✅ 检测到已有虚拟环境: $VENV_DIR"
fi

PYTHON_BIN="$VENV_DIR/bin/python"
PIP_BIN="$VENV_DIR/bin/pip"

# 3. 在虚拟环境中安装/检查 huggingface_hub 依赖 (使用清华源加速)
echo "🔍 检查虚拟环境依赖 huggingface_hub ..."
"$PIP_BIN" install -U "huggingface_hub>=0.20.0" -i https://pypi.tuna.tsinghua.edu.cn/simple --quiet

# 确保卸载 hf_xet（其直连国外 cas-server 易导致国内断流/卡死）
"$PIP_BIN" uninstall -y hf_xet &>/dev/null || true

# 4. 执行 Python 增量下载任务
echo "🌐 启动国内镜像断点续传下载..."
"$PYTHON_BIN" "$SCRIPT_DIR/download_models.py" "$@"
