# Transaction Import

Ядро Kotlin Multiplatform, консольный клиент JVM и локальный web-клиент для
извлечения финансовых операций из текстовых банковских выписок через
совместимый с OpenAI провайдер. Web-клиент поддерживает свободный ответ и
контролируемый JSON с локальной проверкой перед импортом.

## Модули

- `core` — платформонезависимые модели, строгий контракт JSON, локальная
  валидация, контракт провайдера и сервис извлечения;
- `transport` — общий JVM Ktor Client-адаптер DeepSeek для CLI и web-сервера;
- `cli` — приложение JVM, которое читает одну выписку из `stdin`, вызывает
  свободный режим провайдера и записывает ответ в `stdout`;
- `web` — локальный Ktor-сервер и статический русскоязычный интерфейс с ручным
  сравнением свободного и контролируемого режимов;
- `d04` — JVM-исполнитель controlled JSON temperature-серии для
  синтетического hard fixture; сохраняет конфигурацию, reference, raw ответы,
  validation, field-level accuracy, fingerprint и latency;
- `examples` — только синтетические входные данные:
  - `demo-statement.txt` — простой сценарий;
  - `demo-statement-medium-formats.txt` — средний уровень: таблица,
    перенос строки, комиссия и частичный возврат;
  - `demo-statement-medium-mixed.txt` — средний уровень: разные форматы дат,
    суммы в RUB, зарплата и перевод между своими счетами;
  - `demo-statement-hard.txt` — сложный уровень: шум, сторно, комиссия,
    переносы, суммы только в RUB и недоверенная строка описания.

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

## Использование CLI

Ключ провайдера должен находиться только в окружении процесса:

```bash
read -r -s -p 'API-ключ провайдера: ' DEEPSEEK_API_KEY
printf '\n'
export DEEPSEEK_API_KEY

./gradlew :cli:installDist
./cli/build/install/transaction-import/bin/transaction-import parse \
  < examples/demo-statement.txt

unset DEEPSEEK_API_KEY
```

Для демонстрации средней и высокой сложности укажи другой fixture в той же
команде:

```bash
./cli/build/install/transaction-import/bin/transaction-import parse \
  < examples/demo-statement-medium-formats.txt

./cli/build/install/transaction-import/bin/transaction-import parse \
  < examples/demo-statement-medium-mixed.txt

./cli/build/install/transaction-import/bin/transaction-import parse \
  < examples/demo-statement-hard.txt
```

Ответ модели записывается в `stdout`. Поля `finish_reason` и сводка по
использованным токенам записываются в `stderr`. CLI не сохраняет вход или
выходные данные.

## Использование web-приложения

Собери и запусти локальный сервер:

```bash
./gradlew :web:installDist

read -r -s -p 'API-ключ провайдера: ' DEEPSEEK_API_KEY
printf '\n'
export DEEPSEEK_API_KEY

./web/build/install/transaction-import-web/bin/transaction-import-web
```

Открой в браузере `http://127.0.0.1:8080`. Вставь текст банковской выписки,
выбери модель, глубину анализа и формат ответа, затем нажми «Обработать
выписку».

Для сравнения вручную запусти «Без ограничений», затем «Контролируемый JSON»,
не меняя текст, модель и reasoning. Сводка строится только для дословно
совпадающих входов и общих параметров. История хранится только в памяти страницы
и исчезает после перезагрузки; нажатие на запись восстанавливает текст и все
параметры формы.

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

## Контролируемый JSON

Контролируемый запрос передаёт `response_format={"type":"json_object"}`,
`max_tokens=1200` и явную инструкцию закончить сразу после корневого объекта.
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
unset DEEPSEEK_API_KEY
```

Текущая конфигурация провайдера:

- base URL: `https://api.deepseek.com`;
- CLI по умолчанию использует `deepseek-v4-flash`;
- web UI предлагает `deepseek-v4-flash` и `deepseek-v4-pro`;
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

```bash
./gradlew :d04:installDist
./d04/build/install/transaction-import-d04/bin/transaction-import-d04 \
  examples/demo-statement-hard.txt \
  > d04-report.json
```

Команда требует `DEEPSEEK_API_KEY` в окружении. Файл отчёта может содержать
полные ответы модели, поэтому его нельзя отправлять в логи или публиковать
вместе с чувствительными выписками.


## Границы безопасности

- Во время разработки используйте только синтетические или обезличенные выписки.
- Храните ключ провайдера в переменной окружения или локальном менеджере секретов.
- Не помещайте ключи в исходный код, fixtures, тесты, историю shell или логи.
- Вызывайте провайдера из доверенного server-side процесса. Клиентские
  приложения должны обращаться к backend приложения, а не встраивать этот ключ.
