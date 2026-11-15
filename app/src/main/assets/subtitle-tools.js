let aiConfig = {
  hasKey: false,
  sttModel: "openai/whisper-large-v3",
  translationModel: "tencent/hy-mt2-30b-a3b",
  fallbackModel: "",
  language: "auto",
  output: "vtt",
  bilingual: false,
  maxCost: 3,
  glossary: "",
};
let aiJobs = [],
  aiForm = null,
  aiJobId = "",
  aiDraft = [],
  aiDraftJob = "",
  aiDraftPage = 0,
  aiGenerateProject = false,
  aiGenerateMode = "auto",
  aiReturn = "",
  aiJobReturn = "jobs",
  aiExportReturn = "jobs",
  aiReload = "";
const hiddenProgress = new Set();
const aiModels = {
  sttModel: [
    ["openai/whisper-large-v3", "Whisper large-v3", "完整大模型 · 默认"],
    ["openai/whisper-1", "Whisper 1", "备用识别模型"],
  ],
  translationModel: [
    ["tencent/hy-mt2-30b-a3b", "腾讯 Hy-MT2", "翻译专用 · 默认"],
    ["qwen/qwen3-235b-a22b-2507", "通义千问 235B", "多语言翻译"],
  ],
  fallbackModel: [
    ["", "关闭", "技术错误时手动重试"],
    ["qwen/qwen3-235b-a22b-2507", "通义千问 235B", "技术错误时使用此模型"],
    ["tencent/hy-mt2-30b-a3b", "腾讯 Hy-MT2", "技术错误时使用此模型"],
  ],
};
const aiLanguageNames = {
  auto: "自动识别",
  ja: "日语",
  en: "英语",
  ko: "韩语",
  zh: "中文",
  fr: "法语",
  de: "德语",
  es: "西班牙语",
  ru: "俄语",
};
const aiStatusNames = {
  queued: "等待中",
  running: "进行中",
  paused: "已暂停",
  cancelled: "已取消",
  failed: "未完成",
  needs_review: "需要处理",
  done: "已保存",
};
function aiModelName(model) {
  for (const values of Object.values(aiModels)) {
    const found = values.find((x) => x[0] === model);
    if (found) return found[1];
  }
  return model || "关闭";
}
function handleSubtitleEvent(event) {
  if (event.type === "scan" && event.value) hiddenProgress.clear();
  if (event.type === "convert-progress") {
    if (hiddenProgress.has("convert-progress")) return true;
    if (sheetType !== "convert-progress")
      showSheet(
        "convert-progress",
        "生成兼容副本",
        '<p id="convert-title" class="ai-title"></p><p class="ai-secondary">FLAC · 保留原始音频</p><div class="ai-job-progress"><progress id="convert-progress" max="100" value="0" aria-label="转换进度"></progress></div><button class="sticker-btn wide" data-ai="archive-cancel">取消转换</button>',
      );
    document.querySelector("#convert-title").textContent = event.value.title;
    document.querySelector("#convert-progress").value = event.value.duration
      ? Math.min(100, (event.value.position / event.value.duration) * 100)
      : 0;
    return true;
  }
  if (event.type === "converted") {
    if (sheetType === "convert-progress") closeSheet();
    hiddenProgress.delete("convert-progress");
    notice("已切换到 FLAC 兼容副本");
    return true;
  }
  if (event.type === "archive-password") {
    showSheet(
      "archive-password",
      "作品包密码",
      `<p class="ai-title">${esc(event.value)}</p><label class="ai-field"><span>密码</span><input id="archive-password" type="password" autocomplete="off"></label><div class="ai-footer"><button class="sticker-btn primary wide" data-ai="archive-password">继续导入</button></div>`,
    );
    return true;
  }
  if (event.type === "archive-progress") {
    if (hiddenProgress.has("archive-progress")) return true;
    if (sheetType !== "archive-progress")
      showSheet(
        "archive-progress",
        "正在解压",
        '<p id="archive-current" class="ai-title"></p><p id="archive-count" class="ai-secondary"></p><button class="sticker-btn wide" data-ai="archive-cancel">取消导入</button>',
      );
    document.querySelector("#archive-current").textContent = event.value.name;
    document.querySelector("#archive-count").textContent =
      `已解压 ${event.value.files} 个文件 · ${(event.value.bytes / 1048576).toFixed(1)} MB`;
    return true;
  }
  if (event.type === "import-cancelled") {
    closeSheet();
    notice("已取消导入");
    return true;
  }
  if (event.type === "ai-config") {
    aiConfig = { ...aiConfig, ...event.value };
    document
      .querySelectorAll("[data-ai-key-state]")
      .forEach(
        (el) => (el.textContent = aiConfig.hasKey ? "已配置" : "未配置"),
      );
    return true;
  }
  if (event.type === "jobs") {
    const next = event.value.jobs || [];
    for (const job of next)
      if (
        job.status === "done" &&
        aiJobs.find((old) => old.id === job.id)?.status !== "done"
      )
        aiReload = job.projectId + ":" + job.trackId;
    aiJobs = next;
    refreshJobList();
    return true;
  }
  if (event.type === "open-jobs") {
    showAiJobs();
    return true;
  }
  if (event.type === "exported") {
    notice("字幕已导出");
    return true;
  }
  return false;
}
function subtitleSettingsSection() {
  return `<section class="settings-section sticker-panel t1"><h2 class="sticker-tag">中文字幕</h2><div class="settings-card"><button class="setting-row" data-ai="settings"><span class="setting-label">模型与 API</span><span class="setting-end"><span data-ai-key-state>${aiConfig.hasKey ? "已配置" : "未配置"}</span>${icon("forward")}</span></button><button class="setting-row" data-ai="jobs"><span class="setting-label">字幕任务</span>${icon("forward")}</button></div></section>`;
}
function collectAiForm() {
  if (!aiForm) return;
  document.querySelectorAll("[data-ai-field]").forEach((input) => {
    aiForm[input.dataset.aiField] =
      input.type === "number" ? Number(input.value) : input.value;
  });
}
function showAiSettings(reset = true) {
  if (reset) {
    aiReturn = ["ai-generate", "ai-job", "ai-jobs"].includes(sheetType)
      ? sheetType
      : "";
    aiForm = { ...aiConfig, apiKey: "" };
  }
  showSheet(
    "ai-settings",
    "中文字幕设置",
    `<div class="ai-stack"><section class="sticker-panel ai-section"><h3 class="sticker-tag">OpenRouter</h3><label class="ai-field"><span>API Key</span><input id="ai-api-key" data-ai-field="apiKey" type="password" autocomplete="off" autocapitalize="off" spellcheck="false" value="${esc(aiForm.apiKey || "")}" placeholder="${aiConfig.hasKey ? "已保存 · 填写可替换" : "sk-or-…"}"></label><div class="ai-inline"><span class="ai-secondary" data-ai-key-state>${aiConfig.hasKey ? "已配置" : "未配置"}</span><button class="sticker-btn quiet" data-ai="check-key">检查连接</button></div></section>
  <section class="sticker-panel ai-section t1"><h3 class="sticker-tag">模型组合</h3>${aiModelRow("识别模型", "sttModel")}${aiModelRow("翻译模型", "translationModel")}${aiModelRow("备用翻译", "fallbackModel")}<button class="setting-row" data-ai="language"><span>源语言</span><span class="setting-end">${esc(aiLanguageNames[aiForm.language] || aiForm.language)}${icon("forward")}</span></button></section>
  <section class="sticker-panel ai-section t2"><h3 class="sticker-tag">输出与费用</h3><div class="setting-block"><h4>默认导出格式</h4><div class="segmented" role="group" aria-label="默认导出格式">${["vtt", "lrc"].map((value) => `<button class="sticker-btn" data-ai="output" data-value="${value}" aria-pressed="${aiForm.output === value}">${value.toUpperCase()}</button>`).join("")}</div></div><button class="setting-row" data-ai="bilingual" role="switch" aria-checked="${aiForm.bilingual}"><span>生成后显示双语</span><span class="sticker-switch" aria-hidden="true"></span></button><label class="ai-field"><span>单曲费用暂停线（美元）</span><input data-ai-field="maxCost" type="number" inputmode="decimal" min="0.05" max="20" step="0.05" value="${aiForm.maxCost}"></label><label class="ai-field"><span>人名与术语</span><textarea data-ai-field="glossary" rows="3" maxlength="2000" placeholder="每行一项：原文 = 中文">${esc(aiForm.glossary)}</textarea></label></section></div><div class="ai-footer"><button class="sticker-btn primary wide" data-ai="save-settings">保存设置</button></div>`,
  );
}
function aiModelRow(label, key) {
  return `<button class="setting-row ai-model-row" data-ai="model" data-key="${key}"><span>${label}</span><span class="setting-end">${esc(aiModelName(aiForm[key]))}${icon("forward")}</span></button>`;
}
function showAiModel(key) {
  collectAiForm();
  showSheet(
    "ai-model",
    "选择" +
      {
        sttModel: "识别模型",
        translationModel: "翻译模型",
        fallbackModel: "备用翻译",
      }[key],
    `<div class="choice-list" role="radiogroup">${aiModels[key].map(([id, name, detail]) => `<button class="sticker-choice ai-model-choice" role="radio" aria-checked="${aiForm[key] === id}" data-ai="model-preset" data-key="${key}" data-value="${esc(id)}"><strong>${esc(name)}</strong><small>${esc(detail)}</small></button>`).join("")}</div><label class="ai-field ai-custom-model"><span>自定义模型 ID</span><input id="ai-custom-model" type="text" autocapitalize="off" spellcheck="false" placeholder="provider/model" value="${esc(aiForm[key])}"></label><button class="sticker-btn primary wide" data-ai="model-custom" data-key="${key}">使用此模型</button>`,
  );
}
function showAiLanguage() {
  collectAiForm();
  showSheet(
    "ai-language",
    "源语言",
    `<div class="choice-list choice-grid" role="radiogroup">${Object.entries(
      aiLanguageNames,
    )
      .map(
        ([key, label]) =>
          `<button class="sticker-choice" role="radio" aria-checked="${aiForm.language === key}" data-ai="language-pick" data-value="${key}">${label}</button>`,
      )
      .join("")}</div>`,
  );
}
function returnFromAiSettings() {
  const origin = aiReturn;
  aiReturn = "";
  if (origin === "ai-generate") showAiGenerate(aiGenerateProject, true);
  else if (origin === "ai-job") showAiJob(aiJobId);
  else if (origin === "ai-jobs") showAiJobs();
  else closeSheet();
}
function subtitleSheetBack() {
  if (sheetType === "ai-license") {
    showOpenSource();
    return true;
  }
  if (["archive-progress", "convert-progress"].includes(sheetType)) {
    hiddenProgress.add(sheetType);
    return false;
  }
  if (sheetType === "ai-model" || sheetType === "ai-language") {
    showAiSettings(false);
    return true;
  }
  if (sheetType === "ai-settings" && aiReturn) {
    collectAiForm();
    returnFromAiSettings();
    return true;
  }
  if (sheetType === "ai-job" && aiJobReturn === "jobs") {
    showAiJobs();
    return true;
  }
  if (sheetType === "ai-export") {
    if (aiExportReturn === "job") showAiJob(aiJobId);
    else showAiJobs();
    return true;
  }
  if (sheetType === "ai-edit") {
    collectAiDraft();
    showAiJob(aiJobId);
    return true;
  }
  if (["ai-search", "ai-track-info"].includes(sheetType) && page === "player") {
    options();
    return true;
  }
  return false;
}
function styleSubtitleSheetBack(type) {
  const label =
    type === "ai-license"
      ? "返回开源组件"
      : ["ai-model", "ai-language"].includes(type)
        ? "返回字幕设置"
        : type === "ai-settings" && aiReturn
          ? "返回上一页"
          : type === "ai-job" && aiJobReturn === "jobs"
            ? "返回字幕任务"
            : type === "ai-export"
              ? aiExportReturn === "job"
                ? "返回任务详情"
                : "返回字幕任务"
              : type === "ai-edit"
                ? "返回任务详情"
                : ["ai-search", "ai-track-info"].includes(type) &&
                    page === "player"
                  ? "返回播放选项"
                  : "";
  if (label) {
    const button = document.querySelector("#sheet .icon-button");
    button.innerHTML = icon("back");
    button.setAttribute("aria-label", label);
  }
}
function chineseSubtitle(sub) {
  return (
    sub.language === "zh" ||
    /\.zh[.\s-]|\.chs\.|中文|中字|简中|繁中/i.test(sub.name || "")
  );
}
let licenseLoading;
async function showOpenSource() {
  if (!Array.isArray(window.MIMI_LICENSES)) {
    licenseLoading ||= new Promise((resolve, reject) => {
      const script = document.createElement("script");
      script.src = "third-party.js";
      script.onload = resolve;
      script.onerror = () => {
        script.remove();
        licenseLoading = null;
        reject(new Error("无法读取开源组件说明"));
      };
      document.head.append(script);
    });
    await licenseLoading;
  }
  showSheet(
    "ai-licenses",
    "开源组件",
    '<div class="ai-stack">' +
      (window.MIMI_LICENSES || [])
        .map(
          (item, index) =>
            `<button class="setting-row" data-ai="license" data-value="${index}"><span class="ai-license-label"><strong>${esc(item.name)}</strong><small>${esc(item.license)}</small></span>${icon("forward")}</button>`,
        )
        .join("") +
      "</div>",
  );
}
function showLicense(index) {
  const item = window.MIMI_LICENSES?.[index];
  if (item)
    showSheet(
      "ai-license",
      item.name,
      `<p class="ai-secondary">${esc(item.license)}</p><pre class="ai-license-text">${esc(item.text)}</pre>`,
    );
}
function showAiGenerate(whole = false, preserveMode = false) {
  const track = selected(),
    work = project(pid);
  if (!track || !work) return;
  aiGenerateProject = whole;
  const raw = track.subtitles.some(
    (sub) =>
      sub.language === "original" ||
      (!sub.generated && !chineseSubtitle(sub) && sub.language !== "bilingual"),
  );
  if (!preserveMode)
    aiGenerateMode = whole ? "auto" : raw ? "translate" : "recognize";
  const missing = work.tracks.filter(
    (t) => !t.subtitles.some(chineseSubtitle),
  ).length;
  const pendingJob = aiJobs.find(
    (job) =>
      job.projectId === pid &&
      job.trackId === tid &&
      !["cancelled", "done"].includes(job.status),
  );
  if (!whole && pendingJob) {
    showAiJob(pendingJob.id, "player");
    return;
  }
  const completed = aiJobs.find(
    (job) =>
      job.status === "done" &&
      job.projectId === pid &&
      job.trackId === tid &&
      ["sttModel", "translationModel", "language", "glossary"].every(
        (key) => (job.config?.[key] || "") === (aiConfig[key] || ""),
      ) &&
      track.subtitles.some((sub) => sub.generated && sub.jobId === job.id),
  );
  if (!whole && completed) {
    showAiJob(completed.id, "player");
    return;
  }
  showSheet(
    "ai-generate",
    whole ? "补齐中文字幕" : "生成中文字幕",
    `<section class="sticker-panel ai-section t2"><h3 class="sticker-tag">${whole ? "整部作品" : "当前曲目"}</h3><p class="ai-title">${esc(whole ? work.name : track.title)}</p><p class="ai-secondary">${whole ? missing + " 曲待处理 · 共 " + work.tracks.length + " 曲" : fmt(track.duration)}</p>${raw && !whole ? `<div class="segmented" role="group" aria-label="字幕来源"><button class="sticker-btn" data-ai="generate-mode" data-value="translate" aria-pressed="${aiGenerateMode === "translate"}">翻译现有字幕</button><button class="sticker-btn" data-ai="generate-mode" data-value="recognize" aria-pressed="${aiGenerateMode === "recognize"}">重新识别音频</button></div>` : ""}</section><section class="sticker-panel ai-section t1"><h3 class="sticker-tag">模型组合</h3><dl class="ai-details"><div><dt>识别</dt><dd>${esc(aiModelName(aiConfig.sttModel))}</dd></div><div><dt>翻译</dt><dd>${esc(aiModelName(aiConfig.translationModel))}</dd></div><div><dt>语言</dt><dd>${esc(aiLanguageNames[aiConfig.language] || aiConfig.language)}</dd></div><div><dt>费用控制</dt><dd>$${aiConfig.maxCost} / 曲后暂停</dd></div></dl><button class="sticker-btn quiet wide" data-ai="settings">修改设置</button></section><p class="ai-secondary ai-cloud-note">音频片段或字幕文本会发送至 OpenRouter，按实际用量计费。</p><div class="ai-footer"><button class="sticker-btn primary wide" data-ai="start" ${!aiConfig.hasKey || (whole && !missing) ? "disabled" : ""}>${whole ? (missing ? "开始补齐" : "全部已有中文字幕") : "开始生成"}</button>${!aiConfig.hasKey ? '<button class="sticker-btn wide" data-ai="settings">填写 API Key</button>' : ""}</div>`,
  );
}
function showAiJobs() {
  showSheet(
    "ai-jobs",
    "字幕任务",
    '<div id="ai-job-toolbar" class="ai-pagination"><button class="sticker-btn" data-ai="resume-all">继续全部</button><button class="sticker-btn" data-ai="pause-all">全部暂停</button></div><div id="ai-job-list" class="ai-stack"></div>',
  );
  refreshJobList();
}
function jobRow(job) {
  const active = ["queued", "running"].includes(job.status),
    ratio = job.total
      ? Math.min(100, Math.round((job.completed / job.total) * 100))
      : 0;
  return `<article class="sticker-panel ai-job-card ${job.status === "needs_review" ? "t1" : "t2"}" data-job="${job.id}"><div class="ai-job-top"><h3 class="sticker-tag">${aiStatusNames[job.status] || job.status}</h3><span class="ai-cost">${job.cost > 0 ? "$" + Number(job.cost).toFixed(4) : ""}</span></div><button class="ai-job-title" data-ai="job" data-job="${job.id}"><strong>${esc(job.title)}</strong><small>${esc(job.projectName)}</small></button><div class="ai-job-progress" ${job.status === "done" ? "hidden" : ""}><progress max="100" value="${ratio}" aria-label="当前阶段进度"></progress><span>${esc(job.message || { recognizing: "识别", translating: "翻译", saving: "保存" }[job.stage] || "等待处理")}</span></div><div class="ai-job-actions">${job.status === "done" ? `<button class="sticker-btn primary" data-ai="export" data-job="${job.id}">导出字幕</button>` : active ? `<button class="sticker-btn" data-ai="control" data-command="pause" data-job="${job.id}">暂停</button>` : `<button class="sticker-btn primary" data-ai="control" data-command="resume" data-job="${job.id}">${job.status === "failed" || job.status === "needs_review" ? "重试" : "继续"}</button>`}<button class="sticker-btn quiet" data-ai="job" data-job="${job.id}">详情</button></div></article>`;
}
function refreshJobList() {
  const toolbar = document.querySelector("#ai-job-toolbar");
  if (toolbar) {
    const paused = aiJobs.some((job) => job.status === "paused"),
      running = aiJobs.some((job) =>
        ["queued", "running"].includes(job.status),
      );
    toolbar.hidden = !paused && !running;
    toolbar.querySelector('[data-ai="resume-all"]').disabled = !paused;
    toolbar.querySelector('[data-ai="pause-all"]').disabled = !running;
  }
  const container = document.querySelector("#ai-job-list");
  if (container) {
    const list = [...aiJobs].reverse();
    if (!list.length) {
      container.innerHTML =
        '<div class="ai-empty"><h3 class="sticker-tag">暂无任务</h3><p>在曲目的字幕选项中生成中文字幕</p></div>';
      return;
    }
    const present = new Set(list.map((job) => job.id));
    container.querySelectorAll("[data-job]").forEach((el) => {
      if (el.parentElement === container && !present.has(el.dataset.job))
        el.remove();
    });
    container.querySelector(".ai-empty")?.remove();
    list.forEach((job, index) => {
      let card = container.querySelector(`article[data-job="${job.id}"]`);
      const signature = JSON.stringify([
        job.status,
        job.stage,
        job.completed,
        job.total,
        job.message,
        job.cost,
      ]);
      if (card?.dataset.signature === signature) return;
      if (card?.dataset.status === job.status) {
        card.dataset.signature = signature;
        card.querySelector(".ai-cost").textContent =
          job.cost > 0 ? "$" + Number(job.cost).toFixed(4) : "";
        card.querySelector("progress").value = job.total
          ? Math.min(100, Math.round((job.completed / job.total) * 100))
          : 0;
        card.querySelector(".ai-job-progress span").textContent =
          job.message ||
          { recognizing: "识别", translating: "翻译", saving: "保存" }[
            job.stage
          ] ||
          "等待处理";
        return;
      }
      const temp = document.createElement("template");
      temp.innerHTML = jobRow(job);
      const next = temp.content.firstElementChild;
      next.dataset.signature = signature;
      next.dataset.status = job.status;
      if (card) card.replaceWith(next);
      else container.insertBefore(next, container.children[index] || null);
    });
  }
  if (sheetType === "ai-job" && document.querySelector("#ai-job-detail")) {
    const job = aiJobs.find((x) => x.id === aiJobId);
    if (job) {
      const detail = document.querySelector("#ai-job-detail");
      if (detail.dataset.status && detail.dataset.status !== job.status) {
        const scroll = document.querySelector("#sheet-body").scrollTop;
        showAiJob(aiJobId);
        document.querySelector("#sheet-body").scrollTop = scroll;
      } else {
        detail.dataset.status = job.status;
        document.querySelector("#ai-job-state").textContent =
          aiStatusNames[job.status] || job.status;
        document.querySelector("#ai-job-message").textContent =
          job.message || "";
      }
    }
  }
}
function showAiJob(id, origin = null) {
  const job = aiJobs.find((x) => x.id === id);
  if (!job) return;
  aiJobId = id;
  if (origin !== null) aiJobReturn = origin;
  showSheet(
    "ai-job",
    "任务详情",
    `<section class="sticker-panel ai-section t1" id="ai-job-detail"><h3 class="sticker-tag" id="ai-job-state">${aiStatusNames[job.status] || job.status}</h3><p class="ai-title">${esc(job.title)}</p><p class="ai-secondary">${esc(job.projectName)}</p><p id="ai-job-message" class="ai-task-message">${esc(job.message || "")}</p><dl class="ai-details"><div><dt>已计费用</dt><dd>$${Number(job.cost || 0).toFixed(4)}</dd></div><div><dt>识别</dt><dd>${esc(aiModelName(job.config?.sttModel || aiConfig.sttModel))}</dd></div><div><dt>翻译</dt><dd>${esc(aiModelName(job.config?.translationModel || aiConfig.translationModel))}</dd></div>${job.cueCount ? `<div><dt>字幕</dt><dd>${job.cueCount} 条</dd></div><div><dt>保存位置</dt><dd>${job.sidecars?.length ? "作品目录 + 应用内" : "应用内 · 可导出"}</dd></div>` : ""}</dl></section><div class="sheet-actions">${job.status === "done" ? `<button class="sticker-btn primary wide" data-ai="export" data-job="${id}">导出字幕</button>` : ["running", "queued"].includes(job.status) ? `<button class="sticker-btn wide" data-ai="control" data-command="pause" data-job="${id}">暂停任务</button>` : `<button class="sticker-btn primary wide" data-ai="control" data-command="resume" data-job="${id}">继续任务</button>`}${!["running", "queued"].includes(job.status) ? `<button class="sticker-btn wide" data-ai="edit" data-job="${id}">编辑译文</button>` : ""}<button class="sticker-btn wide" data-ai="settings">更换模型</button>${!["running", "queued"].includes(job.status) ? `<button class="sticker-btn quiet wide" data-ai="control" data-command="remove" data-job="${id}">移除任务记录</button>` : ""}</div>`,
  );
}
function showAiExport(id) {
  aiExportReturn = sheetType === "ai-job" ? "job" : "jobs";
  aiJobId = id;
  showSheet(
    "ai-export",
    "导出字幕",
    `<div class="choice-list" role="radiogroup" aria-label="字幕格式">${[
      ["vtt", "中文 VTT"],
      ["lrc", "中文 LRC"],
      ["bilingual", "中文 + 原文 VTT"],
      ["original", "原文 VTT"],
    ]
      .map(
        ([key, label]) =>
          `<button class="sticker-choice" role="radio" data-ai="export-format" data-value="${key}" aria-checked="${aiConfig.output === key}">${label}</button>`,
      )
      .join(
        "",
      )}</div><div class="ai-footer"><button class="sticker-btn primary wide" data-ai="export-picker">选择保存位置</button>${hiddenScope ? "" : '<button class="sticker-btn wide" data-ai="export-beside">保存到作品目录</button>'}</div>`,
  );
}
function selectedExportFormat() {
  return (
    document.querySelector('[data-ai="export-format"][aria-checked="true"]')
      ?.dataset.value || "vtt"
  );
}
function collectAiDraft() {
  document.querySelectorAll("[data-ai-line]").forEach((input) => {
    if (aiDraft[Number(input.dataset.aiLine)])
      aiDraft[Number(input.dataset.aiLine)].text = input.value;
  });
}
function renderAiEditor() {
  const start = aiDraftPage * 10,
    rows = aiDraft.slice(start, start + 10);
  showSheet(
    "ai-edit",
    "编辑译文",
    `<div class="ai-editor-summary"><span>${start + 1}–${Math.min(start + 10, aiDraft.length)} / ${aiDraft.length}</span><span>时间轴保持不变</span></div><div class="ai-stack">${rows.map((cue, index) => `<label class="ai-edit-line"><span class="ai-edit-time">${fmt(cue.start * 1000)} — ${fmt(cue.end * 1000)}</span><span class="ai-original">${esc(cue.original)}</span><textarea rows="2" data-ai-line="${start + index}" maxlength="2000" aria-label="第 ${start + index + 1} 条译文">${esc(cue.text)}</textarea></label>`).join("")}</div><div class="ai-pagination"><button class="sticker-btn" data-ai="edit-page" data-value="-1" ${aiDraftPage === 0 ? "disabled" : ""}>上一页</button><button class="sticker-btn" data-ai="edit-page" data-value="1" ${start + 10 >= aiDraft.length ? "disabled" : ""}>下一页</button></div><div class="ai-footer"><button class="sticker-btn primary wide" data-ai="save-draft">保存字幕</button></div>`,
  );
}
function showCaptionSearch() {
  showSheet(
    "ai-search",
    "搜索字幕",
    '<label class="ai-field"><span class="sr-only">搜索字幕</span><input id="caption-search" type="search" placeholder="输入字幕内容" autocomplete="off"></label><div id="caption-search-results" class="ai-search-results"></div>',
  );
  document.querySelector("#caption-search").focus();
}
function captionSearch(value) {
  const matches = cues
    .map((cue, index) => ({ cue, index }))
    .filter(
      ({ cue }) =>
        value.trim() &&
        (cue.text + " " + (cue.original || ""))
          .toLocaleLowerCase()
          .includes(value.trim().toLocaleLowerCase()),
    );
  document.querySelector("#caption-search-results").innerHTML = matches.length
    ? matches
        .slice(0, 150)
        .map(
          ({ cue, index }) =>
            `<button class="ai-search-result" data-ai="search-seek" data-value="${index}"><time>${fmt(cue.start * 1000)}</time><span>${esc(cue.text)}</span></button>`,
        )
        .join("")
    : value.trim()
      ? '<p class="ai-secondary">没有匹配的字幕</p>'
      : "";
}
function showTrackInfo() {
  const t = selected();
  if (!t) return;
  const info = t.audioInfo || {};
  showSheet(
    "ai-track-info",
    "曲目信息",
    `<section class="sticker-panel ai-section t2"><h3 class="sticker-tag">${esc(t.format)}</h3><p class="ai-title">${esc(t.title)}</p><dl class="ai-details">${[
      ["文件", t.sourceName || t.name],
      ["文件夹", t.folder || "作品根目录"],
      ["时长", fmt(t.duration)],
      ["编码", info.codec || "未读取"],
      [
        "声道",
        info.channels === 2
          ? "双声道"
          : info.channels === 1
            ? "单声道"
            : info.channels
              ? info.channels + " 声道"
              : "—",
      ],
      ["采样率", info.sampleRate ? info.sampleRate + " Hz" : "—"],
      ["位深", info.bits ? info.bits + " bit" : "—"],
      ...(t.cueSource
        ? [
            ["CUE", t.cueSource],
            ["分轨", fmt(t.clipStart) + " — " + fmt(t.clipEnd)],
          ]
        : []),
    ]
      .map(
        ([label, value]) =>
          `<div><dt>${label}</dt><dd>${esc(value)}</dd></div>`,
      )
      .join(
        "",
      )}</dl>${t.mediaError ? `<p class="ai-task-message">${esc(t.mediaError)}</p>` : ""}</section><div class="sheet-actions"><button class="sticker-btn wide" data-ai="${t.converted ? "restore-audio" : "convert-audio"}">${t.converted ? "恢复原始音频" : "生成 FLAC 兼容副本"}</button></div>`,
  );
}
document.addEventListener("input", (event) => {
  if (event.target.id === "caption-search") captionSearch(event.target.value);
});
document.addEventListener("click", async (event) => {
  const button = event.target.closest("[data-ai]");
  if (!button || button.disabled) return;
  const action = button.dataset.ai;
  try {
    switch (action) {
      case "import-issues":
        showSheet(
          "import-issues",
          "导入提示",
          `<div class="ai-stack">${(project(pid)?.importIssues || []).map((item) => `<section class="sticker-panel ai-section"><p class="ai-title">${esc(item.name)}</p><p class="ai-secondary">${esc(item.reason)}</p></section>`).join("")}</div>`,
        );
        break;
      case "import-files":
        await call("import-files");
        break;
      case "archive-password":
        button.disabled = true;
        await call("import-password", {
          password: document.querySelector("#archive-password").value,
        });
        document.querySelector("#archive-password").value = "";
        break;
      case "archive-cancel":
        await call("import-cancel");
        break;
      case "settings":
        showAiSettings();
        break;
      case "licenses":
        await showOpenSource();
        break;
      case "license":
        showLicense(Number(button.dataset.value));
        break;
      case "model":
        showAiModel(button.dataset.key);
        break;
      case "model-preset":
        aiForm[button.dataset.key] = button.dataset.value;
        showAiSettings(false);
        break;
      case "model-custom": {
        const value = document.querySelector("#ai-custom-model").value.trim();
        if (!/^[\w.:-]+\/[\w.:/-]+$/.test(value))
          throw new Error("请输入 provider/model 格式的模型 ID");
        aiForm[button.dataset.key] = value;
        showAiSettings(false);
        break;
      }
      case "language":
        showAiLanguage();
        break;
      case "language-pick":
        aiForm.language = button.dataset.value;
        showAiSettings(false);
        break;
      case "output":
        collectAiForm();
        aiForm.output = button.dataset.value;
        document
          .querySelectorAll('[data-ai="output"]')
          .forEach((el) =>
            el.setAttribute("aria-pressed", String(el === button)),
          );
        break;
      case "bilingual":
        aiForm.bilingual = !aiForm.bilingual;
        button.setAttribute("aria-checked", String(aiForm.bilingual));
        break;
      case "save-settings":
        collectAiForm();
        button.disabled = true;
        aiConfig = await call("ai-config-save", aiForm);
        aiForm.apiKey = "";
        returnFromAiSettings();
        notice("字幕设置已保存");
        break;
      case "check-key":
        collectAiForm();
        button.disabled = true;
        if (aiForm.apiKey) {
          aiConfig = await call("ai-config-save", { apiKey: aiForm.apiKey });
          aiForm.apiKey = "";
          document.querySelector("#ai-api-key").value = "";
        }
        await call("ai-check");
        notice("连接正常");
        break;
      case "generate":
        showAiGenerate(false);
        break;
      case "generate-project":
        showAiGenerate(true);
        break;
      case "generate-mode":
        aiGenerateMode = button.dataset.value;
        document
          .querySelectorAll('[data-ai="generate-mode"]')
          .forEach((el) =>
            el.setAttribute("aria-pressed", String(el === button)),
          );
        break;
      case "start":
        button.disabled = true;
        await call("ai-start", {
          projectId: pid,
          trackId: tid,
          project: aiGenerateProject,
          mode: aiGenerateMode,
        });
        aiJobs = (await call("ai-jobs")).jobs || [];
        showAiJobs();
        break;
      case "jobs":
        aiJobs = (await call("ai-jobs")).jobs || [];
        showAiJobs();
        break;
      case "job":
        showAiJob(button.dataset.job, "jobs");
        break;
      case "control":
        button.disabled = true;
        aiJobs =
          (
            await call("ai-control", {
              jobId: button.dataset.job,
              command: button.dataset.command,
            })
          ).jobs || [];
        showAiJobs();
        break;
      case "resume-all":
      case "pause-all":
        button.disabled = true;
        aiJobs =
          (
            await call("ai-control-all", {
              command: action === "resume-all" ? "resume" : "pause",
            })
          ).jobs || [];
        refreshJobList();
        break;
      case "export":
        showAiExport(button.dataset.job);
        break;
      case "export-format":
        document
          .querySelectorAll('[data-ai="export-format"]')
          .forEach((el) =>
            el.setAttribute("aria-checked", String(el === button)),
          );
        break;
      case "export-picker":
        await call("ai-export", {
          jobId: aiJobId,
          format: selectedExportFormat(),
        });
        break;
      case "export-beside": {
        button.disabled = true;
        const job = aiJobs.find((x) => x.id === aiJobId);
        const result = await call("ai-save-beside", {
          jobId: aiJobId,
          projectId: job.projectId,
          trackId: job.trackId,
          format: selectedExportFormat(),
        });
        notice("已保存：" + result.name);
        break;
      }
      case "edit":
        button.disabled = true;
        aiJobId = button.dataset.job;
        if (aiDraftJob !== aiJobId) {
          aiDraft = await call("ai-draft", { jobId: aiJobId });
          aiDraftPage = 0;
          aiDraftJob = aiJobId;
        }
        renderAiEditor();
        break;
      case "edit-page":
        collectAiDraft();
        aiDraftPage += Number(button.dataset.value);
        renderAiEditor();
        break;
      case "save-draft":
        collectAiDraft();
        button.disabled = true;
        await call("ai-save-draft", { jobId: aiJobId, cues: aiDraft });
        aiDraftJob = "";
        aiJobs = (await call("ai-jobs")).jobs || [];
        showAiJob(aiJobId);
        if (page === "player") loadSubtitles();
        notice("字幕已保存");
        break;
      case "search":
        showCaptionSearch();
        break;
      case "search-seek":
        closeSheet();
        await seekCaption(Number(button.dataset.value));
        break;
      case "track-info":
        showTrackInfo();
        break;
      case "convert-audio":
        hiddenProgress.delete("convert-progress");
        await call("convert-audio", { projectId: pid, trackId: tid });
        break;
      case "restore-audio":
        await call("restore-audio", { projectId: pid, trackId: tid });
        showTrackInfo();
        notice("已恢复原始音频");
        break;
    }
  } catch (error) {
    notice(error.message, true);
  } finally {
    if (button.isConnected) button.disabled = false;
  }
});
