#!/usr/bin/env python3
"""Локальный OpenAI-compatible provider только для демонстрации overflow."""

from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os

HOST = "127.0.0.1"
PORT = int(os.environ.get("DEMO_PROVIDER_PORT", "8091"))
OVERFLOW_CHARACTER_LIMIT = 12_000

INITIAL_RESPONSE = {
    "status": "ready",
    "rejection_reason": None,
    "transactions": [
        {
            "source_index": 1,
            "included": True,
            "direction": "expense",
            "occurred_at": "2026-02-15T09:02:00",
            "posted_at": None,
            "amount_minor": 100,
            "currency": "RUB",
            "merchant": "DEMO FAKE",
            "category_id": "food.groceries",
            "card_last4": None,
            "needs_review": False,
            "issues": [],
        }
    ],
    "unparsed_fragments": [],
}

FOLLOW_UP_RESPONSE = {
    "intent": "correction",
    "message": "Изменений не найдено.",
    "operations": [],
    "transactions": [],
}


class Handler(BaseHTTPRequestHandler):
    def do_POST(self):  # noqa: N802 - required by BaseHTTPRequestHandler
        length = int(self.headers.get("Content-Length", "0"))
        request = json.loads(self.rfile.read(length))
        messages = request.get("messages", [])
        prompt_chars = sum(len(str(message.get("content", ""))) for message in messages)

        if prompt_chars > OVERFLOW_CHARACTER_LIMIT:
            self.respond(
                400,
                {
                    "error": {
                        "message": "context length exceeded by deterministic demo provider"
                    }
                },
            )
            return

        content = FOLLOW_UP_RESPONSE if any(
            message.get("role") == "assistant" for message in messages
        ) else INITIAL_RESPONSE
        prompt_tokens = max(1, prompt_chars // 4)
        completion_tokens = 24
        self.respond(
            200,
            {
                "choices": [
                    {
                        "message": {
                            "role": "assistant",
                            "content": json.dumps(content, ensure_ascii=False),
                        },
                        "finish_reason": "stop",
                    }
                ],
                "usage": {
                    "prompt_tokens": prompt_tokens,
                    "completion_tokens": completion_tokens,
                    "total_tokens": prompt_tokens + completion_tokens,
                },
            },
        )

    def respond(self, status, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, format, *args):
        print("DEMO_PROVIDER", format % args, flush=True)


if __name__ == "__main__":
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(
        f"DEMO_PROVIDER_READY http://{HOST}:{PORT}/chat/completions "
        f"threshold_chars={OVERFLOW_CHARACTER_LIMIT}",
        flush=True,
    )
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
