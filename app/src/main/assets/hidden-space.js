let hiddenScope = false;
let privacyEpoch = 0;
let privacySubmitting = false;
let hiddenFavorites = false;

function clearPrivacyView(event) {
  privacyEpoch = event.epoch;
  hiddenScope = event.value.hidden;
  privacySubmitting = false;
  hiddenFavorites = false;
  for (const [id, request] of pending) {
    clearTimeout(request.timer);
    request.reject(new Error(""));
    pending.delete(id);
  }

  StickerUI.cancelTransition();
  closeSheet(true);
  for (const id of ["sheet-body", "sheet-title", "sheet-notice", "mini", "notice"]) {
    document.getElementById(id).replaceChildren();
  }
  delete $("#mini").dataset.track;
  $("#sheet-notice").hidden = true;
  $("#notice").hidden = true;
  clearTimeout(noticeTimer);
  clearPendingSeek();
  seekSequence++;
  subToken++;
  setCaptionFocus(false, true);
  page = event.value.destination;
  previousPage = page;
  pid = tid = query = queueFolder = subId = subError = choiceReturn = "";
  cues = [];
  activeCue = -1;
  dragging = false;
  readerTab = "captions";
  db = { ...db, projects: [], last: undefined };
  state = {};
  aiJobs = [];
  aiDraft = [];
  aiJobId = aiDraftJob = aiReload = aiReturn = "";
  aiDraftPage = 0;
  aiForm = null;
  hiddenProgress.clear();
  render();
  $("#main").scrollTop = 0;
}

window.privacyCleared = (epoch) => privacyEpoch === epoch && !hiddenScope;

function receivePrivacy(event) {
  if (event.type === "privacy") {
    if (event.epoch > privacyEpoch) clearPrivacyView(event);
    return true;
  }
  if (event.epoch !== undefined && event.epoch !== privacyEpoch) return true;
  if (event.type === "hidden-export-auth") {
    showHiddenPin(event.value);
    return true;
  }
  return false;
}

function pinField(name, label) {
  return `<label class="ai-field"><span>${label}</span><input name="${name}" type="password" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="off" required></label>`;
}

function showHiddenPin(config, mode = "") {
  const setup = !config.configured;
  const change = mode === "change";
  const enable = mode === "enable";
  const title = change
    ? "修改密码"
    : enable
      ? "启用指纹快捷解锁"
      : setup
        ? "设置隐藏空间密码"
        : config.purpose === "move"
          ? "移入隐藏空间"
          : config.purpose === "export"
            ? "验证密码后导出"
            : "解锁隐藏空间";
  const action = change
    ? "hidden-pin-change"
    : enable
      ? "hidden-biometric-enable"
      : setup
        ? "hidden-pin-setup"
        : "hidden-pin-unlock";
  const showFingerprint = !mode && !setup && config.biometricEnabled;
  showSheet(
    "hidden-pin",
    title,
    `<form class="hidden-pin-form" data-hidden-form="${action}">${change ? pinField("previous", "原密码") : ""}
      ${pinField("pin", change ? "新密码（6 位数字）" : "6 位数字密码")}
      ${setup || change ? pinField("confirmation", "再次输入密码") : ""}
      <div class="ai-footer"><button class="sticker-btn primary wide" type="submit">${enable ? "验证密码" : setup || change ? "保存密码" : config.purpose === "move" ? "验证并移入" : config.purpose === "export" ? "验证并导出" : "解锁"}</button>
      ${showFingerprint ? '<button class="sticker-btn wide" type="button" data-action="hidden-use-biometric">使用指纹</button>' : ""}</div>
    </form>`,
  );
}

async function openHiddenPin(projectId = "") {
  showHiddenPin(await call("hidden-prepare", { projectId }));
}

function showHiddenOptions(config) {
  showSheet(
    "hidden-options",
    "隐藏空间设置",
    `<div class="sheet-actions"><button class="setting-row" data-action="hidden-change-pin"><span class="setting-label">修改密码</span>${icon("forward")}</button>
      ${config.biometricAvailable ? `<button class="setting-row" data-action="hidden-fingerprint" role="switch" aria-checked="${config.biometricEnabled}"><span class="setting-label">指纹快捷解锁</span><span class="sticker-switch" aria-hidden="true"></span></button>` : ""}
    </div>`,
  );
}

async function dismissHiddenSheet() {
  if (privacySubmitting) return;
  $("#sheet-body").querySelector("form")?.reset();
  if (sheetType === "hidden-pin" && !hiddenScope) await call("hidden-cancel");
  closeSheet(true);
  $("#sheet-body").replaceChildren();
  $("#sheet-title").replaceChildren();
}

function hiddenSearch() {
  return `<div class="hidden-actions"><button class="sticker-btn" data-ai="jobs">${icon("subtitle")}字幕任务</button>
    <button class="sticker-btn" data-action="hidden-options">${icon("settings")}隐藏空间设置</button></div>`;
}

function hiddenFilters() {
  return `<div class="segmented hidden-filters" role="group" aria-label="筛选隐藏作品">
    <button class="sticker-btn" data-action="hidden-all" aria-pressed="${!hiddenFavorites}">全部</button>
    <button class="sticker-btn" data-action="hidden-favorites" aria-pressed="${hiddenFavorites}">收藏</button>
  </div>`;
}

function filterHiddenFavorites(favorites) {
  hiddenFavorites = favorites;
  $('[data-action="hidden-all"]').setAttribute("aria-pressed", String(!favorites));
  $('[data-action="hidden-favorites"]').setAttribute("aria-pressed", String(favorites));
  renderCards();
}

async function requestHiddenAuthentication(action, payload = {}) {
  if (privacySubmitting) return;
  const form = $("#sheet-body").querySelector("form");
  const buttons = [...(form?.querySelectorAll("button") || [])];
  const epoch = privacyEpoch;
  privacySubmitting = true;
  buttons.forEach((button) => (button.disabled = true));
  try {
    const updated = await call(action, payload);
    if (epoch === privacyEpoch && ["hidden-pin-change", "hidden-biometric-enable"].includes(action)) showHiddenOptions(updated);
  } catch (error) {
    if (epoch === privacyEpoch && error.message) {
      notice(error.message, true);
      form?.querySelector("input")?.focus();
    }
  } finally {
    if (epoch === privacyEpoch) privacySubmitting = false;
    buttons.forEach((button) => (button.disabled = false));
  }
}

document.addEventListener("submit", (event) => {
  const form = event.target.closest("[data-hidden-form]");
  if (!form) return;
  event.preventDefault();
  if (privacySubmitting) return;
  const payload = Object.fromEntries(new FormData(form));
  form.reset();
  void requestHiddenAuthentication(form.dataset.hiddenForm, payload);
});
