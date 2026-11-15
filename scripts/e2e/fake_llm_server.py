#!/usr/bin/env python3
"""A deterministic, OpenAI-compatible fake LLM server for Xiaoman emulator E2E checks.

The Android emulator reaches the host through ``http://10.0.2.2:<port>/v1``.
The server needs nothing but the Python standard library, never calls a real
model, and lets a test script change its behaviour over HTTP while it is running.

    python scripts/e2e/fake_llm_server.py --port 8765

Endpoints
    GET  /v1/models            model list (first chat model + first vision model)
    POST /v1/chat/completions  SSE stream or a single JSON response
    GET  /control              current behaviour
    POST /control              update behaviour with a JSON object
    POST /control/reset        restore defaults and clear the request log
    GET  /log                  recent requests (JSON)
    GET  /health               liveness probe

Control fields (all optional, POST /control merges them)
    reply                 default assistant text
    chunks                explicit list of SSE text chunks (overrides reply)
    chunk_delay_ms        delay between chunks (default 180)
    first_token_delay_ms  delay before the first chunk (default 120)
    response_delay_ms     delay before ANY chat response, including non-streaming
                          operator calls (default 0). Use it to keep a task busy
                          long enough to observe the queue and the pause control.
    tool_calls            list of {name, arguments} to emit as function calls
    operator_action       action object for the operator model
    operator_thought      thought string for the operator model
    error_code            HTTP status to return instead of 200 (0 disables)
    error_body            body for error_code responses
    finish_reason         default "stop"
    usage                 dict merged into the usage object
    force_role            "chat" | "operator" (skip auto detection)

The request log is written as UTF-8 JSONL when --log-file is given. API keys are
redacted before anything is logged.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import threading
import time
from collections import deque
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any
from urllib.parse import urlparse

MODELS = [
    {"id": "fake-chat-v1", "object": "model", "owned_by": "xiaoman-e2e",
     "context_length": 32768, "max_completion_tokens": 8192},
    {"id": "fake-vision-v1", "object": "model", "owned_by": "xiaoman-e2e",
     "context_length": 32768, "max_completion_tokens": 8192},
]

DEFAULT_OPERATOR_ACTION = {
    "type": "finish",
    "success": True,
    "summary": "E2E: operator reached the goal",
}

DEFAULTS: dict[str, Any] = {
    "reply": "你好，我是小满的假模型。这条回复通过 SSE 分块送达，用来验收增量渲染。",
    "chunks": None,
    "chunk_delay_ms": 180,
    "first_token_delay_ms": 120,
    "response_delay_ms": 0,
    "tool_calls": None,
    "operator_action": dict(DEFAULT_OPERATOR_ACTION),
    "operator_thought": "E2E: no real UI action needed",
    "error_code": 0,
    "error_body": '{"error":{"message":"E2E injected failure","type":"e2e_error"}}',
    "finish_reason": "stop",
    "usage": {"prompt_tokens": 12, "completion_tokens": 24, "total_tokens": 36},
    "force_role": "",
    "force_tool_calls": False,
    # Keep the socket open after `data: [DONE]` to reproduce providers that do
    # not close the SSE connection. A client that waits for EOF will hang here.
    "hold_after_done_ms": 0,
}

STATE: dict[str, Any] = dict(DEFAULTS)
LOCK = threading.Lock()
LOG: deque[dict[str, Any]] = deque(maxlen=500)
LOG_FILE: str | None = None


def configure(patch: dict[str, Any]) -> dict[str, Any]:
    with LOCK:
        for key, value in patch.items():
            if key not in DEFAULTS:
                raise KeyError(key)
            STATE[key] = value
        return dict(STATE)


def snapshot() -> dict[str, Any]:
    with LOCK:
        return dict(STATE)


def record(entry: dict[str, Any]) -> None:
    with LOCK:
        LOG.append(entry)
        if LOG_FILE:
            try:
                os.makedirs(os.path.dirname(LOG_FILE) or ".", exist_ok=True)
                with open(LOG_FILE, "a", encoding="utf-8") as fp:
                    fp.write(json.dumps(entry, ensure_ascii=False) + "\n")
            except OSError as exc:  # never let logging break a response
                print(f"[fake-llm] log write failed: {exc}", file=sys.stderr)


def last_user_text(messages: list[dict[str, Any]]) -> str:
    for message in reversed(messages or []):
        if message.get("role") != "user":
            continue
        content = message.get("content")
        if isinstance(content, str):
            return content
        if isinstance(content, list):
            parts = [p.get("text", "") for p in content if isinstance(p, dict) and p.get("type") == "text"]
            if parts:
                return "\n".join(parts)
    return ""


def detect_role(body: dict[str, Any], forced: str) -> str:
    if forced in ("chat", "operator"):
        return forced
    model = str(body.get("model", "")).lower()
    response_format = body.get("response_format")
    if "vision" in model or "operator" in model:
        return "operator"
    if isinstance(response_format, dict) and response_format.get("type") == "json_object":
        return "operator"
    text = last_user_text(body.get("messages") or [])
    if "## 目标" in text or "请给出下一步操作" in text or "节点列表" in text:
        return "operator"
    return "chat"


def chunk_text(text: str, chunks: list[str] | None) -> list[str]:
    if chunks:
        return [str(c) for c in chunks]
    if not text:
        return []
    # Split by characters but keep short multi-character pieces so that the UI
    # visibly grows several times; 2 characters per chunk mirrors a real model.
    return [text[i:i + 2] for i in range(0, len(text), 2)]


def tool_call_objects(calls: list[dict[str, Any]]) -> list[dict[str, Any]]:
    return [
        {
            "id": f"call_e2e_{i}",
            "type": "function",
            "function": {
                "name": c.get("name", "phone_task"),
                "arguments": c.get("arguments")
                if isinstance(c.get("arguments"), str)
                else json.dumps(c.get("arguments", {}), ensure_ascii=False),
            },
        }
        for i, c in enumerate(calls)
    ]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"
    server_version = "XiaomanFakeLLM/1.0"

    def log_message(self, fmt: str, *args: Any) -> None:
        # Keep stdout quiet; /log is the structured source of truth.
        pass

    def handle(self) -> None:
        try:
            super().handle()
        except (ConnectionError, OSError):
            # Stopping a task makes the app cancel its in-flight request, so the
            # socket can disappear while we are writing the reply. Expected: do
            # not let socketserver dump a traceback for a normal Stop.
            pass

    # ------------------------------------------------------------------ helpers
    def _json(self, status: int, payload: Any, extra_headers: dict[str, str] | None = None) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        for key, value in (extra_headers or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(raw)
        self.wfile.flush()

    def _read_body(self) -> bytes:
        length = int(self.headers.get("Content-Length", "0") or "0")
        return self.rfile.read(length) if length else b""

    def _entry(self, path: str, body: dict[str, Any] | None) -> dict[str, Any]:
        return {
            "ts": round(time.time(), 3),
            "method": self.command,
            "path": path,
            "model": (body or {}).get("model"),
            "stream": bool((body or {}).get("stream")),
            "user_text": last_user_text((body or {}).get("messages") or [])[:400],
            "messages": len((body or {}).get("messages") or []),
            "has_tools": bool((body or {}).get("tools")),
            "has_images": any(
                isinstance(m.get("content"), list)
                for m in ((body or {}).get("messages") or [])
                if isinstance(m, dict)
            ),
            "authorization": "Bearer ***" if self.headers.get("Authorization") else None,
        }

    # ------------------------------------------------------------------- routes
    def do_GET(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler API)
        path = urlparse(self.path).path
        if path == "/health":
            return self._json(200, {"ok": True, "service": "xiaoman-fake-llm"})
        if path in ("/v1/models", "/models"):
            record(self._entry(path, None))
            return self._json(200, {"object": "list", "data": MODELS})
        if path == "/control":
            return self._json(200, snapshot())
        if path == "/log":
            with LOCK:
                entries = list(LOG)
            return self._json(200, {"count": len(entries), "requests": entries})
        return self._json(404, {"error": {"message": "not found", "path": path}})

    def do_POST(self) -> None:  # noqa: N802 (BaseHTTPRequestHandler API)
        path = urlparse(self.path).path
        raw = self._read_body()
        try:
            body = json.loads(raw.decode("utf-8") or "{}")
        except (ValueError, UnicodeDecodeError):
            body = {}

        if path in ("/control/reset",):
            with LOCK:
                STATE.clear()
                STATE.update(json.loads(json.dumps(DEFAULTS)))
                LOG.clear()
            return self._json(200, {"ok": True, "state": snapshot()})

        if path == "/control":
            try:
                return self._json(200, {"ok": True, "state": configure(body)})
            except KeyError as exc:
                return self._json(400, {"error": {"message": f"unknown control field {exc}"}})

        if path in ("/v1/chat/completions", "/chat/completions"):
            return self._chat(path, body)

        return self._json(404, {"error": {"message": "not found", "path": path}})

    # --------------------------------------------------------------- chat logic
    def _chat(self, path: str, body: dict[str, Any]) -> None:
        cfg = snapshot()
        entry = self._entry(path, body)
        entry["role"] = detect_role(body, str(cfg["force_role"] or ""))
        entry["reply_chunks"] = None
        record(entry)

        # A slow operator response keeps the single execution slot busy, which
        # makes queued/paused/resumed states observable from the UI. ThreadingHTTPServer
        # gives each request its own thread, so this never blocks /control.
        delay = max(0, int(cfg["response_delay_ms"] or 0)) / 1000.0
        if delay:
            time.sleep(delay)

        error_code = int(cfg["error_code"] or 0)
        if error_code:
            headers = {"Retry-After": "1"} if error_code in (429, 503) else {}
            raw = str(cfg["error_body"]).encode("utf-8")
            self.send_response(error_code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(raw)))
            for key, value in headers.items():
                self.send_header(key, value)
            self.end_headers()
            self.wfile.write(raw)
            self.wfile.flush()
            return

        calls = cfg["tool_calls"]
        want_tools = bool(calls) and (
            bool(cfg["force_tool_calls"]) or bool(body.get("tools"))
        )
        usage = dict(cfg["usage"] or {})
        finish_reason = cfg["finish_reason"]
        if want_tools:
            finish_reason = "tool_calls"

        if entry["role"] == "operator":
            content = json.dumps(
                {"thought": cfg["operator_thought"], "action": cfg["operator_action"]},
                ensure_ascii=False,
            )
            return self._respond(body, content, [], cfg, usage, finish_reason, entry)

        if want_tools:
            return self._respond(body, "", tool_call_objects(calls), cfg, usage, finish_reason, entry)

        return self._respond(body, str(cfg["reply"]), [], cfg, usage, finish_reason, entry)

    def _respond(
        self,
        body: dict[str, Any],
        content: str,
        calls: list[dict[str, Any]],
        cfg: dict[str, Any],
        usage: dict[str, Any],
        finish_reason: str,
        entry: dict[str, Any],
    ) -> None:
        if body.get("stream"):
            self._stream(content, calls, cfg, usage, finish_reason, entry)
        else:
            message: dict[str, Any] = {"role": "assistant", "content": content or None}
            if calls:
                message["tool_calls"] = calls
            self._json(200, {
                "id": "chatcmpl-e2e",
                "object": "chat.completion",
                "created": int(time.time()),
                "model": body.get("model", "fake-chat-v1"),
                "choices": [{"index": 0, "message": message, "finish_reason": finish_reason}],
                "usage": usage,
            })

    def _stream(
        self,
        content: str,
        calls: list[dict[str, Any]],
        cfg: dict[str, Any],
        usage: dict[str, Any],
        finish_reason: str,
        entry: dict[str, Any],
    ) -> None:
        pieces = chunk_text(content, cfg["chunks"])
        entry["reply_chunks"] = len(pieces)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream; charset=utf-8")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "close")
        self.send_header("X-Accel-Buffering", "no")
        self.end_headers()

        def emit(payload: dict[str, Any]) -> bool:
            try:
                line = "data: " + json.dumps(payload, ensure_ascii=False) + "\n\n"
                self.wfile.write(line.encode("utf-8"))
                self.wfile.flush()
                return True
            except (BrokenPipeError, ConnectionResetError, OSError):
                entry["sse_aborted"] = True
                return False

        try:
            first_delay = max(0, int(cfg["first_token_delay_ms"])) / 1000.0
            delay = max(0, int(cfg["chunk_delay_ms"])) / 1000.0
            if first_delay:
                time.sleep(first_delay)

            for piece in pieces:
                if not emit({"choices": [{"index": 0, "delta": {"content": piece}, "finish_reason": None}]}):
                    return
                if delay:
                    time.sleep(delay)

            # Stream tool calls in tiny argument fragments so the client's
            # index-based accumulation is exercised for real.
            for index, call in enumerate(calls):
                fn = call.get("function", {})
                args = fn.get("arguments", "{}")
                fragments = [args[i:i + 6] for i in range(0, len(args), 6)] or ["{}"]
                if not emit({"choices": [{"index": 0, "delta": {"tool_calls": [{
                    "index": index, "id": call.get("id"), "type": "function",
                    "function": {"name": fn.get("name"), "arguments": ""},
                }]}, "finish_reason": None}]}):
                    return
                for fragment in fragments:
                    if not emit({"choices": [{"index": 0, "delta": {"tool_calls": [{
                        "index": index, "function": {"arguments": fragment},
                    }]}, "finish_reason": None}]}):
                        return
                    if delay:
                        time.sleep(delay)

            emit({"choices": [{"index": 0, "delta": {}, "finish_reason": finish_reason}], "usage": usage})
            self.wfile.write(b"data: [DONE]\n\n")
            self.wfile.flush()
            entry["sse_done_sent"] = True
            hold = max(0, int(cfg["hold_after_done_ms"])) / 1000.0
            if hold:
                time.sleep(hold)
            entry["sse_complete"] = True
        finally:
            # OkHttp reads until EOF; closing the connection ends the SSE loop.
            self.close_connection = True


class Server(ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def main() -> int:
    global LOG_FILE
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--log-file", default="")
    args = parser.parse_args()
    LOG_FILE = args.log_file or None

    server = Server((args.host, args.port), Handler)
    print(f"[fake-llm] listening on http://{args.host}:{args.port} (emulator: http://10.0.2.2:{args.port})", flush=True)
    try:
        server.serve_forever(poll_interval=0.2)
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
