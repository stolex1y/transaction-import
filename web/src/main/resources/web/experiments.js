const page = document.documentElement;
const kind = page.dataset.experiment;
const taskInput = document.querySelector("#task");
const rubricInput = document.querySelector("#rubric");
const customContainer = document.querySelector("#custom-configurations");
const addCustomButton = document.querySelector("#add-custom");
const runButton = document.querySelector("#run-experiment");
const status = document.querySelector("#experiment-status");
const jobPanel = document.querySelector("#job-panel");
const progress = document.querySelector("#job-progress");
const progressLabel = document.querySelector("#job-progress-label");
const jobMessage = document.querySelector("#job-message");
const preflightPanel = document.querySelector("#preflight-panel");
const preflightGrid = document.querySelector("#preflight-grid");
const resultsPanel = document.querySelector("#results-panel");
const resultsGrid = document.querySelector("#results-grid");
const notes = document.querySelector("#experiment-notes");
const downloadButton = document.querySelector("#download-report");
let currentReport = null;
let customSequence = 1;

addCustomButton.addEventListener("click", () => addCustomConfiguration());
runButton.addEventListener("click", () => startExperiment());
downloadButton.addEventListener("click", () => downloadReport());

async function startExperiment() {
    const task = taskInput.value.trim();
    if (!task) {
        showError("Введите задачу.");
        taskInput.focus();
        return;
    }

    setBusy(true);
    hideResults();
    status.className = "status";
    status.textContent = "Создаём server-side experiment job...";
    const request = {
        task,
        reference_or_rubric: rubricInput.value.trim() || null,
        custom_configurations: readCustomConfigurations(),
    };

    try {
        const response = await fetch(`/api/experiments/${kind}`, {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(request),
        });
        const payload = await response.json();
        if (!response.ok) {
            throw new Error(payload.error || "Не удалось создать эксперимент.");
        }
        jobPanel.hidden = false;
        await pollJob(payload.job_id);
    } catch (error) {
        showError(error.message);
        setBusy(false);
    }
}

async function pollJob(jobId) {
    for (;;) {
        const response = await fetch(`/api/experiments/jobs/${jobId}`, { cache: "no-store" });
        const payload = await response.json();
        if (!response.ok) {
            throw new Error(payload.error || "Не удалось получить статус эксперимента.");
        }
        const total = Math.max(payload.total || 1, 1);
        progress.max = total;
        progress.value = payload.completed || 0;
        progressLabel.textContent = `${payload.completed || 0} / ${payload.total || 0}`;
        jobMessage.textContent = payload.status === "queued"
            ? "Задание поставлено в очередь."
            : `Сервер последовательно выполняет конфигурации: ${payload.status}.`;

        if (payload.status === "completed") {
            currentReport = payload.result;
            renderReport(currentReport);
            status.className = "status success";
            status.textContent = "Эксперимент завершён. Результат можно скачать как JSON evidence.";
            setBusy(false);
            return;
        }
        if (payload.status === "failed") {
            throw new Error(payload.error || "Эксперимент завершился ошибкой.");
        }
        await wait(700);
    }
}

function addCustomConfiguration() {
    if (customContainer.querySelectorAll(".custom-config").length >= 4) {
        showError("Можно добавить не более четырёх custom-конфигураций.");
        return;
    }
    const row = document.createElement("fieldset");
    row.className = "custom-config";
    row.innerHTML = `
        <legend>Exploratory custom ${customSequence++}</legend>
        <div class="custom-grid">
            <label>Название<input data-field="label" value="Custom"></label>
            <label>Модель<select data-field="model">
                <option value="deepseek-v4-flash">deepseek-v4-flash</option>
                <option value="deepseek-v4-pro">deepseek-v4-pro</option>
                <option value="z-ai/glm-5.2:free">z-ai/glm-5.2:free</option>
            </select></label>
            <label>Reasoning<select data-field="reasoning">
                <option value="disabled">disabled</option>
                <option value="low">low</option>
                <option value="high">high</option>
                <option value="max">max</option>
            </select></label>
            <label>Temperature<input data-field="temperature" type="number" min="0" max="2" step="0.1" placeholder="preset"></label>
            <label>Max tokens<input data-field="max_tokens" type="number" min="1" max="16384" placeholder="preset"></label>
        </div>
        <label>System prompt<textarea data-field="system_prompt" rows="3" placeholder="Необязательно: свой system prompt"></textarea></label>
        <button type="button" class="remove-custom secondary-button">Удалить custom</button>
    `;
    row.querySelector(".remove-custom").addEventListener("click", () => row.remove());
    customContainer.append(row);
}

function readCustomConfigurations() {
    return [...customContainer.querySelectorAll(".custom-config")].map((row) => {
        const value = (field) => row.querySelector(`[data-field="${field}"]`).value.trim();
        const temperature = value("temperature");
        const maxTokens = value("max_tokens");
        return {
            label: value("label"),
            model: value("model"),
            reasoning: value("reasoning"),
            temperature: temperature ? Number(temperature) : null,
            max_tokens: maxTokens ? Number(maxTokens) : null,
            system_prompt: value("system_prompt") || null,
        };
    });
}

function renderReport(report) {
    resultsPanel.hidden = false;
    notes.replaceChildren();
    (report.notes || []).forEach((note) => {
        const item = document.createElement("p");
        item.className = "control-note";
        item.textContent = note;
        notes.append(item);
    });
    renderPreflight(report.preflight || []);
    resultsGrid.replaceChildren(...(report.runs || []).map(createRunCard));
}

function renderPreflight(items) {
    preflightPanel.hidden = items.length === 0;
    preflightGrid.replaceChildren(...items.map((item) => {
        const card = document.createElement("article");
        card.className = `preflight-card ${item.passed ? "success" : "error"}`;
        const title = document.createElement("strong");
        title.textContent = `${item.provider} · ${item.model}`;
        const state = document.createElement("p");
        state.textContent = `${item.passed ? "passed" : "failed"} · reasoning: ${item.reasoning_support}`;
        card.append(title, state);
        if (item.error) {
            const error = document.createElement("p");
            error.textContent = item.error;
            card.append(error);
        }
        return card;
    }));
}

function createRunCard(run) {
    const card = document.createElement("article");
    card.className = "experiment-result-card";
    const heading = document.createElement("div");
    heading.className = "section-heading";
    const title = document.createElement("h3");
    title.textContent = run.label;
    const badge = document.createElement("span");
    badge.className = `result-label ${run.error ? "error" : run.finish_reason === "stop" ? "success" : "warning"}`;
    badge.textContent = run.error ? "provider error" : (run.finish_reason || "нет finish_reason");
    heading.append(title, badge);

    const stats = document.createElement("div");
    stats.className = "result-stats";
    [
        `${run.provider} · ${run.model}`,
        `temperature: ${run.temperature ?? "—"}`,
        `reasoning: ${run.reasoning}`,
        `tokens: ${formatUsage(run.usage)}`,
        `время: ${formatDuration(run.processing_time_ms)}`,
        `длина: ${run.text_length ?? "—"}`,
    ].forEach((value) => stats.append(createStat(value)));

    const details = document.createElement("details");
    const summary = document.createElement("summary");
    summary.textContent = "Параметры запроса";
    const request = document.createElement("pre");
    request.className = "metadata";
    request.textContent = JSON.stringify({
        system_prompt: run.system_prompt,
        generated_prompt: run.generated_prompt,
        max_tokens: run.max_tokens,
        reasoning_content_length: run.reasoning_content_length,
        raw_usage: run.raw_usage,
        response_fingerprint: run.response_fingerprint,
        error: run.error,
    }, null, 2);
    details.append(summary, request);

    const answer = document.createElement("pre");
    answer.className = "answer experiment-answer";
    answer.textContent = run.text || run.error || "Ответ отсутствует.";
    card.append(heading, stats, details, answer);
    return card;
}

function createStat(text) {
    const element = document.createElement("span");
    element.className = "stat";
    element.textContent = text;
    return element;
}

function formatUsage(usage) {
    if (!usage) return "нет данных";
    return `${usage.total_tokens ?? "—"} всего · ${usage.prompt_tokens ?? "—"} вход · ${usage.completion_tokens ?? "—"} выход`;
}

function formatDuration(milliseconds) {
    if (!Number.isFinite(milliseconds)) return "нет данных";
    return milliseconds < 1000
        ? `${milliseconds} мс`
        : `${(milliseconds / 1000).toFixed(1).replace(".", ",")} с`;
}

function downloadReport() {
    if (!currentReport) return;
    const blob = new Blob([JSON.stringify(currentReport, null, 2)], { type: "application/json" });
    const link = document.createElement("a");
    link.href = URL.createObjectURL(blob);
    link.download = `${kind}-web-evidence.json`;
    link.click();
    URL.revokeObjectURL(link.href);
}

function hideResults() {
    resultsPanel.hidden = true;
    preflightPanel.hidden = true;
    jobPanel.hidden = true;
    currentReport = null;
}

function setBusy(value) {
    taskInput.disabled = value;
    rubricInput.disabled = value;
    addCustomButton.disabled = value;
    runButton.disabled = value;
    runButton.textContent = value ? "Выполняем..." : "Запустить сравнение";
}

function showError(message) {
    status.className = "status error";
    status.textContent = message;
    setBusy(false);
}

function wait(milliseconds) {
    return new Promise((resolve) => setTimeout(resolve, milliseconds));
}
