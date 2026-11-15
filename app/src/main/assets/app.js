const $ = (s) => document.querySelector(s);
const paths = {
  library: "M4 3v18 M9 3v18 M14 3l6 18",
  heart:
    "M20.8 4.6a5.5 5.5 0 0 0-7.8 0L12 5.7l-1.1-1.1a5.5 5.5 0 0 0-7.8 7.8L12 21l8.8-8.6a5.5 5.5 0 0 0 0-7.8",
  settings:
    "M9 3h6l1 3 3 1 2 5-2 5-3 1-1 3H9l-1-3-3-1-2-5 2-5 3-1z M15 12a3 3 0 1 1-6 0 3 3 0 0 1 6 0",
  play: "M8 4l13 8-13 8z",
  pause: "M8 4v16 M16 4v16",
  previous: "M5 4v16 M20 4 8 12l12 8z",
  next: "M19 4v16 M4 4l12 8-12 8z",
  plus: "M12 5v14 M5 12h14",
  close: "M6 6l12 12 M18 6 6 18",
  down: "M5 9l7 7 7-7",
  more: "M5 11v2 M12 11v2 M19 11v2",
  search: "M16 10a6 6 0 1 1-12 0 6 6 0 0 1 12 0 M15 15l6 6",
  headphones:
    "M3 14v-3a9 9 0 0 1 18 0v3 M3 13h4v8H5a2 2 0 0 1-2-2z M21 13h-4v8h2a2 2 0 0 0 2-2z",
  folder: "M3 5h6l2 3h10v12H3z",
  moon: "M21 13A9 9 0 0 1 11 3a9 9 0 1 0 10 10",
  back: "M19 12H5 M11 6l-6 6 6 6",
  forward: "M5 12h14 M13 6l6 6-6 6",
  subtitle: "M3 5h18v14H3z M6 10h5 M6 14h3 M13 10h5 M12 14h6",
  trash: "M3 6h18 M5 6l1 15h12l1-15 M9 6V3h6v3",
  check: "M5 12l4 4L20 5",
  lock: "M6 10h12v11H6z M8 10V7a4 4 0 1 1 8 0v3 M12 14v3",
};
const icon = (n) =>
  `<svg viewBox="0 0 24 24" aria-hidden="true"><path d="${paths[n] || paths.headphones}"/></svg>`;
const esc = (v) =>
  String(v ?? "").replace(
    /[&<>"']/g,
    (c) =>
      ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[
        c
      ],
  );
const fmt = (ms) => {
  let n = Math.max(0, Math.floor((ms || 0) / 1000));
  return (
    (n >= 3600 ? Math.floor(n / 3600) + ":" : "") +
    String(Math.floor(n / 60) % 60).padStart(2, "0") +
    ":" +
    String(n % 60).padStart(2, "0")
  );
};
let db = { projects: [], theme: "light" },
  state = {},
  page = "library",
  previousPage = "library",
  pid = "",
  tid = "",
  query = "",
  readerTab = "captions",
  cues = [],
  subId = "",
  subError = "",
  subToken = 0,
  activeCue = -1,
  follow = true,
  dragging = false,
  scanning = false,
  noticeTimer,
  queueMode = "all",
  queueFolder = "",
  sheetType = "";
let captionFocus = false;
let pendingSeek = null,
  seekSequence = 0,
  seekTimer;
function clearPendingSeek() {
  clearTimeout(seekTimer);
  pendingSeek = null;
}
function localSeekPosition() {
  return pendingSeek?.projectId === pid && pendingSeek?.trackId === tid
    ? pendingSeek.position
    : null;
}
async function seekTo(position) {
  const duration =
    (same() ? state.duration : 0) || selected()?.duration || Infinity;
  const target = Math.max(0, Math.min(Number(position) || 0, duration));
  const request = ++seekSequence,
    oldFollow = follow;
  clearPendingSeek();
  pendingSeek = {
    projectId: pid,
    trackId: tid,
    position: target,
    alignCaption: true,
    startedAt: performance.now(),
  };
  follow = true;
  updatePlayer();
  seekTimer = setTimeout(() => {
    if (request !== seekSequence) return;
    clearPendingSeek();
    activeCue = -1;
    updatePlayer();
  }, 3000);
  try {
    if (same()) await call("seek", { position: target });
    else await startTrack(tid, target);
  } catch (error) {
    if (request !== seekSequence) return;
    clearPendingSeek();
    follow = oldFollow;
    activeCue = -1;
    updatePlayer();
    throw error;
  }
}
function setCaptionFocus(value, immediate = false) {
  const next = !!value && page === "player";
  if (next === captionFocus) return;
  captionFocus = next;
  const update = () => {
    document.body.classList.toggle("caption-focus", captionFocus);
    requestAnimationFrame(() => {
      activeCue = -1;
      updatePlayer();
    });
  };
  if (immediate) update();
  else StickerUI.transition(update, "focus");
}
async function seekCaption(index) {
  const cue = cues[index];
  if (!cue) return;
  const position = Math.max(0, (cue.start + (selected()?.delay || 0)) * 1000);
  await seekTo(position);
}
function bindReaderGestures(reader) {
  let gesture = null,
    timer;
  const clear = () => clearTimeout(timer);
  reader.addEventListener("pointerdown", (event) => {
    clear();
    if (!event.isPrimary || event.button !== 0) {
      gesture = null;
      return;
    }
    const cue = event.target.closest("[data-cue]");
    gesture = {
      x: event.clientX,
      y: event.clientY,
      moved: false,
      held: false,
      pid,
      tid,
    };
    if (!cue) return;
    const current = gesture;
    timer = setTimeout(async () => {
      if (
        !reader.isConnected ||
        gesture !== current ||
        current.moved ||
        current.pid !== pid ||
        current.tid !== tid
      )
        return;
      current.held = true;
      try {
        await seekCaption(Number(cue.dataset.cue));
      } catch (error) {
        notice(error.message, true);
      }
    }, 400);
  });
  reader.addEventListener(
    "pointermove",
    (event) => {
      if (!gesture) return;
      if (
        Math.hypot(event.clientX - gesture.x, event.clientY - gesture.y) > 10
      ) {
        gesture.moved = true;
        follow = false;
        clear();
      }
    },
    { passive: true },
  );
  reader.addEventListener("pointerup", clear);
  reader.addEventListener("pointercancel", () => {
    clear();
    if (gesture && !gesture.held) {
      gesture.moved = true;
      follow = false;
    }
  });
  reader.addEventListener(
    "click",
    (event) => {
      if (gesture?.moved || gesture?.held) {
        event.preventDefault();
        event.stopPropagation();
      }
      gesture = null;
    },
    true,
  );
  reader.addEventListener("contextmenu", (event) => event.preventDefault());
}
let serial = 1;
const pending = new Map();
function call(action, payload = {}) {
  return new Promise((resolve, reject) => {
    const id = serial++;
    const timer = setTimeout(() => {
      pending.delete(id);
      reject(new Error("操作超时，请重试"));
    }, 30000);
    pending.set(id, { resolve, reject, timer });
    Native.request(JSON.stringify({ id, action, payload }));
  });
}
const project = (id) => db.projects.find((p) => p.id === id);
const track = (p, t) => project(p)?.tracks.find((x) => x.id === t);
const selected = () => track(pid, tid);
const same = () => pid === state.projectId && tid === state.trackId;
function notice(text, error = false, keep = false) {
  if (!text) return;
  clearTimeout(noticeTimer);
  $("#notice").hidden = true;
  $("#sheet-notice").hidden = true;
  const target = $("#sheet").open ? $("#sheet-notice") : $("#notice");
  target.textContent = text;
  target.classList.toggle("error", error);
  target.hidden = false;
  if (!keep)
    noticeTimer = setTimeout(() => {
      target.hidden = true;
    }, 3500);
}
function artwork(p, cls = "cover") {
  if (!p.cover) return `<div class="${cls} placeholder">${icon("headphones")}</div>`;
  const url = p.cover.startsWith("https://appassets.androidplatform.net/covers/")
    ? `${p.cover}?project=${encodeURIComponent(p.id)}&epoch=${privacyEpoch}`
    : p.cover;
  return `<img class="${cls}" src="${esc(url)}" alt="${esc(p.name)}封面">`;
}
function button(action, label, i, extra = "") {
  return `<button class="sticker-btn icon-button" data-action="${action}" aria-label="${label}" ${extra}>${icon(i)}</button>`;
}
window.receive = (event) => {
  if (receivePrivacy(event) || handleSubtitleEvent(event)) return;
  if (event.type === "reply") {
    let p = pending.get(event.value.id);
    if (!p) return;
    clearTimeout(p.timer);
    pending.delete(event.value.id);
    event.value.error
      ? p.reject(new Error(event.value.error))
      : p.resolve(event.value.value);
    return;
  }
  if (event.type === "library") {
    const previousSubtitle = selected()?.selectedSub || subId;
    db = event.value;
    document.documentElement.dataset.theme = db.theme || "light";
    document.documentElement.style.setProperty(
      "--caption-size",
      (db.captionSize || 20) + "px",
    );
    if (page === "player") {
      if (!project(pid)) {
        navigate(hiddenScope ? "hidden" : "library");
        return;
      }
      if (!selected()) {
        tid = project(pid).resume?.trackId || project(pid).tracks[0]?.id;
        renderPlayer();
        loadSubtitles();
      }
      if (
        selected()?.selectedSub &&
        (previousSubtitle !== selected().selectedSub ||
          aiReload === pid + ":" + tid)
      ) {
        aiReload = "";
        loadSubtitles();
      }
      $(".favorite")?.setAttribute(
        "aria-pressed",
        String(!!project(pid)?.favorite),
      );
    } else if (page === "settings") refreshSettings();
    else render();
    updateMini();
  } else if (event.type === "state") {
    const old = state;
    state = event.value;
    const seekTolerance =
      500 +
      (pendingSeek && state.playing
        ? (performance.now() - pendingSeek.startedAt) * (state.speed || 1)
        : 0);
    if (
      pendingSeek &&
      state.projectId === pendingSeek.projectId &&
      state.trackId === pendingSeek.trackId &&
      Math.abs(state.position - pendingSeek.position) <= seekTolerance &&
      (!pendingSeek.alignCaption ||
        readerTab !== "captions" ||
        subId === "off" ||
        subError ||
        !selected()?.subtitles.length)
    ) {
      clearPendingSeek();
    }
    if (state.trackId) {
      const p = project(state.projectId);
      if (p) p.resume = { trackId: state.trackId, position: state.position };
    }
    if (
      page === "player" &&
      state.projectId === pid &&
      state.trackId &&
      state.trackId !== old.trackId
    ) {
      tid = state.trackId;
      renderPlayer();
      loadSubtitles();
      if (sheetType === "tracks" && $("#sheet").open) renderQueue();
    }
    updatePlayer();
    updateMini();
    refreshOptionValues();
    if (state.error && state.error !== old.error) notice(state.error, true);
  } else if (event.type === "scan") {
    scanning = event.value;
    if (scanning) notice("正在导入作品…", false, true);
    else {
      if (!$("#notice").classList.contains("error")) $("#notice").hidden = true;
      if (!$("#sheet-notice").classList.contains("error"))
        $("#sheet-notice").hidden = true;
    }
    if (page !== "player") render();
  } else if (event.type === "refreshed") {
    closeSheet();
    notice("已刷新作品");
  } else if (event.type === "imported") {
    const preserveContext =
      page === "player" ||
      ($("#sheet").open &&
        !["import", "archive-progress", "archive-password"].includes(
          sheetType,
        ));
    if (!preserveContext) {
      closeSheet();
      navigate("library");
    }
    notice(`已导入 ${event.value} 部作品`);
  } else if (event.type === "error") {
    scanning = false;
    notice(event.value || "操作失败", true);
  }
};
function navigate(next) {
  if (next === page) return;
  if (hiddenScope && next !== "hidden" && next !== "player") {
    void call("hidden-lock").catch((error) => notice(error.message, true));
    return;
  }
  const direction = page === "player" ? "back" : "forward";
  closeSheet(true);
  clearPendingSeek();
  seekSequence++;
  const epoch = privacyEpoch;
  StickerUI.transition(() => {
    if (epoch !== privacyEpoch) return;
    setCaptionFocus(false, true);
    if (next !== "player") {
      page = next;
      pid = "";
      tid = "";
      cues = [];
      subToken++;
    }
    render();
    $("#main").scrollTop = 0;
  }, direction);
}
function openProject(id, trackId) {
  const p = project(id);
  if (!p) return;
  closeSheet(true);
  clearPendingSeek();
  seekSequence++;
  const epoch = privacyEpoch;
  StickerUI.transition(() => {
    if (epoch !== privacyEpoch) return;
    setCaptionFocus(false, true);
    if (page !== "player") previousPage = page;
    pid = id;
    tid =
      trackId ||
      (state.projectId === id ? state.trackId : p.resume?.trackId) ||
      p.tracks[0]?.id;
    page = "player";
    follow = true;
    readerTab = "captions";
    render();
    loadSubtitles();
  });
}
function render() {
  document.body.dataset.page = page;
  $("#nav").hidden = page === "player" || hiddenScope;
  if (!$("#nav").children.length)
    $("#nav").innerHTML = [
      ["library", "作品库", "library"],
      ["favorites", "收藏", "heart"],
      ["settings", "设置", "settings"],
    ]
      .map(
        ([id, label, i]) =>
          `<button data-page="${id}" class="${page === id ? "active" : ""}" ${page === id ? 'aria-current="page"' : ""}>${icon(i)}<span>${label}</span></button>`,
      )
      .join("");
  $("#nav")
    .querySelectorAll("button")
    .forEach((button) => {
      button.classList.toggle("active", button.dataset.page === page);
      if (button.dataset.page === page)
        button.setAttribute("aria-current", "page");
      else button.removeAttribute("aria-current");
    });
  if (page === "player") {
    renderPlayer();
    updateMini();
    return;
  }
  $("#top").className = "";
  $("#top").innerHTML = hiddenScope
    ? `<h1>隐藏空间</h1>${button("hidden-lock", "锁定并退出", "lock")}`
    : `<h1>${page === "library" ? '<span class="brand-mark">' + icon("headphones") + "</span>" : ""}${{ library: "作品库", favorites: "收藏", settings: "设置" }[page]}</h1>${["library", "favorites"].includes(page) ? `<button class="sticker-btn button import-top" data-action="import" ${scanning ? "disabled" : ""}>${icon("plus")}导入</button>` : ""}`;
  if (["library", "favorites", "hidden"].includes(page)) {
    $("#main").innerHTML =
      `${hiddenScope ? hiddenSearch() : ""}<div class="search">${icon("search")}<input id="search" type="search" placeholder="搜索作品、系列" aria-label="搜索作品" value="${esc(query)}"></div>${hiddenScope ? hiddenFilters() : ""}${scanning ? '<p class="scan-status"><span class="busy"></span>正在读取音频和字幕</p>' : ""}<div id="library-content"></div>`;
    renderCards();
  } else renderSettings();
  updateMini();
}
function renderCards() {
  const list = db.projects.filter(
    (p) =>
      (!(page === "favorites" || (hiddenScope && hiddenFavorites)) || p.favorite) &&
      [p.name, p.artist, p.category]
        .join(" ")
        .toLowerCase()
        .includes(query.toLowerCase()),
  );
  $("#library-content").className = list.length ? "library-grid" : "";
  $("#library-content").innerHTML = list.length
    ? list
        .map(
          (p) =>
            `<article class="card"><button class="card-open" data-project="${p.id}">${artwork(p)}<div class="card-copy"><h3>${esc(p.name)}</h3>${p.category ? `<p class="card-series">${esc(p.category)}</p>` : ""}<p class="card-meta">${p.tracks.length} 曲 · ${p.tracks.filter((t) => t.subtitles.length).length ? "字幕 " + p.tracks.filter((t) => t.subtitles.length).length : "无字幕"}</p></div></button></article>`,
        )
        .join("")
    : `<div class="empty sticker-panel ${page === "favorites" ? "t1" : "t2"}">${icon(page === "favorites" || (hiddenScope && hiddenFavorites) ? "heart" : hiddenScope ? "lock" : "library")}<h2 class="sticker-tag">${query ? "没有匹配的作品" : page === "favorites" || (hiddenScope && hiddenFavorites) ? "暂无收藏" : hiddenScope ? "暂无隐藏作品" : "尚未导入作品"}</h2>${!query && page === "library" ? '<button class="sticker-btn button" data-action="import">添加文件夹</button>' : ""}</div>`;
}
function renderPlayer() {
  const p = project(pid),
    t = selected();
  if (!p || !t) {
    navigate(hiddenScope ? "hidden" : "library");
    return;
  }
  $("#top").className = "player-top";
  $("#top").innerHTML =
    `${button("back", "返回" + ({ library: "作品库", favorites: "收藏", settings: "设置", hidden: "隐藏空间" }[previousPage] || "作品库"), "down")}<div class="top-work">${esc(p.name)}</div>${button("work-menu", "作品选项", "more")}`;
  $("#main").innerHTML =
    `<div class="track-heading"><h1>${esc(t.title)}</h1><div class="track-meta"><span>${p.tracks.indexOf(t) + 1} / ${p.tracks.length}${t.folder ? " · " + esc(t.folder.replace(/\/$/, "")) : ""}</span><span id="track-status"></span></div></div><div class="reader-tabs" role="group" aria-label="播放页内容"><button class="sticker-btn" data-reader="captions" aria-pressed="${readerTab === "captions"}">字幕</button><button class="sticker-btn" data-reader="cover" aria-pressed="${readerTab === "cover"}">封面</button><button class="sticker-btn favorite" data-action="favorite" aria-label="收藏作品" aria-pressed="${!!p.favorite}">${icon("heart")}</button></div><p class="player-error" id="play-error" hidden></p><div id="reader"></div><div class="playback-panel"><div class="timeline"><div class="times"><span id="position">00:00</span><span id="duration">${fmt(t.duration)}</span></div><input id="seek" type="range" min="0" max="${t.duration || 1}" value="0" step="100" aria-label="播放进度"></div><div class="transport">${button("previous", "上一曲", "previous", p.tracks.indexOf(t) === 0 ? "disabled" : "")}<button class="sticker-btn icon-button skip-small" data-action="rewind" aria-label="后退10秒">${icon("back")}<span>10</span></button><button class="sticker-btn icon-button play" id="play" data-action="play" aria-label="播放">${icon("play")}</button><button class="sticker-btn icon-button skip-small" data-action="forward" aria-label="前进10秒">${icon("forward")}<span>10</span></button>${button("next", "下一曲", "next", p.tracks.indexOf(t) === p.tracks.length - 1 ? "disabled" : "")}</div><div class="player-tools"><button data-action="options">${icon("settings")}<span>选项</span></button><button class="tracks-trigger" data-action="tracks">${icon("library")}曲目</button></div></div>`;
  $("#reader").setAttribute(
    "aria-label",
    "字幕区，轻点显示或隐藏播放控件，长按字幕跳转",
  );
  bindReaderGestures($("#reader"));
  renderReader();
  updatePlayer();
}
async function loadSubtitles(chosen) {
  const t = selected();
  if (!t) return;
  const token = ++subToken;
  const p = pid,
    id = tid;
  subId = chosen ?? t.selectedSub ?? "";
  if (!subId) subId = t.subtitles[0]?.id || "off";
  cues = [];
  subError = "";
  activeCue = -1;
  renderReader();
  if (subId === "off") {
    setCaptionFocus(false);
    return;
  }
  const sub = t.subtitles.find((s) => s.id === subId) || t.subtitles[0];
  if (!sub) {
    setCaptionFocus(false);
    return;
  }
  subId = sub.id;
  try {
    const payload = await call("subtitles", {
      projectId: p,
      trackId: id,
      selected: sub.id,
    });
    if (token !== subToken) return;
    const text = typeof payload === "string" ? payload : payload.content;
    cues = Array.isArray(payload.cues)
      ? payload.cues.filter(
          (c) =>
            Number.isFinite(c.start) &&
            Number.isFinite(c.end) &&
            c.end > c.start &&
            c.text,
        )
      : parse(text, text.trimStart().startsWith("WEBVTT") ? "vtt" : sub.format);
    if (!cues.length) throw new Error("字幕没有可用时间轴");
    renderReader();
    updatePlayer();
  } catch (e) {
    if (token === subToken) {
      setCaptionFocus(false);
      subError = e.message;
      notice(e.message, true);
      renderReader();
    }
  }
}
function renderReader() {
  if (!$("#reader")) return;
  const p = project(pid);
  if (!p) return;
  document
    .querySelectorAll("[data-reader]")
    .forEach((b) =>
      b.setAttribute("aria-pressed", String(b.dataset.reader === readerTab)),
    );
  if (readerTab === "captions" && subError) {
    $("#reader").innerHTML =
      '<div class="empty reader-empty"><h2 class="sticker-tag">字幕读取失败</h2><button class="sticker-btn button" data-action="retry-subs">重新载入</button></div>';
    return;
  }
  if (readerTab === "captions" && cues.length)
    $("#reader").innerHTML = cues
      .map(
        (c, i) =>
          `<button class="cue" data-cue="${i}"><time>${fmt(c.start * 1000)}</time><span>${esc(c.text)}${c.original && c.original !== c.text ? `<small class="cue-original">${esc(c.original)}</small>` : ""}</span></button>`,
      )
      .join("");
  else
    $("#reader").innerHTML =
      `<div class="art-stage">${artwork(p, "")} ${readerTab === "captions" ? `<p>${subId === "off" && selected()?.subtitles.length ? "字幕已关闭" : selected()?.subtitles.length ? "正在读取字幕…" : "此曲目无字幕"}</p>${!selected()?.subtitles.length ? '<button class="sticker-btn primary" data-ai="generate">生成中文字幕</button>' : ""}` : ""}</div>`;
  activeCue = -1;
}
function updatePlayer() {
  if (page !== "player" || !$("#play")) return;
  const playing = same() && state.playing;
  $("#play").innerHTML = icon(playing ? "pause" : "play");
  $("#play").setAttribute("aria-label", playing ? "暂停" : "播放");
  $("#track-status").textContent = same()
    ? state.error
      ? "播放失败"
      : state.buffering
        ? state.preparingAudio
          ? "准备兼容音频"
          : "正在载入"
        : state.ended
          ? "已结束"
          : playing
            ? "播放中"
            : "已暂停"
    : "待播放";
  const t = selected();
  if (!t) return;
  const duration = (same() ? state.duration : 0) || t.duration || 0,
    actualPosition = same()
      ? state.position
      : project(pid)?.resume?.trackId === tid
        ? project(pid).resume.position
        : 0,
    pos = dragging
      ? Number($("#seek").value)
      : (localSeekPosition() ?? actualPosition);
  if (!dragging) {
    $("#seek").max = duration || 1;
    $("#seek").value = pos;
    $("#position").textContent = fmt(pos);
  }
  $("#duration").textContent = fmt(duration);
  $("#seek").disabled = !duration;
  $("#play-error").hidden = !(same() && state.error);
  $("#play-error").textContent = same() ? state.error || "" : "";
  if (same() || localSeekPosition() !== null || dragging)
    updateCaptions(
      pos,
      localSeekPosition() !== null && pendingSeek.alignCaption,
    );
}
function updateCaptions(position, force = false) {
  if (readerTab === "captions" && cues.length && $("#reader")) {
    const time = position / 1000 - (selected()?.delay || 0);
    let n = -1,
      next = -1;
    for (let i = 0; i < cues.length; i++) {
      if (cues[i].start <= time && cues[i].end > time) n = i;
      if (cues[i].start > time) {
        next = i;
        break;
      }
    }
    if (n !== activeCue || force) {
      $(".cue.current")?.classList.remove("current");
      const el = $(`[data-cue="${n}"]`);
      el?.classList.add("current");
      const destination =
        el ||
        (force ? $(`[data-cue="${next < 0 ? cues.length - 1 : next}"]`) : null);
      if (destination && (follow || force)) {
        const box = $("#reader");
        box.scrollTo({
          top:
            box.scrollTop +
            destination.getBoundingClientRect().top -
            box.getBoundingClientRect().top -
            box.clientTop -
            (destination.clientHeight <= box.clientHeight
              ? box.clientHeight / 2 - destination.clientHeight / 2
              : 0),
          behavior: force || StickerUI.reducedMotion() ? "instant" : "smooth",
        });
      }
      activeCue = n;
      if (force && localSeekPosition() !== null)
        pendingSeek.alignCaption = false;
    }
  }
}
function updateMini() {
  const p = project(state.projectId),
    t = track(state.projectId, state.trackId);
  $("#mini").hidden = page === "player" || !p || !t;
  if ($("#mini").hidden) {
    if (!p || !t) {
      $("#mini").replaceChildren();
      delete $("#mini").dataset.track;
    }
    return;
  }
  const key = JSON.stringify([p.id, t.id, p.name, t.title, p.cover]);
  if ($("#mini").dataset.track !== key || !$("#mini-open")) {
    $("#mini").dataset.track = key;
    $("#mini").innerHTML =
      `<button id="mini-open" data-action="now">${artwork(p, "mini-art")}<span class="mini-text"><strong>${esc(t.title)}</strong><small>${esc(p.name)}</small></span></button><button id="mini-toggle" class="sticker-btn icon-button" data-action="toggle"></button>${button("next-global", "下一曲", "next")}`;
  }
  const label = state.playing ? "暂停" : "播放";
  if ($("#mini-toggle").getAttribute("aria-label") !== label) {
    $("#mini-toggle").setAttribute("aria-label", label);
    $("#mini-toggle").innerHTML = icon(state.playing ? "pause" : "play");
  }
}
function settingRow(label, kind, value, symbol) {
  return (
    '<button class="setting-row" data-choice="' +
    kind +
    '"><span class="setting-label">' +
    (symbol ? icon(symbol) : "") +
    esc(label) +
    '</span><span class="setting-end"><span data-setting-value="' +
    kind +
    '">' +
    esc(value) +
    "</span>" +
    icon("forward") +
    "</span></button>"
  );
}
const repeatName = (value) =>
  ({ 0: "顺序播放", 1: "单曲循环", 2: "列表循环" })[value] || "顺序播放";
function renderSettings() {
  const size = db.captionSize || 20;
  $("#main").innerHTML =
    '<div class="settings-page"><section class="settings-section sticker-panel"><h2 class="sticker-tag">外观</h2><div class="settings-card"><div class="setting-block"><h3>界面主题</h3><div class="segmented" role="group" aria-label="界面主题">' +
    [
      ["light", "浅色"],
      ["dark", "深色"],
    ]
      .map(
        ([value, label]) =>
          '<button class="sticker-btn" data-preference="theme" data-value="' +
          value +
          '" aria-pressed="' +
          (db.theme === value) +
          '">' +
          label +
          "</button>",
      )
      .join("") +
    '</div></div><div class="setting-block"><h3>字幕大小</h3><div class="segmented" role="group" aria-label="字幕大小">' +
    [
      [20, "标准"],
      [22, "大"],
      [24, "更大"],
    ]
      .map(
        ([value, label]) =>
          '<button class="sticker-btn" data-preference="caption-size" data-value="' +
          value +
          '" aria-pressed="' +
          (size === value) +
          '">' +
          label +
          "</button>",
      )
      .join("") +
    '</div></div><button class="setting-row" data-action="click-sounds" role="switch" aria-checked="' +
    (db.clickSounds !== false) +
    '"><span class="setting-label">点击音效</span><span class="sticker-switch" aria-hidden="true"></span></button></div></section><section class="settings-section sticker-panel t1"><h2 class="sticker-tag">播放</h2><div class="settings-card">' +
    settingRow("播放速度", "speed", (db.speed || state.speed || 1) + "×") +
    settingRow("循环方式", "repeat", repeatName(db.repeat ?? state.repeat)) +
    '</div></section><section class="settings-section sticker-panel t2"><h2 class="sticker-tag">资料库</h2><div class="settings-card"><button class="setting-row" data-action="import-project"><span class="setting-label">' +
    icon("folder") +
    "添加一部作品</span>" +
    icon("plus") +
    '</button><button class="setting-row" data-action="import-library"><span class="setting-label">' +
    icon("library") +
    "扫描整个资料库</span>" +
    icon("forward") +
    '</button><button class="setting-row" data-ai="import-files"><span class="setting-label">音频与作品包</span>' +
    icon("plus") +
    '</button><button class="setting-row" data-action="hidden-open"><span class="setting-label">' +
    icon("lock") +
    "隐藏空间</span>" + icon("forward") + "</button></div></section>" +
    subtitleSettingsSection() +
    '<div class="version"><p>MIMI 耳边 · Android 1.3.0</p><button class="sticker-btn quiet" data-ai="licenses">开源组件</button></div></div>';
}
function refreshSettings() {
  if (!$(".settings-page")) {
    renderSettings();
    return;
  }
  document
    .querySelectorAll('[data-preference="theme"]')
    .forEach((button) =>
      button.setAttribute(
        "aria-pressed",
        String(button.dataset.value === db.theme),
      ),
    );
  document
    .querySelectorAll('[data-preference="caption-size"]')
    .forEach((button) =>
      button.setAttribute(
        "aria-pressed",
        String(Number(button.dataset.value) === (db.captionSize || 20)),
      ),
    );
  document
    .querySelector('[data-action="click-sounds"]')
    ?.setAttribute("aria-checked", String(db.clickSounds !== false));
  refreshOptionValues();
}
let choiceReturn = "";
function dismissSheet() {
  if (sheetType.startsWith("hidden-")) {
    void dismissHiddenSheet().catch((error) => notice(error.message, true));
    return;
  }
  if (subtitleSheetBack()) return;
  if (sheetType.startsWith("choose-") && choiceReturn === "options") options();
  else closeSheet();
}
function showChoice(kind, origin = page === "player" ? "options" : "settings") {
  choiceReturn = origin;
  let title = "",
    values = [],
    current = "",
    grid = false;
  if (kind === "speed") {
    title = "播放速度";
    values = [0.5, 0.75, 1, 1.25, 1.5, 2].map((n) => [String(n), n + "×"]);
    current = String(db.speed || state.speed || 1);
    grid = true;
  }
  if (kind === "repeat") {
    title = "循环方式";
    values = [
      ["0", "顺序播放"],
      ["2", "列表循环"],
      ["1", "单曲循环"],
    ];
    current = String(db.repeat ?? state.repeat ?? 0);
  }
  if (kind === "sleep") {
    title = "睡眠定时";
    values = [
      ["0", "关闭"],
      ...[10, 15, 30, 45, 60, 90].map((n) => [String(n), n + " 分钟"]),
    ];
    current = state.sleepUntil > Date.now() ? "active" : "0";
    grid = true;
  }
  if (kind === "subtitle") {
    title = "字幕版本";
    values = [
      ...(selected()?.subtitles || []).map((sub) => [
        sub.id,
        /\.mimi\.zh/.test(sub.name) ? "中文 · 自动生成" : sub.name,
      ]),
      ["off", "关闭字幕"],
    ];
    current = subId;
  }
  showSheet(
    "choose-" + kind,
    title,
    (kind === "sleep" && state.sleepUntil > Date.now()
      ? '<p class="choice-status">剩余 ' +
        Math.ceil((state.sleepUntil - Date.now()) / 60000) +
        " 分钟</p>"
      : "") +
      '<div class="choice-list ' +
      (grid ? "choice-grid" : "") +
      '" role="radiogroup" aria-label="' +
      title +
      '">' +
      values
        .map(
          ([value, label]) =>
            '<button class="sticker-choice" role="radio" data-choice-kind="' +
            kind +
            '" data-value="' +
            esc(value) +
            '" aria-checked="' +
            (value === current) +
            '">' +
            esc(label) +
            "</button>",
        )
        .join("") +
      "</div>",
  );
  $("#sheet .icon-button").innerHTML = icon(
    origin === "options" ? "back" : "close",
  );
  $("#sheet .icon-button").setAttribute(
    "aria-label",
    origin === "options" ? "返回播放选项" : "关闭",
  );
}
async function applyChoice(kind, value) {
  if (kind === "speed") {
    await call("speed", { value: Number(value) });
    db.speed = Number(value);
    state.speed = Number(value);
  }
  if (kind === "repeat") {
    await call("repeat", { value: Number(value) });
    db.repeat = Number(value);
    state.repeat = Number(value);
  }
  if (kind === "sleep") {
    await call("sleep", { minutes: Number(value) });
    state.sleepUntil = Number(value) ? Date.now() + Number(value) * 60000 : 0;
  }
  if (kind === "subtitle") {
    await call("subtitle-pref", {
      projectId: pid,
      trackId: tid,
      selected: value,
      delay: selected()?.delay || 0,
    });
    await loadSubtitles(value);
  }
  if (choiceReturn === "options") options();
  else {
    closeSheet();
    if (page === "settings") refreshSettings();
  }
}
function refreshOptionValues() {
  document
    .querySelectorAll('[data-setting-value="speed"]')
    .forEach((e) => (e.textContent = (db.speed || state.speed || 1) + "×"));
  document
    .querySelectorAll('[data-setting-value="repeat"]')
    .forEach((e) => (e.textContent = repeatName(db.repeat ?? state.repeat)));
  document
    .querySelectorAll('[data-setting-value="sleep"]')
    .forEach(
      (e) =>
        (e.textContent =
          state.sleepUntil > Date.now()
            ? "剩余 " +
              Math.ceil((state.sleepUntil - Date.now()) / 60000) +
              " 分钟"
            : "关闭"),
    );
  const ab = document.querySelector('[data-action="ab"]');
  if (ab)
    ab.textContent =
      state.abA < 0 || state.abA == null
        ? "设置起点"
        : state.abB < 0
          ? "设置终点"
          : "清除循环";
  const label = document.querySelector("#ab-position");
  if (label)
    label.textContent =
      state.abA >= 0
        ? fmt(state.abA) + (state.abB >= 0 ? " — " + fmt(state.abB) : "")
        : "";
}

function showSheet(type, title, content) {
  const previousHeight =
    $("#sheet").open && !$("#sheet").dataset.closing
      ? $("#sheet").getBoundingClientRect().height
      : 0;
  sheetType = type;
  $("#sheet").classList.toggle(
    "t1",
    [
      "options",
      "choose-speed",
      "choose-repeat",
      "choose-sleep",
      "choose-subtitle",
    ].includes(type),
  );
  $("#sheet").classList.toggle(
    "t2",
    ["import", "tracks", "work", "hidden-pin", "hidden-options"].includes(type),
  );
  $("#sheet-notice").hidden = true;
  $("#sheet-title").textContent = title;
  $("#sheet-body").innerHTML = content;
  $("#sheet .icon-button").innerHTML = icon("close");
  $("#sheet .icon-button").setAttribute("aria-label", "关闭");
  styleSubtitleSheetBack(type);
  if (!$("#sheet").open) $("#sheet").showModal();
  $("#sheet-body").scrollTop = 0;
  StickerUI.enterSheet($("#sheet"), previousHeight);
}
function closeSheet(immediate = false) {
  StickerUI.leaveSheet($("#sheet"), immediate);
  sheetType = "";
}
function renderQueue() {
  const p = project(pid);
  if (!p) return;
  const folders = [...new Set(p.tracks.map((t) => t.folder))];
  const children = [
    ...new Set(
      folders
        .filter((f) => f.startsWith(queueFolder) && f !== queueFolder)
        .map((f) => f.slice(queueFolder.length).split("/")[0])
        .filter(Boolean),
    ),
  ];
  const list = p.tracks.filter(
    (t) => queueMode === "all" || t.folder === queueFolder,
  );
  const content = `<div class="queue-tabs"><button data-mode="all" class="${queueMode === "all" ? "active" : ""}">全部曲目</button><button data-mode="folders" class="${queueMode === "folders" ? "active" : ""}">按文件夹</button></div>${queueMode === "folders" ? `<div class="folder-path"><button data-parent-folder>返回上级</button><span>${esc(queueFolder || "作品根目录")}</span></div>${children.map((f) => `<button class="folder-row" data-folder="${esc(queueFolder + f + "/")}">${icon("folder")}<span>${esc(f)}</span></button>`).join("")}` : ""}${list.map((t) => `<button class="track-row ${t.id === tid ? "current" : ""}" data-track="${t.id}" ${t.id === tid ? 'aria-current="true"' : ""}><span class="number">${p.tracks.indexOf(t) + 1}</span><span class="track-copy"><strong>${esc(t.title)}</strong><small>${esc(t.format)}${t.subtitles.length ? " · 字幕" : ""}${queueMode === "all" && t.folder ? " · " + esc(t.folder.replace(/\/$/, "")) : ""}</small></span><span class="track-duration">${fmt(t.duration)}</span></button>`).join("")}`;
  if (sheetType === "tracks" && $("#sheet").open) {
    $("#sheet-body").innerHTML = content;
    StickerUI.reveal($("#sheet-body"));
  } else showSheet("tracks", "曲目列表", content);
}
function options() {
  const t = selected();
  const subtitle = t?.subtitles.find((s) => s.id === subId);
  const subtitleName =
    subId === "off"
      ? "关闭"
      : subtitle
        ? /\.mimi\.zh/.test(subtitle.name)
          ? "中文 · 自动生成"
          : subtitle.format.toUpperCase()
        : "无字幕";
  const delay = t?.delay || 0;
  showSheet(
    "options",
    "播放选项",
    '<section class="options-section sticker-panel"><div class="settings-card">' +
      settingRow("播放速度", "speed", (db.speed || state.speed || 1) + "×") +
      settingRow("循环方式", "repeat", repeatName(db.repeat ?? state.repeat)) +
      settingRow(
        "睡眠定时",
        "sleep",
        state.sleepUntil > Date.now()
          ? "剩余 " +
              Math.ceil((state.sleepUntil - Date.now()) / 60000) +
              " 分钟"
          : "关闭",
      ) +
      '<button class="setting-row" data-ai="track-info"><span>曲目信息</span>' +
      icon("forward") +
      '</button></div></section><section class="options-section sticker-panel t1"><h3 class="sticker-tag">字幕</h3><div class="settings-card">' +
      settingRow("字幕版本", "subtitle", subtitleName) +
      '<button class="setting-row" data-ai="generate"><span>生成中文字幕</span>' +
      icon("forward") +
      '</button><button class="setting-row" data-ai="search" ' +
      (!cues.length ? "disabled" : "") +
      "><span>搜索字幕</span>" +
      icon("search") +
      "</button>" +
      '<button class="setting-row" data-action="follow" role="switch" aria-checked="' +
      follow +
      '"><span>自动跟随</span><span class="sticker-switch" aria-hidden="true"></span></button><div class="setting-block"><div class="setting-block-title"><span>字幕校时</span><span class="setting-value">' +
      (delay > 0
        ? "延后 " + delay + " 秒"
        : delay < 0
          ? "提前 " + Math.abs(delay) + " 秒"
          : "同步") +
      '</span></div><div class="calibration-actions"><button class="sticker-btn" data-delay="-0.5" ' +
      (!cues.length ? "disabled" : "") +
      '>提前 0.5 秒</button><button class="sticker-btn" data-delay="0.5" ' +
      (!cues.length ? "disabled" : "") +
      '>延后 0.5 秒</button><button class="sticker-btn quiet" data-delay="reset" ' +
      (!cues.length ? "disabled" : "") +
      '>重置</button></div></div></div></section><section class="options-section sticker-panel t2"><h3 class="sticker-tag">片段循环</h3><div class="settings-card"><div class="setting-row"><span id="ab-position">' +
      (state.abA >= 0
        ? fmt(state.abA) + (state.abB >= 0 ? " — " + fmt(state.abB) : "")
        : "") +
      '</span><button class="sticker-btn" data-action="ab" ' +
      (!same() ? "disabled" : "") +
      ">" +
      (state.abA < 0 || state.abA == null
        ? "设置起点"
        : state.abB < 0
          ? "设置终点"
          : "清除循环") +
      "</button></div></div></section>",
  );
}

async function startTrack(id, position) {
  const p = project(pid),
    t = p?.tracks.find((t) => t.id === id);
  if (!t) return;
  tid = t.id;
  await call("play", {
    projectId: pid,
    trackId: tid,
    position: position ?? (p.resume?.trackId === tid ? p.resume.position : 0),
  });
  renderPlayer();
  loadSubtitles();
}
document.addEventListener("click", async (event) => {
  if (
    page === "player" &&
    readerTab === "captions" &&
    cues.length &&
    event.target.closest("#reader") &&
    !event.target.closest("[data-action]")
  ) {
    setCaptionFocus(!captionFocus);
    return;
  }
  const el = event.target.closest("button");
  if (!el || el.disabled) return;
  try {
    if (el.dataset.choiceKind) {
      el.disabled = true;
      try {
        await applyChoice(el.dataset.choiceKind, el.dataset.value);
      } finally {
        el.disabled = false;
      }
      return;
    }
    if (el.dataset.preference) {
      await call(el.dataset.preference, {
        value:
          el.dataset.preference === "caption-size"
            ? Number(el.dataset.value)
            : el.dataset.value,
      });
      return;
    }
    if (el.dataset.choice) {
      showChoice(el.dataset.choice);
      return;
    }
    if (el.dataset.page) {
      navigate(el.dataset.page);
      return;
    }
    if (el.dataset.project) {
      openProject(el.dataset.project);
      return;
    }
    if (el.dataset.reader) {
      readerTab = el.dataset.reader;
      renderReader();
      updatePlayer();
      StickerUI.reveal($("#reader"));
      return;
    }
    if (el.dataset.track) {
      closeSheet();
      await startTrack(el.dataset.track, 0);
      return;
    }
    if (el.dataset.mode) {
      queueMode = el.dataset.mode;
      queueFolder = "";
      renderQueue();
      return;
    }
    if (el.dataset.folder) {
      queueFolder = el.dataset.folder;
      renderQueue();
      return;
    }
    if (el.hasAttribute("data-parent-folder")) {
      queueFolder = queueFolder.replace(/[^/]+\/$/, "");
      renderQueue();
      return;
    }

    if (el.dataset.delay) {
      const t = selected(),
        value =
          el.dataset.delay === "reset"
            ? 0
            : Math.round(((t.delay || 0) + Number(el.dataset.delay)) * 10) / 10;
      await call("subtitle-pref", {
        projectId: pid,
        trackId: tid,
        selected: subId,
        delay: value,
      });
      if (selected()) selected().delay = value;
      options();
      return;
    }
    switch (el.dataset.action) {
      case "import":
        showSheet(
          "import",
          "导入作品",
          `<div class="sheet-actions"><button class="sticker-btn button wide" data-action="import-project">${icon("folder")}添加一部作品</button><button class="sticker-btn button secondary wide" data-action="import-library">${icon("library")}扫描整个资料库</button><button class="sticker-btn button secondary wide" data-ai="import-files">${icon("plus")}音频 / ZIP / 7z / RAR</button></div>`,
        );
        break;
      case "import-project":
      case "import-library":
        await call("import", {
          mode: el.dataset.action === "import-project" ? "project" : "library",
        });
        closeSheet();
        break;
      case "back":
        navigate(previousPage);
        break;
      case "now":
        openProject(state.projectId, state.trackId);
        break;
      case "close-sheet":
        dismissSheet();
        break;
      case "play":
        if (same()) await call("toggle");
        else await startTrack(tid);
        break;
      case "toggle":
        await call("toggle");
        break;
      case "next-global":
        await call("next");
        break;
      case "previous":
      case "next": {
        const p = project(pid),
          index =
            p.tracks.findIndex((t) => t.id === tid) +
            (el.dataset.action === "next" ? 1 : -1);
        if (p.tracks[index]) await startTrack(p.tracks[index].id, 0);
        break;
      }
      case "rewind":
      case "forward": {
        const delta = el.dataset.action === "rewind" ? -10000 : 10000;
        const pos = Math.max(
          0,
          Math.min(
            (same() ? state.position : 0) + delta,
            (same() ? state.duration : selected()?.duration) || Infinity,
          ),
        );
        await seekTo(pos);
        break;
      }
      case "retry-subs":
        await loadSubtitles();
        break;
      case "follow":
        follow = !follow;
        activeCue = -1;
        updatePlayer();
        el.setAttribute("aria-checked", String(follow));
        break;
      case "click-sounds":
        await call("click-sounds", { value: db.clickSounds === false });
        break;
      case "favorite":
        await call("favorite", { projectId: pid });
        break;
      case "tracks":
        queueMode = "all";
        queueFolder = "";
        renderQueue();
        break;
      case "options":
        options();
        break;

      case "cancel-sleep":
        await call("sleep", { minutes: 0 });
        options();
        break;
      case "ab":
        await call("ab");
        options();
        break;
      case "hidden-open":
        await openHiddenPin();
        break;
      case "hidden-move":
        await openHiddenPin(pid);
        break;
      case "hidden-restore":
        await call("hidden-restore", { projectId: pid });
        break;
      case "hidden-lock":
        await call("hidden-lock");
        break;
      case "hidden-all":
      case "hidden-favorites":
        filterHiddenFavorites(el.dataset.action === "hidden-favorites");
        break;
      case "hidden-options":
        showHiddenOptions(await call("hidden-options"));
        break;
      case "hidden-change-pin":
        showHiddenPin(await call("hidden-options"), "change");
        break;
      case "hidden-fingerprint": {
        const config = await call("hidden-options");
        if (config.biometricEnabled) showHiddenOptions(await call("hidden-biometric-disable"));
        else showHiddenPin(config, "enable");
        break;
      }
      case "hidden-use-biometric":
        await requestHiddenAuthentication("hidden-biometric-unlock");
        break;
      case "work-menu":
        showSheet(
          "work",
          "作品选项",
          `<div class="sheet-actions"><button class="sticker-btn button secondary wide" data-action="refresh">${icon("folder")}刷新文件夹</button><button class="sticker-btn button secondary wide" data-ai="generate-project">${icon("subtitle")}补齐中文字幕</button>${hiddenScope ? `<button class="sticker-btn button secondary wide" data-action="hidden-restore">${icon("library")}移回作品库</button>` : `<button class="sticker-btn button secondary wide" data-action="hidden-move">${icon("lock")}移入隐藏空间</button>`}${project(pid)?.importIssues?.length ? `<button class="sticker-btn button wide" data-ai="import-issues">导入提示 · ${project(pid).importIssues.length} 项</button>` : ""}<button class="sticker-btn button secondary wide" data-action="remove-confirm">${icon("trash")}移除作品</button></div>`,
        );
        break;
      case "refresh":
        await call("refresh", { projectId: pid });
        closeSheet();
        break;
      case "remove-confirm":
        showSheet(
          "remove",
          "移除作品",
          `<div class="work-summary sticker-panel">${artwork(project(pid), "summary-art")}<p>${esc(project(pid)?.name)}</p></div><div class="confirm-actions"><button class="sticker-btn" data-action="close-sheet">取消</button><button class="sticker-btn danger" data-action="remove">移除作品</button></div>`,
        );
        break;
      case "remove":
        await call("remove", { projectId: pid });
        navigate(hiddenScope ? "hidden" : "library");
        break;
    }
  } catch (e) {
    notice(e.message, true);
  }
});
document.addEventListener("input", (e) => {
  if (e.target.id === "search") {
    query = e.target.value;
    renderCards();
  }
  if (e.target.id === "seek") {
    dragging = true;
    $("#position").textContent = fmt(Number(e.target.value));
    updateCaptions(Number(e.target.value), true);
  }
});
document.addEventListener("change", async (e) => {
  try {
    const value = e.target.value;
    switch (e.target.id) {
      case "seek": {
        let pos = Number(value);
        dragging = false;
        await seekTo(pos);
        break;
      }
    }
  } catch (error) {
    dragging = false;
    notice(error.message, true);
  }
});
$("#sheet").addEventListener("click", (e) => {
  if (e.target === $("#sheet")) {
    const r = e.target.getBoundingClientRect();
    if (e.clientY < r.top) dismissSheet();
  }
});
$("#sheet").addEventListener("cancel", (event) => {
  event.preventDefault();
  dismissSheet();
});
let touchY = 0;
$(".sheet-head").addEventListener(
  "touchstart",
  (e) => (touchY = e.touches[0].clientY),
  { passive: true },
);
$(".sheet-head").addEventListener(
  "touchend",
  (e) => {
    if (e.changedTouches[0].clientY - touchY > 60) dismissSheet();
  },
  { passive: true },
);
window.handleBack = () => {
  if ($("#sheet").open) {
    dismissSheet();
    return true;
  }
  if (captionFocus) {
    setCaptionFocus(false);
    return true;
  }
  if (page === "player") {
    navigate(previousPage);
    return true;
  }
  if (hiddenScope) {
    void call("hidden-lock").catch((error) => notice(error.message, true));
    return true;
  }
  if (page !== "library") {
    navigate("library");
    return true;
  }
  return false;
};
window.addEventListener("resize", () => {
  activeCue = -1;
  updatePlayer();
});
render();
StickerUI.onActivate((button) => {
  if (
    state.playing ||
    db.clickSounds === false ||
    ["play", "toggle", "click-sounds"].includes(button.dataset.action)
  )
    return;
  const kind =
    button.dataset.page ||
    button.dataset.project ||
    button.dataset.choice ||
    ["options", "tracks", "import"].includes(button.dataset.action)
      ? "open"
      : "tap";
  window.Native?.request(
    JSON.stringify({ action: "feedback", payload: { kind }, id: 0 }),
  );
});

setInterval(refreshOptionValues, 1000);
