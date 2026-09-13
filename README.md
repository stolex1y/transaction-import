# Transaction Import

Transaction Import — локальное web-приложение для извлечения финансовых операций
из текстовых банковских выписок с помощью LLM.

## Возможности

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

Приложение не записывает операции в финансовый ledger. Подготовленный JSON —
конечный результат текущего сценария.

## Web-интерфейс

После запуска доступен основной интерфейс:

- `http://127.0.0.1:8080/agent` — Smart Expense Agent с несколькими
  сессиями.

Основной агентный сценарий:

1. одним нажатием «Новая сессия» создать сессию с автоматическим названием;
2. при необходимости изменить provider, model или reasoning для будущих
   запросов;
3. задать общий профиль в выдвижном меню сессий;
4. отправить синтетическую или обезличенную выписку;
5. проверить операции, исправить поля и добавить пользовательские описания;
6. исключить ненужные операции либо изменить выбор массовым флажком;
7. подготовить и скачать JSON с выбранными валидными операциями;
8. отправить следующую выписку в ту же сессию: новые операции добавятся с
   продолжением ID, а точные дубликаты будут пропущены;
9. открыть панель токенов и при переполнении получить объяснимую ошибку без
   изменения draft.

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

Начальные provider/model/reasoning, скрытые параметры LLM, стратегия контекста
и начальный общий профиль задаются в `config/agent.json`. Путь к другому файлу
задаётся через `AGENT_CONFIG_PATH`. Файл читается при старте приложения.

### Верхнеуровневые поля

Минимальная конфигурация выглядит так:

```json
{
  "default_provider_id": "deepseek",
  "default_model_id": "deepseek-v4-flash",
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
| `default_user_prompt` | строка, максимум 8000 символов | Начальный общий профиль пользователя. Он записывается в SQLite только при первом создании профиля; изменение файла не перезаписывает уже сохранённый профиль. |
| `context_management` | объект или `null` | Выбирает одну стратегию контекста для новых сессий. |

`default_*` и `context_management` фиксируются в snapshot сессии при её
создании. Поэтому после изменения `agent.json` нужно перезапустить приложение и
создать новую сессию; существующие сессии продолжают работать со своей
стратегией и provider/model/reasoning. Provider/model/reasoning текущей сессии
можно изменить в web-интерфейсе, стратегию контекста — нет.

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
Для запуска с DeepSeek нужен `DEEPSEEK_API_KEY`.

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
