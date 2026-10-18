# Архитектура Transaction Import

Transaction Import — локальное web-приложение для извлечения финансовых операций
из текстовых банковских выписок с помощью LLM. Подготовленный JSON — результат
текущего сценария; приложение не записывает операции в финансовый ledger.

## Gradle-модули

`settings.gradle.kts` включает `core`, `transport`, `persistence` и `web`.
Зависимости направлены к core-модели: `transport` и `persistence` используют
`core`, а `web` собирает все три модуля.

| Модуль | Назначение в текущем коде | Подтверждение |
|---|---|---|
| `core` | Kotlin Multiplatform-модель и логика агента, сессий, draft и сериализованных контрактов. Основные источники находятся в `commonMain`. | `core/build.gradle.kts`; `core/src/commonMain/.../SmartExpenseAgent.kt`; `core/src/commonMain/.../StatefulAgent.kt`; `core/src/commonMain/.../StructuredImport.kt` |
| `transport` | JVM-адаптеры провайдеров и HTTP Chat Completion; `ConfiguredProviderRegistry` разрешает provider config и credentials из окружения. | `transport/build.gradle.kts`; `transport/src/main/.../ConfiguredProviderRegistry.kt` |
| `persistence` | JVM-хранилища сессий и scheduler через SQLite JDBC. | `persistence/build.gradle.kts`; `persistence/src/main/.../SqliteImportSessionRepository.kt`; `SqliteSchedulerRepository.kt` |
| `web` | Ktor JVM-приложение: собирает runtime, предоставляет web UI и JSON routes; distribution включает `config/`. | `web/build.gradle.kts`; `web/src/main/.../WebApplication.kt`; `AgentWeb.kt` |

`web` зависит от `core`, `transport` и `persistence`. `transport` и `persistence`
зависят от `core`; `core` не зависит от web/persistence.

## Пользовательский и HTTP-вход

Основной пользовательский интерфейс находится на `GET /agent`. Прямое открытие
сессии обслуживается `GET /agent/sessions/{sessionId}`. Оба маршрута загружают
web application resource; это основной пользовательский entry point проекта.

`WebApplication.kt` монтирует JSON handlers из `AgentWeb.agentRoutes`. Среди
текущих session routes:

- `GET /api/agent/sessions` — список сессий;
- `POST /api/agent/sessions` — создание сессии;
- `GET /api/agent/sessions/{id}` — состояние сессии;
- `POST /api/agent/sessions/{id}/messages` — сообщение агента;
- `GET /api/agent/sessions/{id}/batch` — сформированный import batch.
- `GET /api/agent/support/messages` и `POST /api/agent/support/messages` — единая история support-чата без ticket metadata.

Назначение и schema этих routes описаны в [data-api.md](data-api.md). В выбранных
ответах web layer вызывает `withoutOpaqueSourceRefs()` перед сериализацией.

## LLM и MCP

Core ведёт сохраняемую агентную сессию вокруг `ImportDraft`; `transport` закрывает
provider HTTP boundary. Приложение подключает локальные MCP-серверы по Streamable
HTTP. Их endpoint URLs задаются в `config/agent.json`; приложение использует
указанные URL и само не запускает MCP-процессы.

Support orchestration живёт в `web`, полный transcript и состояние follow-up /
routing хранятся в отдельной SQLite-схеме. `SupportKnowledgeBase` индексирует
только `docs/support-faq.md` и `docs/user-guide.md`; пустой retrieval даёт
детерминированный abstention без вызова модели. Для support используется отдельный
provider/model/reasoning profile `support_assistant` и окно последних message
records активного обращения.

Локальный synthetic ticket store доступен через отдельный JSON-backed logical
MCP catalog `support-tickets`. Его allowlist не пересекается с глобальным
каталогом импортных, bank, receipts и scheduler tools. Модель не получает MCP
tools: backend сам валидирует структурированный routing результат и выполняет
только create/close/reopen transitions. Ticket activity выводится из времени
последнего связанного transcript message; deadline — 30 минут.

Банковские идентификаторы остаются внутри соответствующего adapter. Не отправляйте
реальные финансовые данные внешнему LLM-провайдеру.

В support-ответы передаются только найденные документационные passages и
ограниченный transcript. Реальные финансовые данные и внешний CRM не используются.

## Источники

- `README.md`, разделы «Stateful workflow» и «Локальный MCP и T-Банк flow».
- `settings.gradle.kts` и `*/build.gradle.kts`.
- `web/src/main/kotlin/io/github/stolex1y/transactionimport/web/WebApplication.kt`.
- `web/src/main/kotlin/io/github/stolex1y/transactionimport/web/AgentWeb.kt`.
- `transport/src/main/kotlin/io/github/stolex1y/transactionimport/transport/ConfiguredProviderRegistry.kt`.
- `persistence/src/main/kotlin/io/github/stolex1y/transactionimport/persistence/SqliteImportSessionRepository.kt`.
- `web/src/main/kotlin/io/github/stolex1y/transactionimport/web/SupportChatService.kt`.
- `web/src/main/kotlin/io/github/stolex1y/transactionimport/web/SupportKnowledgeBase.kt`.
- `web/src/main/kotlin/io/github/stolex1y/transactionimport/web/JsonSupportTicketMcpServer.kt`.
