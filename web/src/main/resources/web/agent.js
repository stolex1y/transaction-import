const elements = {
    app: document.querySelector(".agent-app"),
    panelNavigation: document.querySelector("#panel-navigation"),
    drawer: document.querySelector("#session-drawer"),
    main: document.querySelector(".agent-main"),
    newSession: document.querySelector("#new-session"),
    drawerNewSession: document.querySelector("#drawer-new-session"),
    emptyNewSession: document.querySelector("#empty-new-session"),
    sessionList: document.querySelector("#session-list"),
    mcpRefresh: document.querySelector("#refresh-mcp-catalog"),
    mcpStatus: document.querySelector("#mcp-catalog-status"),
    mcpList: document.querySelector("#mcp-catalog-list"),
    schedulerRefresh: document.querySelector("#refresh-scheduler"),
    schedulerStatus: document.querySelector("#scheduler-status"),
    schedulerForm: document.querySelector("#scheduler-form"),
    schedulerName: document.querySelector("#scheduler-name"),
    schedulerAccounts: document.querySelector("#scheduler-accounts"),
    schedulerStartDate: document.querySelector("#scheduler-start-date"),
    schedulerInterval: document.querySelector("#scheduler-interval"),
    schedulerSubmit: document.querySelector("#scheduler-submit"),
    schedulerTaskList: document.querySelector("#scheduler-task-list"),
    schedulerHistory: document.querySelector("#scheduler-history"),
    linkedSchedulerTasks: document.querySelector("#linked-scheduler-tasks"),
    linkedSchedulerTaskList: document.querySelector("#linked-scheduler-task-list"),
    receiptsPanel: document.querySelector("#receipts-panel"),
    receiptsAuthStatus: document.querySelector("#receipts-auth-status"),
    receiptsAuthDetail: document.querySelector("#receipts-auth-detail"),
    receiptsBrowserLogin: document.querySelector("#receipts-browser-login"),
    receiptsRefreshSession: document.querySelector("#receipts-refresh-session"),
    receiptsSessionRetry: document.querySelector("#receipts-session-retry"),
    receiptsLogout: document.querySelector("#receipts-logout"),
    receiptsSearchForm: document.querySelector("#receipts-search-form"),
    receiptsFrom: document.querySelector("#receipts-from"),
    receiptsTo: document.querySelector("#receipts-to"),
    receiptsSeller: document.querySelector("#receipts-seller"),
    receiptsSearchSubmit: document.querySelector("#receipts-search-submit"),
    receiptsSearchStatus: document.querySelector("#receipts-search-status"),
    receiptsSummaryList: document.querySelector("#receipts-summary-list"),
    receiptDetailPanel: document.querySelector("#receipt-detail-panel"),
    receiptDetail: document.querySelector("#receipt-detail"),
    tbankLoginForm: document.querySelector("#tbank-login-form"),
    tbankPhone: document.querySelector("#tbank-phone"),
    tbankOtpStep: document.querySelector("#tbank-otp-step"),
    tbankPasswordField: document.querySelector("#tbank-password-field"),
    tbankPassword: document.querySelector("#tbank-password"),
    tbankOtp: document.querySelector("#tbank-otp"),
    tbankSubmit: document.querySelector("#tbank-submit"),
    tbankResend: document.querySelector("#tbank-resend"),
    tbankChangePhone: document.querySelector("#tbank-change-phone"),
    tbankSessionRetry: document.querySelector("#tbank-session-retry"),
    tbankLogout: document.querySelector("#tbank-logout"),
    tbankSessionStatus: document.querySelector("#tbank-session-status"),
    tbankLoginStatus: document.querySelector("#tbank-login-status"),
    tbankQueryForm: document.querySelector("#tbank-query-form"),
    tbankAccount: document.querySelector("#tbank-account"),
    tbankFrom: document.querySelector("#tbank-from"),
    tbankTo: document.querySelector("#tbank-to"),
    tbankLimit: document.querySelector("#tbank-limit"),
    tbankResult: document.querySelector("#tbank-result"),
    preferencesForm: document.querySelector("#preferences-form"),
    globalUserPrompt: document.querySelector("#global-user-prompt"),
    savePreferences: document.querySelector("#save-preferences"),
    confirmedDecisionsList: document.querySelector("#confirmed-decisions-list"),
    merchantRuleForm: document.querySelector("#merchant-rule-form"),
    merchantRuleEditId: document.querySelector("#merchant-rule-edit-id"),
    merchantRuleCanonicalName: document.querySelector("#merchant-rule-canonical-name"),
    merchantRuleAliases: document.querySelector("#merchant-rule-aliases"),
    merchantRuleSuffixPolicy: document.querySelector("#merchant-rule-suffix-policy"),
    saveMerchantRule: document.querySelector("#save-merchant-rule"),
    cancelMerchantRuleEdit: document.querySelector("#cancel-merchant-rule-edit"),
    merchantRuleList: document.querySelector("#merchant-rule-list"),
    categoryForm: document.querySelector("#category-form"),
    categoryEditId: document.querySelector("#category-edit-id"),
    categoryName: document.querySelector("#category-name"),
    categoryParent: document.querySelector("#category-parent"),
    categoryType: document.querySelector("#category-type"),
    categoryHint: document.querySelector("#category-hint"),
    saveCategory: document.querySelector("#save-category"),
    cancelCategoryEdit: document.querySelector("#cancel-category-edit"),
    categoryList: document.querySelector("#category-list"),
    notFoundNewSession: document.querySelector("#not-found-new-session"),
    sessionNotFound: document.querySelector("#session-not-found"),
    themeToggle: document.querySelector("#theme-toggle"),
    toolbarTitle: document.querySelector("#toolbar-session-title"),
    emptySession: document.querySelector("#empty-session"),
    workspace: document.querySelector("#agent-workspace"),
    receiptStatus: document.querySelector("#receipt-status"),
    receiptStatusDetail: document.querySelector("#receipt-status-detail"),
    receiptOperationCount: document.querySelector("#receipt-operation-count"),
    systemInvariantList: document.querySelector("#system-invariant-list"),
    activeTitle: document.querySelector("#active-session-title"),
    provider: document.querySelector("#agent-provider"),
    model: document.querySelector("#agent-model"),
    reasoning: document.querySelector("#agent-reasoning"),
    configNote: document.querySelector("#session-config-note"),
    contextStrategyNote: document.querySelector("#context-strategy-note"),
    forkSession: document.querySelector("#fork-session"),
    factsPanel: document.querySelector("#facts-panel"),
    factsList: document.querySelector("#facts-list"),
    memoryPanel: document.querySelector("#memory-panel"),
    memoryRequestNote: document.querySelector("#memory-request-note"),
    memoryLayerList: document.querySelector("#memory-layer-list"),
    deleteSession: document.querySelector("#delete-session"),
    messageList: document.querySelector("#message-list"),
    messageForm: document.querySelector("#message-form"),
    message: document.querySelector("#agent-message"),
    sendMessage: document.querySelector("#send-message"),
    metricsPanel: document.querySelector("#metrics-panel"),
    metricsSummary: document.querySelector("#metrics-summary"),
    metricsTableBody: document.querySelector("#metrics-table-body"),
    draftPanel: document.querySelector("#draft-panel"),
    draftStatus: document.querySelector("#draft-status"),
    selectAllLabel: document.querySelector("#select-all-label"),
    selectAll: document.querySelector("#select-all"),
    viewSwitch: document.querySelector("#view-switch"),
    tableView: document.querySelector("#table-view"),
    cardView: document.querySelector("#card-view"),
    draftEditor: document.querySelector("#draft-editor"),
    buildBatch: document.querySelector("#build-batch"),
    status: document.querySelector("#agent-status"),
};
let taskInvariantCatalog = { system: [] };
let schedulerState = { accounts: [], tasks: [] };
const THEME_KEY = "smart-expense-theme";
const ACTIVE_SESSION_KEY = "smart-expense-active-session";
const VIEW_KEY = "smart-expense-operation-view";
const MOBILE_VIEW = window.matchMedia("(max-width: 760px)");
let catalog = { providers: [], categories: [] };
const ACTIVE_PANEL_KEY = "smart-expense-active-panel";
let mcpCatalog = { servers: [] };
let tbankSession = { authenticated: false };
let receiptsAuthState = null;
let receiptsStatusPollTimer = null;
let categoryCatalog = [];
let sessions = [];
let activeState = null;
let sessionNavigationVersion = 0;
let memoryProjection = null;
let memoryProjectionRequest = 0;
let expandedMemoryLayer = null;
let editingCategoryId = "";
let editingMerchantRuleId = "";
let lastPreferences = null;
let busy = false;
let operationBusy = false;
let navigationLoading = false;
let activeMcpPreview = null;
let preferredView = localStorage.getItem(VIEW_KEY) === "cards" ? "cards" : "table";
let workingDraft = new Map();
let dirtyFields = new Map();
let loginStage = "phone";
let resendCooldownTimer = null;
let toastTimer = null;

class ApiError extends Error {
    constructor(message, status) {
        super(message);
        this.status = status;
    }
}

function ensureMcpElements() {
    elements.mcpRefresh ||= document.querySelector("#refresh-mcp-catalog");
    elements.mcpStatus ||= document.querySelector("#mcp-catalog-status");
    elements.mcpList ||= document.querySelector("#mcp-catalog-list");
}

ensureMcpElements();
initializeTheme();
elements.schedulerRefresh?.addEventListener("click", loadSchedulerPanel);
elements.schedulerForm?.addEventListener("submit", createSchedulerTask);
elements.linkedSchedulerTaskList?.addEventListener("click", openLinkedSchedulerTask);
elements.schedulerTaskList?.addEventListener("click", handleSchedulerTaskClick);
elements.themeToggle.addEventListener("click", toggleTheme);
elements.panelNavigation?.addEventListener("click", handleAppPanelNavigation);
for (const button of [
    elements.newSession,
    elements.drawerNewSession,
    elements.emptyNewSession,
    elements.notFoundNewSession,
]) {
    button?.addEventListener("click", createSession);
}
elements.receiptsBrowserLogin?.addEventListener("click", handleReceiptsBrowserLogin);
elements.receiptsRefreshSession?.addEventListener("click", refreshReceiptsSession);
elements.receiptsSessionRetry?.addEventListener("click", handleReceiptsSessionRetry);
elements.receiptsLogout?.addEventListener("click", handleReceiptsLogout);
elements.receiptsSearchForm?.addEventListener("submit", searchReceipts);
elements.receiptsSummaryList?.addEventListener("click", handleReceiptSummaryClick);
window.addEventListener("popstate", handlePopState);
elements.mcpRefresh?.addEventListener("click", loadMcpCatalog);
elements.tbankLoginForm?.addEventListener("submit", handleTbankLogin);
elements.tbankResend?.addEventListener("click", handleTbankResend);
elements.tbankChangePhone?.addEventListener("click", handleTbankChangePhone);
elements.tbankSessionRetry?.addEventListener("click", handleTbankSessionRetry);
elements.tbankLogout?.addEventListener("click", handleTbankLogout);
elements.tbankQueryForm?.addEventListener("submit", handleTbankTransactions);
elements.preferencesForm.addEventListener("submit", savePreferences);
elements.merchantRuleForm.addEventListener("submit", saveMerchantRuleForm);
elements.cancelMerchantRuleEdit.addEventListener("click", resetMerchantRuleForm);
elements.merchantRuleList.addEventListener("click", handleMerchantRuleListClick);
elements.categoryForm.addEventListener("submit", saveCategoryForm);
elements.cancelCategoryEdit.addEventListener("click", resetCategoryForm);
elements.categoryParent.addEventListener("change", syncCategoryTypeControl);
elements.categoryList.addEventListener("click", handleCategoryListClick);
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
elements.forkSession.addEventListener("click", forkActiveSession);
elements.messageForm.addEventListener("submit", sendMessage);
elements.selectAll.addEventListener("change", setAllIncluded);
elements.buildBatch.addEventListener("click", exportBatch);
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
    const requestedSessionId = sessionIdFromLocation();
    const initialPanel = requestedSessionId
        ? "sessions"
        : ["sessions", "scheduler", "mcp", "settings"].includes(localStorage.getItem(ACTIVE_PANEL_KEY))
            ? localStorage.getItem(ACTIVE_PANEL_KEY)
            : "sessions";
    selectAppPanel(initialPanel, false);
    try {
        const [providerCatalog, preferences, storedSessions, storedCategories, loadedMcpCatalog, loadedTbankSession] = await Promise.all([
            api("/api/agent/providers"),
            api("/api/agent/preferences"),
            api("/api/agent/sessions"),
            api("/api/agent/categories"),
            api("/api/agent/mcp"),
            api("/api/agent/tbank/session"),
        ]);
        catalog = providerCatalog;
        mcpCatalog = loadedMcpCatalog;
        tbankSession = loadedTbankSession;
        categoryCatalog = storedCategories;
        sessions = storedSessions;
        renderMcpCatalog();
        renderTbankSession();
        renderPreferences(preferences);
        renderCategoryManager();
        void loadSchedulerPanel();
        void refreshReceiptsSession();
        renderSessionList();
        if (tbankSession.authenticated) {
            await loadTbankAccounts();
        }
        if (requestedSessionId !== null) {
            try {
                setActiveState(await api(`/api/agent/sessions/${encodeURIComponent(requestedSessionId)}`));
            } catch (error) {
                if (error.status === 404) {
                    renderSessionNotFound();
                    return;
                }
                throw error;
            }
            return;
        }
        const remembered = localStorage.getItem(ACTIVE_SESSION_KEY);
        const initial = sessions.find((session) => session.id === remembered) || sessions[0];
        if (initial) {
            setActiveState(await api(`/api/agent/sessions/${encodeURIComponent(initial.id)}`));
            if (initialPanel === "sessions") navigateToSession(initial.id, true);
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

function sessionIdFromLocation() {
    const match = window.location.pathname.match(/^\/agent\/sessions\/([^/]+)\/?$/);
    if (!match) return null;
    try {
        return decodeURIComponent(match[1]);
    } catch (_) {
        return "";
    }
}

function navigateToSession(sessionId, replace = false) {
    const path = `/agent/sessions/${encodeURIComponent(sessionId)}`;
    if (window.location.pathname === path) return;
    const update = replace ? "replaceState" : "pushState";
    window.history[update]({ sessionId }, "", path);
    sessionNavigationVersion += 1;
}

function handleAppPanelNavigation(event) {
    const button = event.target.closest("button[data-app-panel]");
    if (button) selectAppPanel(button.dataset.appPanel);
}

function selectAppPanel(panel, persist = true) {
    if (!["sessions", "scheduler", "mcp", "settings"].includes(panel)) return;
    elements.app.dataset.activePanel = panel;
    for (const button of elements.panelNavigation.querySelectorAll("[data-app-panel]")) {
        const selected = button.dataset.appPanel === panel;
        button.classList.toggle("active", selected);
        button.setAttribute("aria-pressed", String(selected));
    }
    for (const content of document.querySelectorAll("[data-app-content]")) {
        content.hidden = content.dataset.appContent !== panel;
    }
    elements.newSession.hidden = panel !== "sessions";
    if (persist) localStorage.setItem(ACTIVE_PANEL_KEY, panel);
    if (persist && panel === "scheduler") void loadSchedulerPanel();
    if (persist && panel === "mcp") {
        void loadMcpCatalog();
        void refreshReceiptsSession();
        if (tbankSession.authenticated) void loadTbankAccounts();
    }
}

async function handlePopState() {
    const navigationVersion = ++sessionNavigationVersion;
    const sessionId = sessionIdFromLocation();
    selectAppPanel("sessions", false);
    if (sessionId === null) {
        renderEmptyState();
        setNavigationLoading(false);
        return;
    }
    setNavigationLoading(true);
    try {
        const state = await api(`/api/agent/sessions/${encodeURIComponent(sessionId)}`);
        if (
            sessionNavigationVersion !== navigationVersion ||
            sessionIdFromLocation() !== sessionId
        ) {
            return;
        }
        setActiveState(state);
    } catch (error) {
        if (
            sessionNavigationVersion !== navigationVersion ||
            sessionIdFromLocation() !== sessionId
        ) {
            return;
        }
        if (error.status === 404) {
            renderSessionNotFound();
        } else {
            renderEmptyState();
            showError(error.message);
        }
    } finally {
        if (
            sessionNavigationVersion === navigationVersion &&
            sessionIdFromLocation() === sessionId
        ) {
            setNavigationLoading(false);
        }
    }
}

async function refreshTbankSession() {
    tbankSession = await api("/api/agent/tbank/session");
    renderTbankSession();
    if (tbankSession.authenticated) {
        await loadTbankAccounts();
    }
}

async function refreshReceiptsSession() {
    if (!elements.receiptsAuthStatus) return;
    try {
        renderReceiptsAuth(await api("/api/agent/receipts/session"));
    } catch (_) {
        if (!receiptsAuthState) {
            renderReceiptsAuth(null);
        } else {
            elements.receiptsAuthDetail.textContent =
                "Не удалось обновить статус; последнее известное состояние сохранено.";
            scheduleReceiptStatusPoll();
        }
    }
}

function renderReceiptsAuth(status) {
    const allowedStatuses = new Set([
        "authenticating",
        "active",
        "login_required",
        "recoverable_error",
        "logout_failed",
    ]);
    const statusCode = allowedStatuses.has(status?.status) ? status.status : "recoverable_error";
    receiptsAuthState = {
        authenticated: statusCode === "active" && status?.authenticated === true,
        retryable: statusCode === "recoverable_error" && status?.retryable === true,
        status: statusCode,
    };
    const labels = {
        authenticating: "Вход выполняется",
        active: "Авторизация активна",
        login_required: "Нужно войти",
        recoverable_error: "Сервис временно недоступен",
        logout_failed: "Не удалось завершить выход",
    };
    const details = {
        authenticating: "Продолжите вход в отдельном официальном окне ФНС. Не вводите код в это приложение.",
        active: "Авторизация готова. Можно искать чеки за выбранный период.",
        login_required: "Нажмите «Войти в ФНС» и завершите вход в отдельном окне.",
        recoverable_error: "Обновите статус или явно повторите проверку авторизации.",
        logout_failed: "Повторите выход, чтобы удалить сохранённую авторизацию.",
    };
    const message = typeof status?.message === "string" ? status.message.trim().slice(0, 240) : "";
    const persistenceStatus = status?.persistence_status;
    const persistenceMessage = typeof status?.persistence_message === "string"
        ? status.persistence_message.trim().slice(0, 240)
        : "";
    let persistenceNote = persistenceMessage;
    if (!persistenceNote) {
        switch (persistenceStatus) {
            case "persisted":
                persistenceNote = "Сессия сохранена в системном хранилище.";
                break;
            case "memory_only":
                persistenceNote = "Сессия доступна только до перезапуска; затем потребуется повторный вход.";
                break;
            case "unavailable":
                persistenceNote = "Системное хранилище учётных данных недоступно.";
                break;
        }
    }
    elements.receiptsAuthStatus.textContent = labels[statusCode];
    elements.receiptsAuthStatus.className = `mcp-server-status ${statusCode === "active" ? "connected" : ""}`;
    elements.receiptsAuthDetail.textContent =
        [details[statusCode], message, persistenceNote].filter(Boolean).join(" ");
    elements.receiptsSessionRetry.hidden = !receiptsAuthState.retryable;
    elements.receiptsLogout.hidden = !receiptsAuthState.authenticated && statusCode !== "logout_failed";
    elements.receiptsBrowserLogin.disabled = statusCode === "authenticating";
    scheduleReceiptStatusPoll();
}

function scheduleReceiptStatusPoll() {
    if (receiptsStatusPollTimer) clearTimeout(receiptsStatusPollTimer);
    receiptsStatusPollTimer = null;
    if (receiptsAuthState?.status !== "authenticating") return;
    receiptsStatusPollTimer = setTimeout(() => void refreshReceiptsSession(), 2_000);
}

async function handleReceiptsBrowserLogin() {
    elements.receiptsBrowserLogin.disabled = true;
    elements.receiptsAuthDetail.textContent = "Открываем отдельное окно официального сайта ФНС…";
    try {
        renderReceiptsAuth(await api("/api/agent/receipts/browser-login", { method: "POST" }));
    } catch (_) {
        elements.receiptsAuthDetail.textContent =
            "Не удалось запустить вход; последнее известное состояние авторизации сохранено.";
    } finally {
        if (receiptsAuthState?.status !== "authenticating") {
            elements.receiptsBrowserLogin.disabled = false;
        }
    }
}

async function handleReceiptsSessionRetry() {
    elements.receiptsSessionRetry.disabled = true;
    try {
        renderReceiptsAuth(await api("/api/agent/receipts/session/retry", { method: "POST" }));
    } catch (_) {
        elements.receiptsAuthDetail.textContent =
            "Не удалось выполнить явное обновление авторизации; последнее состояние сохранено.";
    } finally {
        elements.receiptsSessionRetry.disabled = !receiptsAuthState?.retryable;
    }
}

async function handleReceiptsLogout() {
    elements.receiptsLogout.disabled = true;
    try {
        renderReceiptsAuth(await api("/api/agent/receipts/logout", { method: "POST" }));
    } catch (_) {
        elements.receiptsAuthDetail.textContent =
            "Не удалось завершить выход; последнее известное состояние сохранено.";
    } finally {
        elements.receiptsLogout.disabled =
            !receiptsAuthState?.authenticated && receiptsAuthState?.status !== "logout_failed";
    }
}

async function searchReceipts(event) {
    event.preventDefault();
    const seller = elements.receiptsSeller.value.trim();
    const request = {
        from: elements.receiptsFrom.value,
        to: elements.receiptsTo.value,
    };
    if (seller) request.seller = seller;
    elements.receiptsSearchSubmit.disabled = true;
    elements.receiptsSearchStatus.textContent = "Ищем чеки…";
    elements.receiptsSummaryList.replaceChildren();
    elements.receiptDetailPanel.hidden = true;
    try {
        const result = await api("/api/agent/receipts/search", jsonOptions("POST", request));
        renderReceiptSummaries(result);
    } catch (error) {
        elements.receiptsSearchStatus.textContent = `Поиск не выполнен: ${error.message}`;
        elements.receiptsSearchStatus.className = "control-note error";
    } finally {
        elements.receiptsSearchSubmit.disabled = false;
    }
}

function renderReceiptSummaries(result) {
    elements.receiptsSummaryList.replaceChildren();
    elements.receiptsSearchStatus.className = "control-note";
    elements.receiptsSearchStatus.textContent = result.receipts.length
        ? `${result.receipts.length} чеков${result.has_more ? " (показана ограниченная часть)" : ""}.`
        : "Чеки за выбранный период не найдены.";
    for (const receipt of result.receipts) {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "receipt-summary";
        button.dataset.receiptAlias = receipt.receipt_alias;
        button.textContent =
            `${receipt.merchant} · ${receipt.received_at} · ` +
            `${minorToMajor(receipt.amount_minor, receipt.currency)} ${receipt.currency} · Детали`;
        elements.receiptsSummaryList.append(button);
    }
}

async function handleReceiptSummaryClick(event) {
    const button = event.target.closest("button[data-receipt-alias]");
    if (!button) return;
    button.disabled = true;
    elements.receiptDetailPanel.hidden = false;
    elements.receiptDetail.textContent = "Загружаем детали…";
    try {
        const detail = await api(`/api/agent/receipts/${encodeURIComponent(button.dataset.receiptAlias)}`);
        renderReceiptDetail(detail);
    } catch (error) {
        elements.receiptDetail.textContent = `Детали недоступны: ${error.message}`;
    } finally {
        button.disabled = false;
    }
}

function renderReceiptDetail(detail) {
    elements.receiptDetail.replaceChildren();
    const summary = document.createElement("p");
    summary.textContent =
        `${detail.merchant} · ${detail.date_time} · ` +
        `${minorToMajor(detail.total_minor, detail.currency)} ${detail.currency}`;
    const items = document.createElement("ul");
    for (const receiptItem of detail.items) {
        const item = document.createElement("li");
        item.textContent =
            `${receiptItem.name} · ${receiptItem.quantity} × ` +
            `${minorToMajor(receiptItem.price_minor, detail.currency)} = ` +
            `${minorToMajor(receiptItem.sum_minor, detail.currency)} ${detail.currency}`;
        items.append(item);
    }
    elements.receiptDetail.append(summary, items);
}

async function handleTbankSessionRetry() {
    const button = elements.tbankSessionRetry;
    if (!button) return;
    button.disabled = true;
    elements.tbankLoginStatus.textContent = "Повторяем проверку T-Банк session…";
    elements.tbankLoginStatus.className = "control-note";
    try {
        tbankSession = await api("/api/agent/tbank/session/retry");
        renderTbankSession();
        if (tbankSession.authenticated) {
            await loadTbankAccounts();
        }
    } catch (error) {
        elements.tbankLoginStatus.textContent = `Повторная проверка не выполнена: ${error.message}`;
        elements.tbankLoginStatus.className = "control-note error";
    } finally {
        if (elements.tbankSessionRetry) {
            elements.tbankSessionRetry.disabled = false;
        }
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

async function loadSchedulerPanel() {
    if (!elements.schedulerTaskList) return;
    try {
        const [accounts, tasks] = await Promise.all([
            api("/api/agent/scheduler/accounts"),
            api("/api/agent/scheduler/tasks"),
        ]);
        schedulerState.accounts = accounts.accounts || [];
        schedulerState.tasks = tasks || [];
        renderSchedulerAccounts();
        renderSchedulerTasks();
        elements.schedulerStatus.textContent = accounts.error
            ? `Счета недоступны: ${accounts.error}`
            : "Задания сохраняются в SQLite; следующий запуск использует дату последнего успешного окна.";
        elements.schedulerStatus.className = accounts.error ? "control-note error" : "control-note";
    } catch (error) {
        schedulerState.tasks = [];
        elements.schedulerTaskList.replaceChildren();
        elements.schedulerStatus.textContent = `Планировщик недоступен: ${error.message}`;
        elements.schedulerStatus.className = "control-note error";
    }
}

function renderSchedulerAccounts() {
    if (!elements.schedulerAccounts) return;
    const selected = new Set([...elements.schedulerAccounts.selectedOptions].map((option) => option.value));
    elements.schedulerAccounts.replaceChildren();
    for (const account of schedulerState.accounts) {
        const option = document.createElement("option");
        option.value = account.account_ref;
        const balance = account.balance_minor == null
            ? ""
            : ` · ${minorToMajor(account.balance_minor, account.currency || "RUB")} ${account.currency || "RUB"}`;
        option.textContent = `${account.name || "Счёт / карта"}${balance}`;
        option.selected = selected.has(option.value);
        elements.schedulerAccounts.append(option);
    }
}

function renderSchedulerTasks() {
    if (!elements.schedulerTaskList) return;
    elements.schedulerTaskList.replaceChildren();
    renderLinkedSchedulerTasks();
    if (schedulerState.tasks.length === 0) {
        const empty = document.createElement("p");
        empty.className = "muted";
        empty.textContent = "Фоновые задания пока не созданы.";
        elements.schedulerTaskList.append(empty);
        return;
    }
    for (const task of schedulerState.tasks) {
        const article = document.createElement("article");
        article.className = "scheduler-task";
        article.id = `scheduler-task-${task.id}`;
        article.dataset.taskId = task.id;
        const heading = document.createElement("h3");
        heading.textContent = task.name;
        const status = document.createElement("span");
        status.className = "mcp-server-status";
        status.textContent = task.status === "active" ? "активно" : "пауза";
        heading.append(" ", status);
        const meta = document.createElement("p");
        meta.className = "control-note";
        const nextRun = task.next_run_at_epoch_ms == null
            ? "не запланирован"
            : formatDate(task.next_run_at_epoch_ms);
        meta.textContent =
            `Счета: ${task.account_refs.length}; окно с ${task.cursor_date || task.start_date}; ` +
            `период ${task.interval_minutes} мин; часовой пояс ${task.time_zone}; ` +
            `следующий запуск: ${nextRun}.`;
        const sessionLink = document.createElement("a");
        if (task.target_session_id) {
            sessionLink.href = `/agent/sessions/${encodeURIComponent(task.target_session_id)}`;
            sessionLink.dataset.sessionId = task.target_session_id;
            sessionLink.textContent = "Открыть связанную сессию";
        } else {
            sessionLink.textContent = "Интерактивная сессия появится при первом запуске";
        }
        const result = document.createElement("p");
        result.className = "control-note";
        if (task.last_error) {
            result.textContent = `Последняя ошибка: ${task.last_error}`;
            result.classList.add("error");
        } else if (task.last_result) {
            result.textContent =
                `Последний запуск: ${task.last_result.transaction_count} операций, ` +
                `${task.last_result.enriched_item_count} позиций, ` +
                `${task.last_result.unmatched_count} без чека, ` +
                `${task.last_result.ambiguous_count} неоднозначных.`;
        } else {
            result.textContent = "Запуск ещё не выполнялся.";
        }
        const actions = document.createElement("div");
        actions.className = "category-form-actions";
        actions.append(
            schedulerButton(task.status === "active" ? "pause" : "resume", task.status === "active" ? "Пауза" : "Продолжить"),
            schedulerButton("run", "Запустить сейчас"),
            schedulerButton("history", "История"),
        );
        article.append(heading, meta, sessionLink, result, actions);
        elements.schedulerTaskList.append(article);
    }
}

function renderLinkedSchedulerTasks() {
    if (!elements.linkedSchedulerTasks || !elements.linkedSchedulerTaskList) return;
    const tasks = schedulerState.tasks.filter(
        (task) => task.target_session_id === activeState?.session.id,
    );
    elements.linkedSchedulerTaskList.replaceChildren();
    elements.linkedSchedulerTasks.hidden = tasks.length === 0;
    for (const task of tasks) {
        const item = document.createElement("article");
        item.className = "linked-scheduler-task";
        const details = document.createElement("div");
        const heading = document.createElement("h3");
        heading.textContent = task.name;
        const status = document.createElement("p");
        status.className = "control-note";
        status.textContent = task.status === "active" ? "Фоновая задача активна." : "Фоновая задача приостановлена.";
        details.append(heading, status);
        const button = document.createElement("button");
        button.type = "button";
        button.className = "secondary-button compact-button";
        button.dataset.linkedTaskId = task.id;
        button.textContent = "Открыть задачу";
        item.append(details, button);
        elements.linkedSchedulerTaskList.append(item);
    }
}

async function openLinkedSchedulerTask(event) {
    const button = event.target.closest("button[data-linked-task-id]");
    if (!button) return;
    const taskId = button.dataset.linkedTaskId;
    selectAppPanel("scheduler", false);
    localStorage.setItem(ACTIVE_PANEL_KEY, "scheduler");
    await loadSchedulerPanel();
    const card = document.getElementById(`scheduler-task-${taskId}`);
    if (!card) {
        showError("Связанное фоновое задание больше не существует.");
        return;
    }
    card.scrollIntoView({ block: "center", behavior: "smooth" });
    card.classList.add("linked-task-target");
    setTimeout(() => card.classList.remove("linked-task-target"), 1_500);
}


function schedulerButton(action, text) {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "secondary-button compact-button";
    button.dataset.schedulerAction = action;
    button.textContent = text;
    return button;
}

async function createSchedulerTask(event) {
    event.preventDefault();
    const accountRefs = [...elements.schedulerAccounts.selectedOptions].map((option) => option.value);
    if (accountRefs.length === 0) {
        elements.schedulerStatus.textContent = "Выберите хотя бы один счёт.";
        elements.schedulerStatus.className = "control-note error";
        return;
    }
    await runBusy(async () => {
        await api("/api/agent/scheduler/tasks", jsonOptions("POST", {
            name: elements.schedulerName.value.trim(),
            account_refs: accountRefs,
            start_date: elements.schedulerStartDate.value,
            interval_minutes: Number(elements.schedulerInterval.value),
        }));
        await loadSchedulerPanel();
        await loadSessions();
        showSuccess("Фоновое задание создано.");
    });
}

async function handleSchedulerTaskClick(event) {
    const sessionLink = event.target.closest("a[data-session-id]");
    if (sessionLink) {
        event.preventDefault();
        void openSession(sessionLink.dataset.sessionId);
        return;
    }
    const button = event.target.closest("button[data-scheduler-action]");
    const article = button?.closest("[data-task-id]");
    if (!button || !article) return;
    const taskId = article.dataset.taskId;
    const action = button.dataset.schedulerAction;
    if (action === "history") {
        await loadSchedulerHistory(taskId);
        return;
    }
    await runBusy(async () => {
        try {
            const path = action === "run"
                ? `/api/agent/scheduler/tasks/${encodeURIComponent(taskId)}/run`
                : `/api/agent/scheduler/tasks/${encodeURIComponent(taskId)}/${action}`;
            await api(path, { method: "POST" });
            showSuccess(action === "run" ? "Фоновое задание выполнено." : "Состояние задания обновлено.");
        } finally {
            await loadSchedulerPanel();
        }
    });
}

async function loadSchedulerHistory(taskId) {
    try {
        const history = await api(
            `/api/agent/scheduler/tasks/${encodeURIComponent(taskId)}/history?limit=20`,
        );
        renderSchedulerHistory(history);
    } catch (error) {
        elements.schedulerHistory.textContent = `История недоступна: ${error.message}`;
    }
}

function renderSchedulerHistory(history) {
    elements.schedulerHistory.replaceChildren();
    const heading = document.createElement("h3");
    heading.textContent = "История выбранного задания";
    elements.schedulerHistory.append(heading);
    if (!history.length) {
        const empty = document.createElement("p");
        empty.className = "muted";
        empty.textContent = "Запусков пока нет.";
        elements.schedulerHistory.append(empty);
        return;
    }
    for (const run of history) {
        const item = document.createElement("details");
        const summary = document.createElement("summary");
        summary.textContent = `${run.status === "succeeded" ? "Успешно" : "Ошибка"} · ${run.run_id}`;
        item.append(summary);
        const text = document.createElement("p");
        text.className = "control-note";
        text.textContent = run.error || (
            run.result
                ? `Окно ${run.result.window_from} — ${run.result.window_to}; trace-событий: ${run.result.trace.length}.`
                : "Результат отсутствует."
        );
        item.append(text);
        for (const event of run.result?.trace || []) {
            const trace = document.createElement("div");
            trace.className = "scheduler-trace";
            trace.textContent = [
                event.stage,
                event.server_id,
                event.tool,
                event.status,
                event.detail,
            ].filter(Boolean).join(" · ");
            item.append(trace);
        }
        elements.schedulerHistory.append(item);
    }
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

async function loadMcpCatalog() {
    ensureMcpElements();
    elements.mcpRefresh.disabled = true;
    elements.mcpStatus.textContent = "Обновляем каталог…";
    elements.mcpStatus.className = "control-note";
    try {
        mcpCatalog = await api("/api/agent/mcp");
        renderMcpCatalog();
    } catch (error) {
        elements.mcpStatus.textContent = `Не удалось обновить MCP: ${error.message}`;
        elements.mcpStatus.className = "control-note error";
    } finally {
        elements.mcpRefresh.disabled = false;
    }
}

function renderMcpCatalog() {
    const servers = Array.isArray(mcpCatalog.servers) ? mcpCatalog.servers : [];
    ensureMcpElements();
    elements.mcpList.replaceChildren();
    if (servers.length === 0) {
        elements.mcpStatus.textContent = "MCP-серверы не настроены.";
        elements.mcpStatus.className = "control-note";
        return;
    }

    const connected = servers.filter((server) => server.status === "connected").length;
    elements.mcpStatus.textContent = `Подключено: ${connected} из ${servers.length}. tools/call доступен через форму Т-Банк.`;
    elements.mcpStatus.className = connected === servers.length
        ? "control-note success"
        : "control-note";

    for (const server of servers) {
        const item = document.createElement("article");
        item.className = "mcp-server-item";

        const heading = document.createElement("div");
        heading.className = "section-heading";
        const title = document.createElement("strong");
        title.textContent = server.display_name;
        const status = document.createElement("span");
        status.className = `mcp-server-status ${server.status}`;
        status.textContent = mcpStatusLabel(server.status);
        heading.append(title, status);
        item.append(heading);

        if (server.error) {
            const error = document.createElement("p");
            error.className = "mcp-server-error";
            error.textContent = server.error;
            item.append(error);
        }

        const tools = Array.isArray(server.tools) ? server.tools : [];
        const toolList = document.createElement("div");
        toolList.className = "mcp-tool-list";
        if (tools.length === 0) {
            const empty = document.createElement("p");
            empty.className = "muted";
            empty.textContent = "Tools не обнаружены.";
            toolList.append(empty);
        } else {
            for (const tool of tools) {
                const toolItem = document.createElement("article");
                toolItem.className = "mcp-tool-item";
                const toolName = document.createElement("strong");
                toolName.textContent = tool.name;
                toolItem.append(toolName);

                if (tool.description) {
                    const description = document.createElement("p");
                    description.textContent = tool.description;
                    toolItem.append(description);
                }

                const schemaDetails = document.createElement("details");
                const schemaSummary = document.createElement("summary");
                schemaSummary.textContent = "Входная схема";
                const schema = document.createElement("pre");
                schema.textContent = JSON.stringify(tool.input_schema || {}, null, 2);
                schemaDetails.append(schemaSummary, schema);
                toolItem.append(schemaDetails);
                toolList.append(toolItem);
            }
        }
        item.append(toolList);
        elements.mcpList.append(item);
    }
}

async function handleTbankLogin(event) {
    event.preventDefault();
    const stageBeforeRequest = loginStage;
    const button = elements.tbankSubmit;
    button.disabled = true;
    elements.tbankLoginStatus.textContent = "Выполняем login…";
    elements.tbankLoginStatus.className = "control-note";
    try {
        const response = await api("/api/agent/tbank/login", jsonOptions("POST", {
            phone: elements.tbankPhone.value.trim(),
            password: stageBeforeRequest === "password" ? elements.tbankPassword.value : "",
            otp: stageBeforeRequest !== "phone" ? elements.tbankOtp.value.trim() : "",
        }));
        tbankSession = response;
        renderTbankSession();
        if (response.authenticated) {
            resetLoginForm(true);
        } else if (response.requires_password) {
            setLoginStage("password");
            elements.tbankPassword.focus();
        } else if (response.requires_otp) {
            setLoginStage("otp");
            elements.tbankOtp.focus();
        } else if (stageBeforeRequest === "otp") {
            setLoginStage("otp");
            elements.tbankOtp.value = "";
        } else if (stageBeforeRequest === "password") {
            setLoginStage("password");
            elements.tbankPassword.value = "";
        } else {
            setLoginStage("phone");
        }
        elements.tbankLoginStatus.textContent = response.message;
        elements.tbankLoginStatus.className = response.authenticated
            ? "control-note success"
            : response.status === "requires_otp" || response.status === "requires_password"
                ? "control-note"
                : "control-note error";
        if (response.authenticated) {
            elements.tbankPassword.value = "";
            elements.tbankOtp.value = "";
            await loadTbankAccounts();
            await loadSchedulerPanel();
        }
    } catch (error) {
        if (stageBeforeRequest === "otp") {
            setLoginStage("otp");
            elements.tbankOtp.value = "";
        } else if (stageBeforeRequest === "password") {
            setLoginStage("password");
            elements.tbankPassword.value = "";
        } else {
            setLoginStage("phone");
        }
        elements.tbankLoginStatus.textContent = `Login не выполнен: ${error.message}`;
        elements.tbankLoginStatus.className = "control-note error";
    } finally {
        button.disabled = false;
    }
}

async function handleTbankResend() {
    if (loginStage !== "otp") return;
    elements.tbankResend.disabled = true;
    elements.tbankLoginStatus.textContent = "Отправляем новый SMS-код…";
    elements.tbankLoginStatus.className = "control-note";
    try {
        const response = await api(
            "/api/agent/tbank/otp/resend",
            jsonOptions("POST", { phone: elements.tbankPhone.value.trim() }),
        );
        tbankSession = response;
        renderTbankSession();
        if (response.authenticated) {
            resetLoginForm(true);
            await loadTbankAccounts();
            await loadSchedulerPanel();
        } else if (response.requires_password) {
            setLoginStage("password");
            elements.tbankPassword.focus();
        } else if (response.requires_otp) {
            setLoginStage("otp");
            elements.tbankOtp.value = "";
            startResendCooldown();
            elements.tbankOtp.focus();
        } else {
            setLoginStage("otp");
        }
        elements.tbankLoginStatus.textContent = response.message;
        elements.tbankLoginStatus.className = response.authenticated
            ? "control-note success"
            : response.status === "requires_otp" || response.status === "requires_password"
                ? "control-note"
                : "control-note error";
    } catch (error) {
        setLoginStage("otp");
        elements.tbankLoginStatus.textContent = `SMS-код не отправлен повторно: ${error.message}`;
        elements.tbankLoginStatus.className = "control-note error";
    } finally {
        if (!resendCooldownTimer && loginStage === "otp") {
            elements.tbankResend.disabled = false;
        }
    }
}

function handleTbankChangePhone() {
    resetLoginForm(false);
    elements.tbankLoginStatus.textContent = "Введите номер телефона и запросите SMS-код.";
    elements.tbankLoginStatus.className = "control-note";
    elements.tbankPhone.focus();
}

async function handleTbankLogout() {
    elements.tbankLogout.disabled = true;
    try {
        tbankSession = await api("/api/agent/tbank/logout", { method: "POST" });
        elements.tbankAccount.replaceChildren();
        schedulerState.accounts = [];
        renderSchedulerAccounts();
        resetLoginForm(true);
        elements.tbankResult.hidden = true;
        elements.tbankResult.textContent = "";
        elements.tbankQueryForm.hidden = true;
        elements.tbankLoginStatus.textContent = tbankSession.persistence_status === "unavailable"
            ? "Сессия очищена в памяти; OS credential store недоступен."
            : "Сессия очищена в памяти и OS credential store.";
        elements.tbankLoginStatus.className = "control-note";
        renderTbankSession();
        await loadSchedulerPanel();
    } catch (error) {
        elements.tbankLoginStatus.textContent = `Logout не выполнен: ${error.message}`;
        elements.tbankLoginStatus.className = "control-note error";
    } finally {
        elements.tbankLogout.disabled = false;
    }
}

function tbankPersistenceNote() {
    switch (tbankSession.persistence_status) {
        case "persisted":
            return "Сессия восстановима после перезапуска";
        case "memory_only":
            return "Сессия действует только до перезапуска";
        case "unavailable":
            return "Сохранение сессии недоступно";
        default:
            return "";
    }
}

function renderTbankSession() {
    const authenticated = Boolean(tbankSession.authenticated);
    const recoverable = Boolean(
        tbankSession.retryable || tbankSession.status === "recoverable_error",
    );
    elements.tbankLogout.hidden = !(authenticated || recoverable);
    elements.tbankSessionRetry.hidden = !recoverable;
    if (authenticated) {
        elements.tbankLoginForm.hidden = true;
        const persistenceNote = tbankPersistenceNote();
        const suffix = persistenceNote ? ` · ${persistenceNote}` : "";
        elements.tbankSessionStatus.textContent = `Сессия активна${suffix}`;
        elements.tbankSessionStatus.className = "mcp-server-status connected";
        elements.tbankLoginStatus.textContent = "Сессия активна; повторный login не требуется.";
        elements.tbankLoginStatus.className = "control-note success";
        return;
    }
    elements.tbankLoginForm.hidden = false;
    const persistenceMessage = tbankSession.persistence_message || "";
    if (recoverable) {
        const retryAfter = Number(tbankSession.retry_after_seconds || 0);
        const retryHint = retryAfter > 0
            ? ` Повторите через ${retryAfter} сек.`
            : " Нажмите «Повторить проверку».";
        elements.tbankSessionStatus.textContent =
            `T-Банк временно недоступен: ${persistenceMessage || "session сохранена."}${retryHint}`;
        elements.tbankSessionStatus.className = "mcp-server-status recoverable";
        elements.tbankLoginStatus.textContent =
            `${persistenceMessage || "Сохранённая session не изменена."}${retryHint}`;
        elements.tbankLoginStatus.className = "control-note";
        elements.tbankQueryForm.hidden = true;
        return;
    }
    elements.tbankSessionStatus.textContent = persistenceMessage
        ? `login требуется: ${persistenceMessage}`
        : "login не выполнен";
    elements.tbankSessionStatus.className = persistenceMessage
        ? "mcp-server-status error"
        : "mcp-server-status";
    elements.tbankLoginStatus.textContent = persistenceMessage || "Выполните login для доступа к T-Bank.";
    elements.tbankLoginStatus.className = persistenceMessage
        ? "control-note error"
        : "control-note";
    elements.tbankQueryForm.hidden = true;
}

function normalizeTbankAccounts(payload) {
    if (typeof payload === "string") {
        try {
            return normalizeTbankAccounts(JSON.parse(payload));
        } catch (_) {
            return [];
        }
    }
    if (Array.isArray(payload)) {
        return payload.flatMap((item) => {
            if (item && typeof item === "object") {
                const embedded = item.text ?? item.data ?? item.result;
                if (embedded !== undefined) {
                    return normalizeTbankAccounts(embedded);
                }
            }
            const account = normalizeTbankAccount(item);
            return account ? [account] : [];
        });
    }
    if (!payload || typeof payload !== "object") {
        return [];
    }
    for (const key of [
        "accounts",
        "account_list",
        "items",
        "payload",
        "data",
        "result",
        "response",
        "content",
        "structuredContent",
        "structured_content",
        "text",
    ]) {
        const nested = normalizeTbankAccounts(payload[key]);
        if (nested.length > 0) {
            return nested;
        }
    }
    const account = normalizeTbankAccount(payload);
    return account ? [account] : [];
}


function normalizeTbankAccount(account) {
    if (!account || typeof account !== "object") {
        return null;
    }
    const reference = firstAccountValue(account, [
        "account_ref",
        "account_id",
        "accountId",
        "ref",
        "id",
    ]);
    if (reference == null || String(reference).trim() === "") {
        return null;
    }
    const name = firstAccountValue(account, [
        "name",
        "account_name",
        "accountName",
        "display_name",
        "displayName",
        "title",
        "alias",
    ]);
    return {
        ...account,
        account_ref: String(reference),
        name: name == null || String(name).trim() === "" ? "Счёт" : String(name),
    };
}

function firstAccountValue(account, keys) {
    for (const key of keys) {
        const value = account[key];
        if (value != null && String(value).trim() !== "") {
            return value;
        }
    }
    return null;
}

async function loadTbankAccounts() {
    try {
        const payload = await callTbankTool("list-accounts", {});
        const accounts = normalizeTbankAccounts(payload);
        elements.tbankAccount.replaceChildren();
        for (const account of accounts) {
            const option = document.createElement("option");
            option.value = account.account_ref;
            const balance = account.balance_minor == null
                ? ""
                : ` · ${(account.balance_minor / 100).toFixed(2)} ${account.currency}`;
            option.textContent = `${account.name}${balance}`;
            elements.tbankAccount.append(option);
        }
        elements.tbankQueryForm.hidden = elements.tbankAccount.options.length === 0;
        if (accounts.length === 0) {
            elements.tbankLoginStatus.textContent =
                "list-accounts вернул пустой список: session отвечает, но доступные счета не получены. Проверьте доступ или выполните login заново.";
            elements.tbankLoginStatus.className = "control-note error";
            return false;
        }
        elements.tbankLoginStatus.textContent = `Счета получены через tools/call: ${elements.tbankAccount.options.length}.`;
        elements.tbankLoginStatus.className = "control-note success";
        return true;
    } catch (error) {
        elements.tbankQueryForm.hidden = true;
        elements.tbankLoginStatus.textContent = `Не удалось вызвать list-accounts: ${error.message}`;
        elements.tbankLoginStatus.className = "control-note error";
        return false;
    }
}

async function handleTbankTransactions(event) {
    event.preventDefault();
    const button = document.querySelector("#tbank-load-transactions");
    button.disabled = true;
    elements.tbankResult.hidden = false;
    elements.tbankResult.textContent = "Вызываем get-account-transactions…";
    try {
        const result = await callTbankTool("get-account-transactions", {
            account_ref: elements.tbankAccount.value,
            from: elements.tbankFrom.value,
            to: elements.tbankTo.value,
            limit: Number(elements.tbankLimit.value),
        });
        elements.tbankResult.textContent = JSON.stringify(result, null, 2);
        elements.tbankLoginStatus.textContent = `Результат tools/call получен: ${result.transactions?.length || 0} операций.`;
        elements.tbankLoginStatus.className = "control-note success";
    } catch (error) {
        elements.tbankResult.textContent = `Ошибка tools/call: ${error.message}`;
        elements.tbankLoginStatus.textContent = `Операции не получены: ${error.message}`;
        elements.tbankLoginStatus.className = "control-note error";
    } finally {
        button.disabled = false;
    }
}

async function callTbankTool(tool, argumentsValue) {
    const response = await api(
        "/api/agent/tbank/tools/call",
        jsonOptions("POST", { tool, arguments: argumentsValue }),
    );
    if (response.is_error) {
        throw new ApiError(response.text || "MCP tool вернул ошибку.", 422);
    }
    try {
        return JSON.parse(response.text);
    } catch (_) {
        throw new ApiError("MCP tool вернул невалидный JSON.", 502);
    }
}


function setLoginStage(stage) {
    loginStage = stage;
    const hasChallenge = stage !== "phone";
    elements.tbankOtpStep.hidden = !hasChallenge;
    elements.tbankPhone.readOnly = hasChallenge;
    elements.tbankResend.hidden = stage !== "otp";
    elements.tbankChangePhone.hidden = stage === "phone";
    elements.tbankSubmit.textContent = stage === "phone"
        ? "Получить SMS-код"
        : "Войти";
    setPasswordVisibility(stage === "password");
    if (stage !== "otp") {
        stopResendCooldown();
    } else if (!resendCooldownTimer) {
        elements.tbankResend.disabled = false;
        elements.tbankResend.textContent = "Повторить код";
    }
}

function resetLoginForm(clearPhone) {
    stopResendCooldown();
    if (clearPhone) elements.tbankPhone.value = "";
    elements.tbankOtp.value = "";
    elements.tbankPassword.value = "";
    setLoginStage("phone");
}

function startResendCooldown() {
    stopResendCooldown();
    const deadline = Date.now() + 30_000;
    const update = () => {
        if (loginStage !== "otp") {
            stopResendCooldown();
            return;
        }
        const seconds = Math.ceil(Math.max(0, deadline - Date.now()) / 1_000);
        if (seconds === 0) {
            stopResendCooldown();
            return;
        }
        elements.tbankResend.disabled = true;
        elements.tbankResend.textContent = `Повторить код (${seconds})`;
    };
    resendCooldownTimer = window.setInterval(update, 250);
    update();
}

function stopResendCooldown() {
    if (resendCooldownTimer) {
        window.clearInterval(resendCooldownTimer);
        resendCooldownTimer = null;
    }
    if (elements.tbankResend) {
        elements.tbankResend.disabled = false;
        elements.tbankResend.textContent = "Повторить код";
    }
}

function setPasswordVisibility(visible) {
    elements.tbankPasswordField.hidden = !visible;
    elements.tbankPassword.disabled = !visible;
    if (!visible) elements.tbankPassword.value = "";
}

function mcpStatusLabel(status) {
    return {
        connected: "подключён",
        disabled: "отключён",
        unavailable: "недоступен",
        protocol_error: "ошибка протокола",
    }[status] || status;
}

function renderPreferences(preferences) {
    lastPreferences = preferences;
    elements.globalUserPrompt.value = preferences.user_prompt || "";
    renderMerchantRules(preferences.merchant_canonical_rules || []);
    elements.confirmedDecisionsList.replaceChildren();
    const decisions = preferences.confirmed_decisions || [];
    if (decisions.length === 0) {
        const empty = document.createElement("li");
        empty.className = "muted";
        empty.textContent = "Подтверждённых решений пока нет.";
        elements.confirmedDecisionsList.append(empty);
        return;
    }
    for (const decision of decisions) {
        const item = document.createElement("li");
        item.className = "confirmed-decision-item";
        const input = document.createElement("input");
        input.className = "decision-editor";
        input.type = "text";
        input.maxLength = 500;
        input.value = decision.text;
        input.setAttribute("aria-label", "Текст подтверждённого решения");
        const date = document.createElement("small");
        date.textContent = `сохранено ${formatDate(decision.created_at_epoch_ms)}`;
        const actions = document.createElement("div");
        actions.className = "confirmed-decision-actions";
        const save = document.createElement("button");
        save.type = "button";
        save.className = "secondary-button compact-button";
        save.textContent = "Сохранить";
        save.addEventListener("click", () => saveConfirmedDecision(decision.id, input));
        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "danger-button compact-button";
        remove.textContent = "Удалить";
        remove.addEventListener("click", () => deleteConfirmedDecision(decision.id));
        actions.append(save, remove);
        item.append(input, date, actions);
        elements.confirmedDecisionsList.append(item);
    }
}

function renderMerchantRules(rules) {
    elements.merchantRuleList.replaceChildren();
    if (rules.length === 0) {
        const empty = document.createElement("p");
        empty.className = "muted";
        empty.textContent = "Канонических правил пока нет.";
        elements.merchantRuleList.append(empty);
        return;
    }
    for (const rule of rules) {
        const item = document.createElement("article");
        item.className = "category-item";
        const title = document.createElement("strong");
        title.textContent = rule.canonical_name;
        const meta = document.createElement("small");
        const suffix = rule.suffix_policy === "numeric_terminal"
            ? " · допускается числовой суффикс"
            : "";
        meta.textContent = `Варианты: ${rule.aliases.join(", ")}${suffix}`;
        const actions = document.createElement("div");
        actions.className = "category-actions";
        const edit = document.createElement("button");
        edit.type = "button";
        edit.className = "secondary-button compact-button";
        edit.dataset.action = "edit-merchant-rule";
        edit.dataset.ruleId = rule.id;
        edit.textContent = "Изменить";
        const remove = document.createElement("button");
        remove.type = "button";
        remove.className = "danger-button compact-button";
        remove.dataset.action = "delete-merchant-rule";
        remove.dataset.ruleId = rule.id;
        remove.textContent = "Удалить";
        actions.append(edit, remove);
        item.append(title, meta, actions);
        elements.merchantRuleList.append(item);
    }
}

function handleMerchantRuleListClick(event) {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    const preferences = lastPreferences;
    const rule = (preferences?.merchant_canonical_rules || [])
        .find((item) => item.id === button.dataset.ruleId);
    if (!rule) return;
    if (button.dataset.action === "edit-merchant-rule") {
        editingMerchantRuleId = rule.id;
        elements.merchantRuleEditId.value = rule.id;
        elements.merchantRuleCanonicalName.value = rule.canonical_name;
        elements.merchantRuleAliases.value = rule.aliases.join("\n");
        elements.merchantRuleSuffixPolicy.value = rule.suffix_policy || "none";
        elements.saveMerchantRule.textContent = "Сохранить правило";
        elements.cancelMerchantRuleEdit.hidden = false;
        elements.merchantRuleCanonicalName.focus();
    } else if (button.dataset.action === "delete-merchant-rule") {
        deleteMerchantRule(rule.id);
    }
}

function resetMerchantRuleForm() {
    editingMerchantRuleId = "";
    elements.merchantRuleEditId.value = "";
    elements.merchantRuleCanonicalName.value = "";
    elements.merchantRuleAliases.value = "";
    elements.merchantRuleSuffixPolicy.value = "none";
    elements.saveMerchantRule.textContent = "Добавить правило";
    elements.cancelMerchantRuleEdit.hidden = true;
}

async function saveMerchantRuleForm(event) {
    event.preventDefault();
    const canonicalName = elements.merchantRuleCanonicalName.value.trim();
    const aliases = elements.merchantRuleAliases.value
        .split(/[\n,;]/)
        .map((value) => value.trim())
        .filter(Boolean);
    if (!canonicalName || aliases.length === 0) {
        showError("Укажите каноническое название и хотя бы один вариант.");
        return;
    }
    await runBusy(async () => {
        const ruleId = editingMerchantRuleId;
        const path = ruleId
            ? `/api/agent/preferences/merchant-rules/${encodeURIComponent(ruleId)}`
            : "/api/agent/preferences/merchant-rules";
        const method = ruleId ? "PUT" : "POST";
        const preferences = await api(
            path,
            jsonOptions(method, {
                canonical_name: canonicalName,
                aliases,
                suffix_policy: elements.merchantRuleSuffixPolicy.value,
            }),
        );
        lastPreferences = preferences;
        renderPreferences(preferences);
        resetMerchantRuleForm();
        showSuccess(ruleId ? "Merchant rule обновлено." : "Merchant rule добавлено.");
    });
}

async function deleteMerchantRule(ruleId) {
    if (!window.confirm("Удалить canonical merchant rule? Это действие нельзя отменить.")) return;
    await runBusy(async () => {
        const preferences = await api(
            `/api/agent/preferences/merchant-rules/${encodeURIComponent(ruleId)}`,
            { method: "DELETE" },
        );
        lastPreferences = preferences;
        renderPreferences(preferences);
        resetMerchantRuleForm();
        showSuccess("Merchant rule удалено.");
    });
}

function renderCategoryManager() {
    const selectedParent = elements.categoryParent.value;
    elements.categoryParent.replaceChildren();
    const root = document.createElement("option");
    root.value = "";
    root.textContent = "Корневая категория";
    elements.categoryParent.append(root);
    for (const category of categoryCatalog.filter((item) => !item.archived)) {
        if (category.id === editingCategoryId || categoryIsDescendant(category.id, editingCategoryId)) {
            continue;
        }
        const option = document.createElement("option");
        option.value = category.id;
        option.textContent = categoryDisplayPath(category.id);
        elements.categoryParent.append(option);
    }
    if ([...elements.categoryParent.options].some((option) => option.value === selectedParent)) {
        elements.categoryParent.value = selectedParent;
    }
    syncCategoryTypeControl();

    elements.categoryList.replaceChildren();
    if (categoryCatalog.length === 0) {
        const empty = document.createElement("p");
        empty.className = "muted";
        empty.textContent = "Категорий пока нет.";
        elements.categoryList.append(empty);
        return;
    }
    const sorted = [...categoryCatalog].sort((left, right) =>
        Number(left.archived) - Number(right.archived) ||
        categoryDisplayPath(left.id).localeCompare(categoryDisplayPath(right.id), "ru"),
    );
    for (const category of sorted) {
        const item = document.createElement("article");
        item.className = "category-item";
        if (category.archived) item.classList.add("archived");
        const title = document.createElement("strong");
        title.textContent = categoryDisplayPath(category.id);
        const meta = document.createElement("small");
        meta.textContent = `${categoryTypeLabel(category.type)}${category.archived ? " · Архивная" : ""}`;
        const actions = document.createElement("div");
        actions.className = "category-actions";
        if (!category.archived) {
            const edit = document.createElement("button");
            edit.type = "button";
            edit.className = "secondary-button compact-button";
            edit.dataset.action = "edit-category";
            edit.dataset.categoryId = category.id;
            edit.textContent = "Изменить";
            const archive = document.createElement("button");
            archive.type = "button";
            archive.className = "danger-button compact-button";
            archive.dataset.action = "archive-category";
            archive.dataset.categoryId = category.id;
            archive.textContent = "Архивировать";
            actions.append(edit, archive);
        }
        item.append(title, meta, actions);
        elements.categoryList.append(item);
    }
}

function invariantTypeLabel(type) {
    return {
        no_ledger_write: "Без записи в ledger",
        mask_explicit_phones: "Маскировать телефоны",
        strict_json: "Строгий JSON",
        known_categories: "Известные категории",
        no_silent_ambiguity: "Не исправлять неоднозначность молча",
    }[type] || type;
}

function renderTaskInvariants() {
    elements.systemInvariantList.replaceChildren();
    const invariants = taskInvariantCatalog.system || [];
    if (invariants.length === 0) {
        const empty = document.createElement("p");
        empty.className = "muted";
        empty.textContent = "Системные инварианты загружаются вместе с сессией.";
        elements.systemInvariantList.append(empty);
        return;
    }
    for (const invariant of invariants) {
        elements.systemInvariantList.append(invariantListItem(invariant));
    }
}

function invariantListItem(invariant) {
    const item = document.createElement("article");
    item.className = "category-item";
    const title = document.createElement("strong");
    title.textContent = invariant.title;
    const meta = document.createElement("small");
    meta.textContent = `${invariantTypeLabel(invariant.type)}: ${invariant.value}`;
    const explanation = document.createElement("p");
    explanation.className = "control-note";
    explanation.textContent = invariant.explanation;
    item.append(title, meta, explanation);
    return item;
}

async function refreshTaskInvariants() {
    if (!activeState) {
        taskInvariantCatalog = { system: [] };
        renderTaskInvariants();
        return;
    }
    taskInvariantCatalog = await api(
        `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}/invariants`,
    );
    renderTaskInvariants();
}
function handleCategoryListClick(event) {
    const button = event.target.closest("button[data-action]");
    if (!button) return;
    const category = categoryCatalog.find((item) => item.id === button.dataset.categoryId);
    if (!category) return;
    if (button.dataset.action === "edit-category") {
        editingCategoryId = category.id;
        elements.categoryEditId.value = category.id;
        elements.categoryName.value = category.display_name;
        elements.categoryParent.value = category.parent_id || "";
        elements.categoryHint.value = category.hint || "";
        elements.saveCategory.textContent = "Сохранить категорию";
        elements.cancelCategoryEdit.hidden = false;
        renderCategoryManager();
        elements.categoryName.focus();
    } else if (button.dataset.action === "archive-category") {
        archiveCategory(category);
    }
}

function resetCategoryForm() {
    editingCategoryId = "";
    elements.categoryEditId.value = "";
    elements.categoryName.value = "";
    elements.categoryParent.value = "";
    elements.categoryType.value = "expense";
    elements.categoryHint.value = "";
    elements.saveCategory.textContent = "Добавить категорию";
    elements.cancelCategoryEdit.hidden = true;
    renderCategoryManager();
}

function syncCategoryTypeControl() {
    const parent = categoryCatalog.find((item) => item.id === elements.categoryParent.value);
    if (parent) {
        elements.categoryType.value = parent.type;
        elements.categoryType.disabled = true;
        elements.categoryType.previousElementSibling.textContent = "Тип (унаследован)";
    } else {
        elements.categoryType.disabled = false;
        elements.categoryType.previousElementSibling.textContent = "Тип корневой категории";
    }
}

async function saveCategoryForm(event) {
    event.preventDefault();
    const id = editingCategoryId;
    const parentId = elements.categoryParent.value || null;
    const body = {
        display_name: elements.categoryName.value,
        parent_id: parentId,
        hint: elements.categoryHint.value,
    };
    if (!id) body.type = parentId ? null : elements.categoryType.value;
    await runBusy(async () => {
        const url = id
            ? `/api/agent/categories/${encodeURIComponent(id)}`
            : "/api/agent/categories";
        categoryCatalog = await api(url, jsonOptions(id ? "PUT" : "POST", body));
        resetCategoryForm();
        await reloadCategoryCatalog();
        showSuccess(id ? "Категория обновлена." : "Категория создана.");
    });
}

async function archiveCategory(category) {
    if (!window.confirm(`Архивировать категорию «${category.display_name}»? Старые операции сохранят ссылку на неё.`)) {
        return;
    }
    await runBusy(async () => {
        categoryCatalog = await api(
            `/api/agent/categories/${encodeURIComponent(category.id)}`,
            { method: "DELETE" },
        );
        await reloadCategoryCatalog();
        showSuccess("Категория архивирована.");
    });
}

async function reloadCategoryCatalog() {
    const [providerCatalog, storedCategories] = await Promise.all([
        api("/api/agent/providers"),
        api("/api/agent/categories"),
    ]);
    catalog = providerCatalog;
    categoryCatalog = storedCategories;
    renderCategoryManager();
    if (activeState) {
        renderDraft();
        renderMemoryTrace();
        memoryProjection = null;
        memoryProjectionRequest += 1;
        await refreshMemoryProjection();
    }
}

function categoryDisplayPath(id) {
    const byId = new Map(categoryCatalog.map((category) => [category.id, category]));
    const names = [];
    const visited = new Set();
    let current = byId.get(id);
    while (current && !visited.has(current.id)) {
        names.push(current.display_name);
        visited.add(current.id);
        current = current.parent_id ? byId.get(current.parent_id) : null;
    }
    return names.reverse().join(" / ") || "Категория без названия";
}

function categoryIsDescendant(categoryId, ancestorId) {
    if (!ancestorId) return false;
    const byId = new Map(categoryCatalog.map((category) => [category.id, category]));
    let current = byId.get(categoryId);
    const visited = new Set();
    while (current?.parent_id && !visited.has(current.id)) {
        if (current.parent_id === ancestorId) return true;
        visited.add(current.id);
        current = byId.get(current.parent_id);
    }
    return false;
}

function categoryIsLeaf(categoryId) {
    return !categoryCatalog.some(
        (category) => !category.archived && category.parent_id === categoryId,
    );
}

function categoryTypeLabel(type) {
    return type === "income" ? "Доход" : "Расход";
}


function renderMemoryCandidate(candidate) {
    const item = document.createElement("section");
    item.className = "memory-candidate";
    const title = document.createElement("strong");
    title.textContent = "Кандидат решения";
    const text = document.createElement("p");
    text.textContent = candidate.text;
    const reason = document.createElement("small");
    reason.textContent = `Почему: ${candidate.reason}`;
    item.append(title, text, reason);
    if (candidate.status === "accepted") {
        const accepted = document.createElement("small");
        accepted.className = "candidate-status";
        accepted.textContent = "Добавлено в подтверждённые решения.";
        item.append(accepted);
    } else {
        const accept = document.createElement("button");
        accept.type = "button";
        accept.className = "secondary-button compact-button";
        accept.textContent = "Добавить в подтверждённые решения";
        accept.addEventListener("click", () => acceptMemoryCandidate(candidate.id));
        item.append(accept);
    }
    return item;
}
function renderMerchantCanonicalCandidate(candidate) {
    const item = document.createElement("section");
    item.className = "memory-candidate";
    const title = document.createElement("strong");
    title.textContent = "Предложение правила названия";
    const canonical = document.createElement("p");
    canonical.textContent = `Каноническое название: ${candidate.canonical_name}`;
    const aliases = document.createElement("p");
    aliases.textContent = `Варианты: ${(candidate.aliases || []).join(", ")}`;
    const suffix = document.createElement("small");
    suffix.textContent = candidate.suffix_policy === "numeric_terminal"
        ? "Политика суффикса: допускается числовой суффикс."
        : "Политика суффикса: точное совпадение.";
    const reason = document.createElement("small");
    reason.textContent = `Почему: ${candidate.reason}`;
    item.append(title, canonical, aliases, suffix, reason);
    if (candidate.status === "accepted") {
        const accepted = document.createElement("small");
        accepted.className = "candidate-status";
        accepted.textContent = activeState?.draft
            ? "Правило сохранено и применено к draft."
            : "Правило сохранено и применено к preview.";
        item.append(accepted);
    } else {
        const accept = document.createElement("button");
        accept.type = "button";
        accept.className = "secondary-button compact-button";
        accept.textContent = "Принять правило";
        accept.addEventListener("click", () => acceptMerchantCanonicalCandidate(candidate.id));
        item.append(accept);
    }
    return item;
}


function renderMemoryTrace() {
    const trace = activeState?.memory_trace;
    const projection = memoryProjection;
    const selectedLayers = projection?.selected_layers || trace?.selected_layers || [];
    elements.memoryPanel.hidden = !activeState || (!trace && !projection);
    elements.memoryLayerList.replaceChildren();
    if (elements.memoryPanel.hidden) {
        elements.memoryRequestNote.textContent = "";
        return;
    }
    elements.memoryRequestNote.textContent = selectedLayers.length > 0
        ? `В следующий запрос войдут: ${selectedLayers.map(memoryLayerLabel).join(", ")}. ` +
          "Предпросмотр рассчитан без дополнительного вызова модели."
        : "Для следующего запроса слои памяти ещё не определены.";
    const traceByLayer = new Map((trace?.layers || []).map((layer) => [layer.layer, layer]));
    for (const layer of selectedLayers) {
        const item = document.createElement("article");
        item.className = "memory-layer";
        const toggle = document.createElement("button");
        toggle.type = "button";
        toggle.className = "memory-layer-toggle";
        toggle.setAttribute("aria-expanded", String(expandedMemoryLayer === layer));
        const heading = document.createElement("span");
        heading.className = "memory-layer-heading";
        const title = document.createElement("strong");
        title.textContent = memoryLayerLabel(layer);
        const summary = document.createElement("span");
        summary.className = "memory-layer-summary";
        summary.textContent = memoryLayerSummary(layer, projection, traceByLayer.get(layer));
        heading.append(title, summary);
        const affordance = document.createElement("span");
        affordance.className = "memory-layer-affordance";
        affordance.textContent = expandedMemoryLayer === layer ? "Скрыть" : "Подробнее";
        toggle.append(heading, affordance);
        toggle.addEventListener("click", () => {
            expandedMemoryLayer = expandedMemoryLayer === layer ? null : layer;
            renderMemoryTrace();
        });
        item.append(toggle);
        if (expandedMemoryLayer === layer) {
            item.append(renderMemoryLayerDetail(layer, projection));
        }
        elements.memoryLayerList.append(item);
    }
}

async function refreshMemoryProjection() {
    const sessionId = activeState?.session.id;
    if (!sessionId) return;
    const requestId = ++memoryProjectionRequest;
    try {
        const projection = await api(
            `/api/agent/sessions/${encodeURIComponent(sessionId)}/memory/projection`,
        );
        if (
            requestId !== memoryProjectionRequest ||
            !activeState ||
            activeState.session.id !== sessionId
        ) return;
        memoryProjection = projection;
        renderMemoryTrace();
    } catch (_) {
        if (requestId === memoryProjectionRequest && activeState?.session.id === sessionId) {
            elements.memoryRequestNote.textContent =
                "Предпросмотр слоёв временно недоступен.";
        }
    }
}

function memoryLayerSummary(layer, projection, trace) {
    if (!projection) {
        return trace ? `Доступны: ${(trace.labels || []).join(", ")}` : "Подробности загружаются";
    }
    if (layer === "short_term") {
        const shortTerm = projection.short_term;
        return `${shortTerm?.messages?.length || 0} сообщений` +
            (shortTerm?.summary ? " · есть сжатый контекст" : "");
    }
    if (layer === "working") {
        return `${projection.working?.transactions?.length || 0} операций черновика`;
    }
    const longTerm = projection.long_term || {};
    return `${longTerm.confirmed_decisions?.length || 0} подтверждённых решений` +
        (longTerm.user_prompt ? " · есть общие инструкции" : "");
}

function renderMemoryLayerDetail(layer, projection) {
    const detail = document.createElement("div");
    detail.className = "memory-layer-detail";
    if (!projection) {
        detail.textContent = "Детали загружаются.";
        return detail;
    }
    if (layer === "short_term") {
        const shortTerm = projection.short_term || {};
        appendMemoryText(detail, "Сжатый контекст", shortTerm.summary);
        if ((shortTerm.messages || []).length > 0) {
            const title = document.createElement("strong");
            title.textContent = "Сообщения";
            detail.append(title);
            const list = document.createElement("ul");
            list.className = "memory-detail-list";
            for (const message of shortTerm.messages) {
                const item = document.createElement("li");
                const role = document.createElement("strong");
                role.textContent = message.role === "user" ? "Вы: " : "Агент: ";
                item.append(role, document.createTextNode(message.display_text));
                list.append(item);
            }
            detail.append(list);
        }
        if ((shortTerm.facts || []).length > 0) {
            const title = document.createElement("strong");
            title.textContent = "Явные факты";
            detail.append(title);
            const list = document.createElement("ul");
            list.className = "memory-detail-list";
            for (const fact of shortTerm.facts) {
                const item = document.createElement("li");
                item.textContent = `${fact.key}: ${fact.value}`;
                list.append(item);
            }
            detail.append(list);
        }
        return detail;
    }
    if (layer === "working") {
        const working = projection.working || {};
        appendMemoryText(
            detail,
            "Черновик",
            working.status === "ready"
                ? `готов, операций: ${(working.transactions || []).length}`
                : working.rejection_reason || "требует уточнения",
        );
        appendMemoryText(detail, "Не разобрано", (working.unparsed_fragments || []).join(" · "));
        for (const transaction of working.transactions || []) {
            const item = document.createElement("p");
            item.className = "memory-transaction";
            const category = transaction.category_display_name
                ? ` · ${transaction.category_display_name}`
                : "";
            item.textContent =
                `${transaction.id}: ${transaction.merchant} · ` +
                `${minorToMajor(transaction.amount_minor, transaction.currency)} ${transaction.currency}${category}`;
            detail.append(item);
            for (const issue of transaction.issues || []) {
                detail.append(errorText(issue));
            }
        }
        return detail;
    }
    const longTerm = projection.long_term || {};
    appendMemoryText(detail, "Общие инструкции", longTerm.user_prompt);
    if ((longTerm.confirmed_decisions || []).length > 0) {
        const title = document.createElement("strong");
        title.textContent = "Подтверждённые решения";
        detail.append(title);
        const list = document.createElement("ul");
        list.className = "memory-detail-list";
        for (const decision of longTerm.confirmed_decisions) {
            const item = document.createElement("li");
            item.textContent = decision.text;
            list.append(item);
        }
        detail.append(list);
    }
    return detail;
}

function appendMemoryText(parent, label, value) {
    if (!value) return;
    const paragraph = document.createElement("p");
    const title = document.createElement("strong");
    title.textContent = `${label}: `;
    paragraph.append(title, document.createTextNode(value));
    parent.append(paragraph);
}

function memoryLayerLabel(layer) {
    return {
        short_term: "short-term",
        working: "working",
        long_term: "long-term",
    }[layer] || layer;
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
        const strategy = session.context_management
            ? contextStrategyLabel(session.context_management.strategy)
            : "Legacy";
        details.textContent = `${modelDisplayName(session.config)} · ${strategy} · ${formatDate(session.updated_at_epoch_ms)}`;
        button.append(title, details);
        elements.sessionList.append(button);
    }
}

async function createSession() {
    const navigationVersion = sessionNavigationVersion;
    await runBusy(async () => {
        const state = await api("/api/agent/sessions", { method: "POST" });
        await refreshTbankSession();
        await loadSessions();
        if (sessionNavigationVersion !== navigationVersion) return;
        setActiveState(state);
        selectAppPanel("sessions");
        navigateToSession(state.session.id);
        elements.message.focus();
        showSuccess("Новый импорт готов.");
    });
}

async function openSession(id) {
    const navigationVersion = sessionNavigationVersion;
    await runBusy(async () => {
        try {
            await refreshTbankSession();
            if (sessionNavigationVersion !== navigationVersion) return;
            const state = await api(`/api/agent/sessions/${encodeURIComponent(id)}`);
            if (sessionNavigationVersion !== navigationVersion) return;
            setActiveState(state);
            selectAppPanel("sessions");
            navigateToSession(id);
            showSuccess("Сессия открыта.");
        } catch (error) {
            if (sessionNavigationVersion !== navigationVersion) return;
            throw error;
        }
    });
}


function setActiveState(
    state,
    { preserveDirty = false, clearTransactionId = null, serverFields = [] } = {},
) {
    const previousWorking = workingDraft;
    const previousDirty = dirtyFields;
    activeState = state;
    memoryProjection = null;
    expandedMemoryLayer = null;
    memoryProjectionRequest += 1;
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
            for (const field of retainedFields) {
                editor[field] = previous[field];
                if (field === "occurred_at" || field === "posted_at") {
                    editor[`${field}_iso`] = previous[`${field}_iso`];
                }
            }
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
        occurred_at: formatTransactionDate(transaction.occurred_at),
        occurred_at_iso: transaction.occurred_at,
        posted_at: formatTransactionDate(transaction.posted_at || ""),
        posted_at_iso: transaction.posted_at || null,
        direction: transaction.direction,
        amount: minorToMajor(transaction.amount_minor, transaction.currency),
        currency: normalizeCurrencyCode(transaction.currency),
        merchant: transaction.merchant,
        description: row.description || "",
        category_id: transaction.category_id || "",
        source_label: transaction.source_label || "Счёт / карта",
    };
}

function hasDirtyDraft() {
    return dirtyFields.size > 0;
}

function renderEmptyState() {
    activeState = null;
    memoryProjection = null;
    expandedMemoryLayer = null;
    memoryProjectionRequest += 1;
    workingDraft = new Map();
    dirtyFields = new Map();
    localStorage.removeItem(ACTIVE_SESSION_KEY);
    elements.sessionNotFound.hidden = true;
    elements.toolbarTitle.textContent = "Импорт операций";
    elements.emptySession.hidden = false;
    elements.workspace.hidden = true;
    if (elements.linkedSchedulerTasks) elements.linkedSchedulerTasks.hidden = true;
    elements.deleteSession.hidden = true;
    elements.forkSession.hidden = true;
    elements.factsPanel.hidden = true;
    elements.memoryPanel.hidden = true;
    elements.memoryLayerList.replaceChildren();
    elements.memoryRequestNote.textContent = "";
    elements.factsList.replaceChildren();
    elements.draftPanel.hidden = true;
    elements.metricsPanel.hidden = true;
    elements.metricsSummary.replaceChildren();
    elements.metricsTableBody.replaceChildren();
    taskInvariantCatalog = { system: [] };
    renderTaskInvariants();
    renderReceiptStatus();
    activeMcpPreview = null;
}
function renderSessionNotFound() {
    renderEmptyState();
    elements.emptySession.hidden = true;
    elements.sessionNotFound.hidden = false;
    elements.toolbarTitle.textContent = "Сессия не найдена";
}

function renderActiveState() {
    renderLinkedSchedulerTasks();
    elements.emptySession.hidden = true;
    elements.sessionNotFound.hidden = true;
    elements.workspace.hidden = false;
    elements.deleteSession.hidden = false;
    elements.activeTitle.textContent = activeState.session.title;
    renderReceiptStatus();
    renderTaskInvariants();
    refreshTaskInvariants().catch((error) => showError(error.message));
    elements.toolbarTitle.textContent = activeState.session.title;
    populateConfigControls(activeState.session.config);
    renderContextManagement();
    activeMcpPreview = null;
    renderMessages();

    renderFacts();
    renderMemoryTrace();
    refreshMemoryProjection();
    renderMetrics();
    renderDraft();
}
function renderReceiptStatus() {
    const draft = activeState?.draft;
    const compliance = activeState?.receipt_state?.last_compliance;
    const complianceMessage = compliance?.status === "conflict"
        ? (compliance.conflicts || [])
            .map((conflict) => `${conflict.title}: ${conflict.explanation}`)
            .join(" · ")
        : "";
    if (!draft) {
        if (activeState?.receipt_state?.status === "has_errors") {
            elements.receiptStatus.textContent = "Содержит ошибки";
            elements.receiptStatusDetail.textContent =
                complianceMessage || "Исправьте блокирующий результат обработки.";
        } else {
            elements.receiptStatus.textContent = "Не начато";
            elements.receiptStatusDetail.textContent = "Вставьте выписку или уточнение.";
        }
        elements.receiptOperationCount.textContent = "—";
        return;
    }

    const { included, invalid } = draftMetrics(draft);
    elements.receiptOperationCount.textContent = String(included);
    const blockers = [];
    if (draft.status !== "ready") {
        blockers.push(draft.rejection_reason || "Выписка не распознана как финансовые операции.");
    }
    if ((draft.unparsed_fragments || []).length > 0) {
        blockers.push(`Неразобранных фрагментов: ${draft.unparsed_fragments.length}.`);
    }
    if (invalid > 0) {
        blockers.push(`Исправьте ошибки в ${invalid} ${operationWord(invalid)}.`);
    }
    if (included === 0) {
        blockers.push("Выберите хотя бы одну операцию.");
    }
    if (hasDirtyDraft()) {
        blockers.push("Сохраните изменения в операциях.");
    }
    if (complianceMessage) {
        blockers.push(complianceMessage);
    }
    if (blockers.length > 0) {
        elements.receiptStatus.textContent = "Содержит ошибки";
        elements.receiptStatusDetail.textContent = blockers.join(" ");
        return;
    }

    elements.receiptStatus.textContent = "Готово к экспорту";
    elements.receiptStatusDetail.textContent =
        `Выбрано операций: ${included}. Выписка прошла локальную проверку.`;
}


function renderContextManagement() {
    const context = activeState.session.context_management;
    const lineage = activeState.session.parent_session_id
        ? ` Ветка от ${activeState.session.parent_session_id} после ${activeState.session.checkpoint_message_count} сообщений.`
        : "";
    if (!context) {
        elements.contextStrategyNote.textContent =
            `Стратегия контекста: полная история (legacy-конфигурация).${lineage}`;
        elements.forkSession.hidden = true;
        return;
    }
    const strategy = contextStrategyLabel(context.strategy);
    const details = context.strategy === "sliding_window" || context.strategy === "sticky_facts"
        ? `последние ${context.recent_messages} сообщений`
        : context.strategy === "summary"
            ? `recent: ${context.recent_messages}; batch summary: ${context.summary_batch_messages}`
            : context.strategy === "token_aware_summary"
                ? tokenAwareContextDetails(context)
                : "полная история до checkpoint";
    elements.contextStrategyNote.textContent =
        `Стратегия контекста: ${strategy} · ${details}. Настройка фиксирована при создании сессии.${lineage}`;
    elements.forkSession.hidden = context.strategy !== "branching" || (activeState.messages || []).length < 2;
}

function tokenAwareContextDetails(context) {
    const latestBudgetMetric = [...(activeState.metrics || [])]
        .reverse()
        .find((metric) =>
            metric.compaction_threshold_tokens != null ||
            metric.estimated_context_tokens != null
        );
    const model = selectedModel();
    const contextWindow = latestBudgetMetric?.context_window_tokens ?? model?.context_window_tokens;
    if (!contextWindow || contextWindow <= 1) {
        return `fresh tail до ${formatTokens(context.summary_keep_recent_tokens)} токенов; context window неизвестно`;
    }
    const budget = tokenAwareBudget(context, contextWindow);
    const estimate = latestBudgetMetric?.estimated_context_tokens == null
        ? "оценка появится после вызова"
        : `оценка ${formatTokens(latestBudgetMetric.estimated_context_tokens)} (${latestBudgetMetric.context_estimate_source || "hybrid"})`;
    return `порог ${formatTokens(budget.threshold)}; reserve ${formatTokens(budget.reserve)}; ` +
        `fresh tail до ${formatTokens(context.summary_keep_recent_tokens)}; ${estimate}`;
}

function tokenAwareBudget(context, contextWindow) {
    const maximumReserve = Math.max(1, Math.floor(contextWindow / 2));
    const defaultReserve = Math.max(16_384, Math.floor(contextWindow * 0.15));
    const requestedReserve = context.summary_reserve_tokens ?? defaultReserve;
    const reserve = Math.min(Math.max(1, requestedReserve), maximumReserve);
    let threshold;
    if (context.summary_threshold_tokens != null) {
        threshold = context.summary_threshold_tokens;
    } else if (context.summary_threshold_percent != null) {
        threshold = Math.floor(contextWindow * context.summary_threshold_percent / 100);
    } else {
        threshold = contextWindow - reserve;
    }
    threshold = Math.min(Math.max(1, threshold), contextWindow - 1);
    return { threshold, reserve: contextWindow - threshold };
}

function contextStrategyLabel(strategy) {
    return {
        sliding_window: "Sliding Window",
        sticky_facts: "Sticky Facts",
        branching: "Branching",
        summary: "Summary",
        token_aware_summary: "Token-aware Summary",
    }[strategy] || strategy;
}

function renderFacts() {
    const facts = activeState?.facts || [];
    elements.factsPanel.hidden = facts.length === 0;
    elements.factsList.replaceChildren();
    for (const fact of facts) {
        const item = document.createElement("li");
        const value = document.createElement("strong");
        value.textContent = `${fact.key}:`;
        const source = document.createElement("small");
        source.textContent = `источник ${fact.source_message_id}`;
        item.append(value, document.createTextNode(` ${fact.value} `), source);
        elements.factsList.append(item);
    }
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
        renderPreferences(preferences);
        showSuccess("Общие инструкции сохранены.");
    });
}

async function acceptMemoryCandidate(candidateId) {
    if (!activeState) return;
    await runBusy(async () => {
        const state = await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}` +
                `/memory-candidates/${encodeURIComponent(candidateId)}/accept`,
            jsonOptions("POST", { revision: activeState.session.revision }),
        );
        setActiveState(state);
        await loadSessions();
        renderPreferences(await api("/api/agent/preferences"));
        showSuccess("Кандидат добавлен в подтверждённые решения.");
    }, true);
}
async function acceptMerchantCanonicalCandidate(candidateId) {
    if (!activeState) return;
    await runBusy(async () => {
        const payload = await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}` +
                `/merchant-canonical-candidates/${encodeURIComponent(candidateId)}/accept`,
            jsonOptions("POST", {
                revision: activeState.session.revision,
                preview_id: activeMcpPreview?.id || null,
            }),
        );
        setActiveState(payload.state || payload);
        if (payload.mcp_preview) {
            renderMcpPreview(payload.mcp_preview);
        }
        await loadSessions();
        renderPreferences(await api("/api/agent/preferences"));
        const hasDraft = Boolean(payload.state?.draft);
        const hasPreview = Boolean(payload.mcp_preview);
        showSuccess(
            hasDraft && hasPreview
                ? "Правило сохранено; draft и preview обновлены."
                : hasDraft
                    ? "Правило сохранено и применено к текущему draft."
                    : hasPreview
                        ? "Правило сохранено и применено к preview."
                        : "Правило сохранено.",
        );
    }, true);
}

async function saveConfirmedDecision(decisionId, input) {
    const text = input.value.trim();
    if (!text) {
        showError("Текст подтверждённого решения не должен быть пустым.");
        input.focus();
        return;
    }
    await runBusy(async () => {
        const preferences = await api(
            `/api/agent/preferences/decisions/${encodeURIComponent(decisionId)}`,
            jsonOptions("PUT", { text }),
        );
        renderPreferences(preferences);
        showSuccess("Подтверждённое решение обновлено.");
    });
}

async function deleteConfirmedDecision(decisionId) {
    if (!window.confirm("Удалить подтверждённое решение? Это действие нельзя отменить.")) return;
    await runBusy(async () => {
        const preferences = await api(
            `/api/agent/preferences/decisions/${encodeURIComponent(decisionId)}`,
            { method: "DELETE" },
        );
        renderPreferences(preferences);
        showSuccess("Подтверждённое решение удалено.");
    });
}

async function deleteSession() {
    if (!activeState) return;
    if (busy) {
        showError("Дождитесь завершения текущей операции; сессия не удалена.");
        return;
    }
    const requestedSession = {
        id: activeState.session.id,
        title: activeState.session.title,
        revision: activeState.session.revision,
    };
    const id = requestedSession.id;
    let linkedTasks;
    try {
        const tasks = await api("/api/agent/scheduler/tasks");
        linkedTasks = tasks.filter((task) => task.target_session_id === id);
    } catch (_) {
        showError("Не удалось проверить связанные фоновые задачи; сессия не удалена.");
        return;
    }
    if (busy) {
        showError("Дождитесь завершения текущей операции; сессия не удалена.");
        return;
    }
    const taskNames = linkedTasks.length
        ? linkedTasks.map((task) => `• ${task.name}`).join("\n")
        : "Связанных фоновых задач нет.";
    const warning = linkedTasks.length
        ? "\n\nВместе с сессией навсегда удалятся перечисленные задачи и вся история их запусков."
        : "";
    if (!window.confirm(
        `Удалить сессию «${requestedSession.title}» вместе с диалогом и черновиком?\n\n` +
        `Связанные фоновые задачи:\n${taskNames}${warning}\n\nЭто действие нельзя отменить.`,
    )) {
        return;
    }
    if (busy) {
        showError("Дождитесь завершения текущей операции; сессия не удалена.");
        return;
    }
    await runBusy(async () => {
        await api(
            `/api/agent/sessions/${encodeURIComponent(id)}`,
            jsonOptions("DELETE", {
                revision: requestedSession.revision,
                expected_linked_task_ids: linkedTasks.map((task) => task.id),
            }),
        );
        const deletingCurrentSession =
            activeState?.session.id === id && sessionIdFromLocation() === id;
        const navigationVersionAtDelete = sessionNavigationVersion;
        const deletedSessionPath = `/agent/sessions/${encodeURIComponent(id)}`;
        if (deletingCurrentSession) renderEmptyState();
        sessions = sessions.filter((session) => session.id !== id);
        renderSessionList();
        try {
            await loadSessions();
            await loadSchedulerPanel();
        } catch (error) {
            if (
                deletingCurrentSession &&
                sessionNavigationVersion === navigationVersionAtDelete &&
                window.location.pathname === deletedSessionPath
            ) {
                window.history.replaceState({}, "", "/agent");
                sessionNavigationVersion += 1;
                renderEmptyState();
            }
            if (deletingCurrentSession) {
                showError("Сессия удалена, но интерфейс не обновился. Перезагрузите страницу.");
                return;
            }
            throw error;
        }
        if (
            deletingCurrentSession &&
            sessionNavigationVersion === navigationVersionAtDelete &&
            window.location.pathname === deletedSessionPath
        ) {
            if (sessions.length > 0) {
                const nextId = sessions[0].id;
                let nextState;
                try {
                    nextState = await api(`/api/agent/sessions/${encodeURIComponent(nextId)}`);
                } catch (_) {
                    if (
                        sessionNavigationVersion !== navigationVersionAtDelete ||
                        window.location.pathname !== deletedSessionPath
                    ) {
                        showSuccess("Сессия и связанные фоновые задачи удалены.");
                        return;
                    }
                    window.history.replaceState({}, "", "/agent");
                    sessionNavigationVersion += 1;
                    renderEmptyState();
                    showError("Сессия удалена, но следующую сессию открыть не удалось. Перезагрузите страницу.");
                    return;
                }
                if (
                    sessionNavigationVersion !== navigationVersionAtDelete ||
                    window.location.pathname !== deletedSessionPath
                ) {
                    showSuccess("Сессия и связанные фоновые задачи удалены.");
                    return;
                }
                setActiveState(nextState);
                navigateToSession(nextId, true);
            } else {
                window.history.replaceState({}, "", "/agent");
                sessionNavigationVersion += 1;
                renderEmptyState();
            }
        }
        showSuccess("Сессия и связанные фоновые задачи удалены.");
    });
}

async function forkActiveSession() {
    if (!activeState) return;
    if (hasDirtyDraft()) {
        showError("Сначала сохраните изменения операции.");
        return;
    }
    await runBusy(async () => {
        const state = await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}/fork`,
            jsonOptions("POST", { revision: activeState.session.revision }),
        );
        await loadSessions();
        setActiveState(state);
        selectAppPanel("sessions");
        navigateToSession(state.session.id);
        showSuccess("Создана независимая ветка.");
    });
}

function renderMcpPreview(preview) {
    activeMcpPreview = preview || null;
    renderMessages();
}

function renderInlineMcpPreview(preview) {
    const section = document.createElement("section");
    section.className = "mcp-inline-preview";
    section.setAttribute("aria-labelledby", "mcp-preview-title");

    const heading = document.createElement("h3");
    heading.id = "mcp-preview-title";
    heading.textContent = "Операции из MCP";
    const note = document.createElement("p");
    note.className = "control-note";
    note.textContent =
        `Получено операций: ${(preview.transactions || []).length}. ` +
        "Проверьте результат; черновик изменится только после принятия.";

    const tableWrap = document.createElement("div");
    tableWrap.className = "mcp-inline-preview-table-wrap";
    const table = document.createElement("table");
    table.className = "mcp-inline-preview-table";
    const header = document.createElement("thead");
    const headerRow = document.createElement("tr");
    for (const label of ["Дата", "Сумма", "Merchant", "Описание", "Категория", "Источник"]) {
        const cell = document.createElement("th");
        cell.scope = "col";
        cell.textContent = label;
        headerRow.append(cell);
    }
    header.append(headerRow);

    const body = document.createElement("tbody");
    for (const transaction of preview.transactions || []) {
        const row = document.createElement("tr");
        const date = document.createElement("td");
        date.textContent = formatTransactionDate(transaction.occurred_at);
        const amount = document.createElement("td");
        const currency = normalizeCurrencyCode(transaction.currency);
        amount.className = transaction.amount_minor < 0 ? "amount-expense" : "amount-income";
        amount.textContent = `${minorToMajor(transaction.amount_minor, currency)} ${currency}`;
        const merchant = document.createElement("td");
        merchant.textContent = transaction.merchant || "Без названия";
        const description = document.createElement("td");
        description.textContent = transaction.description || "";
        const category = document.createElement("td");
        const categoryValue = categoryCatalog.find((item) => item.id === transaction.category_id);
        category.textContent = categoryValue
            ? categoryDisplayPath(categoryValue.id)
            : "Не выбрана";
        if (transaction.category_issue) {
            category.append(errorText(transaction.category_issue));
        }
        const source = document.createElement("td");
        source.textContent = transaction.source_label || "Счёт / карта";
        row.append(date, amount, merchant, description, category, source);
        body.append(row);
    }
    table.append(header, body);
    tableWrap.append(table);

    const actions = document.createElement("div");
    actions.className = "message-actions";
    const cancel = document.createElement("button");
    cancel.type = "button";
    cancel.className = "secondary-button";
    cancel.textContent = "Отменить";
    cancel.addEventListener("click", cancelMcpPreview);
    const confirm = document.createElement("button");
    confirm.type = "button";
    confirm.textContent = "Принять операции";
    confirm.addEventListener("click", confirmMcpPreview);
    actions.append(cancel, confirm);
    section.append(heading, note, tableWrap, actions);
    return section;
}

async function confirmMcpPreview() {
    if (!activeState || !activeMcpPreview) return;
    await runBusy(async () => {
        const state = await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}/mcp-previews/` +
                `${encodeURIComponent(activeMcpPreview.id)}/confirm`,
            jsonOptions("POST", { revision: activeState.session.revision }),
        );
        setActiveState(state);
        showSuccess("Операции добавлены в черновик. Проверьте категории и ошибки.");
    }, true);
}

async function cancelMcpPreview() {
    if (!activeState || !activeMcpPreview) return;
    await runBusy(async () => {
        await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}/mcp-previews/` +
                `${encodeURIComponent(activeMcpPreview.id)}`,
            { method: "DELETE" },
        );
        activeMcpPreview = null;
        renderMessages();
        showSuccess("Предварительный просмотр отменён; черновик не изменён.");
    });
}


function renderMarkdown(parent, markdown) {
    const lines = String(markdown || "").replace(/\r\n?/g, "\n").split("\n");
    let index = 0;
    let paragraph = [];
    let list = null;

    const flushParagraph = () => {
        if (paragraph.length === 0) return;
        const block = document.createElement("p");
        paragraph.forEach((line, lineIndex) => {
            if (lineIndex > 0) block.append(document.createElement("br"));
            appendInlineMarkdown(block, line);
        });
        parent.append(block);
        paragraph = [];
    };
    const flushList = () => {
        if (!list) return;
        parent.append(list.element);
        list = null;
    };
    const flushText = () => {
        flushParagraph();
        flushList();
    };

    while (index < lines.length) {
        const line = lines[index];
        if (line.trim() === "") {
            flushText();
            index += 1;
            continue;
        }

        const fence = line.match(/^\s*```(?:[A-Za-z0-9_-]+)?\s*$/);
        if (fence) {
            flushText();
            index += 1;
            const codeLines = [];
            while (index < lines.length && !/^\s*```\s*$/.test(lines[index])) {
                codeLines.push(lines[index]);
                index += 1;
            }
            if (index < lines.length) index += 1;
            const pre = document.createElement("pre");
            const code = document.createElement("code");
            code.textContent = codeLines.join("\n");
            pre.append(code);
            parent.append(pre);
            continue;
        }

        const heading = line.match(/^\s*(#{1,6})\s+(.+)$/);
        if (heading) {
            flushText();
            const title = document.createElement(`h${heading[1].length}`);
            appendInlineMarkdown(title, heading[2]);
            parent.append(title);
            index += 1;
            continue;
        }

        if (isMarkdownTableSeparator(lines[index + 1])) {
            flushText();
            const table = document.createElement("table");
            const head = document.createElement("thead");
            const headRow = document.createElement("tr");
            for (const cellText of markdownTableCells(line)) {
                const cell = document.createElement("th");
                cell.scope = "col";
                appendInlineMarkdown(cell, cellText);
                headRow.append(cell);
            }
            head.append(headRow);
            table.append(head);
            const body = document.createElement("tbody");
            index += 2;
            while (index < lines.length && lines[index].includes("|") && lines[index].trim() !== "") {
                const row = document.createElement("tr");
                for (const cellText of markdownTableCells(lines[index])) {
                    const cell = document.createElement("td");
                    appendInlineMarkdown(cell, cellText);
                    row.append(cell);
                }
                body.append(row);
                index += 1;
            }
            table.append(body);
            parent.append(table);
            continue;
        }

        const unordered = line.match(/^\s{0,3}[-*+]\s+(.+)$/);
        const ordered = line.match(/^\s{0,3}\d+[.)]\s+(.+)$/);
        if (unordered || ordered) {
            flushParagraph();
            const orderedList = Boolean(ordered);
            if (!list || list.ordered !== orderedList) {
                flushList();
                list = {
                    ordered: orderedList,
                    element: document.createElement(orderedList ? "ol" : "ul"),
                };
            }
            const item = document.createElement("li");
            appendInlineMarkdown(item, (unordered || ordered)[1]);
            list.element.append(item);
            index += 1;
            continue;
        }

        if (/^\s{0,3}(?:-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
            flushText();
            parent.append(document.createElement("hr"));
            index += 1;
            continue;
        }

        flushList();
        paragraph.push(line);
        index += 1;
    }
    flushText();
}

function isMarkdownTableSeparator(line) {
    if (!line || !line.includes("|")) return false;
    const cells = markdownTableCells(line);
    return cells.length > 0 && cells.every((cell) => /^:?-{3,}:?$/.test(cell));
}

function markdownTableCells(line) {
    let value = String(line || "").trim();
    if (value.startsWith("|")) value = value.slice(1);
    if (value.endsWith("|")) value = value.slice(0, -1);
    return value.split("|").map((cell) => cell.trim());
}

function appendInlineMarkdown(parent, source) {
    const text = String(source || "");
    const tokenPattern = /(`[^`]*`|!\[[^\]]*\]\([^)]*\)|\[[^\]]+\]\([^)]*\)|\*\*[^*]+\*\*|__[^_]+__|\*[^*]+\*|_[^_]+_)/g;
    let cursor = 0;
    for (const match of text.matchAll(tokenPattern)) {
        const token = match[0];
        const offset = match.index || 0;
        if (offset > cursor) parent.append(document.createTextNode(text.slice(cursor, offset)));
        if (token.startsWith("`")) {
            const code = document.createElement("code");
            code.textContent = token.slice(1, -1);
            parent.append(code);
        } else if (token.startsWith("![")) {
            const alt = token.match(/^!\[([^\]]*)\]/)?.[1] || "";
            parent.append(document.createTextNode(alt));
        } else if (token.startsWith("[")) {
            const link = token.match(/^\[([^\]]+)\]\((https?:\/\/[^)\s]+)\)$/);
            if (!link) {
                parent.append(document.createTextNode(token));
            } else {
                const anchor = document.createElement("a");
                anchor.href = link[2];
                anchor.target = "_blank";
                anchor.rel = "noopener noreferrer";
                anchor.textContent = link[1];
                parent.append(anchor);
            }
        } else {
            const emphasis = document.createElement(
                token.startsWith("**") || token.startsWith("__") ? "strong" : "em",
            );
            const trim = token.startsWith("**") || token.startsWith("__") ? 2 : 1;
            emphasis.textContent = token.slice(trim, -trim);
            parent.append(emphasis);
        }
        cursor = offset + token.length;
    }
    if (cursor < text.length) parent.append(document.createTextNode(text.slice(cursor)));
}

function renderMessages() {
    elements.messageList.replaceChildren();
    const messages = activeState.messages || [];
    const latestAssistantId = [...messages]
        .reverse()
        .find((message) => message.role === "assistant")?.id;
    if (messages.length === 0 && !activeState.summary) {
        const empty = document.createElement("p");
        empty.className = "muted";
        empty.textContent = "Вставьте банковскую выписку первым сообщением.";
        elements.messageList.append(empty);
        return;
    }
    for (const message of messages) {
        const item = document.createElement("article");
        item.className = `message ${message.role}`;
        const role = document.createElement("strong");
        role.textContent = message.role === "user" ? "Вы" : "Агент";
        const content = document.createElement("div");
        content.className = "message-content";
        renderMarkdown(content, message.display_text);
        const copyActions = document.createElement("div");
        copyActions.className = "message-copy-actions";
        const copy = document.createElement("button");
        copy.type = "button";
        copy.className = "secondary-button compact-button";
        copy.textContent = "Копировать";
        copy.setAttribute("aria-label", `Копировать сообщение: ${message.role === "user" ? "пользователя" : "агента"}`);
        copy.addEventListener("click", () => copyMessage(message.display_text));
        item.append(role, content, copyActions);
        copyActions.append(copy);
        if (message.role === "assistant") {
            const candidates = (activeState.memory_candidates || [])
                .filter((candidate) => candidate.source_message_id === message.id);
            for (const candidate of candidates) {
                item.append(renderMemoryCandidate(candidate));
            }
            const merchantCanonicalCandidates = (activeState.merchant_canonical_candidates || [])
                .filter((candidate) => candidate.source_message_id === message.id);
            for (const candidate of merchantCanonicalCandidates) {
                item.append(renderMerchantCanonicalCandidate(candidate));
            }
            if (message.id === latestAssistantId && activeMcpPreview) {
                item.append(renderInlineMcpPreview(activeMcpPreview));
            }
        }
        elements.messageList.append(item);
    }
    if (activeState.summary) {
        const item = document.createElement("article");
        item.className = "message summary";
        const role = document.createElement("strong");
        role.textContent = "Сжатая история";
        const content = document.createElement("p");
        content.textContent = activeState.summary.text;
        const boundary = document.createElement("small");
        const summaryCalls = (activeState.metrics || [])
            .filter((metric) => metric.call_type === "summary")
            .length;
        boundary.textContent =
            `Сжато сообщений истории: ${activeState.summary.summarized_message_count}; ` +
            `вызовов сжатия в сессии: ${summaryCalls}`;
        item.append(role, content, boundary);

        elements.messageList.append(item);
    }
    elements.messageList.scrollTop = elements.messageList.scrollHeight;
}

async function copyMessage(text) {
    try {
        if (!navigator.clipboard || typeof navigator.clipboard.writeText !== "function") {
            throw new Error("Clipboard API недоступен.");
        }
        await navigator.clipboard.writeText(text);
        showSuccess("Сообщение скопировано.");
    } catch (_) {
        showError("Не удалось скопировать сообщение. Разрешите доступ к буферу обмена в браузере.");
    }
}

function renderMetrics() {
    const metrics = activeState?.metrics || [];
    elements.metricsPanel.hidden = metrics.length === 0;
    elements.metricsSummary.replaceChildren();
    elements.metricsTableBody.replaceChildren();
    if (metrics.length === 0) return;

    const complete = metrics.filter(hasCompleteMetricUsage);
    const latest = [...metrics].reverse().find((metric) => metric.status === "succeeded");
    const totals = complete.reduce(
        (sum, metric) => ({
            prompt: sum.prompt + metric.prompt_tokens,
            completion: sum.completion + metric.completion_tokens,
            total: sum.total + metric.total_tokens,
        }),
        { prompt: 0, completion: 0, total: 0 },
    );
    const summary = [
        ["Последний успешный input", latest?.prompt_tokens == null ? "нет usage" : formatTokens(latest.prompt_tokens)],
        ["Последний успешный ответ", latest?.completion_tokens == null ? "нет usage" : formatTokens(latest.completion_tokens)],
        ["Последний успешный вызов", latest?.total_tokens == null ? "нет usage" : formatTokens(latest.total_tokens)],
        ["За сессию input", complete.length === 0 ? "нет usage" : formatTokens(totals.prompt)],
        ["За сессию output", complete.length === 0 ? "нет usage" : formatTokens(totals.completion)],
        ["За сессию всего", complete.length === 0 ? "нет usage" : formatTokens(totals.total)],
    ];
    for (const [label, value] of summary) {
        const stat = document.createElement("div");
        stat.className = "metric-stat";
        const title = document.createElement("span");
        title.textContent = label;
        const amount = document.createElement("strong");
        amount.textContent = value;
        stat.append(title, amount);
        elements.metricsSummary.append(stat);
    }

    metrics.forEach((metric, index) => {
        const row = document.createElement("tr");
        row.dataset.metricId = metric.id;
        const context = metric.total_tokens != null && metric.context_window_tokens
            ? `${formatTokens(metric.total_tokens)} / ${formatTokens(metric.context_window_tokens)} (${formatPercent(metric.total_tokens / metric.context_window_tokens)})`
            : "—";
        const values = [
            String(index + 1),
            metricStatusLabel(metric),
            formatMetricTokens(metric.prompt_tokens),
            formatMetricTokens(metric.completion_tokens),
            formatMetricTokens(metric.total_tokens),
            formatMetricTokens(metric.context_window_tokens),
            context,
        ];
        for (const value of values) {
            const cell = document.createElement("td");
            cell.textContent = value;
            row.append(cell);
        }
        if (metric.status === "context_overflow") row.classList.add("overflow");
        elements.metricsTableBody.append(row);
    });
}

function hasCompleteMetricUsage(metric) {
    return metric.status === "succeeded" &&
        metric.prompt_tokens != null &&
        metric.completion_tokens != null &&
        metric.total_tokens != null;
}

function metricStatusLabel(metric) {
    if (metric.status === "context_overflow") return "Переполнение контекста";
    const status = hasCompleteMetricUsage(metric) ? "Успешно" : "Успешно, usage недоступен";
    const type = metric.call_type === "summary"
        ? "summary"
        : metric.call_type === "facts"
            ? "facts"
            : "normal";
    return `${status} · ${type}${metric.inherited ? " · наследовано" : ""}`;
}

function formatMetricTokens(value) {
    return value == null ? "—" : formatTokens(value);
}

function formatTokens(value) {
    return new Intl.NumberFormat("ru-RU").format(value);
}

function formatPercent(value) {
    return new Intl.NumberFormat("ru-RU", {
        style: "percent",
        maximumFractionDigits: 1,
    }).format(value);
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
        const payload = await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}/messages`,
            jsonOptions("POST", { revision: activeState.session.revision, text }),
        );
        const state = payload.state || payload;
        if (!state.last_error) elements.message.value = "";
        setActiveState(state);
        renderMcpPreview(payload.mcp_preview || null);
        await loadSessions();
        if (state.last_error) {
            showError(state.last_error);
        } else if (payload.mcp_preview) {
            showSuccess("Операции получены. Проверьте preview в сообщении и подтвердите импорт.");
        } else {
            showSuccess("Ответ агента получен. Черновик обновлён.");
        }
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
    if (control.name === "occurred_at" || control.name === "posted_at") {
        editor[`${control.name}_iso`] = transactionDateToIso(value, control.name);
    }
    const fields = dirtyFields.get(holder.dataset.transactionId) || new Set();
    fields.add(control.name);
    dirtyFields.set(holder.dataset.transactionId, fields);
    if (control.name === "included") {
        renderDraft();
    } else {
        holder.classList.add("dirty");
        updateDraftSummary(activeState.draft);
    }
    applyBusyState();
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
    renderReceiptStatus();
    elements.buildBatch.disabled = busy || included === 0 || invalid > 0 || hasDirtyDraft();
    elements.selectAll.checked = included === draft.transactions.length && draft.transactions.length > 0;
    elements.selectAll.indeterminate = included > 0 && included < draft.transactions.length;
}

function renderDraft() {
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
    applyBusyState();
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
    const descriptionField = fieldControl(
        "Описание",
        textInput("description", editor.description, 500),
        row,
        "description",
        editor.included,
    );
    grid.append(
        fieldControl("Дата и время операции", transactionDateInput("occurred_at", editor.occurred_at), row, "occurred_at", editor.included),
        fieldControl("Дата проводки", transactionDateInput("posted_at", editor.posted_at), row, "posted_at", editor.included),
        fieldControl("Тип операции", directionSelect(editor.direction), row, "direction", editor.included),
        fieldControl("Сумма", amountInput(editor.amount), row, "amount_minor", editor.included),
        fieldControl("Валюта", readOnlyInput("currency", editor.currency), row, "currency", editor.included),
        fieldControl("Магазин или получатель", textInput("merchant", editor.merchant), row, "merchant", editor.included),
        descriptionField,
        fieldControl("Категория", categorySelect(editor.category_id, editor.direction), row, "category_id", editor.included),
        fieldControl("Счёт / карта", readOnlyInput("source_label", editor.source_label), row, "source_label", editor.included),
    );
    const itemDetails = transactionItemsDetails(row.transaction.items);

    const generalErrors = editor.included ? (row.field_errors?._transaction || []) : [];
    const footer = document.createElement("div");
    footer.className = "operation-card-footer";
    const errorBox = document.createElement("div");
    errorBox.className = "operation-errors";
    for (const error of generalErrors) errorBox.append(errorText(error));
    footer.append(errorBox, saveOperationButton(row.id, "Сохранить операцию"));
    card.append(header, grid);
    if (itemDetails) card.append(itemDetails);
    card.append(footer);
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
        "Валюта", "Магазин или получатель", "Описание", "Категория", "Счёт / карта", "",
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
    const descriptionCell = tableFieldCell(
        "Описание",
        textInput("description", editor.description, 500),
        row,
        "description",
        editor.included,
    );
    const itemDetails = transactionItemsDetails(row.transaction.items);
    if (itemDetails) descriptionCell.append(itemDetails);
    tableRow.append(
        tableFieldCell("Дата и время", transactionDateInput("occurred_at", editor.occurred_at), row, "occurred_at", editor.included),
        tableFieldCell("Дата проводки", transactionDateInput("posted_at", editor.posted_at), row, "posted_at", editor.included),
        tableFieldCell("Тип", directionSelect(editor.direction), row, "direction", editor.included),
        tableFieldCell("Сумма", amountInput(editor.amount), row, "amount_minor", editor.included),
        tableFieldCell("Валюта", readOnlyInput("currency", editor.currency), row, "currency", editor.included),
        tableFieldCell("Магазин или получатель", textInput("merchant", editor.merchant), row, "merchant", editor.included),
        descriptionCell,
        tableFieldCell("Категория", categorySelect(editor.category_id, editor.direction), row, "category_id", editor.included),
        tableFieldCell("Счёт / карта", readOnlyInput("source_label", editor.source_label), row, "source_label", editor.included),
    );

    const action = document.createElement("div");
    for (const error of editor.included ? (row.field_errors?._transaction || []) : []) {
        action.append(errorText(error));
    }
    action.append(saveOperationButton(row.id, "Сохранить"));
    tableRow.append(tableCell(action));
    return tableRow;
}
function transactionItemsDetails(items) {
    if (!Array.isArray(items) || items.length === 0) return null;
    const details = document.createElement("details");
    details.className = "transaction-items";
    const summary = document.createElement("summary");
    summary.textContent = `Позиции чека (${items.length})`;
    details.append(summary);
    const list = document.createElement("ul");
    for (const item of items) {
        const line = document.createElement("li");
        const quantity = Number(item.quantity);
        const sum = Number(item.sum_minor);
        line.textContent =
            `${item.name || "Позиция"} · ${Number.isFinite(quantity) ? quantity : "?"} × ` +
            `${minorToMajor(Number(item.price_minor) || 0, item.currency || "RUB")} ` +
            `${item.currency || "RUB"} = ` +
            `${minorToMajor(Number.isFinite(sum) ? sum : 0, item.currency || "RUB")} ${item.currency || "RUB"}`;
        list.append(line);
    }
    details.append(list);
    return details;
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

function categorySelect(selected, direction) {
    const select = document.createElement("select");
    select.name = "category_id";
    const empty = document.createElement("option");
    empty.value = "";
    empty.textContent = "Не выбрана";
    select.append(empty);
    const eligible = categoryCatalog.filter(
        (category) => !category.archived &&
            categoryIsLeaf(category.id) &&
            category.type === direction,
    );
    for (const category of eligible) {
        const option = document.createElement("option");
        option.value = category.id;
        option.textContent = categoryDisplayPath(category.id);
        option.selected = category.id === selected;
        select.append(option);
    }
    if (selected && !eligible.some((category) => category.id === selected)) {
        const current = categoryCatalog.find((category) => category.id === selected);
        const option = document.createElement("option");
        option.value = selected;
        option.textContent = current
            ? `${categoryDisplayPath(selected)} (${current.archived ? "архивная" : "требует замены"})`
            : "Неизвестная категория (нужно заменить)";
        option.selected = true;
        select.append(option);
    }
    return select;
}

function normalizeCurrencyCode(currency) {
    const value = String(currency || "").trim().toUpperCase();
    return {
        "643": "RUB",
        "840": "USD",
        "978": "EUR",
        "826": "GBP",
        "156": "CNY",
    }[value] || value;
}

function currencyFractionDigits(currency) {
    try {
        return new Intl.NumberFormat("ru-RU", {
            style: "currency",
            currency: normalizeCurrencyCode(currency),
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
        occurred_at: editor.occurred_at_iso,
        posted_at: editor.posted_at_iso,
        amount_minor: amountMinor,
        merchant: editor.merchant,
        description: editor.description,
        category_id: editor.category_id || null,
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

async function exportBatch() {
    if (!activeState) return;
    await runBusy(async () => {
        const exportedBatch = await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}/batch`,
        );
        const refreshedState = await api(
            `/api/agent/sessions/${encodeURIComponent(activeState.session.id)}`,
        );
        setActiveState(refreshedState);
        downloadJson(exportedBatch, `${safeFileName(refreshedState.session.title)}.json`);
        showSuccess(`Экспортировано операций: ${exportedBatch.transactions.length}.`);
    });
}

function downloadJson(payload, filename) {
    const blob = new Blob([`${JSON.stringify(payload, null, 2)}\n`], { type: "application/json" });
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = filename;
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
    operationBusy = value;
    applyBusyState();
}

function setNavigationLoading(value) {
    navigationLoading = value;
    applyBusyState();
}

function applyBusyState() {
    const value = operationBusy || navigationLoading;
    busy = value;
    elements.app.setAttribute("aria-busy", String(value));
    for (const control of elements.app.querySelectorAll("button, select, input, textarea")) {
        control.disabled = value;
    }
    elements.globalUserPrompt.disabled = value;
    elements.categoryName.disabled = value;
    elements.categoryHint.disabled = value;
    elements.categoryParent.disabled = value;
    elements.categoryType.disabled = value || Boolean(elements.categoryParent.value);
    elements.message.disabled = value || !activeState;
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

function transactionDateInput(name, value) {
    const input = textInput(name, value);
    input.inputMode = "numeric";
    input.placeholder = name === "posted_at"
        ? "DD-MM-YYYY или DD-MM-YYYY HH:mm"
        : "DD-MM-YYYY HH:mm";
    return input;
}

function formatTransactionDate(value) {
    const trimmed = value?.trim() || "";
    if (!trimmed) return "";
    const dateTime = trimmed.match(
        /^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::\d{2})?(?:Z|[+-]\d{2}:\d{2})?$/,
    );
    if (dateTime) {
        return `${dateTime[3]}-${dateTime[2]}-${dateTime[1]} ${dateTime[4]}:${dateTime[5]}`;
    }
    const date = trimmed.match(/^(\d{4})-(\d{2})-(\d{2})$/);
    if (date) return `${date[3]}-${date[2]}-${date[1]}`;
    return value;
}

function transactionDateToIso(value, fieldName) {
    const trimmed = value.trim();
    if (!trimmed) return fieldName === "posted_at" ? null : "";
    const dateTime = trimmed.match(
        /^(\d{2})-(\d{2})-(\d{4})[ T](\d{2}):(\d{2})$/,
    );
    if (dateTime) {
        return `${dateTime[3]}-${dateTime[2]}-${dateTime[1]}T${dateTime[4]}:${dateTime[5]}:00`;
    }
    const date = trimmed.match(/^(\d{2})-(\d{2})-(\d{4})$/);
    if (date) return `${date[3]}-${date[2]}-${date[1]}`;
    return trimmed;
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
