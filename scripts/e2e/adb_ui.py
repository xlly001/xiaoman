#!/usr/bin/env python3
"""Tiny adb + uiautomator helper for Xiaoman emulator E2E checks.

Compose UI has no resource ids in a uiautomator dump, so everything is located
by visible text / content-description and bounds. This module is intentionally
dependency-free and works on Windows, macOS and Linux.

CLI examples
    python scripts/e2e/adb_ui.py dump
    python scripts/e2e/adb_ui.py tap --text "获取可用模型"
    python scripts/e2e/adb_ui.py type --text "http://10.0.2.2:8765/v1"
    python scripts/e2e/adb_ui.py key --code 66
    python scripts/e2e/adb_ui.py wait --text "已保存"
    python scripts/e2e/adb_ui.py shot --out dist/e2e-chat.png
    python scripts/e2e/adb_ui.py a11y            # status
    python scripts/e2e/adb_ui.py a11y --on       # (re)bind Xiaoman's service

Android quirk
    `uiautomator dump` registers a temporary UiAutomationService and, when it
    finishes, resets BOTH accessibility secure settings to 0/null. That unbinds
    Xiaoman's own accessibility service, so a task started after any UI dump
    would otherwise fail with "service not connected". `dump()` therefore
    re-enables the service and waits for the system to rebind it. Set
    XIAOMAN_NO_A11Y_RESTORE=1 to disable the automatic restore.

Python usage
    from adb_ui import dump, tap_text, type_text, wait_for_text, screenshot, a11y_enable
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from typing import Iterable

DUMP_PATH = "/sdcard/xiaoman_window.xml"

A11Y_COMPONENT = "dev.xiaoman.app/dev.xiaoman.hands.XiaomanAccessibilityService"
AUTO_RESTORE_A11Y = os.environ.get("XIAOMAN_NO_A11Y_RESTORE") != "1"


def find_adb() -> str:
    for key in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(key)
        if root:
            candidate = os.path.join(root, "platform-tools", "adb.exe" if os.name == "nt" else "adb")
            if os.path.isfile(candidate):
                return candidate
    for candidate in (r"D:\Android\sdk\platform-tools\adb.exe", "/d/Android/sdk/platform-tools/adb"):
        if os.path.isfile(candidate):
            return candidate
    found = shutil.which("adb")
    if found:
        return found
    raise RuntimeError("adb not found; set ANDROID_HOME or add adb to PATH")


ADB = find_adb()


def run(*args: str, check: bool = True, timeout: int = 30) -> str:
    result = subprocess.run(
        [ADB, *args], capture_output=True, text=True, errors="replace", timeout=timeout
    )
    if check and result.returncode != 0:
        raise RuntimeError(f"adb {' '.join(args)} failed ({result.returncode}): {result.stderr.strip()}")
    return (result.stdout or "") + (result.stderr or "")


def shell(*args: str, check: bool = True) -> str:
    return run("shell", *args, check=check)


def serial() -> str:
    for line in run("devices").splitlines():
        if "\tdevice" in line:
            return line.split("\t")[0]
    raise RuntimeError("no connected adb device")


def a11y_enabled() -> bool:
    """True when the two secure settings still name Xiaoman's service."""
    on = shell("settings", "get", "secure", "accessibility_enabled", check=False).strip()
    services = shell("settings", "get", "secure", "enabled_accessibility_services", check=False).strip()
    return on == "1" and A11Y_COMPONENT in services


def a11y_bound() -> bool:
    """True when the system has actually bound an accessibility service."""
    state = run("shell", "dumpsys", "accessibility", check=False)
    match = re.search(r"Bound services:\{([^}]*)\}", state)
    return bool(match and "Service[" in match.group(1))


def a11y_enable(*, wait_bound: bool = True, timeout: float = 8.0) -> bool:
    """(Re)enable Xiaoman's accessibility service and wait for the rebind.

    `settings put` alone is not enough: AccessibilityManagerService binds
    asynchronously, and the app's `XiaomanAccessibilityService.instance`
    stays null until `onServiceConnected()` runs.
    """
    shell("settings", "put", "secure", "enabled_accessibility_services", A11Y_COMPONENT)
    shell("settings", "put", "secure", "accessibility_enabled", "1")
    if not wait_bound:
        return True
    deadline = time.time() + timeout
    while time.time() < deadline:
        if a11y_enabled() and a11y_bound():
            return True
        time.sleep(0.25)
    return False


@dataclass
class Node:
    text: str
    desc: str
    bounds: tuple[int, int, int, int]
    cls: str = ""
    clickable: bool = False
    editable: bool = False
    enabled: bool = True

    @property
    def center(self) -> tuple[int, int]:
        left, top, right, bottom = self.bounds
        return (left + right) // 2, (top + bottom) // 2

    def label(self) -> str:
        return self.text or self.desc


def _attr(fragment: str, name: str) -> str:
    match = re.search(rf'{name}="([^"]*)"', fragment)
    return match.group(1) if match else ""


def dump(check: bool = True, restore_a11y: bool | None = None) -> list[Node]:
    """Dump the current window and return its nodes in layout order.

    `uiautomator dump` wipes the accessibility secure settings on exit. When
    the service was enabled before the dump, re-enable it and wait for the
    system to rebind before returning, so a following task can actually run.
    """
    auto = AUTO_RESTORE_A11Y if restore_a11y is None else restore_a11y
    was_enabled = a11y_enabled() if auto else False
    shell("uiautomator", "dump", DUMP_PATH, check=check)
    xml = shell("cat", DUMP_PATH, check=check)
    nodes: list[Node] = []
    for match in re.finditer(r"<node\b[^>]*?/?>", xml):
        fragment = match.group(0)
        bounds = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", fragment)
        if not bounds:
            continue
        nodes.append(Node(
            text=_attr(fragment, "text"),
            desc=_attr(fragment, "content-desc"),
            bounds=tuple(int(bounds.group(i)) for i in range(1, 5)),  # type: ignore[arg-type]
            cls=_attr(fragment, "class"),
            clickable=_attr(fragment, "clickable") == "true",
            editable="EditText" in _attr(fragment, "class"),
            enabled=_attr(fragment, "enabled") != "false",
        ))
    if auto and was_enabled and not a11y_enabled():
        a11y_enable()
    return nodes


def find(nodes: Iterable[Node], needle: str, *, exact: bool = True, include_desc: bool = True) -> Node | None:
    for node in nodes:
        candidates = [node.text]
        if include_desc:
            candidates.append(node.desc)
        for candidate in candidates:
            if not candidate:
                continue
            if (candidate == needle) if exact else (needle in candidate):
                return node
    return None


def tap(node: Node | tuple[int, int]) -> None:
    x, y = node.center if isinstance(node, Node) else node
    shell("input", "tap", str(x), str(y))


def tap_text(needle: str, *, exact: bool = True, include_desc: bool = True, retries: int = 3) -> Node:
    for attempt in range(retries):
        nodes = dump()
        node = find(nodes, needle, exact=exact, include_desc=include_desc)
        if node:
            tap(node)
            return node
        time.sleep(0.6)
    raise LookupError(f"no UI node matching {needle!r}; nodes={[n.label() for n in dump() if n.label()][:40]}")


def type_text(text: str, *, commit: bool = False) -> None:
    # `input text` treats a space as an argument separator; %s is the escape.
    escaped = text.replace(" ", "%s").replace("&", "\\&").replace("|", "\\|")
    shell("input", "text", escaped)
    if commit:
        keyevent(66)


def keyevent(code: int) -> None:
    shell("input", "keyevent", str(code))


def screenshot(out_path: str) -> str:
    os.makedirs(os.path.dirname(out_path) or ".", exist_ok=True)
    with open(out_path, "wb") as fp:
        result = subprocess.run([ADB, "exec-out", "screencap", "-p"], capture_output=True, timeout=60)
        if result.returncode != 0 or not result.stdout:
            raise RuntimeError(f"screencap failed: {result.stderr!r}")
        fp.write(result.stdout)
    return out_path


def wait_for_text(needle: str, *, timeout: float = 15, exact: bool = False) -> Node:
    deadline = time.time() + timeout
    while time.time() < deadline:
        node = find(dump(), needle, exact=exact)
        if node:
            return node
        time.sleep(0.5)
    raise TimeoutError(f"text {needle!r} did not appear within {timeout}s; nodes={[n.label() for n in dump() if n.label()][:40]}")


def broadcast(action: str, *extras: str) -> str:
    args = ["am", "broadcast", "-n", "dev.xiaoman.app/.debug.DebugReceiver", "-a", action]
    for extra in extras:
        args.append(extra)
    return shell(*args)


def logcat_clear() -> None:
    run("logcat", "-c", check=False)


def logcat(tag: str = "Xiaoman", lines: int = 200) -> str:
    return run("logcat", "-d", "-t", str(lines), "-v", "threadtime", "-s", tag, check=False)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("dump")
    p_tap = sub.add_parser("tap"); p_tap.add_argument("--text", required=True); p_tap.add_argument("--contains", action="store_true")
    p_type = sub.add_parser("type"); p_type.add_argument("--text", required=True); p_type.add_argument("--commit", action="store_true")
    p_key = sub.add_parser("key"); p_key.add_argument("--code", type=int, required=True)
    p_wait = sub.add_parser("wait"); p_wait.add_argument("--text", required=True); p_wait.add_argument("--timeout", type=float, default=15)
    p_shot = sub.add_parser("shot"); p_shot.add_argument("--out", required=True)
    p_log = sub.add_parser("log"); p_log.add_argument("--lines", type=int, default=200)
    p_a11y = sub.add_parser("a11y"); p_a11y.add_argument("--on", action="store_true")
    args = parser.parse_args()

    if args.command == "dump":
        for node in dump():
            if node.label():
                print(f"{node.label()[:52]:<54} {node.bounds} clickable={node.clickable}")
    elif args.command == "tap":
        print("tapped", tap_text(args.text, exact=not args.contains).label())
    elif args.command == "type":
        type_text(args.text, commit=args.commit); print("typed", args.text)
    elif args.command == "key":
        keyevent(args.code); print("key", args.code)
    elif args.command == "wait":
        print("found", wait_for_text(args.text, timeout=args.timeout).label())
    elif args.command == "shot":
        print("saved", screenshot(args.out))
    elif args.command == "log":
        print(logcat(lines=args.lines))
    elif args.command == "a11y":
        if args.on:
            print("a11y rebind =", a11y_enable())
        else:
            print("a11y enabled =", a11y_enabled(), "bound =", a11y_bound())
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
