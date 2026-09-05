# Transaction Import

Ядро Kotlin Multiplatform, консольный клиент JVM и локальный web-клиент для
извлечения финансовых операций из текстовых банковских выписок через
совместимый с OpenAI провайдер.

## Модули

- `core` — платформонезависимые модели, JSON-сериализация, контракт провайдера
  и сервис извлечения текста;
- `transport` — общий JVM Ktor Client-адаптер DeepSeek для CLI и web-сервера;
- `cli` — приложение JVM, которое читает одну выписку из `stdin`, вызывает
  провайдера и записывает ответ в `stdout`;
- `web` — локальный Ktor-сервер и статический русскоязычный интерфейс браузера;
- `examples` — только синтетические входные данные:
  - `demo-statement.txt` — простой сценарий;
  - `demo-statement-medium-formats.txt` — средний уровень: таблица,
    перенос строки, комиссия и частичный возврат;
  - `demo-statement-medium-mixed.txt` — средний уровень: разные форматы дат,
    две валюты, зарплата и перевод между своими счетами;
  - `demo-statement-hard.txt` — сложный уровень: шум, сторно, комиссия,
    переносы, разные валюты и недоверенная строка описания.

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

## Использование web demo

Собери и запусти локальный сервер:

```bash
./gradlew :web:installDist

read -r -s -p 'API-ключ провайдера: ' DEEPSEEK_API_KEY
printf '\n'
export DEEPSEEK_API_KEY

./web/build/install/transaction-import-web/bin/transaction-import-web
```

Открой в браузере `http://127.0.0.1:8080`. Вставь содержимое одного из
файлов `examples/demo-statement*.txt` в `textarea`, выбери модель и уровень
reasoning, затем нажми «Извлечь операции».

Web-сервер поддерживает `deepseek-v4-flash` и `deepseek-v4-pro`. История
запусков хранится только в памяти страницы и исчезает после перезагрузки.
API-ключ остаётся в server-side процессе и не передаётся браузеру.

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
- streaming: отключён.

## Границы безопасности

- Во время разработки используйте только синтетические или обезличенные выписки.
- Храните ключ провайдера в переменной окружения или локальном менеджере секретов.
- Не помещайте ключи в исходный код, fixtures, тесты, историю shell или логи.
- Вызывайте провайдера из доверенного server-side процесса. Клиентские
  приложения должны обращаться к backend приложения, а не встраивать этот ключ.
