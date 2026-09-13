# Transaction Import

Transaction Import — локальное web-приложение для извлечения финансовых операций
из текстовых банковских выписок с помощью LLM.

## Возможности

- разовый импорт в свободном или контролируемом JSON-режиме;
- локальная проверка обязательных полей, типов и предметных инвариантов;
- отдельные агентные сессии с изменяемыми provider/model/reasoning;
- общий пользовательский профиль для будущих запросов всех сессий;
- follow-up сообщения с автоопределением correction/append_statement/clarification,
  локальной проверкой patch и атомарным объединением выписок; дедупликация
  различает конфликтующие непустые `posted_at` и `card_last4`;
- прямое редактирование, описание, массовый выбор и исключение операций без вызова LLM;
- привязанные к полям ошибки только у выбранных операций;
- локальное маскирование явных телефонов до SQLite и запроса провайдеру;
- русские причины ошибок модели с нейтральным fallback для неизвестных сообщений;
- временные success/info и error уведомления, включая отдельное уведомление ответа агента;
- восстановление сообщений, конфигурации, черновика и профиля после перезапуска;
- стабильные числовые ID операций в UI, storage и patch API с миграцией старых `tx-*`;
- светлая и тёмная темы, табличное и карточное представления операций без горизонтальной прокрутки;
- подготовка и скачивание подтверждённых операций;
- native token usage каждого LLM-вызова, суммы за сессию, context limit и
  отдельная диагностика переполнения контекста; token-aware strategy дополнительно
  сохраняет консервативную локальную оценку occupancy и effective reserve;
- даты операций в UI показываются как `DD-MM-YYYY HH:mm`, а в SQLite и API
  сохраняются в ISO 8601.
- отдельные страницы воспроизводимых экспериментов.

Приложение не записывает операции в финансовый ledger. Подготовленный JSON —
конечный результат текущего сценария.

## Web-интерфейс

После запуска доступны:

- `http://127.0.0.1:8080/agent` — Smart Expense Agent с несколькими сессиями;
- `http://127.0.0.1:8080/` — разовый импорт;
- `http://127.0.0.1:8080/experiments` — страницы сравнительных экспериментов.

Основной агентный сценарий:

1. одним нажатием «Новый импорт» создать сессию с автоматическим названием;
2. при необходимости изменить provider, model или reasoning для будущих запросов;
3. задать общий профиль в выдвижном меню сессий;
4. отправить синтетическую или обезличенную выписку;
5. выбрать табличное или карточное представление, проверить операции, исправить поля и добавить пользовательские описания;
6. исключить ненужные операции либо изменить выбор массовым флажком;
7. подготовить и скачать JSON с выбранными валидными операциями;
8. отправить следующую выписку в ту же сессию: новые операции добавятся с
   продолжением ID, а точные дубликаты будут пропущены;
9. открыть панель токенов, сравнить input/output/total короткого и длинного
   диалога и при переполнении получить объяснимую ошибку без изменения draft.

Явные телефоны с международным префиксом либо меткой `тел.`, `телефон` или
`phone` маскируются локально с сохранением последних четырёх цифр. Длинные
account/reference ID без телефонных признаков не изменяются. Несмотря на это,
не отправляйте реальные финансовые данные внешнему LLM-провайдеру.

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

Путь к другому каталогу задаётся через `PROVIDER_CONFIG_PATH`. Провайдер
отображается недоступным, если указанная в `credential_env` переменная не
задана; это не блокирует другие настроенные провайдеры.

Начальный каталог использует:

- `DEEPSEEK_API_KEY` для DeepSeek;
- `OPENROUTER_API_KEY` для OpenRouter.

## Конфигурация агента

Начальные provider/model/reasoning, скрытые параметры LLM и начальный общий
профиль задаются в `config/agent.json`. Путь к другому файлу задаётся через
`AGENT_CONFIG_PATH`.

По умолчанию `temperature` не переопределяет настройку провайдера, а
`max_tokens` равен `100000`. Эти параметры не показываются в web-интерфейсе.
Если выбранная модель задаёт `max_output_tokens`, фактический бюджет запроса
ограничивается этим значением. Это предотвращает ложное переполнение контекста
для моделей с меньшим окном, например LFM с лимитом `2048`.

`context_management` выбирает ровно одну стратегию контекста при создании
сессии. Переключение выполняется между запусками через `AGENT_CONFIG_PATH`;
web-интерфейс показывает выбранную стратегию только для чтения:

- `strategy=sliding_window`: в normal-запрос попадают последние
  `recent_messages`, полный архив остаётся в SQLite;
- `strategy=sticky_facts`: отдельный strict-JSON `facts`-вызов обновляет
  подтверждённые факты перед normal-вызовом; факты и обмен сохраняются
  атомарно;
- `strategy=branching`: запрос использует полный архив; кнопка «Создать
  ветку» создаёт независимую копию checkpoint без изменения источника;
- `strategy=summary`: сохраняет D04 summary-поведение с
  `recent_messages`, `summary_batch_messages` и `summary_max_tokens`;
- `strategy=token_aware_summary`: запускает Summary по effective token
  threshold с reserve fallback, сохраняет `summary_keep_recent_tokens` свежего
  хвоста и использует максимум provider `prompt_tokens` и локальной
  консервативной оценки.

Готовые профили для сравнения находятся в
`examples/agent-sliding-window.json`, `agent-sticky-facts.json`,
`agent-branching.json`, `agent-summary.json` и
`agent-token-aware-summary.json`. Для каждого запуска используйте отдельную
SQLite-базу.

Для воспроизводимой записи token-aware compaction используйте
`examples/agent-token-aware-demo.json` вместе с
`examples/demo-providers.json`: threshold равен `20000`, fresh-tail budget —
`500`, summary budget — `2048`. Этот профиль предназначен для демонстрации,
а не для изменения основного `config/agent.json`.

Старый `context_compression` продолжает читаться для совместимости с
предыдущими конфигурациями: `enabled=true` преобразуется в `summary`, а
`enabled=false` сохраняет legacy-поведение полной истории. Новый active-файл
использует только `context_management`.

В одной базе хранятся изолированные сессии, snapshot выбранной стратегии,
конфигурации агентов, полный архив сообщений, отдельная накопительная summary,
sticky facts, metrics normal/facts/summary/token-aware LLM-вызовов, строки
черновиков, ветки checkpoint, пользовательские описания и общий профиль.

По умолчанию сессии сохраняются в `.data/agent.sqlite`. Другой путь задаётся
через `TRANSACTION_IMPORT_DB`. Native `usage` provider сохраняется как
наблюдаемая метрика. Token-aware strategy дополнительно использует локальную
UTF-8 upper-bound оценку без внешнего tokenizer API; она нужна только для
консервативного compaction и не подменяет provider billing.

Существующая схема предыдущей версии расширяется при запуске без удаления
сессий.

## Запуск

Для реального LLM-вызова задайте API key нужного провайдера из
`config/providers.json`, затем из корня проекта:

```bash
./gradlew :web:installDist
./web/build/install/transaction-import-web/bin/transaction-import-web
```

Порт меняется переменной `PORT`. Создание, просмотр и локальное редактирование
сессий доступны без ключа; отправка сообщения требует доступного провайдера.
Для базовых страниц и экспериментов нужен `DEEPSEEK_API_KEY`.


## Локальные demo instances

Для записи сравнительных сценариев конфигурации и отдельные SQLite-базы
создаются в игнорируемом каталоге `.gradle/demo/instances`:

```text
01-main/                 18180  Summary
02-sliding-window/       18181  Sliding Window
03-sticky-facts/         18182  Sticky Facts
04-summary/              18183  Summary
05-branching/            18184  Branching
06-token-aware-summary/  18185  Token-aware Summary
07-lfm-full-history/     18186  LFM без compression
08-lfm-summary/          18187  LFM с Summary
```

Каждый инстанс запускается своим `run.sh`. Скрипт пересобирает web distribution,
подставляет локальные `providers.json`, `agent.json`, SQLite и порт. Для
ускорения повторного запуска используйте `SKIP_BUILD=1`.

Clean-выписка для сравнительного видео генерируется командой:

```bash
python3 examples/generate_context_memory_fixture.py \
  --output .gradle/demo/demo-context-memory.txt
```

Runner `examples/run_summary_overflow_demo.py` отправляет одну большую
выписку, три промежуточных сообщения и пятый запрос. Он предназначен для
локального deterministic smoke; реальный OpenRouter/LFM запуск выполняется
пользователем отдельно во время записи.

## Проверки

Обычный набор тестов не вызывает внешние API и не запускает браузер:

```bash
./gradlew test
```

Постоянный fake-provider проверяет HTTP-flow агента, prompt, локальный отказ,
редактирование draft, validation и подготовку batch на изолированной временной
SQLite-базе. Отдельный Playwright E2E запускается против реального Ktor Netty,
временной SQLite-базы и того же fake-provider:

```bash
./gradlew :web:playwrightInstall
./gradlew :web:browserTest
```

Browser-тест проверяет создание сессии, LLM-flow, native token metrics и рост
input при повторной отправке истории, table/card switch с несохранёнными
правками, автоматический card fallback ниже 768 px, validation, reload/restart
и изолированное удаление. Отдельный overflow-сценарий подтверждает русскую
ошибку без изменения messages и draft. `playwrightInstall` нужен один раз на
окружение после изменения версии Playwright.

## Серия экспериментов

- `/experiments/d01` — baseline unrestricted;
- `/experiments/d02` — свободный ответ и controlled JSON;
- `/experiments/d03` — prompt strategies;
- `/experiments/d04` — preset temperature `0`, `0.7` и `1.2`;
- `/experiments/d05` — DeepSeek и OpenRouter.

Для D05 модель OpenRouter задаётся через `OPENROUTER_MODEL`; по умолчанию
используется `z-ai/glm-5.2:free`.
