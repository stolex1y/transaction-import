const form = document.querySelector("#extract-form");
const statement = document.querySelector("#statement");
const model = document.querySelector("#model");
const reasoning = document.querySelector("#reasoning");
const responseMode = document.querySelector("#mode");
const submitButton = document.querySelector("#submit-button");
const status = document.querySelector("#status");
const currentResult = document.querySelector("#current-result");
const currentSettings = document.querySelector("#current-settings");
const currentStats = document.querySelector("#current-stats");
const validationElement = document.querySelector("#validation");
const answer = document.querySelector("#answer");
const metadata = document.querySelector("#metadata");
const comparison = document.querySelector("#comparison");
const comparisonSettings = document.querySelector("#comparison-settings");
const comparisonGrid = document.querySelector("#comparison-grid");
const historyElement = document.querySelector("#history");
const history = [];
let nextSequence = 1;

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const text = statement.value.trim();
    if (!text) {
        showError("Введите текст банковской выписки.");
        return;
    }

    const request = {
        statement: text,
        model: model.value,
        reasoning: reasoning.value,
        mode: responseMode.value,
    };
    setBusy(true);
    status.className = "status";
    status.textContent = request.mode === "controlled_json"
        ? "Запрашиваем контролируемый JSON..."
        : "Запрашиваем ответ без ограничений...";

    try {
        const response = await fetch("/api/extract", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(request),
        });
        const payload = await response.json();
        if (!response.ok) {
            throw new Error(payload.error || "Сервер вернул ошибку.");
        }

        const run = {
            ...payload,
            statement: text,
            createdAt: new Date().toLocaleTimeString("ru-RU"),
            sequence: nextSequence++,
        };
        history.unshift(run);
        renderCurrent(run);
        const paired = renderComparison(run);
        renderHistory();
        showRunStatus(run, paired);
    } catch (error) {
        showError(error.message);
    } finally {
        setBusy(false);
    }
});

function renderCurrent(run) {
    currentResult.hidden = false;
    currentSettings.textContent =
        `${modeLabel(run.mode)} · ${run.model} · ${reasoningLabel(run.reasoning)}`;
    renderRunStats(currentStats, run);
    renderValidation(run.validation);
    answer.textContent = run.text;
    metadata.textContent = JSON.stringify(metadataFor(run), null, 2);
}

function renderValidation(validation) {
    validationElement.replaceChildren();
    validationElement.hidden = !validation;
    if (!validation) {
        return;
    }

    validationElement.className = validation.valid
        ? (validation.importable ? "validation success" : "validation warning")
        : "validation error";

    const heading = document.createElement("strong");
    if (!validation.valid) {
        heading.textContent = "Локальная проверка: ошибка";
    } else if (validation.importable) {
        heading.textContent = "Локальная проверка: можно импортировать";
    } else {
        heading.textContent = "Локальная проверка: корректный отказ";
    }
    validationElement.append(heading);

    const summary = document.createElement("p");
    const parts = [
        `status: ${validation.status ?? "—"}`,
        `операций: ${validation.transaction_count ?? "—"}`,
        `схема: ${validation.schema_signature ?? "—"}`,
    ];
    summary.textContent = parts.join(" · ");
    validationElement.append(summary);

    if (validation.rejection_reason) {
        const rejection = document.createElement("p");
        rejection.textContent = `Причина: ${validation.rejection_reason}`;
        validationElement.append(rejection);
    }

    if (validation.errors?.length) {
        const errors = document.createElement("ul");
        validation.errors.forEach((message) => {
            const item = document.createElement("li");
            item.textContent = message;
            errors.append(item);
        });
        validationElement.append(errors);
    }
}

function renderComparison(currentRun) {
    const counterpart = history.find((candidate) =>
        candidate !== currentRun
        && candidate.mode !== currentRun.mode
        && candidate.statement === currentRun.statement
        && candidate.model === currentRun.model
        && candidate.reasoning === currentRun.reasoning
    );

    if (!counterpart) {
        comparison.hidden = true;
        comparisonGrid.replaceChildren();
        return false;
    }

    const unrestricted = currentRun.mode === "unrestricted" ? currentRun : counterpart;
    const controlled = currentRun.mode === "controlled_json" ? currentRun : counterpart;
    comparisonSettings.textContent =
        `${currentRun.model} · ${reasoningLabel(currentRun.reasoning)}`;
    comparisonGrid.replaceChildren(
        createComparisonCard(unrestricted),
        createComparisonCard(controlled),
    );
    comparison.hidden = false;
    return true;
}

function createComparisonCard(run) {
    const card = document.createElement("article");
    card.className = "comparison-card";

    const heading = document.createElement("div");
    heading.className = "comparison-card-heading";
    const title = document.createElement("h3");
    title.textContent = modeLabel(run.mode);
    const badge = document.createElement("span");
    badge.className = validationBadgeClass(run);
    badge.textContent = validationLabel(run);
    heading.append(title, badge);

    const stats = document.createElement("div");
    stats.className = "result-stats";
    renderRunStats(stats, run);

    const result = document.createElement("pre");
    result.className = "answer comparison-answer";
    result.textContent = run.text;

    const details = document.createElement("pre");
    details.className = "metadata";
    details.textContent = JSON.stringify(metadataFor(run), null, 2);

    card.append(heading, stats, result, details);
    return card;
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

    history.forEach((run) => {
        const item = document.createElement("button");
        item.type = "button";
        item.className = "history-item";
        item.setAttribute("aria-label", `Открыть обработку ${run.sequence} от ${run.createdAt}`);
        item.addEventListener("click", () => restoreRun(run));

        const heading = document.createElement("div");
        heading.className = "history-heading";
        heading.textContent = `Обработка ${run.sequence} · ${run.createdAt}`;

        const settings = document.createElement("span");
        settings.className = "muted";
        settings.textContent =
            `${modeLabel(run.mode)} · ${run.model} · ${reasoningLabel(run.reasoning)}`;
        heading.append(settings);

        const stats = document.createElement("div");
        stats.className = "history-stats";
        renderRunStats(stats, run);

        const result = document.createElement("pre");
        result.className = "history-answer";
        result.textContent = run.text;

        item.append(heading, stats, result);
        historyElement.append(item);
    });
}

function renderRunStats(container, run) {
    container.replaceChildren(
        createStat(`Токены: ${formatUsage(run.usage)}`),
        createStat(`Время: ${formatDuration(run.processing_time_ms)}`),
        createStat(`Длина: ${run.text_length ?? run.text.length} симв.`),
        createStat(`Завершение: ${run.finish_reason ?? "нет данных"}`),
    );
}

function createStat(text) {
    const stat = document.createElement("span");
    stat.className = "stat";
    stat.textContent = text;
    return stat;
}

function metadataFor(run) {
    return {
        mode: run.mode,
        model: run.model,
        reasoning: run.reasoning,
        controls: run.controls,
        finish_reason: run.finish_reason,
        usage: run.usage,
        reasoning_content_length: run.reasoning_content_length,
        processing_time_ms: run.processing_time_ms,
        text_length: run.text_length,
        validation: run.validation,
    };
}

function validationBadgeClass(run) {
    if (!run.validation) {
        return "result-label neutral";
    }
    if (!run.validation.valid) {
        return "result-label error";
    }
    return run.validation.importable ? "result-label success" : "result-label warning";
}

function validationLabel(run) {
    if (!run.validation) {
        return "свободный текст";
    }
    if (!run.validation.valid) {
        return "ошибка контракта";
    }
    return run.validation.importable ? "валиден" : "отказ";
}

function showRunStatus(run, paired) {
    if (run.validation && !run.validation.valid) {
        status.className = "status error";
        status.textContent = "Ответ получен, но не прошёл локальную проверку.";
        return;
    }

    status.className = "status success";
    if (run.validation && !run.validation.importable) {
        status.textContent = "Получен корректный отказ: операции не импортируются.";
    } else {
        status.textContent = "Выписка обработана.";
    }
    if (!paired) {
        const counterpart = run.mode === "unrestricted"
            ? "контролируемый JSON"
            : "ответ без ограничений";
        status.textContent += ` Для сравнения запустите ${counterpart} с теми же входными данными.`;
    }
}

function formatUsage(usage) {
    if (!usage) {
        return "нет данных";
    }
    return `${usage.total_tokens ?? "—"} всего · ${usage.prompt_tokens ?? "—"} вход · ${usage.completion_tokens ?? "—"} выход`;
}

function formatDuration(milliseconds) {
    if (!Number.isFinite(milliseconds)) {
        return "нет данных";
    }
    if (milliseconds < 1000) {
        return `${milliseconds} мс`;
    }
    return `${(milliseconds / 1000).toFixed(1).replace(".", ",")} с`;
}

function restoreRun(run) {
    statement.value = run.statement;
    model.value = run.model;
    reasoning.value = run.reasoning;
    responseMode.value = run.mode;
    renderCurrent(run);
    renderComparison(run);
    status.className = "status";
    status.textContent = "Предыдущая обработка восстановлена.";
    window.scrollTo({ top: 0, behavior: "smooth" });
}

function modeLabel(value) {
    return {
        unrestricted: "Без ограничений",
        controlled_json: "Контролируемый JSON",
    }[value] || value;
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
    statement.disabled = value;
    model.disabled = value;
    reasoning.disabled = value;
    responseMode.disabled = value;
    submitButton.disabled = value;
    submitButton.textContent = value ? "Обрабатываем..." : "Обработать выписку";
}

function showError(message) {
    status.className = "status error";
    status.textContent = message;
}
