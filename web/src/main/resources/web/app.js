const form = document.querySelector("#extract-form");
const statement = document.querySelector("#statement");
const model = document.querySelector("#model");
const reasoning = document.querySelector("#reasoning");
const submitButton = document.querySelector("#submit-button");
const status = document.querySelector("#status");
const currentResult = document.querySelector("#current-result");
const currentSettings = document.querySelector("#current-settings");
const answer = document.querySelector("#answer");
const metadata = document.querySelector("#metadata");
const historyElement = document.querySelector("#history");
const history = [];

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const text = statement.value.trim();
    if (!text) {
        showError("Введите текст банковской выписки.");
        return;
    }

    setBusy(true);
    status.className = "status";
    status.textContent = "Обрабатываем выписку...";

    try {
        const response = await fetch("/api/extract", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                statement: text,
                model: model.value,
                reasoning: reasoning.value,
            }),
        });
        const payload = await response.json();
        if (!response.ok) {
            throw new Error(payload.error || "Сервер вернул ошибку.");
        }

        const run = {
            ...payload,
            createdAt: new Date().toLocaleTimeString("ru-RU"),
        };
        history.unshift(run);
        renderCurrent(run);
        renderHistory();
        status.className = "status success";
        status.textContent = "Выписка обработана.";
    } catch (error) {
        showError(error.message);
    } finally {
        setBusy(false);
    }
});

function renderCurrent(run) {
    currentResult.hidden = false;
    currentSettings.textContent = `${run.model} · ${reasoningLabel(run.reasoning)}`;
    answer.textContent = run.text;
    metadata.textContent = JSON.stringify({
        model: run.model,
        reasoning: run.reasoning,
        finish_reason: run.finish_reason,
        usage: run.usage,
    }, null, 2);
}

function renderHistory() {
    historyElement.replaceChildren();
    if (history.length === 0) {
        const empty = document.createElement("p");
        empty.className = "muted";
        empty.textContent = "Обработок пока нет.";
        historyElement.append(empty);
        return;
    }

    history.forEach((run, index) => {
        const item = document.createElement("article");
        item.className = "history-item";

        const heading = document.createElement("div");
        heading.className = "history-heading";
        heading.textContent = `Обработка ${history.length - index} · ${run.createdAt}`;

        const settings = document.createElement("span");
        settings.className = "muted";
        settings.textContent = `${run.model} · ${reasoningLabel(run.reasoning)}`;
        heading.append(settings);

        const result = document.createElement("pre");
        result.className = "history-answer";
        result.textContent = run.text;

        item.append(heading, result);
        historyElement.append(item);
    });
}

function reasoningLabel(value) {
    return {
        disabled: "анализ: стандартный",
        low: "анализ: базовый",
        high: "анализ: подробный",
        max: "анализ: максимальный",
    }[value] || value;
}

function setBusy(value) {
    submitButton.disabled = value;
    submitButton.textContent = value ? "Обрабатываем..." : "Обработать выписку";
}

function showError(message) {
    status.className = "status error";
    status.textContent = message;
}
