#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import subprocess
from pathlib import Path

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent
TARGET_FILE = PROJECT_ROOT / "MiniCPM5-2B-Q4_K_M.gguf"

MODELSCOPE_URL = "https://modelscope.cn/models/OpenBMB/MiniCPM5-2B-GGUF/resolve/master/MiniCPM5-2B-Q4_K_M.gguf"
HF_MIRROR_URL = "https://hf-mirror.com/openbmb/MiniCPM5-2B-GGUF/resolve/main/MiniCPM5-2B-Q4_K_M.gguf"

print("=" * 65)
print("🚀 MiniCPM5-2B (Q4_K_M, ~1.56GB) 端侧大模型高速下载工具")
print(f"📂 本地目标文件: {TARGET_FILE}")
print("🌐 默认加速线路: ModelScope 阿里云国内千兆 CDN")
print("=" * 65)

# 使用 curl 支持断点续传 (-C -)
cmd = [
    "curl", "-L", "-C", "-",
    "--progress-bar",
    "-o", str(TARGET_FILE),
    MODELSCOPE_URL
]

print("⏳ 正在建立高速连接并启动断点续传下载...")
ret = subprocess.run(cmd)

if ret.returncode != 0:
    print("\n⚠️ ModelScope 下载异常，尝试使用 HF-Mirror 备用节点重试...")
    cmd[-1] = HF_MIRROR_URL
    ret = subprocess.run(cmd)

if ret.returncode == 0 and TARGET_FILE.exists() and TARGET_FILE.stat().st_size > 1_000_000_000:
    print(f"\n🎉 恭喜！MiniCPM5-2B 模型权重下载成功 ({TARGET_FILE.stat().st_size / 1024 / 1024:.1f} MB)！")
    print("📱 请运行以下命令一键推送到手机私有沙盒：")
    print("   bash Script/push_models_to_phone.sh")
else:
    print("\n❌ 下载未完成，您可以重新运行本脚本继续断点续传。")
