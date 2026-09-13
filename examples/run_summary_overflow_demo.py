#!/usr/bin/env python3
"""Run the same long statement through full-history and Summary instances."""

from __future__ import annotations

import argparse
import json
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any

WARMUP_PROMPTS = [
    "Подтверди получение выписки. Черновик не меняй.",
    "Проверь, что текущий черновик содержит операции из исходной выписки. Ничего не меняй.",
    "Сохрани текущий черновик без изменений и подготовься к следующей строке выписки.",
]
FINAL_APPEND = """Дополни выписку одной новой операцией:
2026-04-01 09:30 | ACME 24 | -799,00 RUB | карта **** 4404 | назначение отсутствует.
Добавь её в черновик, не меняя остальные операции."""


def request(base_url: str, path: str, payload: dict[str, Any] | None = None) -> tuple[int, Any]:
    body = None
    headers: dict[str, str] = {}
    method = "GET"
    if payload is not None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
        method = "POST"
    req = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=body,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(req, timeout=300) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        raw = error.read().decode("utf-8", errors="replace")
        try:
            return error.code, json.loads(raw)
        except json.JSONDecodeError:
            return error.code, raw


def report(label: str, status: int, body: Any) -> None:
    if not isinstance(body, dict):
        print(json.dumps({"step": label, "http_status": status, "body": body}, ensure_ascii=False))
        return
    session = body.get("session") or {}
    metrics = body.get("metrics") or []
    metric = metrics[-1] if metrics else {}
    print(
        json.dumps(
            {
                "step": label,
                "http_status": status,
                "revision": session.get("revision"),
                "message_count": len(body.get("messages") or []),
                "summary": body.get("summary"),
                "last_error": body.get("last_error"),
                "metric_status": metric.get("status"),
                "metric_call_type": metric.get("call_type"),
                "prompt_tokens": metric.get("prompt_tokens"),
                "context_window_tokens": metric.get("context_window_tokens"),
            },
            ensure_ascii=False,
        )
    )


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:18186")
    parser.add_argument(
        "--fixture",
        type=Path,
        default=Path(".gradle/demo/demo-context-memory.txt"),
    )
    args = parser.parse_args()

    fixture = args.fixture.read_text(encoding="utf-8")
    status, state = request(args.base_url, "/api/agent/sessions", {})
    report("create-session", status, state)
    if status != 201 or not isinstance(state, dict):
        return 1

    session = state.get("session") or {}
    session_id = session.get("id")
    revision = session.get("revision", 0)
    if not session_id:
        return 1

    status, state = request(
        args.base_url,
        f"/api/agent/sessions/{session_id}/messages",
        {"revision": revision, "text": fixture},
    )
    report("large-initial-statement", status, state)
    if status != 200 or not isinstance(state, dict) or state.get("last_error"):
        return 1

    for index, prompt in enumerate(WARMUP_PROMPTS, start=1):
        revision = (state.get("session") or {}).get("revision", revision)
        status, state = request(
            args.base_url,
            f"/api/agent/sessions/{session_id}/messages",
            {"revision": revision, "text": prompt},
        )
        report(f"warmup-{index}", status, state)
        if status != 200 or not isinstance(state, dict) or state.get("last_error"):
            return 0 if isinstance(state, dict) and state.get("last_error") else 1

    revision = (state.get("session") or {}).get("revision", revision)
    status, state = request(
        args.base_url,
        f"/api/agent/sessions/{session_id}/messages",
        {"revision": revision, "text": FINAL_APPEND},
    )
    report("fifth-message-overflow-or-summary", status, state)
    return 0


if __name__ == "__main__":
    sys.exit(main())
