"""Measure the real foreground chat path without reinstalling/clearing the app.

Synthetic ASCII fixtures only. Settings are never changed by this script.
Requires a loaded model and the main chat screen; uses observed accessibility
bounds, then asserts native decode speed from live process-scoped logcat.
"""
import argparse
import json
import os
import re
import selectors
import subprocess
import time
import xml.etree.ElementTree as ET

PACKAGE = "cn.xxstudy.assistant"
parser = argparse.ArgumentParser()
parser.add_argument("--prompt", default="Hive")
parser.add_argument("--min-tps", type=float, default=10.0)
parser.add_argument("--timeout", type=int, default=120)
parser.add_argument("--require-answer", action="store_true")
parser.add_argument("--check-last", action="store_true", help="Read the last visible completed reply without sending")
parser.add_argument("--expected-total-ms", type=int)
args = parser.parse_args()
if not re.fullmatch(r"[A-Za-z0-9 .,?!-]+", args.prompt):
    raise SystemExit("Use a synthetic ASCII question without shell metacharacters")
adb = ["adb"] + (["-s", os.environ["ADB_SERIAL"]] if os.getenv("ADB_SERIAL") else [])


def run(*command):
    return subprocess.check_output(adb + list(command), text=True, timeout=20)


def ui():
    activity = run("shell", "dumpsys", "activity", "activities")
    if not re.search(r"(?:topResumedActivity|mResumedActivity)=[^\n]*" + re.escape(PACKAGE), activity):
        raise RuntimeError("Assistant must be foreground; no other apps will be operated")
    run("shell", "uiautomator", "dump", "/data/local/tmp/tangren-perf-ui.xml")
    xml = run("shell", "cat", "/data/local/tmp/tangren-perf-ui.xml")
    return [n for n in ET.fromstring(xml).iter("node") if n.get("package") == PACKAGE]


def tap(node):
    x1, y1, x2, y2 = map(int, re.findall(r"\d+", node.get("bounds", "")))
    run("shell", "input", "tap", str((x1 + x2) // 2), str((y1 + y2) // 2))


def assert_last_answer(nodes, expected_total_ms=None):
    for attempt in range(4):
        positions = [i for i, node in enumerate(nodes) if "tk/s" in node.get("text", "")]
        targets = [i for i in positions if expected_total_ms is None or
                   (str(int(expected_total_ms)) + " ms") in nodes[i].get("text", "")]
        if targets:
            break
        if not any(n.get("class") == "android.widget.EditText" for n in nodes):
            raise RuntimeError("Chat screen is no longer visible")
        scroll = next((n for n in nodes if n.get("scrollable") == "true"), None)
        if scroll is None or attempt == 3:
            raise RuntimeError("This generation's metrics are not visible; refusing to assess an older reply")
        x1, y1, x2, y2 = map(int, re.findall(r"\d+", scroll.get("bounds", "")))
        # Use the chat's outer gutter: code/text children may consume a drag.
        scroll_x = x1 + min(40, (x2 - x1) // 4)
        run("shell", "input", "swipe", str(scroll_x), str(y2 - 80),
            str(scroll_x), str(y1 + 80), "300")
        nodes = ui()
    if not positions:
        raise RuntimeError("No completed reply visible")
    end = targets[-1]
    earlier = [i for i in positions if i < end]
    start = earlier[-1] + 1 if earlier else 0
    texts = [node.get("text", "") for node in nodes[start:end]]
    if any("尚未输出正文" in text or "（回复为空）" in text for text in texts):
        raise SystemExit("FAIL: last reply reached thinking limit without an answer")
    print("PASS: last reply has no missing-answer warning", flush=True)


nodes = ui()
if args.check_last:
    assert_last_answer(nodes, args.expected_total_ms)
    raise SystemExit(0)
edit = next(n for n in nodes if n.get("class") == "android.widget.EditText")
if edit.get("text") not in ("", args.prompt):
    raise SystemExit("Unsent user text exists; refusing to overwrite it")
if not edit.get("text"):
    tap(edit)
    # The voice-first field becomes editable on the next Compose frame. Wait
    # for that state change and avoid flooding it with synthetic key events.
    ui()
    for char in args.prompt:
        run("shell", "input", "text", "%s" if char == " " else char)
        time.sleep(0.05)
    nodes = ui()
edit = next(n for n in nodes if n.get("class") == "android.widget.EditText")
if edit.get("text") != args.prompt:
    raise SystemExit("Injected text does not match fixture; refusing to send it")
send = next(n for n in nodes if n.get("content-desc") == "发送")
pid = run("shell", "pidof", PACKAGE).strip()
if not re.fullmatch(r"\d+", pid):
    raise SystemExit("Expected one assistant process")
log = subprocess.Popen(adb + ["logcat", "--pid=" + pid, "-T", "1", "-v", "brief",
                                  "-s", "TangRenLlamaJNI:I", "MainViewModel:D", "KnowledgeRetriever:I"],
                       stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0)
selector = selectors.DefaultSelector()
selector.register(log.stdout, selectors.EVENT_READ)
metrics = None
stages = None
started = False
finished_at = None
try:
    tap(send)
    pending = b""
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline and (finished_at is None or time.monotonic() < finished_at + 2):
        for key, _ in selector.select(timeout=1):
            data = os.read(key.fileobj.fileno(), 4096)
            if not data:
                raise RuntimeError("Logcat terminated before generation completed")
            pending += data
            while b"\n" in pending:
                line, pending = pending.split(b"\n", 1)
                line = line.decode("utf-8", errors="replace")
                if "RAG Retrieval done:" in line:
                    print("retrieval=" + line.split("RAG Retrieval done:", 1)[1].strip(), flush=True)
                if "Received prompt:" in line:
                    started = True
                if started and "RAG answer failed grounding" in line:
                    print("grounding_fallback=true", flush=True)
                if started and "Thinking disabled via logit bias" in line:
                    print("thinking_disabled=true", flush=True)
                if started and "Generation metrics:" in line:
                    match = re.search(r"tokens=(\d+) ttft_ms=(\d+) total_ms=(\d+) speed=([\d.]+)", line)
                    if match:
                        metrics = dict(zip(["tokens", "ttft_ms", "total_ms", "speed"], map(float, match.groups())))
                        print(json.dumps({"metrics": metrics}), flush=True)
                if started and "Generation stages:" in line:
                    stages = {k: float(v) for k, v in re.findall(r"(\w+)=([\d.]+)", line)}
                    print(json.dumps({"stages": stages}), flush=True)
                    finished_at = time.monotonic()
finally:
    selector.close()
    log.terminate()
    log.wait(timeout=5)
if metrics is None or stages is None:
    raise SystemExit("FAIL: no complete live native metrics within timeout (generation not stopped)")
if args.require_answer:
    assert_last_answer(ui(), metrics["total_ms"])
if metrics["speed"] < args.min_tps:
    raise SystemExit("FAIL: application decode %.1f < %.1f token/s" % (metrics["speed"], args.min_tps))
print("PASS: application decode meets %.1f token/s" % args.min_tps)
