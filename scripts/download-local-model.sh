#!/usr/bin/env bash
set -euo pipefail

source "$(dirname "${BASH_SOURCE[0]}")/local-ollama-config.sh"

if ! command -v ollama >/dev/null 2>&1; then
    printf 'Не найден Ollama CLI. Установите Ollama перед загрузкой модели.\n' >&2
    exit 127
fi

if ollama_local list >/dev/null 2>&1; then
    printf 'Ollama runtime уже доступен на %s. Остановите его перед запуском download script.\n' "$LOCAL_OLLAMA_HOST" >&2
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
        printf 'Не удалось запустить временный Ollama runtime на %s. Проверьте порт.\n' "$LOCAL_OLLAMA_HOST" >&2
        exit 1
    fi
    if ollama_local list >/dev/null 2>&1; then
        sleep 0.5
        if ! kill -0 "$OLLAMA_PID" 2>/dev/null; then
            printf 'Запущенный download runtime завершился во время readiness check; проверьте порт %s.\n' "$LOCAL_OLLAMA_HOST" >&2
            exit 1
        fi
        ready=true
        break
    fi
    sleep 0.5
done

if [[ "$ready" != true ]]; then
    printf 'Временный Ollama runtime не стал доступен на %s за 30 секунд.\n' "$LOCAL_OLLAMA_HOST" >&2
    exit 1
fi

if ! kill -0 "$OLLAMA_PID" 2>/dev/null; then
    printf 'Временный Ollama runtime завершился перед загрузкой модели.\n' >&2
    exit 1
fi

printf 'Загружаю %s через Ollama registry...\n' "$LOCAL_OLLAMA_MODEL"
ollama_local pull "$LOCAL_OLLAMA_MODEL"
ollama_local show "$LOCAL_OLLAMA_MODEL" >/dev/null
if ! kill -0 "$OLLAMA_PID" 2>/dev/null; then
    printf 'Временный Ollama runtime завершился после загрузки модели.\n' >&2
    exit 1
fi
printf 'Модель %s установлена. Запустите scripts/start-local-ollama.sh для локального inference.\n' "$LOCAL_OLLAMA_MODEL"
