#!/usr/bin/env python3
"""Generate a large, clean synthetic statement for the Summary overflow demo."""

from __future__ import annotations

import argparse
from datetime import date, timedelta
from pathlib import Path

OPERATIONS = [
    ("TX-001", "NOVA MARKET", -125000, "food.groceries"),
    ("TX-002", "NOVA CAFE", -49000, "food.cafe"),
    ("TX-003", "RIVER PAY", -129900, "services.digital"),
    ("TX-004", "CITY RIDE", -185000, "transport"),
    ("TX-005", "MEDI-CORNER", -178050, "health.pharmacy"),
    ("TX-006", "NOVA MARKET", -83500, "food.groceries"),
    ("TX-007", "GREEN MARKET", -125050, "food.groceries"),
    ("TX-008", "STREAM BOX", -79900, "services.digital"),
    ("TX-009", "RIVER PAY", 65000, None),
    ("TX-010", "TRANSFER OWN", 1000000, "transfer.internal"),
    ("TX-011", "SALARY TEST", 8500000, "income.salary"),
    ("TX-012", "CITY RIDE", -92000, "transport"),
]

DETAILS = (
    "название позиции {position:03d}; количество {quantity}; цена {price} RUB; "
    "магазин {merchant}; касса {cashier:02d}; чек {receipt:08d}; "
    "дата покупки {day}; способ оплаты карта **** {card}; статус проведено"
)


def build_statement(target_chars: int, operation_count: int, detail_lines: int) -> str:
    rows = [
        "СИНТЕТИЧЕСКАЯ БАНКОВСКАЯ ВЫПИСКА",
        "Период: 2026-03-01 — 2026-03-31",
        "В каждой секции ОПЕРАЦИЯ указана одна агрегированная банковская операция.",
        "Строки ДЕТАЛЬ являются позициями чека и не являются отдельными операциями.",
        "Извлеки только операции из заголовков секций; сохрани порядок и суммы.",
        "",
    ]

    detail_index = 0
    for operation_index, (operation_id, merchant, amount_minor, category) in enumerate(
        OPERATIONS[:operation_count]
    ):
        occurred_at = date(2026, 3, 1) + timedelta(days=operation_index * 2)
        direction = "income" if amount_minor >= 0 else "expense"
        category_text = category or "не указана; требуется ручная проверка"
        rows.extend(
            [
                f"ОПЕРАЦИЯ {operation_id}",
                f"Дата операции: {occurred_at.isoformat()} 09:{operation_index:02d}",
                f"Merchant: {merchant}",
                f"Сумма: {amount_minor / 100:.2f} RUB ({direction})",
                f"Категория источника: {category_text}",
                f"Карта: **** {4404 + operation_index}",
                f"Статус: проведено; идентификатор операции: {operation_id}",
                "ДЕТАЛИЗАЦИЯ ЧЕКА (не отдельные операции):",
            ]
        )
        for line_index in range(detail_lines):
            detail_index += 1
            rows.append(
                "ДЕТАЛЬ "
                + DETAILS.format(
                    position=line_index + 1,
                    quantity=(line_index % 3) + 1,
                    price=100 + (line_index * 17) % 9000,
                    merchant=merchant,
                    cashier=(operation_index % 8) + 1,
                    receipt=detail_index,
                    day=occurred_at.isoformat(),
                    card=4404 + operation_index,
                )
            )
        rows.append("")

    text = "\n".join(rows) + "\n"
    if len(text) < target_chars:
        raise ValueError(
            f"generated statement has {len(text)} chars; reduce --target-chars or "
            "increase --operation-count or --detail-lines"
        )
    return text[:target_chars]


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--target-chars", type=int, default=120_000)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--operation-count", type=int, default=len(OPERATIONS))
    parser.add_argument("--detail-lines", type=int, default=100)
    args = parser.parse_args()

    text = build_statement(args.target_chars, args.operation_count, args.detail_lines)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(text, encoding="utf-8")
    print(f"wrote {len(text)} chars to {args.output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
