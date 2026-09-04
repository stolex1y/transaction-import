# Transaction Import

Kotlin Multiplatform core and a JVM command-line client for extracting financial
transactions from text bank statements through an OpenAI-compatible provider.

## Modules

- `core` — platform-independent models, JSON serialization, provider contract,
  and text extraction service;
- `cli` — JVM application that reads one statement from `stdin`, calls the
  provider, and writes the response to `stdout`;
- `examples` — synthetic input data only.

The core has no UI or server-framework dependency. A Kotlin/JVM backend can use
it directly, while Android, iOS, and Desktop clients can reuse its models and
serialization without receiving a provider API key.

## Requirements

- JDK 17 or 21; JVM bytecode target is 17;
- JDK 25 is not supported by the configured Kotlin/Gradle toolchain.
- access to Maven Central for the first dependency download.

## Build and test

```bash
./gradlew test
```

Tests use fake gateways and Ktor `MockEngine`. They do not access the network,
require an API key, or send statement data to an external service.

## CLI usage

The provider key must exist only in the process environment:

```bash
read -r -s -p 'Provider API key: ' DEEPSEEK_API_KEY
printf '\n'
export DEEPSEEK_API_KEY

./gradlew :cli:installDist
./cli/build/install/transaction-import/bin/transaction-import parse \
  < examples/statement.txt

unset DEEPSEEK_API_KEY
```

The model response is written to `stdout`. The `finish_reason` and token usage
summary are written to `stderr`. The CLI does not persist input or output.

The current provider configuration is:

- base URL: `https://api.deepseek.com`;
- model: `deepseek-v4-flash`;
- thinking mode: disabled;
- streaming: disabled.

## Security boundaries

- Use only synthetic or anonymized statements during development.
- Keep the provider key in an environment variable or a local secret manager.
- Do not put keys in source files, fixtures, tests, shell history, or logs.
- Call the provider from a trusted server-side process. Client applications
  should communicate with the application backend instead of embedding this
  provider key.
