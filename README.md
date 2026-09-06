# Transaction Import

Ядро Kotlin Multiplatform, консольный клиент JVM и локальный web-клиент для
извлечения финансовых операций из текстовых банковских выписок через
совместимый с OpenAI провайдер. Web-клиент поддерживает свободный ответ и
контролируемый JSON с локальной проверкой перед импортом.

## Модули

- `core` — платформонезависимые модели, строгий контракт JSON, локальная
  валидация, контракт провайдера и сервис извлечения;
- `transport` — общий JVM Ktor Client-адаптер DeepSeek для server-side интеграции;
- `web` — локальный Ktor-сервер и статический русскоязычный интерфейс с
  основной страницей импорта, отдельным baseline D01 `/experiments/d01` и
  web-разделами `/experiments/d02`, `/experiments/d03`,
  `/experiments/d04`, `/experiments/d05`;
- `d04` — JVM-исполнитель controlled JSON temperature-серии для
  синтетического hard fixture; сохраняет конфигурацию, reference, raw ответы,
  validation, field-level accuracy, fingerprint и latency. Пользовательский
  web-сценарий находится на странице `/experiments/d04`;
- `d05` — JVM-исполнитель сравнения `deepseek-v4-flash`, OpenRouter-модели,
  заданной через `OPENROUTER_MODEL` (по умолчанию `z-ai/glm-5.2:free`) и
  `deepseek-v4-pro`; фиксирует reasoning controls, provider-specific usage,
  validation, latency, cost/quota и compatibility failures. Пользовательский
  web-сценарий находится на странице `/experiments/d05`.
- `examples` — пять синтетических task-файлов:
  - `demo-task-d01.txt` — hard-выписка для D01;
  - `demo-task-d02.txt` — та же hard-выписка для controlled JSON D02;
  - `demo-task-d03.txt` — задача о фальшивой монете;
  - `demo-task-d04.txt` — полный prompt для эксперимента temperature;
  - `demo-task-d05.txt` — полный prompt для стрессового сравнения моделей.

`core` не зависит от UI или серверного фреймворка. Kotlin/JVM backend может
использовать его напрямую, а Android-, iOS- и Desktop-клиенты могут повторно
использовать модели и сериализацию, не получая API-ключ провайдера.

## Требования

- JDK 17 или 21; целевая версия JVM bytecode — 17;
- JDK 25 не поддерживается настроенной Kotlin/Gradle toolchain;
- доступ к Maven Central для первой загрузки зависимостей.

## Сборка и тесты

```bash
./gradlew test
```

Тесты используют fake gateway и Ktor `MockEngine`. Они не обращаются к сети,
не требуют API-ключа и не отправляют текст выписки во внешний сервис.


## Использование web-приложения

Собери и запусти локальный сервер:

```bash
./gradlew :web:installDist

read -r -s -p 'API-ключ провайдера: ' DEEPSEEK_API_KEY
printf '\n'
export DEEPSEEK_API_KEY

# Для страницы D05:
read -r -s -p 'OpenRouter API-ключ: ' OPENROUTER_API_KEY
printf '\n'
export OPENROUTER_API_KEY
export OPENROUTER_MODEL=minimax/minimax-m3:free

./web/build/install/transaction-import-web/bin/transaction-import-web
```

Открой в браузере `http://127.0.0.1:8080`. На основной странице можно
выбрать модель, глубину анализа, формат ответа и token budget. Отдельная
страница `/experiments/d01` оставляет только baseline unrestricted-сценарий:
формат ответа фиксирован, выбора формата и блока сравнения нет.

Для сравнения D02 вручную запусти «Без ограничений», затем «Контролируемый
JSON», не меняя текст, модель, reasoning и явный token budget. Сводка строится
только для дословно совпадающих входов и общих параметров. История хранится
только в памяти страницы и исчезает после перезагрузки; нажатие на запись
восстанавливает текст и все параметры формы.

Контролируемый режим показывает raw JSON, `finish_reason`, токены, время,
длину ответа, schema signature и результат локальной проверки. Корректный
`status=not_applicable` является явным отказом и никогда не помечается
пригодным для импорта.

Thinking mode DeepSeek может вернуть внутреннее рассуждение в
`reasoning_content`, а финальный `message.content` при фиксированном
`max_tokens` может оказаться пустым. Web-клиент показывает такую ситуацию как
невалидный контролируемый результат с `finish_reason` и длиной reasoning, без
502 и без возможности импорта; по умолчанию controlled JSON использует
`reasoning=disabled`. Полный текст `reasoning_content` не сохраняется.

Token budget имеет три состояния:

- `Авто` сохраняет текущие defaults: unrestricted не отправляет `max_tokens`,
  controlled JSON использует `1200`, D03 — `1600`, D04 — `1200`, D05 — `4096`;
- `Без ограничения` не отправляет `max_tokens`, поэтому provider применяет
  собственный default/maximum;
- положительное явное число отправляется без искусственного верхнего лимита
  приложения. Provider может отклонить слишком большое значение.

`max_tokens` ограничивает completion budget, а не общий `total_tokens`.
Prompt tokens в него не входят; при reasoning budget также расходуется на
`reasoning_content`. UI и JSON evidence показывают requested/applied controls,
usage и provider-specific token warning.

## Web-страницы экспериментов D01-D05

Пользовательский запуск заданий выполняется через web. После запуска
web-сервера откройте `http://127.0.0.1:8080/experiments`.

- `/experiments/d01` — baseline unrestricted без выбора формата и сравнения;
- `/experiments/d02` — банковский statement, базовый и controlled JSON;
- `/experiments/d03` — свободная задача и четыре prompt strategies;
- `/experiments/d04` — свободная задача и preset temperature `0`, `0.7`, `1.2`;
- `/experiments/d05` — свободная задача и preset DeepSeek/OpenRouter models.

Входные данные страниц соответствуют task-файлам в `examples`:
`demo-task-d01.txt` — D01, `demo-task-d02.txt` — D02,
`demo-task-d03.txt` — D03, `demo-task-d04.txt` — D04,
`demo-task-d05.txt` — D05. Для D03-D05 полный prompt вставляется
в textarea страницы.

Web runner выполняет preset-вызовы последовательно, показывает progress и
provider errors, а завершённый результат можно скачать как JSON evidence.
- `/experiments/d05` дополнительно требует `OPENROUTER_API_KEY`; его preset
  OpenRouter использует `OPENROUTER_MODEL`, а без переменной сохраняет
  `z-ai/glm-5.2:free`.

## Контролируемый JSON

В режиме `Авто` controlled-запрос передаёт
`response_format={"type":"json_object"}`, `max_tokens=1200` и явную
инструкцию закончить сразу после корневого объекта. Явный token-budget override
меняет только completion budget и фиксируется в evidence.
Ответ обязан содержать `status`, `rejection_reason`, `transactions` и
`unparsed_fragments`. Неизвестные поля, неверные типы, нарушение предметных
инвариантов и `finish_reason`, отличный от `stop`, делают результат невалидным.

Для демонстрации используется закрытый синтетический каталог:

- `food.groceries`;
- `income.salary`;
- `services.digital`;
- `transfer.internal`;
- `health.pharmacy`;
- `food.cafe`;
- `transport`;
- `housing`.

Категория вне списка не принимается. Если подходящего значения нет, модель
должна вернуть `category_id=null`, `needs_review=true` и конкретную проблему.
Этот каталог не заменяет справочник целевого приложения.

После демонстрации останови сервер и выполни:

```bash
unset DEEPSEEK_API_KEY OPENROUTER_API_KEY OPENROUTER_MODEL
```

Текущая конфигурация провайдера:

- base URL: `https://api.deepseek.com`;
- web UI предлагает `deepseek-v4-flash` и `deepseek-v4-pro`;
- D05 использует `OPENROUTER_MODEL` или default `z-ai/glm-5.2:free` для
  server-side OpenRouter preset;
- reasoning: `disabled`, `low`, `high` или `max`;
- при включённом reasoning `reasoning_effort` отправляется отдельным
  top-level полем, а не внутри `thinking`;
- streaming: отключён;
- контролируемый web-режим: JSON Output, `max_tokens=1200` и явное завершение
  после корневого объекта;
- локальная схема: `transaction-import.v1`;
- timeout web-запроса: до 300 секунд; соединение устанавливается до 30 секунд.

## Temperature experiment

Модуль `d04` выполняет 15 последовательных controlled JSON вызовов:
`temperature=0`, `0.7` и `1.2`, по пять повторов на значение. Модель,
fixture и остальные request controls не меняются. Reference содержит шесть
операций hard fixture; отчёт отдельно хранит schema validation, importability,
field-level accuracy, лишние операции, canonical response fingerprint,
`finish_reason`, usage и latency.

Эксперимент требует `DEEPSEEK_API_KEY` в окружении. Файл отчёта может содержать
полные ответы модели, поэтому его нельзя отправлять в логи или публиковать
вместе с чувствительными выписками.

## Model comparison experiment

Модуль `d05` использует один hard fixture и controlled JSON contract:
`temperature=0`, reasoning `high`, `response_format=json_object`,
`max_tokens=4096`, `stream=false`, по пять последовательных запусков на модель.
DeepSeek вызывается напрямую через `DEEPSEEK_API_KEY`; OpenRouter использует
`OPENROUTER_API_KEY` и model ID из `OPENROUTER_MODEL`.

`OPENROUTER_MODEL` — необязательная server-side переменная. Если она не задана
или пустая, используется `z-ai/glm-5.2:free`. Например:

```bash
export OPENROUTER_MODEL=minimax/minimax-m3:free
```

Перед серией D05 проверяет именно настроенный ID через OpenRouter `/models`.
Недоступная модель или несовместимый параметр фиксируются как compatibility
error; модель не заменяется автоматически. Provider/model сохраняются в отчёте.

Для web D05 можно вставить полный prompt из
`examples/demo-task-d05.txt` в свободное поле задачи.

Запуск требует обе переменные API-ключей. `OPENROUTER_MODEL` меняет preset
после запуска процесса без правки исходников; web-сервер также читает её при
старте. В DeepSeek cost estimate использует официальные V4 rates и помечает
peak/off-peak UTC window; если cache fields не пришли, input считается cache
miss. Для OpenRouter `:free` стоимость не трактуется как отсутствие квоты:
отчёт отдельно сохраняет provider metadata и compatibility/rate-limit errors.

Фактический запуск на hard fixture подтвердил OpenRouter model metadata, но
дал HTTP 429 upstream rate limit после первого completion запроса. Оба
DeepSeek-провайдера выполнили по пять запросов, однако при reasoning
`max_tokens=4096` каждый завершился `finish_reason=length` без финального JSON:
reasoning израсходовал весь бюджет. Полный JSON evidence сохранён в parent
репозитории дневника D05; provider не заменялся молча.


## Границы безопасности

- Во время разработки используйте только синтетические или обезличенные выписки.
- Храните ключ провайдера в переменной окружения или локальном менеджере секретов.
- Не помещайте ключи в исходный код, fixtures, тесты, историю shell или логи.
- Вызывайте провайдера из доверенного server-side процесса. Клиентские
  приложения должны обращаться к backend приложения, а не встраивать этот ключ.
