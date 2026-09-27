# Transaction Import

Transaction Import — локальное web-приложение для извлечения финансовых операций
из текстовых банковских выписок с помощью LLM.

## Возможности

- локальные memory layers: short-term для диалога, working для текущего
  `ImportDraft`, long-term для общих инструкций, confirmed decisions и
  canonical merchant rules;
- список canonical merchant rules в профиле: aliases, optional numeric terminal
  suffix, model context и deterministic post-processing;
- read-only trace и проекция следующего запроса: слои выбираются без нового
  LLM-вызова, а подробности не раскрывают raw provider prompt/payload;
- общий локальный каталог категорий с глобально уникальными названиями,
  группами и конечными leaf-категориями, типами income/expense, подсказками и
  архивированием без переиспользования ID;
- предложения модели в виде кандидатов решений: они сохраняются в long-term
  память только после явного подтверждения пользователя;
- подтверждённые решения не предлагаются повторно: long-term-контекст не
  считается основанием нового кандидата, а новые кандидаты сохраняются только
  при поддержке текущего сообщения пользователя;
- восстановление сообщений, конфигурации, черновика, memory trace, кандидатов,
  общих инструкций и `receipt_state` после перезапуска;
- единые общие инструкции пользователя, применяемые ко всем сессиям и
  provider requests без отдельной профильной сущности;
- data-driven `receipt_state` со статусами `not_started`, `has_errors` и
  `ready_for_export`; закрытие и restart используют обычное сохранение, без
  отдельного lifecycle или pause/resume API;
- системные инварианты без пользовательского CRUD, краткий compliance-result
  и блокировка batch при детерминированном конфликте;
- стабильные числовые ID операций в UI, storage и patch API с миграцией старых `tx-*`;
- светлая и тёмная темы, табличное и карточное представления операций без горизонтальной прокрутки;
- модель заполняет пустое поле `description` кратким русским описанием
  явного содержимого или назначения операции, но не merchant, местом,
  датой/временем, суммой, валютой, картой, каналом или способом оплаты;
  ручные описания не перезаписываются;
- подготовка и скачивание подтверждённых операций;
- native token usage каждого LLM-вызова, суммы за сессию, context limit и
  отдельная диагностика переполнения контекста; token-aware strategy дополнительно
  сохраняет консервативную локальную оценку occupancy и effective reserve;
- даты операций в UI показываются как `DD-MM-YYYY HH:mm`, а в SQLite и API
  сохраняются в ISO 8601.
- конфигурируемый native LLM tool-calling loop: `mcp_servers.enabled` и
  `allowed_tools` ограничивают поверхность MCP, а bounded limits останавливают
  цикл; банк возвращает только нормализованные операции;
- MCP-операции показываются inline внутри сообщения агента с компактной таблицей
  и добавляются в `ImportDraft` только после явного действия «Принять
  операции»; отдельной preview-панели ниже диалога нет;
- post-tool enrichment и обычное извлечение используют merchant и description:
  технический merchant нормализуется в official/common Russian label, повтор
  merchant из description удаляется, а явные переводы получают merchant
  `Перевод` и описание получателя/назначения;
- MCP post-tool pass получает текущий запрос и тот же релевантный memory context,
  что и draft: общие инструкции, подтверждённые решения и доступные слои
  short-term/working/sticky facts; пользовательские правила вроде
  `BeFit → еда` учитываются только вместе с валидным направлением и активным
  leaf-каталогом;
- неоднозначная категория или тип перевода остаются на review;
- отдельное поле `card_last4` удалено из draft DTO, storage, card/table views и
  экспортного batch; источник операции остаётся единым полем `Счёт / карта`;
Приложение не записывает операции в финансовый ledger. Подготовленный JSON —
конечный результат текущего сценария.

## Реализованный Stateful workflow

Приложение работает как сохраняемый агент вокруг текущего `ImportDraft`, а не
как набор независимых LLM-вызовов:

- сессия восстанавливает диалог, draft, выбранную стратегию контекста,
  memory trace, кандидатов решений, общие инструкции, метрики и
  `receipt_state` после перезапуска;
- revision защищает от устаревших параллельных изменений; стратегия
  `branching` позволяет создать независимую ветку после checkpoint;
- initial-сообщение создаёт draft, а follow-up автоматически распознаётся как
  correction, append statement или needs clarification;
- correction изменяет разрешённые поля текущей операции, append добавляет
  новые операции с продолжением числовых ID и пропускает точные дубликаты;
- каждая операция проходит локальную проверку полей, категорий и системных
  инвариантов до экспорта;
- `receipt_state` пересчитывается программно после импорта, исправления,
  добавления, исключения и повторного экспорта; ручные lifecycle-переходы
  отсутствуют;
- memory layers разделяют short-term диалог, working draft и long-term общие
  инструкции с подтверждёнными решениями;
- memory candidates принимаются пользователем явно, а уже подтверждённые
  решения не предлагаются повторно;
- контекстные стратегии поддерживают sliding window, sticky facts, summary,
  token-aware summary и branching;
- native token usage, context limit, compaction и context overflow отображаются
  как диагностические данные без потери draft;
- категории поддерживают иерархию, тип income/expense, подсказки для модели и
  безопасное архивирование;
- модельное описание операции создаётся только из явного содержимого или
  назначения, а ручное описание пользователя имеет приоритет;
- экспорт остаётся повторяемым: после экспорта операции можно исправлять,
  добавлять и снова экспортировать.

Эти функции доступны через единственную пользовательскую web-точку
`/agent`; удалённые экспериментальные страницы и API не публикуются.


## Web-интерфейс

После запуска доступен основной интерфейс:

- `http://127.0.0.1:8080/agent` — Smart Expense Agent с несколькими
  сессиями.
Это единственная пользовательская web-точка проекта; корневой URL и
экспериментальные страницы/API не публикуются.

Основной агентный сценарий:

1. одним нажатием «Новая сессия» создать сессию с автоматическим названием;
2. в drawer настроить и сохранить общие инструкции для всех сессий, затем при
   необходимости изменить provider, model или reasoning для будущих запросов;
3. отправить синтетическую или обезличенную выписку и проверить, что общие
   инструкции учитываются в provider request;
4. проверить панель `Состояние выписки`: `Не начато`, `Содержит ошибки` или
   `Готово к экспорту`; при ошибке рядом отображается причина;
5. исправить ошибки в редакторе операций или сообщением; ручных кнопок
   перехода между внутренними этапами нет;
6. открыть список системных инвариантов в drawer и настроить canonical merchant
   rules: например, `У дома` с alias `U doma` и включённым numeric suffix
   преобразует `U doma 23`, но не произвольный `U doma Coffee`;
7. проверить панель memory layers: initial-запрос использует long-term, а
   follow-up по draft — short-term, working и long-term; карточки раскрывают
   безопасную проекцию без нового вызова модели;
8. открыть выдвижное меню и управлять общим каталогом категорий: создавать и
   редактировать названия, выбирать тип корня или родителя и архивировать
   неиспользуемые категории;
9. при необходимости скопировать сообщение пользователя или агента кнопкой
   «Копировать»;
10. проверить операции, исправить поля и добавить пользовательские описания;
11. исключить ненужные операции либо изменить выбор массовым флажком;
12. нажать `Экспортировать`: приложение построит batch и сразу скачает JSON
   с выбранными валидными операциями; MCP preview подтверждается отдельно в
   сообщении агента и не запускает экспорт автоматически;
13. после экспорта изменить поле операции, сохранить его и снова нажать
   `Экспортировать`: сессия остаётся редактируемой, а повторный JSON содержит
   актуальное значение;
14. отправить следующую выписку в ту же сессию: новые операции добавятся с
   продолжением ID, а точные дубликаты будут пропущены; после исправления
   новых ошибок их также можно экспортировать;
15. открыть панель токенов; при переполнении получить объяснимую ошибку без
   изменения draft.

Явные телефоны с международным префиксом либо меткой `тел.`, `телефон` или
`phone` маскируются локально с сохранением последних четырёх цифр. Для MCP
raw bank `account_id` и transaction ID остаются внутри адаптера; LLM получает
только opaque `account_alias` и нормализованные поля операции. Несмотря на
это, не отправляйте реальные финансовые данные внешнему LLM-провайдеру.

## Локальный MCP и T-Банк flow

Приложение подключает локальные MCP-серверы через Streamable HTTP. Настройки
серверов находятся в `config/agent.json` в поле `mcp_servers`: приложение
использует только endpoint URL и не запускает MCP-процессы.

Для demo откройте три терминала. В первом запускается fake target
`bank-transactions-mcp-server`; приложение в третьем терминале использует тот же
endpoint, что и для real target.

Терминал 1 — fake T‑Банк MCP target:

```bash
cd solutions/bank-transactions-mcp-server
MCP_PORT=3001 ./gradlew runFakeServer
```

Терминал 2 — MCP-сервер чеков:

```bash
cd solutions/receipts-mcp-server
./gradlew installDist
MCP_PORT=3002 ./build/install/receipts-mcp-server/bin/receipts-mcp-server
```

Терминал 3 — приложение:

```bash
cd solutions/transaction-import
./gradlew :web:installDist
./web/build/install/transaction-import-web/bin/transaction-import-web
```

1. проверьте каталог `MCP-серверы`, статус серверов и разрешённые tools;
2. в единой форме T-Банка введите phone `+79990000000`, запросите SMS-код,
   затем введите OTP `000000` и password `demo`;
3. для ручной проверки выберите счёт и период с 1 по 30 сентября через
   нативный date picker, затем нажмите `Вызвать get-account-transactions`;
4. проверьте JSON результата: opaque `account_ref`, `amount_minor`, дату,
   merchant и список нормализованных операций.

Fake target детерминирован, не обращается к банку и использует synthetic
значения только внутри server demo. Он проходит тот же phone → OTP → password
flow, что и real target; web UI не показывает выбор backend и не меняет форму.
Ошибки остаются на текущем шаге и очищают введённые OTP/password.

Для real сценария остановите fake target и запустите вместо него real target:

```bash
cd solutions/bank-transactions-mcp-server
./gradlew installDist
MCP_PORT=3001 ./build/install/bank-transactions-mcp-server/bin/bank-transactions-mcp-server
```

Приложение, его конфигурация и пользовательский flow при этом не меняются.
Real login предназначен только для собственного read-only аккаунта: private API
не является официальным публичным контрактом, credentials передаются только на
loopback во время login. После успешного login session envelope сохраняется
MCP-сервером в OS credential store; при недоступном store session живёт только
до restart и это явно показывается пользователю. Явный logout real session,
повреждённый envelope и подтверждённая финальная ревокация удаляют сохранённый
envelope.

После обновления страницы сохранённая session проверяется сервером; при успехе
показываются статус активной session и только кнопка `Выйти`, login-формы
скрыты. При временном сетевом/VPN/региональном отказе показывается
recoverable status, envelope сохраняется, а кнопка `Повторить проверку`
повторяет восстановление без OTP/password. Нативные поля периода отображаются
в формате locale браузера, а их значения отправляются в MCP-контракте как
`YYYY-MM-DD`.

Для real session T-Банк MCP-server хранит access/refresh metadata в защищённом
session envelope, обновляет access token заранее перед account/transaction
tool и сохраняет rotated refresh token. Если refresh отклонён, сервер пытается
выполнить silent relogin через сохранённый SSO cookie; OTP и password повторно
не запрашиваются, пока refresh или SSO session действительны. Временная
недоступность не инвалидирует session; подтверждённая финальная ревокация
переводит UI в обычный real login.

Phone, password, OTP, access/refresh tokens, session IDs, auth cookies,
fingerprints и raw private API responses не попадают в БД, logs, LLM context или
MCP arguments. Наружу выходят только безопасные статусы, количества, коды
ошибок без payload и нормализованные read-only операции. MFA, certificate
pinning и anti-bot bypass не выполняются; реальный smoke не является частью
обычных тестов.

Сетевые ошибки и HTTP non-2xx от T-Банк server показываются как понятный
login error; HTML или сырой response body не десериализуются как credentials.

Обычная проверка этого real UI не обращается к upstream: browser smoke
перехватывает локальные `/api/agent/tbank/*` fixture-ответы, а backend state
machine проверяется `MockEngine`. Реальные credentials и SMS в тестах не
используются; real smoke выполняется отдельно только пользователем на своём
аккаунте.

Основной путь MCP использует native LLM tool-calling loop. После сообщения
пользователя агент получает только configured tools: сервер должен быть
`enabled`, а имя tool должно входить в `allowed_tools`. Каждая итерация и
число вызовов ограничены `mcp_tool_loop`; автономный scheduler запускается
только по явно сохранённой task и не использует этот conversational loop.

Для T-Банк LLM выбирает только `account_alias`. MCP-адаптер разрешает alias в
внутренний raw account ID, получает операции, удаляет идентификаторы и
возвращает нормализованные `date`, `amount_minor`, `currency`, `merchant` и
`description`. Приложение повторно allowlist-ит DTO перед следующим LLM
сообщением. Post-tool pass получает текущий запрос и релевантные memory layers
сессии, обогащает merchant и description по `merchant + description`, применяет
общие инструкции и подтверждённые решения к категории и выбирает только
совместимую конечную категорию. Узнаваемые бренды нормализуются в
official/common Russian label, повтор merchant из description удаляется, а
явный перевод получает merchant `Перевод` и описание получателя/назначения.
Неоднозначный результат остаётся на review. Полученный список отображается
inline в сообщении агента; только кнопка «Принять операции» вызывает изменение
`ImportDraft`.

При старте и по кнопке «Обновить» приложение выполняет через HTTP MCP
`initialize` и `tools/list`. Ручная T-Банк форма `tools/call` остаётся
диагностическим способом проверки того же read-only контракта и не заменяет
native loop.

## Фоновый импорт и enrichment чеков

Панель `Фоновый импорт` в drawer сохраняет scheduler tasks в той же SQLite
базе, что и сессии. Для task пользователь выбирает безопасные `account_ref`,
дату начала, cadence в минутах и timezone. Task можно поставить на паузу,
возобновить, запустить немедленно и открыть историю запусков.

Один запуск последовательно:

1. запрашивает операции через read-only `tbank-transactions`;
2. ищет и получает кандидатов чеков через read-only `receipts`;
3. отбрасывает кандидатов с несовпадающими абсолютной суммой, валютой или
   датой за пределами ±1 дня;
4. при нескольких допустимых кандидатах использует LLM только для выбора
   непрозрачного alias; невалидный или недостаточно уверенный выбор оставляет
   операцию неоднозначной;
5. сохраняет `items[]` чека и безопасный `source_ref` в общий редактируемый
   session draft.

Первый успешный запуск создаёт session. Следующие запуски append-ят данные в
ту же session, пропускают уже сохранённые `source_ref` и не перезаписывают
ручные правки. Cursor и история записываются только после успешного
сохранения draft; при ошибке cursor не продвигается, а повторный запуск
получает окно с последней успешной даты.

Для D19 порядок handoff виден в trace: bank transactions → receipt
candidates → receipt details → match proposal → session persistence →
aggregate result. Для D20 scheduler публикует только bounded status/history/run
tools; scheduler не пишет в банковский ledger и не даёт модели raw receipt
keys, account IDs или transaction IDs.

Локальный fake receipts target запускается так:

```bash
cd solutions/receipts-mcp-server
MCP_PORT=3002 ./gradlew runFakeServer
```

Synthetic login использует `+79990000000`, OTP `000000` и password `demo`.
Real LKDR login не является частью обычных тестов: ввод выполняется только
пользователем на loopback, разрешены только явные HTTPS endpoints, CAPTCHA,
MFA и anti-bot обход не выполняются. Access/refresh envelope хранится в OS
credential store; raw tax receipt keys, tokens и credentials не попадают в
SQLite, LLM context или application logs.

## Конфигурация провайдеров

Версионируемый каталог находится в `config/providers.json`. Он содержит только
несекретные параметры:

- `id`, `display_name`, `base_url` и `chat_completions_path`;
- имя переменной окружения `credential_env`, но не сам API key;
- модели, режимы reasoning, `context_window_tokens` и необязательное поле
  `max_output_tokens` для безопасного бюджета ответа.

Совместимого с OpenAI Chat Completions провайдера или модель можно добавить
правкой каталога без перекомпиляции. Поля `request_fields` добавляются в JSON
запрос, но не могут переопределить `model`, `messages`, `response_format`,
`max_tokens`, `temperature` и `stream`.

- DeepSeek: `deepseek-flash` (DeepSeek V4.1 Flash) и `deepseek-v4-pro`
  (DeepSeek V4 Pro 0813); старый ID `deepseek-v4-flash` мигрируется при
  восстановлении сохранённых сессий.

Путь к другому каталогу задаётся через `PROVIDER_CONFIG_PATH`. Провайдер
отображается недоступным, если указанная в `credential_env` переменная не
задана; это не блокирует другие настроенные провайдеры.

Начальный каталог использует:

- `DEEPSEEK_API_KEY` для DeepSeek;
- `OPENROUTER_API_KEY` для OpenRouter.

## Конфигурация агента

Начальные provider/model/reasoning, скрытые параметры LLM, стратегия контекста
и начальные общие инструкции задаются в `config/agent.json`. Путь к другому
файлу задаётся через `AGENT_CONFIG_PATH`. Файл читается при старте приложения.

### Верхнеуровневые поля

Минимальная конфигурация выглядит так:

```json
{
  "default_provider_id": "deepseek",
  "default_model_id": "deepseek-flash",
  "default_reasoning_mode_id": "low",
  "temperature": null,
  "max_tokens": 100000,
  "default_user_prompt": "",
  "context_management": {
    "strategy": "summary",
    "recent_messages": 5,
    "summary_batch_messages": 5,
    "summary_max_tokens": 1024
  }
}
```

| Поле | Тип и ограничения | Назначение |
| --- | --- | --- |
| `default_provider_id` | строка | `id` провайдера из `providers.json`; используется при создании новой сессии. |
| `default_model_id` | строка | `id` модели выбранного провайдера; модель должна существовать в его `models`. |
| `default_reasoning_mode_id` | строка | `id` режима из `reasoning_modes` выбранной модели. |
| `temperature` | `null` или число `0.0..2.0` | Температура всех агентных LLM-вызовов. `null` не добавляет поле в запрос и оставляет решение провайдеру. |
| `max_tokens` | положительное целое | Максимум output tokens обычного agent-вызова. Фактическое значение ограничивается `max_output_tokens` модели, если оно задано. Для summary и facts используются отдельные поля `summary_max_tokens` и `facts_max_tokens`. |
| `default_user_prompt` | строка, максимум 8000 символов | Начальные общие инструкции пользователя. Они записываются в SQLite при первом создании настроек; изменение файла не перезаписывает уже сохранённое значение. |
| `mcp_servers` | массив объектов | HTTP или in-process logical MCP servers, их enabled-флаг и allowlist tools. |
| `mcp_tool_loop` | объект | Native tool-calling loop и его bounded limits. |

### Поля `mcp_servers`

Каждый объект содержит:

- `id` и `display_name` — стабильный идентификатор и название в drawer;
- `endpoint` — URL Streamable HTTP MCP endpoint либо `in-process://<id>` для
  встроенного logical server;
- `enabled` — признак включения, по умолчанию `true`;
- `allowed_tools` — точный список имён tools, которые можно рекламировать и
  вызывать native loop; пустой список не даёт LLM доступных tools.

Приложение не запускает и не останавливает внешние MCP-серверы. Оно сохраняет
HTTP MCP-соединение до остановки приложения и повторно запрашивает `tools/list`
по кнопке «Обновить». Scheduler остаётся встроенным logical server и вызывает
только allowlisted source tools через тот же catalog; серверы с
`enabled=false` не подключаются.

### Поля `mcp_tool_loop`

| Поле | Тип и ограничения | Назначение |
| --- | --- | --- |
| `enabled` | boolean | Включает native tool-calling path; `false` сохраняет обычный LLM import flow. |
| `max_iterations` | целое `1..32` | Верхняя граница раундов LLM в одном bounded loop. |
| `max_tool_calls` | целое `1..64` | Общий лимит MCP-вызовов одного сообщения. |
| `call_timeout_ms` | целое `1000..300000` | Таймаут каждого MCP-вызова. |
| `result_mode` | `normalized` | Фиксирует безопасный DTO-режим результата. |

### Поля `context_management`

Допустимые значения `strategy`: `sliding_window`, `sticky_facts`,
`branching`, `summary`, `token_aware_summary`.

| Поле | По умолчанию | Используется | Что означает |
| --- | ---: | --- | --- |
| `strategy` | `summary` | всегда | Ровно одна стратегия обработки истории. |
| `recent_messages` | `10` | `sliding_window`, `sticky_facts`, `summary`, `token_aware_summary` | Количество последних сообщений, оставляемых доступными без сжатия. Для `summary` это хвост после summary; для `token_aware_summary` — дополнительная минимальная граница по числу сообщений. |
| `summary_batch_messages` | `10` | `summary`, `token_aware_summary` | Сколько старых несжатых сообщений передавать в одну summary-компакцию. |
| `summary_max_tokens` | `1024` | `summary`, `token_aware_summary` | Максимальный output budget отдельного summary-вызова. Значение дополнительно ограничивается `max_output_tokens` модели. |
| `summary_threshold_tokens` | `null` | `token_aware_summary` | Жёсткий порог estimated prompt tokens. Имеет приоритет над `summary_threshold_percent`. |
| `summary_threshold_percent` | `null` | `token_aware_summary` | Порог как процент context window, только `1..99`; используется, если `summary_threshold_tokens` не задан. |
| `summary_reserve_tokens` | `null` | `token_aware_summary` | Резерв под текущий запрос и output, если порог не задан явно. По умолчанию используется большее из `16384` и `15%` окна; резерв ограничен половиной окна. |
| `summary_keep_recent_tokens` | `20000` | `token_aware_summary` | Целевой объём свежего хвоста, который не следует отправлять в summary. Он дополнительно ограничен вычисленным threshold; `recent_messages` сохраняет минимальное число сообщений независимо от оценки токенов. |
| `max_facts` | `32` | `sticky_facts` | Максимальное число sticky facts, хранимых в сессии. |
| `fact_value_max_chars` | `500` | `sticky_facts` | Максимальная длина одного значения fact. Ключ ограничен приложением 100 символами. |
| `facts_max_tokens` | `1024` | `sticky_facts` | Максимальный output budget отдельного facts-вызова. |

Поля, не относящиеся к выбранной стратегии, лучше не указывать: схема их
принимает для общего JSON-формата, но они не влияют на поведение. Валидатор
проверяет положительность используемых числовых полей; для
`summary_threshold_percent` допустим диапазон `1..99`.

### Стратегии контекста

#### `sliding_window`

```json
{
  "context_management": {
    "strategy": "sliding_window",
    "recent_messages": 8
  }
}
```

В обычный и follow-up запрос попадают только последние
`recent_messages` сообщений. Полный архив остаётся в SQLite, но старые
сообщения не отправляются модели. Дополнительных summary/facts-вызовов нет.
Это самый предсказуемый и дешёвый вариант для длинных диалогов, если важен
только недавний контекст; цена — потеря старых деталей для модели.

#### `sticky_facts`

```json
{
  "context_management": {
    "strategy": "sticky_facts",
    "recent_messages": 6,
    "max_facts": 32,
    "fact_value_max_chars": 500,
    "facts_max_tokens": 1024
  }
}
```

Перед обычным запросом выполняется отдельный strict-JSON facts-вызов. Он
обновляет подтверждённые факты и удаляет устаревшие ключи; затем обычный
запрос получает facts и последние `recent_messages` сообщений. Facts и обмен
сохраняются атомарно. Каждый пользовательский запрос может породить
дополнительный facts-вызов, поэтому стратегия требует дополнительного
latency/budget. `max_facts` ограничивает число записей после обновления,
`fact_value_max_chars` отбрасывает слишком длинные значения, а
`facts_max_tokens` должен быть достаточно большим для JSON-ответа.

#### `branching`

```json
{
  "context_management": {
    "strategy": "branching"
  }
}
```

В запрос передаётся полный архив до текущего checkpoint; поля
`recent_messages`, `summary_*` и `*_facts` для этой стратегии не применяются.
Кнопка «Создать ветку» доступна после завершённого обмена и создаёт
независимую сессию-копию. Изменения в ветке не меняют исходную сессию.
`parent_session_id`, `checkpoint_message_count` и `branch_label` — внутренние
поля созданной сессии, а не параметры `agent.json`. Стратегия удобна для
сравнения альтернатив, но полный архив может переполнить context window.

#### `summary`

```json
{
  "context_management": {
    "strategy": "summary",
    "recent_messages": 5,
    "summary_batch_messages": 5,
    "summary_max_tokens": 1024
  }
}
```

После накопления первых `recent_messages + summary_batch_messages` сообщений
старшая часть истории сворачивается отдельным summary-вызовом. Далее
компакция запускается, когда несжатых сообщений становится больше
`recent_messages`; за один проход обрабатывается до
`summary_batch_messages` сообщений. Обычный запрос получает накопленную
summary и оставшийся свежий хвост. Это хороший общий вариант для длинных
диалогов, но summary — дополнительный LLM-вызов и сжатое содержание может
потерять неявные детали.

#### `token_aware_summary`

```json
{
  "context_management": {
    "strategy": "token_aware_summary",
    "recent_messages": 4,
    "summary_batch_messages": 4,
    "summary_max_tokens": 1024,
    "summary_threshold_percent": 75,
    "summary_keep_recent_tokens": 12000
  }
}
```

Стратегия оценивает полный будущий prompt до обычного вызова и начинает
компакцию, когда effective token count превышает threshold. Для оценки берётся
максимум между последним `prompt_tokens` от provider и локальной
консервативной UTF-8 upper-bound оценкой. Старые сообщения сворачиваются
порциями `summary_batch_messages`; свежий хвост сохраняется с учётом
`summary_keep_recent_tokens` и минимальных `recent_messages`.

Effective threshold выбирается в следующем порядке:

1. `summary_threshold_tokens`, если задан;
2. `summary_threshold_percent` от `context_window_tokens`;
3. `context_window_tokens - summary_reserve_tokens`;
4. если reserve не задан, резервом служит большее из `16384` и `15%` окна.

Значения threshold ограничиваются диапазоном `1..context_window_tokens-1`, а
reserve — диапазоном `1..50%` окна. Если у модели в `providers.json` нет
`context_window_tokens`, token-aware compaction не может рассчитать бюджет и
не запускается; для этой стратегии поле должно быть заполнено. Отдельно
проверьте `max_output_tokens`: малое значение модели ограничит summary и
обычный ответ независимо от `max_tokens`.

### Совместимость со старой конфигурацией

`context_compression` оставлен только для чтения старых файлов. Если
`context_management` задан, он имеет приоритет. Иначе:

- `context_compression.enabled=true` преобразуется в `summary` с его
  `recent_messages`, `summary_batch_messages` и `summary_max_tokens`;
- `enabled=false` сохраняет legacy-поведение полной истории без стратегии.

В новых файлах используйте только `context_management`: он явно описывает
выбранную стратегию и не смешивает старый и новый форматы.

В одной базе хранятся изолированные сессии, snapshot выбранной стратегии,
конфигурации агентов, полный архив сообщений, отдельная накопительная summary,
sticky facts, metrics normal/facts/summary/token-aware LLM-вызовов, строки
черновиков, ветки checkpoint, общие инструкции и `receipt_state`.

По умолчанию сессии сохраняются в `.data/agent.sqlite`. Другой путь задаётся
через `TRANSACTION_IMPORT_DB`. Native `usage` provider сохраняется как
наблюдаемая метрика. Token-aware strategy дополнительно использует локальную
UTF-8 upper-bound оценку без внешнего tokenizer API; она нужна только для
консервативного compaction и не подменяет provider billing.

Существующая схема предыдущей версии расширяется при запуске без удаления
сессий; устаревшие профильные настройки переносятся в общие инструкции,
пользовательские task invariants и pause-поля удаляются миграцией.

## Запуск

Для реального LLM-вызова задайте API key нужного провайдера из
`config/providers.json`, затем из корня проекта:

```bash
./gradlew :web:installDist
./web/build/install/transaction-import-web/bin/transaction-import-web
```

Порт меняется переменной `PORT`. Создание, просмотр и локальное редактирование
сессий доступны без ключа; отправка сообщения требует доступного провайдера.
Для запуска с DeepSeek нужен `DEEPSEEK_API_KEY`.

## Проверки

Обычный набор тестов не вызывает внешние API и не запускает браузер:

```bash
./gradlew test
```

Постоянный fake-provider проверяет HTTP-flow агента, prompt с общими
инструкциями и системными инвариантами, вычисление и сохранение `receipt_state`,
редактирование draft после первого экспорта, добавление новых операций и
повторную подготовку JSON на изолированной временной SQLite-базе.
Отдельный surface-тест подтверждает `200` для `/agent` и `404` для корневой
страницы и удалённых экспериментальных маршрутов. Playwright E2E запускается
против реального Ktor Netty, временной SQLite-базы и того же fake-provider:

```bash
./gradlew :web:playwrightInstall
./gradlew :web:browserTest
```

Browser-тест проверяет создание сессии, отображение системных инвариантов,
публичные статусы выписки, отсутствие ручных переходов, два последовательных
скачивания JSON с редактированием после первого экспорта и hover/focus memory
layers в светлой и тёмной темах, LLM-flow, native token metrics и рост input
при повторной отправке истории, table/card switch с несохранёнными правками,
отсутствие отдельного `card_last4` в редакторе и экспорте, inline MCP preview
внутри сообщения агента с кнопкой принятия, reload/restart и изолированное
удаление. Отдельный overflow-сценарий подтверждает русскую ошибку без
изменения messages и draft.
`playwrightInstall` нужен один раз на окружение после изменения версии Playwright.
