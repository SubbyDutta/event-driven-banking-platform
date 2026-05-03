// findoc-verify UI — vanilla JS, Tailwind-styled
const API = "/api/v1/loan-origination";
const $ = (id) => document.getElementById(id);
let pollTimer = null;
const docDetailsCache = new Map();      // documentId -> detail JSON

// ---------- Submit ----------

$("submit-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  const form = e.currentTarget;
  const fd = new FormData(form);
  // Drop empty file fields
  for (const [k, v] of Array.from(fd.entries())) {
    if (v instanceof File && v.size === 0) fd.delete(k);
  }
  $("submit-status").textContent = "Uploading…";
  try {
    const res = await fetch(`${API}/submit`, { method: "POST", body: fd });
    const body = await res.json();
    if (!res.ok) {
      const miss = body.missingFields
        ? Object.entries(body.missingFields).map(([k, v]) => `${k}: ${v}`).join("; ")
        : body.detail || "Unknown error";
      $("submit-status").innerHTML = `<span class="text-red-700">Error: ${miss}</span>`;
      return;
    }
    $("submit-status").innerHTML = `Accepted — <code class="font-mono text-xs">${body.applicationId}</code> (${body.documentsAccepted} docs). Polling auto-started.`;
    $("app-id").value = body.applicationId;
    docDetailsCache.clear();
    startPoll();
  } catch (err) {
    $("submit-status").innerHTML = `<span class="text-red-700">${err}</span>`;
  }
});

// ---------- Polling ----------

function startPoll() {
  if (pollTimer) clearInterval(pollTimer);
  fetchStatus();
  pollTimer = setInterval(fetchStatus, 3000);
  $("poll-btn").textContent = "Stop polling";
}

function stopPoll() {
  if (pollTimer) { clearInterval(pollTimer); pollTimer = null; }
  $("poll-btn").textContent = "Start polling";
}

$("fetch-btn").addEventListener("click", fetchStatus);
$("poll-btn").addEventListener("click", () => (pollTimer ? stopPoll() : startPoll()));

async function fetchStatus() {
  const id = $("app-id").value.trim();
  if (!id) return;
  const res = await fetch(`${API}/${id}`);
  if (!res.ok) {
    $("status-panel").classList.add("hidden");
    $("pipeline").classList.add("hidden");
    return;
  }
  const data = await res.json();
  renderStatus(data);
  if (data.hasReport) {
    const r = await fetch(`${API}/${id}/report`);
    if (r.ok) renderReport(await r.json());
    stopPoll();
  }
}

// ---------- Pipeline timeline ----------

function updatePipeline(d) {
  $("pipeline").classList.remove("hidden");
  const stageDone = {
    submit: d.documents.length > 0,
    ocr: d.documents.length > 0 && d.documents.every(x => x.ocrDone),
    classify: d.documents.length > 0 && d.documents.every(x => x.classifiedType != null),
    extract: d.documents.length > 0 && d.documents.every(x => x.fieldsExtracted > 0),
    compliance: d.compliance.length > 0,
    crossdoc: d.crossDoc.length > 0,
    fraud: d.fraud.length > 0,
    risk: d.hasReport,
  };
  for (const [stage, done] of Object.entries(stageDone)) {
    const dot = document.querySelector(`[data-stage="${stage}"]`);
    if (!dot) continue;
    dot.className = "pipe-dot mx-auto w-6 h-6 rounded-full " + (done ? "bg-emerald-500 ring-2 ring-emerald-200" : "bg-slate-300");
  }
}

// ---------- Status rendering ----------

function renderStatus(d) {
  $("status-panel").classList.remove("hidden");
  $("summary-appid").textContent = d.applicationId;
  $("summary-extid").textContent = d.externalId;
  $("app-status").textContent = d.status;

  const rb = $("app-recommendation");
  if (d.recommendation) {
    rb.textContent = `→ ${d.recommendation}`;
    rb.className = "px-3 py-1 rounded-full text-xs font-semibold " + recColor(d.recommendation);
  } else {
    rb.textContent = "";
    rb.className = "px-3 py-1 rounded-full text-xs font-semibold";
  }

  updatePipeline(d);

  // documents — each collapsible, lazy-loads details
  $("docs-list").innerHTML = d.documents.map(doc => `
    <details class="border rounded" data-doc-id="${doc.documentId}">
      <summary class="p-3 bg-slate-50 flex items-center gap-3 flex-wrap">
        <span class="font-medium">${doc.docType}</span>
        <span class="text-xs text-slate-500 truncate max-w-[240px]">${doc.originalFilename}</span>
        <span class="ml-auto flex gap-2 text-xs flex-wrap">
          <span class="px-2 py-0.5 rounded ${doc.ocrDone ? "bg-emerald-100 text-emerald-800" : "bg-slate-200 text-slate-600"}">${doc.ocrDone ? "OCR ✓" : "OCR …"}</span>
          <span class="px-2 py-0.5 rounded ${doc.classifiedType ? "bg-emerald-100 text-emerald-800" : "bg-slate-200 text-slate-600"}">${doc.classifiedType ? `cls: ${doc.classifiedType}` : "classify …"}</span>
          <span class="px-2 py-0.5 rounded ${doc.fieldsExtracted > 0 ? "bg-emerald-100 text-emerald-800" : "bg-slate-200 text-slate-600"}">fields: ${doc.fieldsExtracted}</span>
          ${doc.periodMonth ? `<span class="px-2 py-0.5 rounded bg-slate-100 text-slate-700">period: ${doc.periodMonth}</span>` : ""}
        </span>
      </summary>
      <div class="p-3 text-xs" data-doc-body>Loading…</div>
    </details>`).join("");

  // Wire lazy loading of doc details
  for (const el of document.querySelectorAll("#docs-list details")) {
    el.addEventListener("toggle", async () => {
      if (!el.open) return;
      const id = el.dataset.docId;
      const body = el.querySelector("[data-doc-body]");
      if (docDetailsCache.has(id)) {
        body.innerHTML = renderDocDetail(docDetailsCache.get(id));
        return;
      }
      const appId = $("app-id").value.trim();
      const r = await fetch(`${API}/${appId}/documents/${id}/details`);
      if (!r.ok) { body.textContent = "(failed to load)"; return; }
      const det = await r.json();
      docDetailsCache.set(id, det);
      body.innerHTML = renderDocDetail(det);
    }, { once: false });
  }

  // Compliance / cross-doc / fraud
  const compCounts = countBy(d.compliance, "status");
  $("comp-counts").textContent = fmtCounts(compCounts);
  $("compliance-list").innerHTML = d.compliance.map(c => checkRow(c.name, c.status, c.details)).join("") || emptyRow();

  const cdCounts = countBy(d.crossDoc, "status");
  $("cd-counts").textContent = fmtCounts(cdCounts);
  $("crossdoc-list").innerHTML = d.crossDoc.map(c => checkRow(c.ruleName, c.status, c.details)).join("") || emptyRow();

  const frCounts = d.fraud.reduce((a, f) => (a[f.severity] = (a[f.severity] || 0) + 1, a), {});
  $("fr-counts").textContent = Object.entries(frCounts).map(([k,v]) => `${v} ${k}`).join(" · ") || "";
  $("fraud-list").innerHTML = d.fraud.map(f => fraudRow(f)).join("") || emptyRow();
}

function renderDocDetail(det) {
  const parts = [];

  if (det.ocr) {
    parts.push(`
      <div class="mb-3">
        <div class="font-semibold text-slate-700 mb-1">OCR</div>
        <div class="text-slate-500 mb-1">provider: ${det.ocr.provider} · latency: ${det.ocr.latencyMs}ms · confidence: ${det.ocr.avgConfidence?.toFixed(2) ?? "n/a"} · pages: ${det.ocr.pageCount} · length: ${det.ocr.rawTextLength} chars</div>
        <details><summary class="text-slate-600">Raw text preview</summary>
          <pre class="mini mt-2 bg-slate-100 p-2 rounded">${esc((det.ocr.rawTextPreview || "").slice(0, 2000))}</pre>
        </details>
      </div>`);
  }

  if (det.classification) {
    const c = det.classification;
    parts.push(`
      <div class="mb-3">
        <div class="font-semibold text-slate-700 mb-1">Classification</div>
        <div>type: <b>${c.classifiedType}</b> · confidence: ${c.confidence?.toFixed(2)}</div>
        ${c.reasoning ? `<div class="text-slate-500">${esc(c.reasoning)}</div>` : ""}
      </div>`);
  }

  if (det.fields && det.fields.length > 0) {
    parts.push(`
      <div>
        <div class="font-semibold text-slate-700 mb-1">Extracted fields (${det.fields.length})</div>
        <table class="kv w-full">
          <thead><tr class="text-left text-slate-500"><th>field</th><th>value</th><th>conf</th><th>method</th></tr></thead>
          <tbody>
          ${det.fields.map(f => `
            <tr>
              <td class="font-mono">${esc(f.name)}</td>
              <td class="font-mono">${esc(f.value)}</td>
              <td>${f.confidence?.toFixed(2) ?? ""}</td>
              <td>${f.method}</td>
            </tr>`).join("")}
          </tbody>
        </table>
      </div>`);
  } else {
    parts.push(`<div class="text-slate-400">(no extracted fields yet)</div>`);
  }

  return parts.join("");
}

function checkRow(name, status, details) {
  const detailStr = details && Object.keys(details).length > 0 ? JSON.stringify(details, null, 2) : "";
  return `
    <details class="border rounded">
      <summary class="p-2 flex items-center gap-2">
        ${statusBadge(status)}
        <span class="font-mono text-xs">${name}</span>
      </summary>
      ${detailStr ? `<pre class="mini bg-slate-50 p-2 border-t">${esc(detailStr)}</pre>` : ""}
    </details>`;
}

function fraudRow(f) {
  const sev = sevClass(f.severity);
  const detailStr = f.details && Object.keys(f.details).length > 0 ? JSON.stringify(f.details, null, 2) : "";
  return `
    <details class="border rounded">
      <summary class="p-2 flex items-center gap-2">
        <span class="px-2 py-0.5 rounded text-xs ${sev}">${f.severity}</span>
        <span class="font-mono text-xs">${f.signalName}</span>
        <span class="ml-auto text-xs text-slate-600">score: ${f.score.toFixed(2)}</span>
      </summary>
      ${detailStr ? `<pre class="mini bg-slate-50 p-2 border-t">${esc(detailStr)}</pre>` : ""}
    </details>`;
}

// ---------- Report ----------

function renderReport(report) {
  $("report-empty").classList.add("hidden");
  $("report-panel").classList.remove("hidden");

  const rec = report.recommendation || "?";
  const badge = $("report-rec-badge");
  badge.textContent = rec.toUpperCase();
  badge.className = "mt-1 px-4 py-2 rounded text-xl font-bold inline-block " + recColor(rec);

  $("report-score").textContent = (report.overall_score ?? "—").toString();
  $("report-credit").textContent = report.credit_score ?? "—";
  $("report-dti").textContent = report.debt?.dti_ratio != null ? `${(report.debt.dti_ratio * 100).toFixed(1)}%` : "—";
  $("report-fraud").textContent = report.fraud?.overall_score != null ? report.fraud.overall_score.toString() : "—";

  const inc = report.income || {};
  const fmt = (x) => x == null ? "—" : `₹ ${Number(x).toLocaleString("en-IN")}`;
  $("report-income").innerHTML = `
    <tr><td>Declared monthly</td><td class="text-right font-mono">${fmt(inc.declared_monthly_inr)}</td></tr>
    <tr><td>Declared annual</td><td class="text-right font-mono">${fmt(inc.declared_annual_inr)}</td></tr>
    <tr><td>From payslips (annual)</td><td class="text-right font-mono">${fmt(inc.annual_from_payslip)}</td></tr>
    <tr><td>From bank (annual)</td><td class="text-right font-mono">${fmt(inc.annual_from_bank)}</td></tr>
    <tr><td>From ITR (annual)</td><td class="text-right font-mono">${fmt(inc.annual_from_itr)}</td></tr>
    <tr><td>Existing EMI</td><td class="text-right font-mono">${fmt(report.debt?.existing_emi_inr)}</td></tr>`;

  const reasons = report.reasons || {};
  const reasonLines = [];
  if (reasons.reject_reason) reasonLines.push(`<b>Reject reason:</b> ${esc(reasons.reject_reason)}`);
  if (reasons.compliance_fails?.length) reasonLines.push(`<b>Compliance failures:</b> ${reasons.compliance_fails.join(", ")}`);
  if (reasons.compliance_warnings?.length) reasonLines.push(`<b>Compliance warnings:</b> ${reasons.compliance_warnings.join(", ")}`);
  if (reasons.cross_doc_fails?.length) reasonLines.push(`<b>Cross-doc failures:</b> ${reasons.cross_doc_fails.join(", ")}`);
  if (reasons.cross_doc_warnings?.length) reasonLines.push(`<b>Cross-doc warnings:</b> ${reasons.cross_doc_warnings.join(", ")}`);
  $("report-reasons").innerHTML = reasonLines.length
    ? reasonLines.map(l => `<li>${l}</li>`).join("")
    : "<li class='list-none text-slate-500'>No blockers — clean application.</li>";

  $("report-json").textContent = JSON.stringify(report, null, 2);
}

// ---------- helpers ----------

function statusBadge(s) {
  const cls = s === "pass" ? "bg-emerald-100 text-emerald-800"
    : s === "fail" ? "bg-red-100 text-red-800"
    : "bg-amber-100 text-amber-800";
  return `<span class="inline-block px-1.5 py-0.5 rounded text-xs ${cls}">${s}</span>`;
}

function sevClass(s) {
  return s === "high" ? "bg-red-100 text-red-800"
    : s === "med" ? "bg-amber-100 text-amber-800"
    : "bg-slate-100 text-slate-700";
}

function recColor(rec) {
  return rec === "approve" ? "bg-emerald-100 text-emerald-800"
    : rec === "reject" ? "bg-red-100 text-red-800"
    : "bg-amber-100 text-amber-800";
}

function countBy(list, key) {
  return list.reduce((a, x) => (a[x[key]] = (a[x[key]] || 0) + 1, a), {});
}

function fmtCounts(c) {
  return Object.entries(c).map(([k, v]) => `${v} ${k}`).join(" · ");
}

function emptyRow() {
  return `<div class="text-xs text-slate-400">(not yet run)</div>`;
}

function esc(s) {
  return String(s).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));
}
