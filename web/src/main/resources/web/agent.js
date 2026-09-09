const elements = {
    app: document.querySelector(".agent-app"),
    openDrawer: document.querySelector("#open-session-drawer"),
    closeDrawer: document.querySelector("#close-session-drawer"),
    drawer: document.querySelector("#session-drawer"),
    drawerBackdrop: document.querySelector("#drawer-backdrop"),
    newSession: document.querySelector("#new-session"),
    drawerNewSession: document.querySelector("#drawer-new-session"),
    emptyNewSession: document.querySelector("#empty-new-session"),
    sessionList: document.querySelector("#session-list"),
    preferencesForm: document.querySelector("#preferences-form"),
    globalUserPrompt: document.querySelector("#global-user-prompt"),
    savePreferences: document.querySelector("#save-preferences"),
    themeToggle: document.querySelector("#theme-toggle"),
    toolbarTitle: document.querySelector("#toolbar-session-title"),
    emptySession: document.querySelector("#empty-session"),
    workspace: document.querySelector("#agent-workspace"),
    activeTitle: document.querySelector("#active-session-title"),
    provider: document.querySelector("#agent-provider"),
    model: document.querySelector("#agent-model"),
    reasoning: document.querySelector("#agent-reasoning"),
    configNote: document.querySelector("#session-config-note"),
    deleteSession: document.querySelector("#delete-session"),
    messageList: document.querySelector("#message-list"),
    messageForm: document.querySelector("#message-form"),
    message: document.querySelector("#agent-message"),
    sendMessage: document.querySelector("#send-message"),
    draftPanel: document.querySelector("#draft-panel"),
    draftStatus: document.querySelector("#draft-status"),
    selectAllLabel: document.querySelector("#select-all-label"),
    selectAll: document.querySelector("#select-all"),
    viewSwitch: document.querySelector("#view-switch"),
    tableView: document.querySelector("#table-view"),
    cardView: document.querySelector("#card-view"),
    draftEditor: document.querySelector("#draft-editor"),
    buildBatch: document.querySelector("#build-batch"),
    batchResult: document.querySelector("#batch-result"),
    batchJson: document.querySelector("#batch-json"),
    downloadBatch: document.querySelector("#download-batch"),
    status: document.querySelector("#agent-status"),
};

const ACTIVE_SESSION_KEY = "smart-expense-active-session";
const VIEW_KEY = "smart-expense-operation-view";
const MOBILE_VIEW = window.matchMedia("(max-width: 767px)");
const THEME_KEY = "smart-expense-theme";
let catalog = { providers: [], categories: [] };
let sessions = [];
let activeState = null;
let batchPayload = null;
let busy = false;
let preferredView = localStorage.getItem(VIEW_KEY) === "cards" ? "cards" : "table";
let workingDraft = new Map();
let dirtyFields = new Map();
let toastTimer = null;

class ApiError extends Error {
    constructor(message, status) {
        super(message);
        this.status = status;
    }
}

initializeTheme();
elements.themeToggle.addEventListener("click", toggleTheme);
elements.openDrawer.addEventListener("click", openDrawer);
elements.closeDrawer.addEventListener("click", closeDrawer);
elements.drawerBackdrop.addEventListener("click", closeDrawer);
document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") closeDrawer();
});
for (const button of [elements.newSession, elements.drawerNewSession, elements.emptyNewSession]) {
    button.addEventListener("click", createSession);
}
elements.preferencesForm.addEventListener("submit", savePreferences);
elements.provider.addEventListener("change", () => {
    syncModels();
    saveSessionConfig();
});
elements.model.addEventListener("change", () => {
    syncReasoning();
    saveSessionConfig();
});
elements.reasoning.addEventListener("change", saveSessionConfig);
elements.deleteSession.addEventListener("click", deleteSession);
elements.messageForm.addEventListener("submit", sendMessage);
elements.selectAll.addEventListener("change", setAllIncluded);
elements.buildBatch.addEventListener("click", buildBatch);
elements.downloadBatch.addEventListener("click", downloadBatch);
elements.tableView.addEventListener("click", () => selectOperationView("table"));
elements.cardView.addEventListener("click", () => selectOperationView("cards"));
MOBILE_VIEW.addEventListener("change", renderDraft);
elements.draftEditor.addEventListener("input", updateWorkingDraft);
elements.draftEditor.addEventListener("click", (event) => {
    const button = event.target.closest("button[data-action='save-operation']");
    if (button) saveOperationEditor(button.closest("[data-transaction-id]"));
});

initialize();

async function initialize() {
    setBusy(true);
    try {
        const [providerCatalog, preferences, storedSessions] = await Promise.all([
            api("/api/agent/providers"),
            api("/api/agent/preferences"),
            api("/api/agent/sessions"),
        ]);
        catalog = providerCatalog;
        sessions = storedSessions;
        elements.globalUserPrompt.value = preferences.user_prompt;
        renderSessionList();
        const remembered = localStorage.getItem(ACTIVE_SESSION_KEY);
        const initial = sessions.find((session) => session.id === remembered) || sessions[0];
        if (initial) {
            setActiveState(await api(`/api/agent/sessions/${encodeURIComponent(initial.id)}`));
        } else {
            renderEmptyState();
        }
    } catch (error) {
        showError(error.message);
        renderEmptyState();
    } finally {
        setBusy(false);
    }
}

async function api(url, options = {}) {
    const response = await fetch(url, options);
    const text = await response.text();
    let payload = null;
    if (text) {
        try {
            payload = JSON.parse(text);
        } catch (_) {
            throw new ApiError("Сервис вернул непонятный ответ.", response.status);
        }
    }
    if (!response.ok) {
        throw new ApiError(payload?.error || "Не удалось выполнить действие.", response.status);
    }
    return payload;
}

function jsonOptions(method, body) {
    return {
        method,
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(body),
    };
}

function openDrawer() {
    elements.drawer.classList.add("open");
    elements.drawer.setAttribute("aria-hidden", "false");
    elements.openDrawer.setAttribute("aria-expanded", "true");
    elements.drawerBackdrop.hidden = false;
    elements.closeDrawer.focus();
}

function closeDrawer() {
    if (!elements.drawer.classList.contains("open")) return;
    elements.drawer.classList.remove("open");
    elements.drawer.setAttribute("aria-hidden", "true");
    elements.openDrawer.setAttribute("aria-expanded", "false");
    elements.drawerBackdrop.hidden = true;
}

function initializeTheme() {
    const stored = localStorage.getItem(THEME_KEY);
    const theme = stored === "light" || stored === "dark"
        ? stored
        : (window.matchMedia("(prefers-color-scheme: light)").matches ? "light" : "dark");
    applyTheme(theme);
    const media = window.matchMedia("(prefers-color-scheme: light)");
    media.addEventListener("change", (event) => {
        if (!localStorage.getItem(THEME_KEY)) applyTheme(event.matches ? "light" : "dark");
    });
}

function toggleTheme() {
    const next = document.documentElement.dataset.theme === "dark" ? "light" : "dark";
    localStorage.setItem(THEME_KEY, next);
    applyTheme(next);
}

function applyTheme(theme) {
    document.documentElement.dataset.theme = theme;
    const nextLabel = theme === "dark" ? "Светлая тема" : "Тёмная тема";
    elements.themeToggle.textContent = nextLabel;
    elements.themeToggle.setAttribute("aria-label", `Включить: ${nextLabel.toLowerCase()}`);
}

async function loadSessions() {
    sessions = await api("/api/agent/sessions");
    renderSessionList();
}

function renderSessionList() {
    elements.sessionList.replaceChildren();
    if (sessions.length === 0) {
        const empty = document.createElement("p");
        empty.className = "muted";
        empty.textContent = "Сессий пока нет.";
        elements.sessionList.append(empty);
        return;
    }
    for (const session of sessions) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "session-item";
        if (activeState?.session.id === session.id) button.classList.add("active");
        button.addEventListener("click", () => openSession(session.id));

        const title = document.createElement("strong");
        title.textContent = session.title;
        const details = document.createElement("span");
        details.textContent = `${modelDisplayName(session.config)} · ${formatDate(session.updated_at_epoch_ms)}`;
        button.append(title, details);
        elements.sessionList.append(button);
    }
}

async function createSession() {
    await runBusy(async () => {
        const state = await api("/api/agent/sessions", { method: "POST" });
        await loadSessions();
        setActiveState(state);
        closeDrawer();
        elements.message.focus();
        showSuccess("Новый импорт готов.");
    });
}

async function openSession(id) {
    await runBusy(async () => {
        setActiveState(await api(`/api/agent/sessions/${encodeURIComponent(id)}`));
        closeDrawer();
        showSuccess("Сессия открыта.");
    });
}

function setActiveState(
    state,
    { preserveDirty = false, clearTransactionId = null, serverFields = [] } = {},
) {
    const previousWorking = workingDraft;
    const previousDirty = dirtyFields;
    activeState = state;
    batchPayload = null;
    workingDraft = new Map();
    dirtyFields = new Map();
    for (const row of state.draft?.transactions || []) {
        workingDraft.set(row.id, editorFromRow(row));
    }
    if (preserveDirty) {
        for (const [transactionId, fields] of previousDirty) {
            if (transactionId === clearTransactionId || !workingDraft.has(transactionId)) continue;
            const retainedFields = new Set([...fields].filter((field) => !serverFields.includes(field)));
            if (retainedFields.size === 0) continue;
            const editor = workingDraft.get(transactionId);
            const previous = previousWorking.get(transactionId);
            for (const field of retainedFields) editor[field] = previous[field];
            dirtyFields.set(transactionId, retainedFields);
        }
    }
    localStorage.setItem(ACTIVE_SESSION_KEY, state.session.id);
    const index = sessions.findIndex((session) => session.id === state.session.id);
    if (index >= 0) sessions[index] = state.session;
    else sessions.unshift(state.session);
    renderSessionList();
    renderActiveState();
}

function editorFromRow(row) {
    const transaction = row.transaction;
    return {
        included: row.included,
        occurred_at: transaction.occurred_at,
        posted_at: transaction.posted_at || "",
        direction: transaction.direction,
        amount: minorToMajor(transaction.amount_minor, transaction.currency),
        currency: transaction.currency,
        merchant: transaction.merchant,
        description: row.description || "",
        category_id: transaction.category_id || "",
        card_last4: transaction.card_last4 || "",
    };
}

function hasDirtyDraft() {
    return dirtyFields.size > 0;
}

function renderEmptyState() {
    activeState = null;
    workingDraft = new Map();
    dirtyFields = new Map();
    localStorage.removeItem(ACTIVE_SESSION_KEY);
    elements.toolbarTitle.textContent = "Импорт операций";
    elements.emptySession.hidden = false;
    elements.workspace.hidden = true;
    elements.deleteSession.hidden = true;
    elements.draftPanel.hidden = true;
}

function renderActiveState() {
    elements.emptySession.hidden = true;
    elements.workspace.hidden = false;
    elements.deleteSession.hidden = false;
    elements.activeTitle.textContent = activeState.session.title;
    elements.toolbarTitle.textContent = activeState.session.title;
    populateConfigControls(activeState.session.config);
    renderMessages();
    renderDraft();
}

function populateConfigControls(config) {
    elements.provider.replaceChildren();
    for (const provider of catalog.providers) {
        const option = document.createElement("option");
        option.value = provider.id;
        option.textContent = provider.available
            ? provider.display_name
            : `${provider.display_name} — сейчас недоступен`;
        elements.provider.append(option);
    }
    elements.provider.value = config.provider_id;
    syncModels(config.model_id, config.reasoning_mode_id);
    renderConfigAvailability();
}

function syncModels(selectedModelId, selectedReasoningId) {
    const provider = selectedProvider();
    const previousModel = selectedModelId || elements.model.value;
    elements.model.replaceChildren();
    for (const model of provider?.models || []) {
        const option = document.createElement("option");
        option.value = model.id;
        option.textContent = model.display_name;
        elements.model.append(option);
    }
    if ([...elements.model.options].some((option) => option.value === previousModel)) {
        elements.model.value = previousModel;
    }
    syncReasoning(selectedReasoningId);
}

function syncReasoning(selectedReasoningId) {
    const model = selectedModel();
    const previousMode = selectedReasoningId || elements.reasoning.value;
    elements.reasoning.replaceChildren();
    for (const mode of model?.reasoning_modes || []) {
        const option = document.createElement("option");
        option.value = mode.id;
        option.textContent = mode.display_name;
        elements.reasoning.append(option);
    }
    if ([...elements.reasoning.options].some((option) => option.value === previousMode)) {
        elements.reasoning.value = previousMode;
    }
    renderConfigAvailability();
}

function selectedProvider() {
    return catalog.providers.find((provider) => provider.id === elements.provider.value);
}

function selectedModel() {
    return selectedProvider()?.models.find((model) => model.id === elements.model.value);
}

function modelDisplayName(config) {
    const provider = catalog.providers.find((item) => item.id === config.provider_id);
    return provider?.models.find((item) => item.id === config.model_id)?.display_name || config.model_id;
}

function renderConfigAvailability() {
    if (!activeState) return;
    elements.configNote.textContent = selectedProvider()?.available
        ? "Изменения применяются к следующим сообщениям; диалог и черновик сохраняются."
        : "Провайдер сейчас недоступен. Выберите доступный провайдер, чтобы отправить сообщение.";
}

async function saveSessionConfig() {
    if (!activeState) return;
    const config = {
        provider_id: elements.provider.value,
        model_id: elements.model.value,
        reasoning_mode_id: elements.reasoning.value,
    };
    await runBusy(async () => {
        const state = await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}/config`,
            jsonOptions("PUT", { revision: activeState.session.revision, config }),
        );
        setActiveState(state, { preserveDirty: true });
        await loadSessions();
        showSuccess("Настройки агента сохранены.");
    }, true);
}

async function savePreferences(event) {
    event.preventDefault();
    await runBusy(async () => {
        const preferences = await api(
            "/api/agent/preferences",
            jsonOptions("PUT", { user_prompt: elements.globalUserPrompt.value }),
        );
        elements.globalUserPrompt.value = preferences.user_prompt;
        showSuccess("Общий профиль сохранён.");
    });
}

async function deleteSession() {
    if (!activeState) return;
    const title = activeState.session.title;
    if (!window.confirm(`Удалить сессию «${title}» вместе с диалогом и черновиком? Это действие нельзя отменить.`)) {
        return;
    }
    await runBusy(async () => {
        const id = activeState.session.id;
        await api(
            `/api/agent/sessions/${encodeURIComponent(id)}`,
            jsonOptions("DELETE", { revision: activeState.session.revision }),
        );
        activeState = null;
        localStorage.removeItem(ACTIVE_SESSION_KEY);
        await loadSessions();
        if (sessions.length > 0) {
            setActiveState(await api(`/api/agent/sessions/${encodeURIComponent(sessions[0].id)}`));
        } else {
            renderEmptyState();
        }
        showSuccess("Сессия удалена.");
    });
}

function renderMessages() {
    elements.messageList.replaceChildren();
    if (activeState.messages.length === 0) {
        const empty = document.createElement("p");
        empty.className = "muted";
        empty.textContent = "Вставьте банковскую выписку первым сообщением.";
        elements.messageList.append(empty);
        return;
    }
    for (const message of activeState.messages) {
        const item = document.createElement("article");
        item.className = `message ${message.role}`;
        const role = document.createElement("strong");
        role.textContent = message.role === "user" ? "Вы" : "Агент";
        const content = document.createElement("p");
        content.textContent = message.display_text;
        item.append(role, content);
        elements.messageList.append(item);
    }
    elements.messageList.scrollTop = elements.messageList.scrollHeight;
}

async function sendMessage(event) {
    event.preventDefault();
    if (!activeState) return;
    const text = elements.message.value.trim();
    if (!text) {
        showError("Введите выписку или уточнение.");
        return;
    }
    await runBusy(async () => {
        const state = await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}/messages`,
            jsonOptions("POST", { revision: activeState.session.revision, text }),
        );
        elements.message.value = "";
        setActiveState(state);
        await loadSessions();
        showSuccess("Ответ агента получен. Черновик обновлён.");
    }, true);
}

function effectiveOperationView() {
    return MOBILE_VIEW.matches ? "cards" : preferredView;
}

function selectOperationView(view) {
    if (view === "table" && MOBILE_VIEW.matches) return;
    preferredView = view;
    localStorage.setItem(VIEW_KEY, view);
    renderDraft();
}

function updateViewSwitch(view, visible) {
    elements.viewSwitch.hidden = !visible;
    elements.tableView.disabled = busy || MOBILE_VIEW.matches;
    elements.tableView.setAttribute("aria-pressed", String(view === "table"));
    elements.cardView.setAttribute("aria-pressed", String(view === "cards"));
    elements.tableView.title = MOBILE_VIEW.matches
        ? "На узком экране используется представление карточками."
        : "";
}

function updateWorkingDraft(event) {
    const control = event.target;
    const holder = control.closest("[data-transaction-id]");
    if (!holder || !control.name || !workingDraft.has(holder.dataset.transactionId)) return;
    const editor = workingDraft.get(holder.dataset.transactionId);
    const value = control.type === "checkbox" ? control.checked : control.value;
    if (editor[control.name] === value) return;
    editor[control.name] = value;
    const fields = dirtyFields.get(holder.dataset.transactionId) || new Set();
    fields.add(control.name);
    dirtyFields.set(holder.dataset.transactionId, fields);
    elements.batchResult.hidden = true;
    batchPayload = null;
    if (control.name === "included") {
        renderDraft();
    } else {
        holder.classList.add("dirty");
        updateDraftSummary(activeState.draft);
    }
    setBusy(busy);
}

function draftMetrics(draft) {
    const included = draft.transactions.filter((row) => workingDraft.get(row.id)?.included).length;
    const invalid = draft.transactions.filter(
        (row) => workingDraft.get(row.id)?.included && hasFieldErrors(row),
    ).length;
    return { included, invalid };
}

function updateDraftSummary(draft) {
    const { included, invalid } = draftMetrics(draft);
    const parts = [
        `Выбрано ${included} из ${draft.transactions.length}.`,
    ];
    if (invalid > 0) {
        parts.push(`Исправьте ошибки в ${invalid} ${operationWord(invalid)}.`);
    }
    if (hasDirtyDraft()) parts.push("Есть несохранённые изменения.");
    elements.draftStatus.textContent = parts.join(" ");
    elements.buildBatch.disabled = busy || included === 0 || invalid > 0 || hasDirtyDraft();
    elements.selectAll.checked = included === draft.transactions.length && draft.transactions.length > 0;
    elements.selectAll.indeterminate = included > 0 && included < draft.transactions.length;
}

function renderDraft() {
    elements.batchResult.hidden = true;
    batchPayload = null;
    const draft = activeState?.draft;
    elements.draftPanel.hidden = !draft;
    elements.draftEditor.replaceChildren();
    elements.draftEditor.className = "operation-editor";
    delete elements.draftEditor.dataset.view;
    elements.selectAllLabel.hidden = true;
    updateViewSwitch("cards", false);
    if (!draft) return;

    if (draft.status === "not_applicable") {
        elements.draftStatus.textContent = draft.rejection_reason || "Ввод не содержит данных о финансовых операциях.";
        elements.buildBatch.disabled = true;
        return;
    }

    updateDraftSummary(draft);
    elements.selectAllLabel.hidden = draft.transactions.length === 0;
    const view = effectiveOperationView();
    updateViewSwitch(view, draft.transactions.length > 0);
    elements.draftEditor.dataset.view = view;
    if (view === "table") {
        elements.draftEditor.classList.add("operation-table-wrap");
        elements.draftEditor.append(createOperationTable(draft.transactions));
    } else {
        elements.draftEditor.classList.add("operation-cards");
        for (const row of draft.transactions) {
            elements.draftEditor.append(createOperationCard(row));
        }
    }
}

function createOperationCard(row) {
    const editor = workingDraft.get(row.id);
    const card = document.createElement("article");
    card.className = "operation-card";
    card.dataset.transactionId = row.id;
    if (!editor.included) card.classList.add("excluded");
    if (editor.included && hasFieldErrors(row)) card.classList.add("has-errors");
    if (dirtyFields.has(row.id)) card.classList.add("dirty");

    const header = document.createElement("div");
    header.className = "operation-card-header";
    const inclusion = document.createElement("label");
    inclusion.className = "operation-inclusion";
    const checkbox = checkboxInput("included", editor.included);
    const inclusionText = document.createElement("span");
    inclusionText.textContent = "Включить";
    inclusion.append(checkbox, inclusionText);
    const id = document.createElement("code");
    id.className = "transaction-id";
    id.textContent = row.id;
    header.append(inclusion, id);

    const grid = document.createElement("div");
    grid.className = "operation-grid";
    grid.append(
        fieldControl("Дата и время операции", textInput("occurred_at", editor.occurred_at), row, "occurred_at", editor.included),
        fieldControl("Дата проводки", textInput("posted_at", editor.posted_at), row, "posted_at", editor.included),
        fieldControl("Тип операции", directionSelect(editor.direction), row, "direction", editor.included),
        fieldControl("Сумма", amountInput(editor.amount), row, "amount_minor", editor.included),
        fieldControl("Валюта", readOnlyInput("currency", editor.currency), row, "currency", editor.included),
        fieldControl("Магазин или получатель", textInput("merchant", editor.merchant), row, "merchant", editor.included),
        fieldControl("Описание", textInput("description", editor.description, 500), row, "description", editor.included),
        fieldControl("Категория", categorySelect(editor.category_id), row, "category_id", editor.included),
        fieldControl("Последние 4 цифры карты (необязательно)", textInput("card_last4", editor.card_last4, 4), row, "card_last4", editor.included),
    );

    const generalErrors = editor.included ? (row.field_errors?._transaction || []) : [];
    const footer = document.createElement("div");
    footer.className = "operation-card-footer";
    const errorBox = document.createElement("div");
    errorBox.className = "operation-errors";
    for (const error of generalErrors) errorBox.append(errorText(error));
    footer.append(errorBox, saveOperationButton(row.id, "Сохранить операцию"));
    card.append(header, grid, footer);
    return card;
}

function createOperationTable(rows) {
    const table = document.createElement("table");
    table.id = "operation-table";
    table.className = "operation-table";
    table.setAttribute("aria-label", "Редактор операций");
    const header = document.createElement("thead");
    const headerRow = document.createElement("tr");
    for (const label of [
        "Включить", "ID", "Дата и время", "Дата проводки", "Тип", "Сумма",
        "Валюта", "Магазин или получатель", "Описание", "Категория", "Карта", "",
    ]) {
        const cell = document.createElement("th");
        cell.scope = "col";
        cell.textContent = label;
        headerRow.append(cell);
    }
    header.append(headerRow);
    const body = document.createElement("tbody");
    for (const row of rows) body.append(createOperationTableRow(row));
    table.append(header, body);
    return table;
}

function createOperationTableRow(row) {
    const editor = workingDraft.get(row.id);
    const tableRow = document.createElement("tr");
    tableRow.dataset.transactionId = row.id;
    if (!editor.included) tableRow.classList.add("excluded");
    if (editor.included && hasFieldErrors(row)) tableRow.classList.add("has-errors");
    if (dirtyFields.has(row.id)) tableRow.classList.add("dirty");

    const include = checkboxInput("included", editor.included);
    include.setAttribute("aria-label", `Включить операцию ${row.id}`);
    tableRow.append(tableCell(include));
    const id = document.createElement("code");
    id.className = "transaction-id";
    id.textContent = row.id;
    tableRow.append(tableCell(id));
    tableRow.append(
        tableFieldCell("Дата и время", textInput("occurred_at", editor.occurred_at), row, "occurred_at", editor.included),
        tableFieldCell("Дата проводки", textInput("posted_at", editor.posted_at), row, "posted_at", editor.included),
        tableFieldCell("Тип", directionSelect(editor.direction), row, "direction", editor.included),
        tableFieldCell("Сумма", amountInput(editor.amount), row, "amount_minor", editor.included),
        tableFieldCell("Валюта", readOnlyInput("currency", editor.currency), row, "currency", editor.included),
        tableFieldCell("Магазин или получатель", textInput("merchant", editor.merchant), row, "merchant", editor.included),
        tableFieldCell("Описание", textInput("description", editor.description, 500), row, "description", editor.included),
        tableFieldCell("Категория", categorySelect(editor.category_id), row, "category_id", editor.included),
        tableFieldCell("Последние 4 цифры карты", textInput("card_last4", editor.card_last4, 4), row, "card_last4", editor.included),
    );
    const action = document.createElement("div");
    for (const error of editor.included ? (row.field_errors?._transaction || []) : []) {
        action.append(errorText(error));
    }
    action.append(saveOperationButton(row.id, "Сохранить"));
    tableRow.append(tableCell(action));
    return tableRow;
}

function tableCell(content) {
    const cell = document.createElement("td");
    cell.append(content);
    return cell;
}

function tableFieldCell(label, control, row, fieldName, included) {
    const cell = tableCell(control);
    control.setAttribute("aria-label", `${label}, ${row.id}`);
    const errors = included ? (row.field_errors?.[fieldName] || []) : [];
    if (errors.length > 0) {
        cell.classList.add("invalid");
        control.setAttribute("aria-invalid", "true");
        for (const error of errors) cell.append(errorText(error));
    }
    return cell;
}

function saveOperationButton(transactionId, text) {
    const save = document.createElement("button");
    save.type = "button";
    save.className = "compact-button";
    save.dataset.action = "save-operation";
    save.textContent = text;
    save.setAttribute("aria-label", `${text} ${transactionId}`);
    return save;
}

function fieldControl(labelText, control, row, fieldName, included) {
    const wrapper = document.createElement("label");
    wrapper.className = "operation-field";
    const label = document.createElement("span");
    label.textContent = labelText;
    wrapper.append(label, control);
    const errors = included ? (row.field_errors?.[fieldName] || []) : [];
    if (errors.length > 0) {
        wrapper.classList.add("invalid");
        control.setAttribute("aria-invalid", "true");
        for (const error of errors) wrapper.append(errorText(error));
    }
    return wrapper;
}

function errorText(message) {
    const error = document.createElement("small");
    error.className = "field-error";
    error.textContent = message;
    return error;
}

function textInput(name, value, maxLength) {
    const input = document.createElement("input");
    input.name = name;
    input.value = value;
    if (maxLength) input.maxLength = maxLength;
    return input;
}

function readOnlyInput(name, value) {
    const input = textInput(name, value);
    input.readOnly = true;
    input.setAttribute("aria-readonly", "true");
    return input;
}
function amountInput(value) {
    const input = document.createElement("input");
    input.type = "text";
    input.inputMode = "decimal";
    input.name = "amount";
    input.value = value;
    return input;
}

function checkboxInput(name, checked) {
    const input = document.createElement("input");
    input.type = "checkbox";
    input.name = name;
    input.checked = checked;
    return input;
}

function directionSelect(selected) {
    const select = document.createElement("select");
    select.name = "direction";
    for (const [value, label] of [["expense", "Расход"], ["income", "Доход"]]) {
        const option = document.createElement("option");
        option.value = value;
        option.textContent = label;
        option.selected = value === selected;
        select.append(option);
    }
    return select;
}

function categorySelect(selected) {
    const select = document.createElement("select");
    select.name = "category_id";
    const empty = document.createElement("option");
    empty.value = "";
    empty.textContent = "Не выбрана";
    select.append(empty);
    for (const category of catalog.categories) {
        const option = document.createElement("option");
        option.value = category.id;
        option.textContent = category.display_name;
        option.selected = category.id === selected;
        select.append(option);
    }
    return select;
}

function currencyFractionDigits(currency) {
    try {
        return new Intl.NumberFormat("ru-RU", {
            style: "currency",
            currency,
        }).resolvedOptions().maximumFractionDigits;
    } catch (_) {
        return 2;
    }
}

function minorToMajor(amountMinor, currency) {
    const digits = currencyFractionDigits(currency);
    const sign = amountMinor < 0 ? "-" : "";
    const absolute = Math.abs(amountMinor).toString().padStart(digits + 1, "0");
    if (digits === 0) return `${sign}${absolute}`;
    return `${sign}${absolute.slice(0, -digits)}.${absolute.slice(-digits)}`;
}

function majorToMinor(raw, currency) {
    const normalized = raw.trim().replace(",", ".");
    const digits = currencyFractionDigits(currency);
    const match = normalized.match(new RegExp(`^(-?)(\\d+)(?:\\.(\\d{0,${digits}}))?$`));
    if (!match) return null;
    const fraction = (match[3] || "").padEnd(digits, "0");
    const scale = 10 ** digits;
    const value = Number(match[2]) * scale + Number(fraction || 0);
    const signed = match[1] === "-" ? -value : value;
    return Number.isSafeInteger(signed) ? signed : null;
}

async function saveOperationEditor(editorElement) {
    if (!activeState || !editorElement) return;
    const transactionId = editorElement.dataset.transactionId;
    const editor = workingDraft.get(transactionId);
    const amountMinor = majorToMinor(editor.amount, editor.currency);
    if (amountMinor === null) {
        showError("Сумма должна быть числом с допустимым количеством знаков после запятой.");
        return;
    }
    const body = {
        revision: activeState.session.revision,
        included: editor.included,
        direction: editor.direction,
        occurred_at: editor.occurred_at,
        posted_at: editor.posted_at || null,
        amount_minor: amountMinor,
        merchant: editor.merchant,
        description: editor.description,
        category_id: editor.category_id || null,
        card_last4: editor.card_last4 || null,
    };
    await runBusy(async () => {
        const state = await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}/transactions/${encodeURIComponent(transactionId)}`,
            jsonOptions("PUT", body),
        );
        setActiveState(state, {
            preserveDirty: true,
            clearTransactionId: transactionId,
        });
        await loadSessions();
        showSuccess("Операция сохранена.");
    }, true);
}

async function setAllIncluded() {
    if (!activeState?.draft) return;
    const included = elements.selectAll.checked;
    await runBusy(async () => {
        const state = await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}/selection`,
            jsonOptions("PUT", { revision: activeState.session.revision, included }),
        );
        setActiveState(state, { preserveDirty: true, serverFields: ["included"] });
        await loadSessions();
        showSuccess(included ? "Все операции выбраны." : "Все операции исключены.");
    }, true);
}

function hasFieldErrors(row) {
    return Object.values(row.field_errors || {}).some((messages) => messages.length > 0);
}

function operationWord(count) {
    return count === 1 ? "операции" : "операциях";
}

async function buildBatch() {
    if (!activeState) return;
    await runBusy(async () => {
        batchPayload = await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}/batch`,
        );
        elements.batchJson.textContent = JSON.stringify(batchPayload, null, 2);
        elements.batchResult.hidden = false;
        showSuccess(`Подготовлено операций: ${batchPayload.transactions.length}.`);
    });
}

function downloadBatch() {
    if (!batchPayload || !activeState) return;
    const blob = new Blob([`${JSON.stringify(batchPayload, null, 2)}\n`], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = `${safeFileName(activeState.session.title)}.json`;
    document.body.append(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
}

function safeFileName(value) {
    return value.toLowerCase().replace(/[^a-zа-яё0-9]+/gi, "-").replace(/^-|-$/g, "") || "operations";
}

async function runBusy(action, reloadOnConflict = false) {
    if (busy) return;
    setBusy(true);
    try {
        await action();
    } catch (error) {
        if (reloadOnConflict && error.status === 409 && activeState) {
            setActiveState(
                await api(`/api/agent/sessions/${encodeURIComponent(activeState.session.id)}`),
                { preserveDirty: true },
            );
        }
        showError(error.message);
    } finally {
        setBusy(false);
    }
}

function setBusy(value) {
    busy = value;
    elements.app.setAttribute("aria-busy", String(value));
    for (const control of elements.app.querySelectorAll("button, select")) control.disabled = value;
    elements.globalUserPrompt.disabled = value;
    elements.message.disabled = value;
    elements.provider.disabled = value || !activeState;
    elements.model.disabled = value || !activeState;
    elements.reasoning.disabled = value || !activeState;
    elements.deleteSession.disabled = value || !activeState;
    elements.sendMessage.disabled = value || !activeState || selectedProvider()?.available !== true;
    elements.selectAll.disabled = value || !activeState?.draft;
    if (activeState?.draft?.status === "ready") {
        const { included, invalid } = draftMetrics(activeState.draft);
        elements.buildBatch.disabled = value || included === 0 || invalid > 0 || hasDirtyDraft();
        updateViewSwitch(effectiveOperationView(), activeState.draft.transactions.length > 0);
    } else {
        elements.buildBatch.disabled = true;
        updateViewSwitch("cards", false);
    }
}

function formatDate(epochMs) {
    return new Intl.DateTimeFormat("ru-RU", {
        day: "2-digit",
        month: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
    }).format(new Date(epochMs));
}

function showToast(type, message, durationMs) {
    if (toastTimer !== null) window.clearTimeout(toastTimer);
    elements.status.className = `status agent-toast ${type}`;
    elements.status.textContent = message;
    toastTimer = window.setTimeout(() => {
        elements.status.className = "status agent-toast";
        elements.status.textContent = "";
        toastTimer = null;
    }, durationMs);
}

function showSuccess(message) {
    showToast("success", message, 4_000);
}

function showError(message) {
    showToast("error", message, 8_000);
}
