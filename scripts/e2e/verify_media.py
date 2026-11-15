#!/usr/bin/env python3
"""Verify the built-in ASR / TTS / OCR routing on the emulator, without a real API key.

Prerequisites
    python scripts/e2e/fake_llm_server.py --port 8765 --log-file build/e2e/requests.jsonl
    python scripts/e2e/setup_model.py --key e2etestkey2026    (once, saves the encrypted key)
    ./gradlew :app:assembleDebug && adb install -r app/build/outputs/apk/debug/app-debug.apk

    python scripts/e2e/verify_media.py

Checks
    1. AUTO routing: a deepseek-* chat model OR provider.id=deepseek selects the official
       /v1/tools endpoints; a non-DeepSeek provider selects the platform fallback.
    2. MEDIA_ASR / MEDIA_TTS / MEDIA_OCR really hit the fake tools API with the documented
       multipart / JSON shapes, and the app parses the responses.
"""

from __future__ import annotations

import json
import sys
import time
import urllib.request
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from adb_ui import a11y_enable, broadcast, logcat, logcat_clear  # noqa: E402
import setup_media as config  # noqa: E402

HOST = "http://127.0.0.1:8765"
RECEIVER_LINE = "debug: media"


def control(**fields):
    request = urllib.request.Request(
        f"{HOST}/control",
        data=json.dumps(fields).encode(),
        headers={"Content-Type": "application/json"},
    )
    return urllib.request.urlopen(request, timeout=5).read()


def request_log() -> list[dict]:
    with urllib.request.urlopen(f"{HOST}/log", timeout=5) as response:
        return json.load(response)["requests"]


def reset_server() -> None:
    urllib.request.urlopen(
        urllib.request.Request(f"{HOST}/control/reset", data=b"{}", headers={"Content-Type": "application/json"}),
        timeout=5,
    ).read()


def media_lines() -> list[str]:
    return [
        line.split("I Xiaoman :")[-1].strip()
        for line in logcat("Xiaoman", 500).splitlines()
        if RECEIVER_LINE in line
    ]


def set_profile(profile: str) -> None:
    config.base.write_prefs(config.patch(config.base.read_prefs(), profile))
    config.base.restart_app()
    time.sleep(2.5)


def status() -> str:
    logcat_clear()
    broadcast("dev.xiaoman.debug.MEDIA_STATUS")
    time.sleep(1.0)
    lines = [line for line in media_lines() if "media status" in line]
    return lines[-1] if lines else ""


def main() -> int:
    a11y_enable()
    reset_server()
    failures: list[str] = []

    expectations = {
        "model": ("deepSeekProvider=true", "asr=DEEPSEEK", "tts=DEEPSEEK", "ocr=DEEPSEEK", "/v1/tools"),
        "provider": ("deepSeekProvider=true", "asr=DEEPSEEK", "tts=DEEPSEEK", "ocr=DEEPSEEK", "/v1/tools"),
        "system": ("deepSeekProvider=false", "asr=SYSTEM", "tts=SYSTEM", "ocr=VISION_MODEL", "endpoint=-"),
        "forced": ("deepSeekProvider=false", "asr=DEEPSEEK", "tts=DEEPSEEK", "ocr=DEEPSEEK", "/v1/tools"),
    }
    print("=== AUTO routing ===")
    for profile, needles in expectations.items():
        set_profile(profile)
        line = status()
        ok = bool(line) and all(needle in line for needle in needles)
        print(f"  {profile:9s} {'PASS' if ok else 'FAIL'}  {line or '(no status line)'}")
        if not ok:
            failures.append(f"routing/{profile}: expected {needles}")

    # Back to the model-name-driven DeepSeek profile for the real calls.
    set_profile("model")
    reset_server()
    control(asr_text="E2E 语音识别结果", ocr_text="E2E 图片文字", tts_duration_ms=160, tools_error_code=0)
    logcat_clear()
    print("=== triggering the three engines ===")
    # DebugCommand reads extras with Intent.getStringExtra, so millis must be a string extra.
    broadcast("dev.xiaoman.debug.MEDIA_ASR", "--es", "millis", "1200")
    time.sleep(3.0)
    broadcast("dev.xiaoman.debug.MEDIA_TTS", "--es", "text", "小满端到端朗读", "--es", "voice", "tide")
    time.sleep(2.5)
    broadcast("dev.xiaoman.debug.MEDIA_OCR", "--es", "purpose", "RICH_LAYOUT")
    time.sleep(3.0)

    lines = media_lines()
    for line in lines:
        print("  " + line[:170])

    entries = request_log()
    tools = [e for e in entries if e.get("tool")]

    def one(tool: str) -> dict:
        matches = [e for e in tools if e.get("tool") == tool]
        if not matches:
            failures.append(f"no /v1/tools/{tool} request")
            return {}
        return matches[-1]

    asr = one("asr")
    tts = one("tts")
    ocr = one("ocr")
    checks = [
        ("asr multipart file", asr.get("has_file_part") is True),
        ("asr WAV payload", asr.get("riff") is True),
        # 16 kHz / 16-bit / mono for ~1.2 s => tens of KB, not a stub upload.
        ("asr audio length", int(asr.get("audio_bytes") or 0) >= 30_000),
        ("asr authorized", asr.get("authorization") == "Bearer ***"),
        ("tts voice", tts.get("voice_id") == "tide"),
        ("tts format", tts.get("format") == "wav"),
        ("tts text", tts.get("text") == "小满端到端朗读"),
        ("ocr rich layout", ocr.get("purpose") == "rich_layout_recognition"),
        ("ocr multipart file", ocr.get("has_file_part") is True),
        ("asr parsed", any("media asr engine=DEEPSEEK" in line and "E2E 语音识别结果" in line for line in lines)),
        ("tts played", any("media tts engine=DEEPSEEK" in line and "bytes=" in line for line in lines)),
        ("ocr parsed", any("media ocr engine=DEEPSEEK" in line and "pages=1" in line for line in lines)),
    ]
    print("=== assertions ===")
    passed = 0
    for name, ok in checks:
        print(f"  {name:24s} {'PASS' if ok else 'FAIL'}")
        if ok:
            passed += 1
        else:
            failures.append(name)

    print(f"\n=== {passed}/{len(checks)} assertions passed ===")
    if failures:
        print("FAILURES:", ", ".join(failures))
        return 1
    print("ALL MEDIA E2E CHECKS PASSED")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
