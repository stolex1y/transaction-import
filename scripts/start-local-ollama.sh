#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/local-ollama-config.sh"

if ! command -v ollama >/dev/null 2>&1; then
    printf 'Не найден Ollama CLI. Установите Ollama перед запуском локального runtime.\n' >&2
    exit 127
fi

if ollama_local list >/dev/null 2>&1; then
    printf 'Ollama runtime уже доступен на %s. Остановите его перед запуском второго runtime.\n' "$LOCAL_OLLAMA_HOST" >&2
    exit 1
fi

env \
    OLLAMA_HOST="$LOCAL_OLLAMA_HOST" \
    OLLAMA_MODELS="$LOCAL_OLLAMA_MODELS_DIR" \
    OLLAMA_CONTEXT_LENGTH="$LOCAL_OLLAMA_CONTEXT_LENGTH" \
    OLLAMA_NO_CLOUD=1 \
    ollama serve &
readonly OLLAMA_PID=$!

cleanup() {
    kill "$OLLAMA_PID" 2>/dev/null || true
    wait "$OLLAMA_PID" 2>/dev/null || true
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

ready=false
for ((attempt = 0; attempt < 60; attempt++)); do
    if ! kill -0 "$OLLAMA_PID" 2>/dev/null; then
        printf 'Ollama не смогла запуститься на %s. Проверьте, не занят ли порт.\n' "$LOCAL_OLLAMA_HOST" >&2
        exit 1
    fi
    if ollama_local list >/dev/null 2>&1; then
        sleep 0.5
        if ! kill -0 "$OLLAMA_PID" 2>/dev/null; then
            printf 'Запущенный Ollama runtime завершился во время readiness check; проверьте порт %s.\n' "$LOCAL_OLLAMA_HOST" >&2
            exit 1
        fi
        ready=true
        break
    fi
    sleep 0.5
done

if [[ "$ready" != true ]]; then
    printf 'Ollama runtime не стал доступен на %s за 30 секунд.\n' "$LOCAL_OLLAMA_HOST" >&2
    exit 1
fi

if ! ollama_local show "$LOCAL_OLLAMA_MODEL" >/dev/null 2>&1; then
    printf 'Модель %s не найдена. Запустите scripts/download-local-model.sh; startup ничего не скачивает.\n' "$LOCAL_OLLAMA_MODEL" >&2
    exit 1
fi
if ! kill -0 "$OLLAMA_PID" 2>/dev/null; then
    printf 'Ollama runtime завершился после проверки модели.\n' >&2
    exit 1
fi

printf 'Локальный Ollama runtime доступен на http://%s; OLLAMA_NO_CLOUD=1, context=%s. Остановите его через Ctrl+C.\n' \
    "$LOCAL_OLLAMA_HOST" "$LOCAL_OLLAMA_CONTEXT_LENGTH"
wait "$OLLAMA_PID"
