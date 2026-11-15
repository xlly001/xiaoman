#!/usr/bin/env python3
"""Point the emulator's Xiaoman app at the local fake LLM server.

API keys live in EncryptedSharedPreferences, so adb cannot inject them. This
script injects the *plain* settings with ``run-as`` (base URL, model catalog,
provider), restarts the app, then uses the Settings UI once to type a single
ASCII key and press Save. It is idempotent and safe to re-run.

    python scripts/e2e/setup_model.py --port 8765 --key e2etestkey2026

Why not type the URL with ``adb shell input text``? The Google IME rewrites
``http://10.0.2.2:8765/v1`` into full-width punctuation (see HANDOVER §8).
Injecting the plain prefs file avoids that entirely.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
import time
import xml.etree.ElementTree as ET
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from adb_ui import ADB, dump, find, keyevent, shell, tap, tap_text, type_text, wait_for_text  # noqa: E402

PKG = "dev.xiaoman.app"
PREFS = "shared_prefs/settings.xml"
CHAT_MODEL = "fake-chat-v1"
VISION_MODEL = "fake-vision-v1"


def read_prefs() -> str:
    return shell("run-as", PKG, "cat", PREFS)


def patch_prefs(xml: str, base_url: str) -> bytes:
    root = ET.fromstring(xml)

    def put_string(name: str, value: str) -> None:
        element = root.find(f".//*[@name='{name}']")
        if element is None:
            element = ET.SubElement(root, "string", {"name": name})
        element.tag = "string"
        element.attrib = {"name": name}
        element.text = value

    def put_bool(name: str, value: bool) -> None:
        element = root.find(f".//*[@name='{name}']")
        if element is None:
            element = ET.SubElement(root, "boolean", {"name": name})
        element.tag = "boolean"
        element.attrib = {"name": name, "value": "true" if value else "false"}
        element.text = None

    for prefix, model, vision in (("chat", CHAT_MODEL, False), ("operator", VISION_MODEL, True)):
        put_string(f"{prefix}.baseUrl", base_url)
        put_string(f"{prefix}.model", model)
        put_string(f"{prefix}.displayName", model)
        put_string(f"{prefix}.temperature", "0.7")
        put_string(f"{prefix}.maxTokens", "8192")
        put_string(f"{prefix}.contextWindow", "32768")
        put_bool(f"{prefix}.supportsVision", vision)

    profiles = [
        {"id": CHAT_MODEL, "displayName": CHAT_MODEL, "maxTokens": 8192,
         "temperature": 0.7, "contextWindow": 32768, "supportsVision": False, "chatCapable": True},
        {"id": VISION_MODEL, "displayName": VISION_MODEL, "maxTokens": 8192,
         "temperature": 0.7, "contextWindow": 32768, "supportsVision": True, "chatCapable": True},
    ]
    put_string("provider.id", "custom")
    put_string("provider.models", json.dumps(profiles, ensure_ascii=False, separators=(",", ":")))
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def write_prefs(data: bytes) -> None:
    absolute = f"/data/data/{PKG}/{PREFS}"
    owner = shell("stat", "-c", "%u:%g", f"/data/data/{PKG}").strip().splitlines()[-1]
    # `run-as` runs in the runas_app SELinux context, which may only read.
    # Emulator adbd can be restarted as root; write as root and restore ownership
    # so the app can still rewrite its own prefs on Save.
    if "uid=0(root)" not in shell("id", check=False):
        subprocess.run([ADB, "root"], capture_output=True, timeout=30)
        subprocess.run([ADB, "wait-for-device"], capture_output=True, timeout=30)
        time.sleep(1.0)
    result = subprocess.run(
        [ADB, "shell", "sh", "-c", f"cat > {absolute}"],
        input=data, capture_output=True, timeout=30,
    )
    if result.returncode != 0:
        raise RuntimeError(f"writing prefs failed: {result.stderr.decode(errors='replace')}")
    shell("chown", owner, absolute, check=False)
    shell("chmod", "660", absolute, check=False)


def restart_app() -> None:
    shell("am", "force-stop", PKG)
    time.sleep(0.5)
    shell("monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1", check=False)
    time.sleep(2.5)


def open_settings() -> None:
    if not find(dump(), "模型服务", exact=False) and not find(dump(), "Model service", exact=False):
        tap_text("去设置")
    # After a cold start the first tap can be swallowed by the launch animation;
    # wait for the actual settings content instead of a fixed delay.
    wait_for_text("模型服务", timeout=15)


def type_api_key(key: str) -> None:
    # The password field is the second EditText on the settings page (the first
    # is Base URL, the third is the chat dock input).
    fields = [n for n in dump() if n.editable and n.bounds[1] > 500 and n.bounds[3] < 2000]
    if len(fields) < 2:
        raise RuntimeError(f"expected 2 settings text fields, got {len(fields)}")
    field = sorted(fields, key=lambda n: n.bounds[1])[1]
    tap(field)
    time.sleep(0.3)
    keyevent(123)  # MOVE_END
    shell("input", "keyevent", *(["67"] * 60))  # clear any previous key
    type_text(key)
    time.sleep(0.4)


def scroll_until(needle: str, attempts: int = 12) -> None:
    for _ in range(attempts):
        if find(dump(), needle, exact=False):
            return
        # With the soft keyboard open, the dock moves up to ~y=1400 and the
        # keyboard swallows gestures below it. Stay inside the content area.
        shell("input", "swipe", "540", "1300", "540", "500", "400")
        time.sleep(0.7)
    raise LookupError(f"could not scroll to {needle!r}; visible={[n.label() for n in dump() if n.label()][:30]}")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--key", default="e2etestkey2026")
    parser.add_argument("--skip-ui", action="store_true", help="only inject plain prefs (key already saved before)")
    args = parser.parse_args()
    base_url = f"http://10.0.2.2:{args.port}/v1"

    print(f"[e2e] injecting plain prefs: {base_url}")
    write_prefs(patch_prefs(read_prefs(), base_url))
    restart_app()

    if not args.skip_ui:
        open_settings()
        print(f"[e2e] typing API key via Settings UI")
        type_api_key(args.key)
        scroll_until("保存所有模型与选择")
        tap_text("保存所有模型与选择")
        wait_for_text("已保存", timeout=10)
        print("[e2e] saved")

    # Verify what actually landed on disk.
    final = read_prefs()
    ok = base_url in final and CHAT_MODEL in final and VISION_MODEL in final
    print(f"[e2e] plain prefs ok={ok} (baseUrl={base_url}, chat={CHAT_MODEL}, operator={VISION_MODEL})")
    secrets = shell("run-as", PKG, "ls", "-l", "shared_prefs/settings-secrets.xml", check=False)
    print(f"[e2e] secrets file: {secrets.strip()[:120]}")
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
