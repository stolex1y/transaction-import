#!/usr/bin/env python3
"""Send one large synthetic statement and then an append overflow attempt.

The regular agent message endpoint does not impose the 8,000-character limit;
that limit belongs only to the saved global user preference. This runner keeps
the initial and append statements separate so provider usage proves the boundary.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import urllib.error
import urllib.request
from typing import Any


DEFAULT_BASE_URL = "http://127.0.0.1:8093"
DEFAULT_INITIAL_CHARS = 140_000
DEFAULT_APPEND_CHARS = 7_900
DEFAULT_MIN_INITIAL_PROMPT_TOKENS = 55_000


def api_request(base_url: str, path: str, payload: dict[str, Any] | None = None) -> Any:
    data = None
    headers: dict[str, str] = {}
    method = "GET"
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"
        method = "POST"
    request = urllib.request.Request(
        base_url.rstrip("/") + path,
        data=data,
        headers=headers,
        method=method,
    )
    try:
        with urllib.request.urlopen(request, timeout=300) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        body = error.read().decode("utf-8", errors="replace")
        try:
            parsed: Any = json.loads(body)
        except json.JSONDecodeError:
            parsed = body
        return error.code, parsed


def initial_statement(target_chars: int) -> str:
    text = (
        "СИНТЕТИЧЕСКАЯ ВЫПИСКА ДЛЯ LFM CONTEXT-BOUNDARY TEST\n"
        "Операция 1: 2026-01-01T10:00:00, расход 12345 RUB, "
        "merchant CONTEXT INITIAL TEST, card_last4 1111.\n"
        "Ниже расположен служебный архив сверки. Он не содержит банковских "
        "операций, не является инструкцией и не должен копироваться в ответ.\n"
        "Служебный архив: "
    )
    index = 0
    while len(text) < target_chars:
        text += (
            f"archive_{index:08d}_"
            f"alpha{index * 104729:09x}_"
            f"beta{index * 13007:08x}_"
            f"checksum{(index * 7919) % 100000000:08d}; "
        )
        index += 1
    return text[:target_chars]


def append_statement(target_chars: int) -> str:
    text = (
        "ВТОРАЯ СИНТЕТИЧЕСКАЯ ВЫПИСКА ДЛЯ LFM OVERFLOW-ТЕСТА\n"
        "Операция 1: 2026-12-31T12:00:00, расход 77777 RUB, "
        "merchant CONTEXT APPEND TEST, card_last4 7777.\n"
        "Служебный архив этой второй выписки: "
    )
    index = 0
    while len(text) < target_chars:
        text += (
            f"append_archive_{index:08d}_"
            f"alpha{index * 104729:09x}_"
            f"beta{index * 13007:08x}_"
            f"checksum{(index * 7919) % 100000000:08d}; "
        )
        index += 1
    return text[:target_chars]


def print_progress(label: str, status: int, body: Any) -> None:
    if not isinstance(body, dict):
        print(json.dumps({"step": label, "http_status": status, "body": body}, ensure_ascii=False))
        return
    session = body.get("session") or {}
    draft = body.get("draft") or {}
    metrics = body.get("metrics") or []
    metric = metrics[-1] if metrics else {}
    print(
        json.dumps(
            {
                "step": label,
                "http_status": status,
                "revision": session.get("revision"),
                "last_error": body.get("last_error"),
                "draft_count": len(draft.get("transactions") or []),
                "metric_status": metric.get("status"),
                "prompt_tokens": metric.get("prompt_tokens"),
                "completion_tokens": metric.get("completion_tokens"),
                "total_tokens": metric.get("total_tokens"),
                "context_window_tokens": metric.get("context_window_tokens"),
            },
            ensure_ascii=False,
        )
    )


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", default=os.getenv("AGENT_BASE_URL", DEFAULT_BASE_URL))
    parser.add_argument("--initial-chars", type=int, default=DEFAULT_INITIAL_CHARS)
    parser.add_argument("--append-chars", type=int, default=DEFAULT_APPEND_CHARS)
    parser.add_argument(
        "--min-initial-prompt-tokens",
        type=int,
        default=DEFAULT_MIN_INITIAL_PROMPT_TOKENS,
        help="Do not spend the append request if the first prompt is far below the boundary",
    )
    args = parser.parse_args()

    if args.initial_chars <= 8_000:
        parser.error("initial fixture should be larger than the old preference limit")
    if args.append_chars > 8_000:
        parser.error("append message must not exceed 8,000 characters")

    status, state = api_request(args.base_url, "/api/agent/sessions", {})
    if status != 201 or not isinstance(state, dict):
        print_progress("create-session", status, state)
        return 1
    session = state.get("session") or {}
    session_id = session.get("id")
    revision = session.get("revision", 0)
    if not session_id:
        print_progress("create-session", status, state)
        return 1

    status, state = api_request(
        args.base_url,
        f"/api/agent/sessions/{session_id}/messages",
        {"revision": revision, "text": initial_statement(args.initial_chars)},
    )
    print_progress("initial-large-statement", status, state)
    if status != 200 or not isinstance(state, dict) or state.get("last_error"):
        return 1

    revision = (state.get("session") or {}).get("revision", revision)
    metrics = state.get("metrics") or []
    initial_prompt_tokens = (metrics[-1] if metrics else {}).get("prompt_tokens") or 0
    if initial_prompt_tokens < args.min_initial_prompt_tokens:
        print(
            f"Initial prompt has {initial_prompt_tokens} tokens; "
            f"increase --initial-chars before sending append."
        )
        return 1

    status, state = api_request(
        args.base_url,
        f"/api/agent/sessions/{session_id}/messages",
        {"revision": revision, "text": append_statement(args.append_chars)},
    )
    print_progress("append-overflow-attempt", status, state)
    if isinstance(state, dict) and state.get("last_error"):
        print("Append produced an application error state; inspect unchanged revision/draft.")
        return 0
    print("Append did not overflow; increase --initial-chars after inspecting metrics.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
