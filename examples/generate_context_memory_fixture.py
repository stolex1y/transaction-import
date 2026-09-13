#!/usr/bin/env python3
"""Generate the clean multi-row fixture used by the context-strategy video."""

from __future__ import annotations

import argparse
from datetime import date, timedelta
from pathlib import Path

OPERATIONS = [
    ("NOVA MARKET", "продукты и молоко", -125000, "food.groceries"),
    ("NOVA CAFE", "кофе и выпечка", -49000, "food.cafe"),
    ("RIVER PAY", "оплата по QR", -129900, "services.digital"),
    ("CITY RIDE", "поездка завершена", -18500, "transport"),
    ("MEDI-CORNER", "витамины", -178050, "health.pharmacy"),
    ("GREEN MARKET", "овощи и фрукты", -125050, "food.groceries"),
    ("STREAM BOX", "подписка за месяц", -79900, "services.digital"),
    ("NORTH CAFE", "завтрак", -72000, "food.cafe"),
    ("CITY RIDE", "поездка завершена", -9200, "transport"),
    ("RIVER PAY", "возврат части заказа", 65000, None),
    ("TRANSFER OWN", "между своими счетами", 1000000, "transfer.internal"),
    ("SALARY TEST", "заработная плата", 8500000, "income.salary"),
    ("NOVA MARKET", "продукты", -236700, "food.groceries"),
    ("CITY RIDE", "поездка завершена", -11700, "transport"),
    ("BOOK HOUSE", "книги", -149900, None),
    ("HOME NET", "домашний интернет", -89000, "services.digital"),
    ("RIVER PAY", "оплата заказа", -204500, None),
    ("MEDI-CORNER", "аптечные товары", -63000, "health.pharmacy"),
    ("NORTH CAFE", "обед", -118000, "food.cafe"),
    ("GREEN MARKET", "продукты", -217800, "food.groceries"),
    ("CITY RIDE", "поездка завершена", -16400, "transport"),
    ("STREAM BOX", "подписка за месяц", -79900, "services.digital"),
    ("NOVA MARKET", "продукты", -187600, "food.groceries"),
    ("BOOK HOUSE", "канцелярия", -58300, None),
    ("TRANSFER OWN", "между своими счетами", -700000, "transfer.internal"),
    ("RIVER PAY", "возврат заказа", 43000, None),
    ("MEDI-CORNER", "лекарства", -245000, "health.pharmacy"),
    ("NORTH CAFE", "кофе", -54000, "food.cafe"),
    ("CITY RIDE", "поездка завершена", -9800, "transport"),
    ("GREEN MARKET", "продукты", -142300, "food.groceries"),
    ("HOME NET", "домашний интернет", -89000, "services.digital"),
    ("SALARY TEST", "аванс", 4200000, "income.salary"),
]


def build_fixture() -> str:
    rows = [
        "СИНТЕТИЧЕСКАЯ БАНКОВСКАЯ ВЫПИСКА",
        "Период: 2026-03-01 — 2026-03-31",
        "Источник: synthetic household account export",
        "Каждая строка ниже — одна банковская операция.",
        "",
        "transaction_id | occurred_at | posted_at | merchant | description | amount | currency | card_last4 | channel | status | source_category | merchant_country | mcc | authorization_code | terminal_id | counterparty | balance_after | payment_purpose | provider_reference | cashback_minor",
    ]
    start = date(2026, 3, 1)
    for index, (merchant, description, amount_minor, category) in enumerate(OPERATIONS, start=1):
        occurred_at = start + timedelta(days=(index - 1) % 28)
        posted_at = occurred_at + timedelta(days=1)
        rows.append(
            " | ".join(
                [
                    f"TX-{index:03d}",
                    f"{occurred_at.isoformat()} {8 + index % 10:02d}:{(index * 7) % 60:02d}",
                    posted_at.isoformat(),
                    merchant,
                    description,
                    f"{amount_minor / 100:.2f}",
                    "RUB",
                    f"{4404 + index % 6:04d}",
                    "card" if index % 3 else "qr",
                    "posted",
                    category or "unknown",
                    "RU",
                    f"{5300 + index % 8}",
                    f"AUTH-{index:08d}",
                    f"TERM-{100 + index % 17:03d}",
                    f"counterparty-{index % 9:02d}",
                    f"{5_000_000 + amount_minor - index * 317 / 100:.2f}",
                    f"household; {description}; monthly export",
                    f"PROVIDER-202603-{index:06d}",
                    f"{max(0, abs(amount_minor) // 1000)}",
                ]
            )
        )
    return "\n".join(rows) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    text = build_fixture()
    args.output.write_text(text, encoding="utf-8")
    print(f"wrote {len(text)} chars and {len(OPERATIONS)} operations to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
