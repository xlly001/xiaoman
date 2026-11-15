#!/usr/bin/env python3
"""Switch how the app routes its built-in ASR / TTS / OCR engines, for E2E checks.

The DeepSeek tools endpoint is derived from ``chat.baseUrl`` by
``DeepSeekToolsClient.toolsRoot``; when the app is pointed at the local fake
server that becomes ``http://10.0.2.2:8765/v1/tools``.

    python scripts/e2e/setup_media.py --profile model      # chat.model = deepseek-flash
    python scripts/e2e/setup_media.py --profile provider    # provider.id = deepseek
    python scripts/e2e/setup_media.py --profile system      # neither -> platform fallback
    python scripts/e2e/setup_media.py --profile forced      # force DeepSeek despite a non-DeepSeek model

Run setup_model.py first so the base URL and API key are already configured.
"""

from __future__ import annotations

import argparse
import xml.etree.ElementTree as ET

import setup_model as base

PROFILES = ("model", "provider", "system", "forced")


def patch(xml: str, profile: str) -> bytes:
    root = ET.fromstring(xml)

    def put_string(name: str, value: str) -> None:
        element = root.find(f".//*[@name='{name}']")
        if element is None:
            element = ET.SubElement(root, "string", {"name": name})
        element.tag = "string"
        element.attrib = {"name": name}
        element.text = value

    if profile == "model":
        # Only the model name says "DeepSeek"; provider stays custom.
        put_string("chat.model", "deepseek-flash")
        put_string("provider.id", "custom")
        put_string("media.engineMode", "AUTO")
    elif profile == "provider":
        put_string("chat.model", "fake-chat-v1")
        put_string("provider.id", "deepseek")
        put_string("media.engineMode", "AUTO")
    elif profile == "system":
        put_string("chat.model", "fake-chat-v1")
        put_string("provider.id", "custom")
        put_string("media.engineMode", "AUTO")
    elif profile == "forced":
        put_string("chat.model", "fake-chat-v1")
        put_string("provider.id", "custom")
        put_string("media.engineMode", "DEEPSEEK")
    else:
        raise ValueError(profile)

    put_string("media.toolsBaseUrl", "")
    put_string("media.ttsVoice", "TIDE")
    put_string("media.ocrPurpose", "RICH_LAYOUT")
    return ET.tostring(root, encoding="utf-8", xml_declaration=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--profile", choices=PROFILES, default="model")
    parser.add_argument("--no-restart", action="store_true")
    args = parser.parse_args()

    base.write_prefs(patch(base.read_prefs(), args.profile))
    if not args.no_restart:
        base.restart_app()
    final = base.read_prefs()
    print(f"[e2e] media profile={args.profile}")
    for key in ("chat.model", "provider.id", "media.engineMode", "media.ttsVoice", "media.ocrPurpose"):
        marker = f'name="{key}"'
        print(f"  {key}: {'set' if marker in final else 'MISSING'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
