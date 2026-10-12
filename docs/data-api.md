# Данные и HTTP API

Документ отражает сериализованные модели и routes текущего исходного кода. Он не
содержит примеров банковских данных.

## Structured import schema

`core/StructuredImport.kt` объявляет сигнатуру `transaction-import.v3`.
Сериализуемый `StructuredImport` содержит:

| JSON-поле | Kotlin type | Значение |
|---|---|---|
| `status` | `ImportStatus` | `ready` или `not_applicable` |
| `rejection_reason` | `String?` | Причина отказа, если она есть |
| `transactions` | `List<StructuredTransaction>` | Структурированные операции |
| `unparsed_fragments` | `List<String>` | Части входного текста, которые не удалось разобрать |

`StructuredTransaction`:

| JSON-поле | Kotlin type | Значение |
|---|---|---|
| `source_index` | `Int` | Индекс исходной записи |
| `direction` | `TransactionDirection` | `income`, `expense` или `refund` |
| `occurred_at` | `String` | Дата/время операции |
| `posted_at` | `String?` | Необязательная дата проводки |
| `amount_minor` | `Long` | Целочисленная сумма; единица хранится в поле с суффиксом `_minor` |
| `currency` | `String` | Валюта операции |
| `merchant` | `String` | Название контрагента/назначение операции |
| `category_id` | `String?` | Необязательный ID категории |
| `needs_review` | `Boolean` | Требуется ли проверка операции |
| `issues` | `List<String>` | Найденные проблемы |
| `source_label` | `String?` | Необязательная метка источника |
| `items` | `List<TransactionItem>` | Необязательные позиции чека |
| `receipt_association` | `ReceiptAssociation?` | Необязательное состояние связи с чеком |
| `source_ref` | `String?` | Необязательная непрозрачная ссылка на источник |

`TransactionItem` содержит `name`, `quantity`, `price_minor`, `sum_minor` и
`currency` (по умолчанию `RUB`). `StructuredValidation` сообщает `valid`,
`importable`, `schema_signature`, `status`, `transaction_count`,
`rejection_reason` и `errors`.

Routes, возвращающие данные сессии или batch, в `AgentWeb.kt` применяют
`withoutOpaqueSourceRefs()` до ответа. Поэтому поле `source_ref` из внутренней
модели не следует считать публичным идентификатором банковской записи.

## Основные HTTP routes

Основной UI: `GET /agent`; прямой URL выбранной сессии:
`GET /agent/sessions/{sessionId}`.

| Метод и путь | Назначение по handler |
|---|---|
| `GET /api/agent/sessions` | Получить список сессий |
| `POST /api/agent/sessions` | Создать сессию с конфигурацией по умолчанию |
| `GET /api/agent/sessions/{id}` | Получить состояние сессии без opaque source refs |
| `POST /api/agent/sessions/{id}/messages` | Передать текст сообщения агенту вместе с ожидаемой ревизией |
| `GET /api/agent/sessions/{id}/batch` | Построить import batch без opaque source refs |

`WebApplication.kt` устанавливает JSON content negotiation и монтирует routes из
`AgentWeb.agentRoutes`. DTO этих handlers объявлены в web-модуле. Для точного
request/response shape сверяйтесь с `AgentWeb.kt`; эта краткая справка намеренно
не дублирует все DTO и не объявляет экспериментальные routes публичным контрактом.

## Источники

- `core/src/commonMain/kotlin/io/github/stolex1y/transactionimport/core/StructuredImport.kt`.
- `web/src/main/kotlin/io/github/stolex1y/transactionimport/web/WebApplication.kt`.
- `web/src/main/kotlin/io/github/stolex1y/transactionimport/web/AgentWeb.kt`.
- `README.md`, раздел «Реализованный Stateful workflow».
