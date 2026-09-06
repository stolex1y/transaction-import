const form = document.querySelector("#extract-form");
const statement = document.querySelector("#statement");
const model = document.querySelector("#model");
const reasoning = document.querySelector("#reasoning");
const responseMode = document.querySelector("#mode");
const fixedResponseMode = document.documentElement.dataset.responseMode || null;
const maxTokensMode = document.querySelector("#max-tokens-mode");
const maxTokensInput = document.querySelector("#max-tokens");
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
maxTokensMode?.addEventListener("change", syncTokenBudgetInput);
syncTokenBudgetInput();

form.addEventListener("submit", async (event) => {
    event.preventDefault();
    const text = statement.value.trim();
    if (!text) {
        showError("Введите текст банковской выписки.");
        return;
    }

    let tokenBudget;
    try {
        tokenBudget = readTokenBudget();
    } catch (error) {
        showError(error.message);
        return;
    }
    const request = {
        statement: text,
        model: model.value,
        reasoning: reasoning.value,
        mode: fixedResponseMode || responseMode?.value || "unrestricted",
        ...tokenBudget,
    };
    setBusy(true);
    status.className = "status";
    status.textContent = request.mode === "controlled_json"
        ? "Запрашиваем контролируемый JSON..."
        : "Запрашиваем ответ без ограничений...";

    try {
        const endpoint = fixedResponseMode ? "/api/d01/extract" : "/api/extract";
        const response = await fetch(endpoint, {
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
        `${modeLabel(run.mode)} · ${run.model} · ${reasoningLabel(run.reasoning)} · ${tokenBudgetLabel(run)}`;
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
    if (!comparison || !comparisonGrid || !comparisonSettings) {
        return false;
    }
    const counterpart = history.find((candidate) =>
        candidate !== currentRun
        && candidate.mode !== currentRun.mode
        && candidate.statement === currentRun.statement
        && candidate.model === currentRun.model
        && candidate.reasoning === currentRun.reasoning
        && candidate.max_tokens_mode === currentRun.max_tokens_mode
        && candidate.requested_max_tokens === currentRun.requested_max_tokens
    );

    if (!counterpart) {
        comparison.hidden = true;
        comparisonGrid.replaceChildren();
        return false;
    }

    const unrestricted = currentRun.mode === "unrestricted" ? currentRun : counterpart;
    const controlled = currentRun.mode === "controlled_json" ? currentRun : counterpart;
    comparisonSettings.textContent =
        `${currentRun.model} · ${reasoningLabel(currentRun.reasoning)} · ${tokenBudgetLabel(currentRun)}`;
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
            `${modeLabel(run.mode)} · ${run.model} · ${reasoningLabel(run.reasoning)} · ${tokenBudgetLabel(run)}`;
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
    const stats = [
        `Токены: ${formatUsage(run.usage)}`,
        `Время: ${formatDuration(run.processing_time_ms)}`,
        `Длина: ${run.text_length ?? run.text.length} симв.`,
        `Завершение: ${run.finish_reason ?? "нет данных"}`,
    ];
    if (run.token_budget_warning) {
        stats.push(`Token budget: ${run.token_budget_warning}`);
    }
    container.replaceChildren(...stats.map(createStat));
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
        max_tokens_mode: run.max_tokens_mode,
        requested_max_tokens: run.requested_max_tokens,
        token_budget_warning: run.token_budget_warning,
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
    if (!comparison || paired) {
        return;
    }
    const counterpart = run.mode === "unrestricted"
        ? "контролируемый JSON"
        : "ответ без ограничений";
    status.textContent += ` Для сравнения запустите ${counterpart} с теми же входными данными.`;
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
    if (responseMode) {
        responseMode.value = run.mode;
    }
    restoreTokenBudget(run);
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

function tokenBudgetLabel(run) {
    if (run.max_tokens_mode === "unlimited") {
        return "tokens: без ограничения";
    }
    if (run.requested_max_tokens != null) {
        return `tokens: ${run.requested_max_tokens}`;
    }
    return run.controls?.max_tokens == null
        ? "tokens: авто"
        : `tokens: авто → ${run.controls.max_tokens}`;
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
    if (responseMode) {
        responseMode.disabled = value;
    }
    if (maxTokensMode) {
        maxTokensMode.disabled = value;
    }
    if (maxTokensInput) {
        maxTokensInput.disabled = value || maxTokensMode?.value !== "explicit";
    }
    submitButton.disabled = value;
    submitButton.textContent = value ? "Обрабатываем..." : "Обработать выписку";
}
function syncTokenBudgetInput() {
    if (!maxTokensInput) {
        return;
    }
    const explicit = maxTokensMode?.value === "explicit";
    maxTokensInput.disabled = !explicit || submitButton.disabled;
}

function readTokenBudget() {
    const selectedMode = maxTokensMode?.value || "auto";
    const rawValue = maxTokensInput?.value.trim() || "";
    if (selectedMode === "unlimited") {
        return { max_tokens_mode: "unlimited", max_tokens: null };
    }
    if (selectedMode === "explicit" && rawValue) {
        const value = Number(rawValue);
        if (!Number.isFinite(value) || !Number.isInteger(value) || value <= 0) {
            throw new Error("Лимит completion tokens должен быть положительным целым числом.");
        }
        return { max_tokens_mode: "explicit", max_tokens: value };
    }
    return { max_tokens_mode: "auto", max_tokens: null };
}

function restoreTokenBudget(run) {
    if (!maxTokensMode || !maxTokensInput) {
        return;
    }
    maxTokensMode.value = run.max_tokens_mode === "unlimited"
        ? "unlimited"
        : run.requested_max_tokens == null ? "auto" : "explicit";
    maxTokensInput.value = run.requested_max_tokens ?? "";
    syncTokenBudgetInput();
}


function showError(message) {
    status.className = "status error";
    status.textContent = message;
}
