"use strict";
const $ = (id) => document.getElementById(id);
const S = { sys: null, set: {}, src: null, t: 0, cx: 0.5, cy: 0.5, plan: null, logN: 0, liveN: -1, poll: null, prepPoll: null };

const TOKEN = document.querySelector('meta[name="hdr-token"]').content;
async function api(name, body) {
  const r = await fetch("/api/" + name, { method: "POST", headers: { "Content-Type": "application/json", "X-HDR-Token": TOKEN }, body: JSON.stringify(body || {}) });
  const j = await r.json().catch(() => ({ error: "رد غير صالح" }));
  if (!r.ok || j.error) throw new Error(j.error || r.statusText);
  return j;
}
let toastT;
function toast(msg) {
  const t = $("toast");
  t.textContent = msg; t.classList.remove("hidden");
  clearTimeout(toastT); toastT = setTimeout(() => t.classList.add("hidden"), 6000);
}
function dur(s) {
  if (s == null || !isFinite(s)) return "—";
  s = Math.round(s);
  const h = Math.floor(s / 3600), m = Math.floor((s % 3600) / 60), x = s % 60;
  return (h ? h + ":" + String(m).padStart(2, "0") : m) + ":" + String(x).padStart(2, "0");
}
// Settings survive a restart; the file-specific ones (paths, trim) do not.
const STORE = "hdrstudio.settings.v1", TRANSIENT = ["input", "output", "start", "duration"];
function store(k, v) { try { localStorage.setItem(k, JSON.stringify(v)); } catch (e) { /* storage unavailable */ } }
function load(k, d) { try { const v = JSON.parse(localStorage.getItem(k)); return v == null ? d : v; } catch (e) { return d; } }
function saveSettings() { const o = { ...S.set }; TRANSIENT.forEach((k) => delete o[k]); store(STORE, o); }
function savedSettings() {
  const o = load(STORE, {}), d = S.sys.defaults, out = {};
  for (const k of Object.keys(d)) if (!TRANSIENT.includes(k) && typeof o[k] === typeof d[k]) out[k] = o[k];
  if (!S.sys.presets[out.preset]) delete out.preset;
  if (!S.sys.quality[out.quality]) delete out.quality;
  return out;
}
// One-click starting points; every value stays editable afterwards.
const LOOKS = [
  { id: "natural", label: "طبيعي", note: "متوازن: تنظيف خفيف وألوان المصدر مع توسيع HDR معتدل.", set: {} },
  { id: "crisp", label: "تفاصيل أوضح", note: "حدة وقوام أكثر وتنظيف أقل. للمصادر النظيفة.",
    set: { sharpen: 0.5, ai_amount: 0.8, ai_texture: 1.0, denoise_luma: 0.2, grain_keep: 0.35 } },
  { id: "rich", label: "ألوان أغنى", note: "حيوية أعلى بلا تشبّع مبالغ، وإضاءات HDR أقوى.",
    set: { vibrance: 0.25, expand: 0.9 } },
  { id: "clean", label: "نظيف ناعم", note: "تنظيف أقوى للمقاطع المضغوطة أو الليلية.",
    set: { denoise_luma: 0.5, denoise_chroma: 0.7, grain_keep: 0.1, sharpen: 0.25, ai_texture: 0.4 } },
];
const LOOK_KEYS = ["sharpen", "ai_amount", "ai_texture", "denoise_luma", "denoise_chroma", "grain_keep", "vibrance", "expand"];
const fmt = (v) => (+v).toFixed(2).replace(/\.?0+$/, "") || "0";
function lookValues(l) { const o = {}; LOOK_KEYS.forEach((k) => { o[k] = k in l.set ? l.set[k] : S.sys.defaults[k]; }); return o; }
function currentLook() { return LOOKS.find((l) => { const v = lookValues(l); return LOOK_KEYS.every((k) => Math.abs(S.set[k] - v[k]) < 1e-6); }); }
function syncControls() {
  document.querySelectorAll("[data-k]").forEach((el) => {
    const k = el.dataset.k, v = S.set[k];
    if (el.classList.contains("slider")) { el.querySelector("input").value = v; el.querySelector("output").textContent = fmt(v); }
    else if (el.type === "checkbox") el.checked = !!v;
    else el.value = String(v);
  });
  $("resolution").value = S.set.resolution;
}
function recentAdd(path) { store("hdrstudio.recent", [path, ...load("hdrstudio.recent", []).filter((p) => p !== path)].slice(0, 5)); }
function renderRecent() {
  const list = load("hdrstudio.recent", []).filter((p) => typeof p === "string"), el = $("recentList");
  el.textContent = ""; $("recent").classList.toggle("hidden", !list.length);
  for (const p of list) {
    const b = document.createElement("button");
    b.className = "recent-i"; b.dir = "ltr"; b.title = p; b.textContent = p.split(/[\\/]/).pop();
    b.onclick = () => openSource(p); el.appendChild(b);
  }
}

const debounce = (fn, ms) => { let t; return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); }; };

// ------------------------------------------------------------------ settings panel
function buildPanel() {
  const pc = $("presetCards");
  for (const [id, p] of Object.entries(S.sys.presets)) {
    const b = document.createElement("button");
    b.className = "card"; b.dataset.id = id;
    b.innerHTML = `<b></b><span></span>`;
    b.children[0].textContent = p.label; b.children[1].textContent = p.note;
    b.onclick = () => { S.set.preset = id; changed(); };
    pc.appendChild(b);
  }
  const lk = $("looks");
  for (const l of LOOKS) {
    const b = document.createElement("button");
    b.className = "look"; b.dataset.id = l.id; b.textContent = l.label; b.title = l.note;
    b.onclick = () => { Object.assign(S.set, lookValues(l)); syncControls(); changed(); };
    lk.appendChild(b);
  }
  $("resetBtn").onclick = () => {
    const keep = {}; TRANSIENT.forEach((k) => { keep[k] = S.set[k]; });
    S.set = { ...S.sys.defaults, ...keep }; syncControls(); changed();
  };
  const showTab = (id) => {
    document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("on", t.dataset.tab === id));
    document.querySelectorAll(".tabpage").forEach((p) => p.classList.toggle("hidden", p.dataset.page !== id));
    $("panel").scrollTop = 0; store("hdrstudio.tab", id);
  };
  document.querySelectorAll(".tab").forEach((t) => { t.onclick = () => showTab(t.dataset.tab); });
  if (load("hdrstudio.tab", "basic") === "adv") showTab("adv");
  document.querySelectorAll("details[data-sec]").forEach((d) => {
    const key = "hdrstudio.sec." + d.dataset.sec, v = load(key, null);
    if (v !== null) d.open = !!v;
    d.ontoggle = () => store(key, d.open);
  });
  const tr = $("tiers");
  for (const [id, q] of Object.entries(S.sys.quality)) {
    const b = document.createElement("button");
    b.className = "tier"; b.dataset.id = id; b.textContent = q.label;
    b.onclick = () => { S.set.quality = id; changed(); };
    tr.appendChild(b);
  }
  document.querySelectorAll(".slider").forEach((el) => {
    const k = el.dataset.k;
    el.innerHTML = `<div class="sl-h"><span></span><output></output></div><input type="range" dir="ltr">`;
    el.querySelector("span").textContent = el.dataset.label;
    const inp = el.querySelector("input"), out = el.querySelector("output");
    inp.min = el.dataset.min; inp.max = el.dataset.max; inp.step = el.dataset.step;
    inp.value = S.set[k]; out.textContent = (+S.set[k]).toFixed(2).replace(/\.?0+$/, "") || "0";
    inp.oninput = () => { S.set[k] = +inp.value; out.textContent = (+inp.value).toFixed(2).replace(/\.?0+$/, "") || "0"; changed(); };
  });
  document.querySelectorAll("[data-k]").forEach((el) => {
    const k = el.dataset.k;
    if (el.classList.contains("slider")) return;
    if (el.type === "checkbox") { el.checked = !!S.set[k]; el.onchange = () => { S.set[k] = el.checked; changed(); }; }
    else if (el.tagName === "SELECT") { el.value = String(S.set[k]); el.onchange = () => { S.set[k] = el.dataset.num ? +el.value : el.value; changed(); }; }
    else { el.value = S.set[k]; el.onchange = () => { S.set[k] = Math.max(0, +el.value || 0); el.value = S.set[k]; changed(); }; }
  });
  $("resolution").value = S.set.resolution;
  $("resolution").onchange = () => { S.set.resolution = $("resolution").value; changed(); };
}

function reflect() {
  document.querySelectorAll(".card").forEach((c) => c.classList.toggle("on", c.dataset.id === S.set.preset));
  document.querySelectorAll(".tier").forEach((c) => c.classList.toggle("on", c.dataset.id === S.set.quality));
  const look = currentLook();
  document.querySelectorAll(".look").forEach((c) => c.classList.toggle("on", !!look && c.dataset.id === look.id));
  $("lookHint").textContent = look ? look.note : "إعدادات مخصّصة.";
  $("presetHint").textContent = S.sys.presets[S.set.preset].note;
  const hdrOut = S.sys.presets[S.set.preset].hdr, srcHdr = S.src && S.src.isHdr;
  $("hdrSec").classList.toggle("dis", !hdrOut || !!srcHdr);
  const q = S.sys.quality[S.set.quality], hint = $("tierHint");
  hint.textContent = "";
  if (q.model) {
    const m = S.sys.models.find((x) => x.id === q.model);
    const bench = S.sys.bench[q.model];
    if (!m.ready) {
      hint.textContent = m.needsDownload ? `${m.label} غير منزَّل (نحو 64MB من GitHub الرسمي).` : `${m.label} يحتاج تجهيزاً.`;
      const b = document.createElement("button");
      b.className = "btn small"; b.textContent = m.needsDownload ? "تنزيل وتجهيز" : "تجهيز";
      b.onclick = () => prepare(q.model);
      hint.appendChild(b);
    } else if (!bench) {
      hint.textContent = `${m.label} — ${m.note}. سيُختبر على جهازك عند أول استخدام.`;
    } else {
      hint.textContent = `${m.label} — ${m.note}. الخلفية: ${bench.best}`;
    }
  } else hint.textContent = "تحسين تقليدي فقط: تنظيف + حدة + HDR، وهو الأسرع.";
}

const replan = debounce(async () => {
  if (!S.src) return;
  try {
    S.plan = await api("plan", { settings: S.set });
    renderSummary(); drawRoi();
  } catch (e) { toast(e.message); }
}, 250);

function changed() {
  reflect(); saveSettings();
  if ($("imgAfter").src) $("stale").classList.remove("hidden");
  replan();
}

function renderSummary() {
  const p = S.plan, el = $("summary");
  if (!p) { el.textContent = ""; return; }
  const parts = [
    ["الناتج", `${p.outW}×${p.outH} · ${p.fps} fps`],
    ["الترميز", `${p.codec === "hevc" ? "HEVC" : "H.264"} ${p.hdr ? (p.trc === "hlg" ? "HLG" : "HDR10") : "SDR"} · CRF ${p.crf}`],
    ["الإطارات", p.frames],
  ];
  if (p.model) parts.push(["دخل AI", `${p.lrW}×${p.lrH}`]);
  if (p.interp) parts.push(["توليد الإطارات", "RIFE ×2"]);
  if (p.hdr10plus) parts.push(["بيانات ديناميكية", "HDR10+"]);
  if (p.estimate) {
    parts.push(["لكل إطار", `${p.estimate.secPerFrame} ث`]);
    parts.push(["الزمن المتوقع", dur(p.estimate.totalSec), p.estimate.totalSec > 3600 ? "long" : "est"]);
  } else if (p.model) parts.push(["الزمن المتوقع", "يُقاس عند أول معاينة"]);
  el.innerHTML = "";
  for (const [k, v, cls] of parts) {
    const s = document.createElement("div"), l = document.createElement("span"), b = document.createElement("b");
    l.textContent = k; b.textContent = v; s.append(l, b); s.className = "kv" + (cls ? " " + cls : ""); el.appendChild(s);
  }
  if (p.estimate && p.estimate.totalSec > 3600) {
    p.notes = [...(p.notes || []), "تصدير طويل: يمكن إيقافه واستئنافه، أو اختيار مستوى AI أسرع"];
  }
  $("notes").textContent = (p.notes || []).join(" · ");
  $("exportBtn").textContent = p.resumable ? "استئناف التصدير" : "تصدير";
}

async function prepare(model) {
  try { await api("prepare", { model }); } catch (e) { return toast(e.message); }
  clearInterval(S.prepPoll);
  S.prepPoll = setInterval(async () => {
    const st = await api("prepare_status", { model });
    const pct = st.total ? ` ${Math.round((st.got / st.total) * 100)}%` : "";
    $("tierHint").textContent = `${st.msg}${pct}…`;
    if (st.state !== "running") {
      clearInterval(S.prepPoll);
      if (st.state === "error") toast(st.msg);
      await refreshSystem(); changed();
    }
  }, 700);
}

// ------------------------------------------------------------------ source
async function openSource(path) {
  try {
    const info = await api("probe", { path });
    S.src = info; S.set.input = info.path; S.set.output = info.suggestedOutput;
    S.t = Math.min(info.duration * 0.25, Math.max(0, info.duration - 0.1)); S.cx = S.cy = 0.5;
    S.set.start = 0; S.set.duration = 0; syncControls(); recentAdd(info.path);
    $("empty").classList.add("hidden"); $("work").classList.remove("hidden");
    $("srcName").textContent = info.path.split(/[\\/]/).pop();
    const bits = [`${info.width}×${info.height}`, `${info.fps} fps${info.vfr ? " (متغير)" : ""}`, info.codec.toUpperCase() + " " + info.bitDepth + "-bit",
      info.transfer, dur(info.duration)];
    if (info.assumed.length) bits.push("مفترض: " + info.assumed.join("، "));
    const meta = $("srcMeta"); meta.textContent = "";
    bits.forEach((b, i) => { if (i) meta.append(" · "); const e = document.createElement("bdi"); e.textContent = b; meta.appendChild(e); });
    $("outPath").value = S.set.output;
    $("tSlider").max = Math.max(0.001, info.duration - 0.05); $("tSlider").value = S.t;
    clearPreview(); loadThumb(); changed();
  } catch (e) { toast(e.message); }
}
function loadThumb() {
  $("tLabel").textContent = dur(S.t) + "." + Math.floor((S.t % 1) * 10);
  $("thumb").src = `/api/thumb?path=${encodeURIComponent(S.src.path)}&t=${S.t.toFixed(3)}&k=${TOKEN}`;
}
function drawRoi() {
  const img = $("thumb"), roi = $("roi");
  if (!S.plan || !S.src || !img.clientWidth) { roi.style.display = "none"; return; }
  const f = S.plan.outW / S.src.width;
  const fw = Math.min(1, 448 / f / S.src.width), fh = Math.min(1, 448 / f / S.src.height);
  const fr = $("frame").getBoundingClientRect(), r = img.getBoundingClientRect();
  const x = Math.min(Math.max(S.cx - fw / 2, 0), 1 - fw), y = Math.min(Math.max(S.cy - fh / 2, 0), 1 - fh);
  roi.style.display = "block";
  roi.style.left = r.left - fr.left + x * r.width + "px"; roi.style.top = r.top - fr.top + y * r.height + "px";
  roi.style.width = fw * r.width + "px"; roi.style.height = fh * r.height + "px";
}
function clearPreview() {
  $("imgAfter").removeAttribute("src"); $("imgBefore").removeAttribute("src");
  $("compare").classList.remove("has"); $("cmpEmpty").classList.remove("hidden");
  $("prevInfo").textContent = ""; $("stale").classList.add("hidden");
}
async function runPreview() {
  if (!S.src) return;
  const q = S.sys.quality[S.set.quality];
  $("cmpSpin").classList.remove("hidden"); $("previewBtn").disabled = true;
  $("cmpSpinText").textContent = q.model && !S.sys.bench[q.model] ? "اختبار خلفيات AI على جهازك (مرة واحدة)…" : "معالجة…";
  try {
    const r = await api("preview", { settings: S.set, t: S.t, cx: S.cx, cy: S.cy });
    $("imgBefore").src = r.before; $("imgAfter").src = r.after;
    $("compare").classList.add("has"); $("cmpEmpty").classList.add("hidden"); $("stale").classList.add("hidden");
    setSplit(0.5);
    const e = r.estimate;
    $("prevInfo").textContent = `${r.size[0]}×${r.size[1]} · ${r.ms} ms` + (r.info.tiles ? ` · ${r.info.tiles} tiles` : "") +
      ` · σ ${(r.info.sigma * 255).toFixed(2)}/255` + (e.backend ? ` · ${e.backend}` : "");
    if (!S.sys.bench[q.model || ""]) { await refreshSystem(); changed(); }
  } catch (e) { toast(e.message); }
  $("cmpSpin").classList.add("hidden"); $("previewBtn").disabled = false;
}
function setSplit(f) {
  const c = $("compare"), w = c.clientWidth;
  f = Math.min(1, Math.max(0, f));
  $("imgBefore").style.clipPath = `inset(0 ${(1 - f) * 100}% 0 0)`;
  $("handle").style.left = f * w + "px";
}

// ------------------------------------------------------------------ export
async function startExport() {
  S.set.output = $("outPath").value.trim();
  try { await api("export", { settings: S.set }); } catch (e) { return toast(e.message); }
  $("overlay").classList.remove("hidden");
  $("jobTitle").textContent = "جارٍ التصدير";
  for (const id of ["report", "revealBtn", "closeBtn"]) $(id).classList.add("hidden");
  $("cancelBtn").classList.remove("hidden"); $("cancelBtn").disabled = false;
  $("log").textContent = ""; $("jobBar").style.width = "0";
  $("liveImg").removeAttribute("src"); $("liveEmpty").classList.remove("hidden"); S.liveN = -1;
  clearInterval(S.poll); S.poll = setInterval(pollStatus, 1000); pollStatus();
}
async function pollStatus() {
  let st;
  try { st = await api("status", { since: S.logN }); } catch (e) { return; }
  const log = $("log");
  for (const l of st.logs) { log.textContent += `${l.t}  ${l.msg}\n`; S.logN = l.n; }
  if (st.logs.length) log.scrollTop = log.scrollHeight;
  const j = st.job;
  if (!j) return;
  $("jobStage").textContent = j.stage || "";
  $("jobBar").style.width = (j.total ? Math.min(100, (j.frame / j.total) * 100) : 0) + "%";
  $("stFrame").textContent = `${j.frame}/${j.total || "?"}`;
  $("stFps").textContent = j.fps ? (j.fps >= 1 ? j.fps.toFixed(1) : j.fps.toFixed(3)) : "—";
  $("stElapsed").textContent = dur(j.elapsed); $("stEta").textContent = dur(j.eta);
  if (st.liveN !== S.liveN && st.liveN > 0) {
    S.liveN = st.liveN; $("liveImg").src = "/api/live?n=" + st.liveN + "&k=" + TOKEN; $("liveEmpty").classList.add("hidden");
  }
  if (["done", "error", "cancelled"].includes(j.state)) {
    clearInterval(S.poll);
    $("cancelBtn").classList.add("hidden"); $("closeBtn").classList.remove("hidden");
    $("jobTitle").textContent = { done: "اكتمل التصدير", error: "فشل التصدير", cancelled: "أُوقف التصدير" }[j.state];
    if (j.state === "done") $("jobBar").style.width = "100%";
    if (st.report) { renderReport(st.report); if (st.report.ok) $("revealBtn").classList.remove("hidden"); }
    replan();
  }
}
function renderReport(r) {
  const rows = [
    ["الحالة", r.ok ? "اجتاز كل الفحوص ✓" : "مرفوض: " + r.problems.join("؛ "), r.ok ? "ok" : "bad"],
    ["الفيديو", `${r.codec} ${r.profile || ""} · ${r.size} · ${r.pixFmt}`],
    ["الألوان", `${r.primaries} / ${r.transfer} / ${r.matrix} / ${r.range}`],
    ["الإطارات", `${r.frames} / ${r.expectedFrames}`],
    ["الصوت", r.audio || "لا يوجد"],
  ];
  if (r.maxCll != null) rows.push(["MaxCLL / MaxFALL (مقاس)", `${r.maxCll} / ${r.maxFall} nit`], ["شاشة المرجع (معلنة)", `P3-D65 · ${r.masterPeak} nit`]);
  rows.push(["الحجم", (r.bytes / 1048576).toFixed(1) + " MB"], ["الزمن", dur(r.seconds)]);
  const t = document.createElement("table");
  for (const [k, v, cls] of rows) {
    const tr = t.insertRow(); tr.insertCell().textContent = k;
    const c = tr.insertCell(); c.textContent = v; if (cls) c.className = cls;
  }
  const el = $("report"); el.innerHTML = ""; el.appendChild(t); el.classList.remove("hidden");
}

// ------------------------------------------------------------------ boot
async function refreshSystem() {
  S.sys = await api("system");
  const chips = $("chips"); chips.innerHTML = "";
  const add = (txt, bad) => { const c = document.createElement("span"); c.className = "chip" + (bad ? " bad" : ""); c.innerHTML = "<i></i>"; c.append(txt); chips.appendChild(c); };
  const gpu = S.sys.backends.find((b) => /GPU|NVIDIA/.test(b));
  add(gpu ? gpu.replace(/ · FP\d+$/, "").replace("OpenVINO ", "") : "CPU فقط", !gpu);
  add(S.sys.ffmpeg ? S.sys.ffmpeg.replace(/^ffmpeg version (\S+).*/, "FFmpeg $1") : "FFmpeg غير موجود", !S.sys.ffmpeg);
}
async function boot() {
  await refreshSystem();
  S.set = { ...S.sys.defaults };
  Object.assign(S.set, savedSettings());
  buildPanel(); reflect(); renderRecent();
  $("pickBtn").onclick = $("changeBtn").onclick = async () => { const r = await api("pick", { kind: "open" }); if (r.path) openSource(r.path); };
  $("pathForm").onsubmit = (e) => { e.preventDefault(); const p = $("pathInput").value.trim().replace(/^"|"$/g, ""); if (p) openSource(p); };
  $("outBtn").onclick = async () => { const r = await api("pick", { kind: "save", initial: $("outPath").value }); if (r.path) { $("outPath").value = r.path; S.set.output = r.path; replan(); } };
  $("outPath").onchange = () => { S.set.output = $("outPath").value.trim(); replan(); };
  const thumbLater = debounce(loadThumb, 180);
  $("tSlider").oninput = () => { S.t = +$("tSlider").value; $("tLabel").textContent = dur(S.t) + "." + Math.floor((S.t % 1) * 10); thumbLater(); if ($("imgAfter").src) $("stale").classList.remove("hidden"); };
  $("thumb").onload = drawRoi;
  window.addEventListener("resize", () => { drawRoi(); });
  $("frame").onclick = (e) => {
    const r = $("thumb").getBoundingClientRect();
    if (!r.width) return;
    S.cx = Math.min(1, Math.max(0, (e.clientX - r.left) / r.width)); S.cy = Math.min(1, Math.max(0, (e.clientY - r.top) / r.height));
    drawRoi(); if ($("imgAfter").src) $("stale").classList.remove("hidden");
  };
  $("previewBtn").onclick = runPreview;
  const cmp = $("compare");
  const move = (e) => { const r = cmp.getBoundingClientRect(); setSplit((e.clientX - r.left) / r.width); };
  cmp.onpointerdown = (e) => { if (!cmp.classList.contains("has")) return; cmp.setPointerCapture(e.pointerId); move(e); cmp.onpointermove = move; };
  cmp.onpointerup = cmp.onpointercancel = () => { cmp.onpointermove = null; };
  $("exportBtn").onclick = startExport;
  $("cancelBtn").onclick = async () => { $("cancelBtn").disabled = true; await api("cancel"); };
  $("closeBtn").onclick = () => $("overlay").classList.add("hidden");
  $("revealBtn").onclick = () => api("reveal", { path: S.set.output });
  document.addEventListener("dragover", (e) => e.preventDefault());
  const q = new URLSearchParams(location.search);
  if (q.get("src")) await openSource(q.get("src"));
  // a running export survives a window reload
  const st = await api("status", { since: 0 });
  if (st.job && ["running", "queued"].includes(st.job.state)) { $("overlay").classList.remove("hidden"); S.poll = setInterval(pollStatus, 1000); }
}
boot().catch((e) => toast(e.message));
