#!/usr/bin/env python3
# -*- coding: utf-8 -*-

import os
import sys
import time
from pathlib import Path

# 1. 强制设置国内镜像源并禁用国外直连后端
os.environ["HF_ENDPOINT"] = "https://hf-mirror.com"
os.environ["HF_HUB_DISABLE_XET"] = "1"
os.environ["HF_HUB_ENABLE_HF_TRANSFER"] = "0"

try:
    from huggingface_hub import HfApi, snapshot_download
except ImportError:
    print("❌ 未检测到 huggingface_hub 库。")
    print("💡 请确保已激活虚拟环境，并执行: pip install -U huggingface_hub")
    sys.exit(1)

SCRIPT_DIR = Path(__file__).resolve().parent
PROJECT_ROOT = SCRIPT_DIR.parent

TASKS = [
    {
        "title": "VITS MeloTTS 中英双语超清语音合成 (44.1kHz / 51MB INT8)",
        "repo_id": "csukuangfj/vits-melo-tts-zh_en",
        "local_dir": PROJECT_ROOT / "vits-melo-tts-zh_en",
        "allow_patterns": [
            "model.int8.onnx",
            "model.onnx",
            "lexicon.txt",
            "tokens.txt",
            "date.fst",
            "number.fst",
            "phone.fst",
            "new_heteronym.fst",
            "dict/*",
            "dict/**/*",
        ],
    },
    {
        "title": "SenseVoice Small INT8 (语音识别模型)",
        "repo_id": "csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17",
        "local_dir": PROJECT_ROOT / "sense-voice-int8",
        "allow_patterns": ["model.int8.onnx", "tokens.txt"],
    },
    {
        "title": "MiniCPM5-2B-Q4_K_M (深度思考大模型, ~1.4GB)",
        "repo_id": "openbmb/MiniCPM5-2B-GGUF",
        "local_dir": PROJECT_ROOT / "llm",
        "allow_patterns": ["MiniCPM5-2B-Q4_K_M.gguf"],
    },
    {
        "title": "Qwen2.5-0.5B-Instruct (轻量级大模型, ~491MB)",
        "repo_id": "qwen/Qwen2.5-0.5B-Instruct-GGUF",
        "local_dir": PROJECT_ROOT / "llm",
        "allow_patterns": ["qwen2.5-0.5b-instruct-q4_k_m.gguf"],
    },
]


def cleanup_stale_locks(local_dir: Path):
    """
    清理因非正常终止遗留的 .lock 文件和 0 字节无效临时文件，防止死锁
    """
    cache_dir = local_dir / ".cache" / "huggingface" / "download"
    if cache_dir.exists():
        for lock_file in cache_dir.glob("*.lock"):
            try:
                lock_file.unlink(missing_ok=True)
            except Exception:
                pass
        for inc_file in cache_dir.glob("*.incomplete"):
            try:
                if inc_file.stat().st_size == 0:
                    inc_file.unlink(missing_ok=True)
            except Exception:
                pass


def check_missing_files(api: HfApi, repo_id: str, local_dir: Path, allow_patterns: list | None):
    """
    检查本地是否已有全部目标文件，返回已存在与缺失文件列表
    """
    if not local_dir.exists():
        return None

    try:
        repo_files = api.list_repo_files(repo_id=repo_id)
    except Exception as e:
        print(f"   ⚠️ 远端文件清单获取略过 ({e})，将直接通过镜像层校验。")
        return None

    if allow_patterns:
        expected_files = [f for f in repo_files if f in allow_patterns]
    else:
        expected_files = [f for f in repo_files if not f.startswith(".git/")]

    missing_files = []
    existing_files = []

    for rel_path in expected_files:
        local_file = local_dir / rel_path
        if local_file.exists() and local_file.is_file() and local_file.stat().st_size > 0:
            existing_files.append(rel_path)
        else:
            missing_files.append(rel_path)

    return existing_files, missing_files


def download_task(task: dict, max_retries: int = 5, retry_delay: int = 3):
    title = task["title"]
    repo_id = task["repo_id"]
    local_dir: Path = task["local_dir"]
    allow_patterns = task["allow_patterns"]

    print("\n" + "=" * 68)
    print(f"📦 正在处理: {title}")
    print(f"📌 仓库 ID: {repo_id}")
    print(f"📂 本地路径: {local_dir}")
    print("=" * 68)

    # 预先清理可能存在的残留锁
    cleanup_stale_locks(local_dir)

    api = HfApi()

    # 1. 检查本地现有文件状态
    check_result = check_missing_files(api, repo_id, local_dir, allow_patterns)
    if check_result is not None:
        existing_files, missing_files = check_result
        if existing_files:
            print(f"✅ 本地已存在完整文件 ({len(existing_files)} 个):")
            for f in existing_files:
                f_size_mb = (local_dir / f).stat().st_size / (1024 * 1024)
                print(f"   • {f} ({f_size_mb:.2f} MB)")

        if len(missing_files) == 0:
            print(f"✨ 该模型所有必需文件已全部就绪，跳过下载！")
            return True
        else:
            print(f"⏳ 待增量下载补齐 ({len(missing_files)} 个):")
            for f in missing_files:
                print(f"   • {f}")
    else:
        print("🔍 准备通过国内镜像建立连接...")

    # 2. 带自动重试与断点续传的下载
    for attempt in range(1, max_retries + 1):
        try:
            print(f"\n[尝试 {attempt}/{max_retries}] 正在连接国内镜像源...")
            snapshot_download(
                repo_id=repo_id,
                local_dir=str(local_dir),
                allow_patterns=allow_patterns,
                max_workers=4,
            )
            print(f"🎉 成功完成: {title}")
            return True

        except Exception as e:
            print(f"⚠️ [尝试 {attempt}] 下载波动中断: {e}")
            cleanup_stale_locks(local_dir)
            if attempt < max_retries:
                print(f"⏳ 将在 {retry_delay} 秒后自动尝试断点续传...")
                time.sleep(retry_delay)
            else:
                print(f"❌ 任务 [{title}] 达到最大重试次数。")
                return False


def main():
    print("####################################################################")
    print("###       SenseAssistant 端侧 AI 模型国内自动化增量下载脚本        ###")
    print("###                 镜像源: https://hf-mirror.com                ###")
    print("####################################################################")

    auto_yes = "--yes" in sys.argv or "-y" in sys.argv or "--all" in sys.argv

    success_count = 0
    for task in TASKS:
        if not auto_yes:
            ans = input(f"\n❓ 是否需要下载 {task['title']} ? [Y/n]: ").strip().lower()
            if ans == 'n':
                print(f"⏭️ 已跳过 {task['title']}")
                success_count += 1
                continue
                
        ok = download_task(task)
        if ok:
            success_count += 1

    print("\n" + "#" * 68)
    if success_count == len(TASKS):
        print("🎯 所有选定模型均已下载/补齐完毕，项目依赖就绪！")
    else:
        print(f"⚠️ 部分任务未完成 (成功 {success_count}/{len(TASKS)})，可重新运行本脚本继续断点续传。")
    print("#" * 68)

if __name__ == "__main__":
    main()
