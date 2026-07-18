const runBtn = document.getElementById("runBtn");
const statusEl = document.getElementById("status");
const stepsEl = document.getElementById("steps");
const metaEl = document.getElementById("meta");
const conflictIdEl = document.getElementById("conflictId");
const dbEl = document.getElementById("db");

const openSourceBtn = document.getElementById("openSourceBtn");
const openSwaggerBtn = document.getElementById("openSwaggerBtn");
const queryCertsBtn = document.getElementById("queryCertsBtn");
const queryConflictsBtn = document.getElementById("queryConflictsBtn");
const pageEl = document.getElementById("page");
const sizeEl = document.getElementById("size");
const rirEl = document.getElementById("rir");
const hasConflictEl = document.getElementById("hasConflict");

let config = null;

function jsonBlock(value) {
  if (value === null || value === undefined) return "(空)";
  if (typeof value === "string") return value;
  return JSON.stringify(value, null, 2);
}

function renderResult(result) {
  metaEl.innerHTML = `
    <div><b>开始:</b> ${result.startedAt || "-"}</div>
    <div><b>结束:</b> ${result.finishedAt || "-"}</div>
    <div><b>后端地址:</b> ${result.backendBase || "-"}</div>
    <div><b>Conflict ID:</b> ${result.conflictId || "-"}</div>
  `;

  stepsEl.innerHTML = "";
  (result.steps || []).forEach((step) => {
    const card = document.createElement("article");
    card.className = "card";
    const ok = !!step.ok;
    card.innerHTML = `
      <h3>${step.step || "未命名步骤"}</h3>
      <div class="${ok ? "ok" : "bad"}">${ok ? "SUCCESS" : "FAILED"} ${step.status ? `(HTTP ${step.status})` : ""}</div>
      ${step.error ? `<p class="bad">错误: ${step.error}</p>` : ""}
      <h4>结构化结果</h4>
      <pre>${jsonBlock(step.json)}</pre>
      <h4>原始输出</h4>
      <pre>${jsonBlock(step.raw)}</pre>
    `;
    stepsEl.appendChild(card);
  });
}

function renderDb(title, payload) {
  dbEl.style.display = "block";
  dbEl.innerHTML = `
    <h3>${title}</h3>
    <pre>${jsonBlock(payload)}</pre>
  `;
}

async function runDemo() {
  runBtn.disabled = true;
  statusEl.textContent = "运行中...";
  stepsEl.innerHTML = "";
  try {
    const resp = await fetch("/api/demo/run", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ conflictId: conflictIdEl.value.trim() || "1" })
    });
    const result = await resp.json();
    renderResult(result);
    statusEl.textContent = resp.ok ? "完成" : "执行失败";
  } catch (e) {
    statusEl.textContent = `请求失败: ${e.message}`;
  } finally {
    runBtn.disabled = false;
  }
}

async function loadConfig() {
  try {
    const resp = await fetch("/api/config");
    config = await resp.json();
  } catch (_) {
    config = null;
  }
}

function getPagingParams() {
  const page = String(pageEl.value || "1");
  const size = String(sizeEl.value || "10");
  const rir = (rirEl.value || "").trim();
  const hasConflict = hasConflictEl.value;
  return { page, size, rir, hasConflict };
}

async function queryCerts() {
  statusEl.textContent = "查询证书表中...";
  const { page, size, rir, hasConflict } = getPagingParams();
  const qs = new URLSearchParams({ page, size });
  if (rir) qs.set("rir", rir);
  if (hasConflict) qs.set("hasConflict", hasConflict);
  const resp = await fetch(`/api/db/certs?${qs.toString()}`);
  const data = await resp.json();
  renderDb("DB 查询结果：证书表 /cert/certs", data);
  statusEl.textContent = "完成";
}

async function queryConflicts() {
  statusEl.textContent = "查询冲突表中...";
  const { page, size } = getPagingParams();
  const qs = new URLSearchParams({ page, size });
  const resp = await fetch(`/api/db/conflicts?${qs.toString()}`);
  const data = await resp.json();
  renderDb("DB 查询结果：冲突表 /cert/conflicts", data);
  statusEl.textContent = "完成";
}

openSourceBtn.addEventListener("click", async () => {
  if (!config) await loadConfig();
  const url = config?.dataSourceUrl;
  if (url) window.open(url, "_blank");
});

openSwaggerBtn.addEventListener("click", async () => {
  if (!config) await loadConfig();
  const url = config?.swaggerUrl;
  if (url) window.open(url, "_blank");
});

queryCertsBtn.addEventListener("click", queryCerts);
queryConflictsBtn.addEventListener("click", queryConflicts);
runBtn.addEventListener("click", runDemo);

loadConfig();
