import { useEffect, useMemo, useRef, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, type AppDetail } from "../api";
import { RecBadge, SevBadge, StatusBadge, UseCaseBadge } from "../components/Badges";
import { CopyButton } from "../components/CopyButton";
import { Tooltip } from "../components/Tooltip";
import { useToast, useConfirm } from "../components/Toast";
import { RuleDetails, ruleDescription, ruleHeadline, ruleTitle, sourceLabel, rejectionDrivers, warningDrivers, type DriverItem } from "../components/Humanize";

type Doc = AppDetail["documents"][number];

type FindingsTabKey = "compliance" | "crossdoc" | "fraud" | "report" | "override" | "drivers";

export default function ApplicationDetail() {
  const { id = "" } = useParams();
  const toast = useToast();
  const [data, setData] = useState<AppDetail | null>(null);
  const [report, setReport] = useState<any>(null);
  const [err, setErr] = useState<string | null>(null);
  const [findingsTab, setFindingsTab] = useState<FindingsTabKey>("drivers");
  const [polling, setPolling] = useState(false);
  const [lastUpdated, setLastUpdated] = useState<number>(Date.now());
  const [autoRefresh, setAutoRefresh] = useState(true);
  const [activeDocId, setActiveDocId] = useState<string | null>(null);
  const pollRef = useRef<number | null>(null);
  const prevReportRef = useRef(false);

  async function load(opts: { silent?: boolean } = {}) {
    try {
      if (opts.silent) setPolling(true);
      const d = await api.getApp(id);
      setData(d);
      setLastUpdated(Date.now());
      if (d.hasReport) {
        const r = await api.getReport(id);
        setReport(r);
        if (!prevReportRef.current) {
          prevReportRef.current = true;
          toast.success("Verification report ready", "Pipeline finished — view the Report tab.");
        }
        if (pollRef.current) { clearInterval(pollRef.current); pollRef.current = null; }
      }

      if (!activeDocId && d.documents.length > 0) {
        setActiveDocId(d.documents[0].documentId);
      }
    } catch (e: any) {
      setErr(e.message || "failed to load");
    } finally {
      setPolling(false);
    }
  }

  useEffect(() => {
    load();
    if (autoRefresh) {
      pollRef.current = window.setInterval(() => load({ silent: true }), 3000);
    }
    return () => { if (pollRef.current) clearInterval(pollRef.current); };
    // eslint-disable-next-line
  }, [id, autoRefresh]);

  if (err) return (
    <>
      <div className="fv-topbar">
        <div className="fv-breadcrumb">
          <Link to="/">Verification</Link>
          <span className="sep">/</span>
          <span className="current">Error</span>
        </div>
      </div>
      <main className="fv-page-pad">
        <div className="fv-alert error" style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <span style={{ flex: 1 }}>
            <div style={{ fontWeight: 500, marginBottom: 2 }}>Couldn't load application</div>
            <div style={{ fontSize: 11, fontFamily: "'JetBrains Mono', monospace", opacity: 0.85 }}>{err}</div>
          </span>
          <button onClick={() => { setErr(null); load(); }} className="fv-btn fv-btn-secondary fv-btn-sm">Retry</button>
        </div>
      </main>
    </>
  );

  if (!data) return (
    <>
      <div className="fv-topbar">
        <div className="fv-breadcrumb">
          <Link to="/">Verification</Link>
          <span className="sep">/</span>
          <span className="current">Loading…</span>
        </div>
      </div>
      <main className="fv-page-pad">
        <DetailSkeleton />
      </main>
    </>
  );

  const activeDoc = activeDocId
    ? data.documents.find((d) => d.documentId === activeDocId) ?? data.documents[0]
    : data.documents[0];

  return (
    <>
      <div className="fv-topbar">
        <div className="fv-breadcrumb">
          <Link to="/">Verification</Link>
          <span className="sep">/</span>
          <Link to="/">Submissions</Link>
          <span className="sep">/</span>
          <span className="current" style={{ maxWidth: 320, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap", display: "inline-block", verticalAlign: "bottom" }}>
            {data.applicantName}
          </span>
        </div>
        <div className="fv-topbar-right">
          <Tooltip label={autoRefresh ? "Auto-refresh on (3s) · click to pause" : "Auto-refresh paused · click to resume"}>
            <button
              onClick={() => setAutoRefresh(!autoRefresh)}
              className={`fv-status-pill ${autoRefresh ? "" : "muted"}`}
              style={{ border: "1px solid", borderColor: autoRefresh ? "rgba(45,110,107,0.2)" : "var(--rule)", cursor: "pointer", background: autoRefresh ? "rgba(45,110,107,0.08)" : "var(--paper-2)" }}
            >
              <span className="dot" />
              {autoRefresh ? "Live" : "Paused"}
            </button>
          </Tooltip>
          <span className="fv-mono" style={{ fontSize: 11 }}>
            updated {relativeTime(new Date(lastUpdated).toISOString())}
          </span>
          <button
            onClick={() => load({ silent: true })}
            disabled={polling}
            className="fv-btn-icon"
            aria-label="Refresh now"
            title="Refresh now"
          >
            <svg viewBox="0 0 20 20" className={`h-4 w-4 ${polling ? "animate-spin" : ""}`} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
              <path d="M3 10a7 7 0 0 1 12-5l2 2M17 10a7 7 0 0 1-12 5l-2-2" /><path d="M17 3v4h-4M3 17v-4h4" />
            </svg>
          </button>
        </div>
      </div>

      <main className="fv-page-pad" style={{ animation: "fadeUp 240ms cubic-bezier(0.22, 1, 0.36, 1)" }}>
        <DetailHeader
          d={data}
          report={report}
          onAfterReplay={() => load()}
          onJumpOverride={() => {
            setFindingsTab("override");
            requestAnimationFrame(() => {
              const el = document.getElementById("fv-findings");
              if (el) el.scrollIntoView({ behavior: "smooth", block: "start" });
            });
          }}
        />
        <PipelineTimeline d={data} />


        <DocsHeading d={data} />
        <DocWorkspace
          appId={data.applicationId}
          docs={data.documents}
          activeDocId={activeDoc?.documentId ?? null}
          setActiveDocId={setActiveDocId}
        />


        <FindingsSection
          tab={findingsTab}
          setTab={setFindingsTab}
          d={data}
          report={report}
          onOverride={() => load()}
        />
      </main>
    </>
  );
}


function DetailHeader({ d, report, onAfterReplay, onJumpOverride }: {
  d: AppDetail; report: any; onAfterReplay: () => void; onJumpOverride: () => void;
}) {
  const overallScore = report?.overall_score;
  const fraudScore = report?.fraud?.overall_score;

  return (
    <div className="fv-panel" style={{ marginBottom: 18 }}>
      <div style={{ padding: "20px 24px" }}>
        <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 24, flexWrap: "wrap" }}>
          <div style={{ minWidth: 0, flex: 1 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 8, flexWrap: "wrap" }}>
              <UseCaseBadge u={d.useCase} />
              <Tooltip label="Click to copy application ID">
                <span style={{ fontFamily: "'JetBrains Mono', monospace", fontSize: 11, color: "var(--muted)", display: "inline-flex", alignItems: "center", gap: 4, padding: "3px 8px", background: "var(--paper-2)", border: "1px solid var(--rule)", borderRadius: 1, cursor: "help" }}>
                  {d.applicationId.slice(0, 8)}…
                  <CopyButton value={d.applicationId} label="application id" />
                </span>
              </Tooltip>
              {!d.hasReport && (
                <span className="fv-tag warn">
                  <span className="dot" />
                  pipeline running
                </span>
              )}
            </div>
            <h1 className="fv-h-display" style={{ fontSize: 28, marginBottom: 4 }}>{d.applicantName}</h1>
            <div style={{ display: "flex", alignItems: "center", gap: 4, fontSize: 11, color: "var(--muted)", fontFamily: "'JetBrains Mono', monospace", marginBottom: 8 }}>
              {d.externalId}
              <CopyButton value={d.externalId} label="external ID" />
            </div>
            <div style={{ display: "flex", alignItems: "center", gap: 12, fontSize: 12, color: "var(--muted)", flexWrap: "wrap" }}>
              <a href={`mailto:${d.email}`} style={{ color: "var(--muted)", textDecoration: "none" }} className="hover:!text-ink-900">{d.email}</a>
              <span style={{ color: "var(--rule)" }}>·</span>
              <a href={`tel:${d.phone}`} style={{ color: "var(--muted)", textDecoration: "none" }}>{d.phone}</a>
              <span style={{ color: "var(--rule)" }}>·</span>
              <Tooltip label={new Date(d.createdAt).toLocaleString()}>
                <span style={{ cursor: "help" }}>Submitted {relativeTime(d.createdAt)}</span>
              </Tooltip>
              <span style={{ color: "var(--rule)" }}>·</span>
              <span>{d.documents.length} document{d.documents.length === 1 ? "" : "s"}</span>
            </div>
          </div>

          <div style={{ display: "flex", gap: 12, alignItems: "stretch", flexWrap: "wrap" }}>
            <ReplayButton appId={d.applicationId} onAfterReplay={onAfterReplay} />
            {d.hasReport && (
              <button
                type="button"
                onClick={onJumpOverride}
                className="fv-override-jump"
                title="Jump to override panel"
              >
                <svg viewBox="0 0 20 20" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M4 13.5V16h2.5L15 7.5 12.5 5 4 13.5Z" />
                  <path d="M11 6.5 13.5 9" />
                </svg>
                Override decision
              </button>
            )}
            <ScoreCard
              label="Decision"
              primary={<RecBadge r={d.effectiveRecommendation} big />}
              sub={
                d.recommendation && d.effectiveRecommendation !== d.recommendation
                  ? <span>was: <RecBadge r={d.recommendation} /></span>
                  : <span>Status: <span style={{ color: "var(--ink)", fontWeight: 500 }}>{d.status}</span></span>
              }
            />
            {overallScore != null && (
              <ScoreCard
                label="Overall"
                primary={<span className="primary mono">{Number(overallScore).toFixed(2)}</span>}
                sub="0 → 1, higher is stronger"
              />
            )}
            {fraudScore != null && (
              <ScoreCard
                label="Fraud"
                primary={<span className="primary mono">{Number(fraudScore).toFixed(2)}</span>}
                sub={fraudScore >= 0.5 ? "high risk" : fraudScore >= 0.25 ? "watch" : "low risk"}
              />
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

function ScoreCard({ label, primary, sub }: { label: string; primary: React.ReactNode; sub?: React.ReactNode }) {
  return (
    <div className="fv-stat-card">
      <div className="label">{label}</div>
      <div className="primary">{primary}</div>
      {sub && <div className="sub">{sub}</div>}
    </div>
  );
}

function ReplayButton({ appId, onAfterReplay }: { appId: string; onAfterReplay: () => void }) {
  const toast = useToast();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const wrapRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    function onClick(e: MouseEvent) {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    }
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    window.addEventListener("mousedown", onClick);
    window.addEventListener("keydown", onKey);
    return () => {
      window.removeEventListener("mousedown", onClick);
      window.removeEventListener("keydown", onKey);
    };
  }, [open]);

  async function run() {
    if (reason.trim().length < 3) { setErr("Reason required (min 3 chars)."); return; }
    setBusy(true); setErr(null);
    try {
      const r = await api.replay(appId, reason.trim());
      toast.success("Replay started", `run #${r.runNumber} · ${r.appliedOverrides} override${r.appliedOverrides === 1 ? "" : "s"} applied`);
      setOpen(false); setReason("");
      onAfterReplay();
    } catch (e: any) {
      setErr(e.message || "replay failed");
      toast.error("Replay failed", e?.message);
    } finally { setBusy(false); }
  }

  return (
    <div ref={wrapRef} style={{ position: "relative" }}>
      <button onClick={() => setOpen(!open)} className="fv-replay-btn">
        <svg viewBox="0 0 20 20" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M3 10a7 7 0 0 1 12-5l2 2M17 10a7 7 0 0 1-12 5l-2-2" />
          <path d="M17 3v4h-4M3 17v-4h4" />
        </svg>
        Replay pipeline
      </button>
      {open && (
        <div className="fv-replay-popover">
          <div style={{ marginBottom: 10 }}>
            <div style={{ fontFamily: "'Fraunces', Georgia, serif", fontSize: 14, color: "var(--ink)", fontWeight: 500 }}>Replay reason</div>
            <div style={{ fontSize: 11, color: "var(--muted)", marginTop: 2 }}>Required · written to the audit trail.</div>
          </div>
          <textarea
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            rows={3}
            placeholder="e.g. corrected credit_score; re-evaluating with admin patch"
            className="fv-input"
            autoFocus
          />
          {err && <div className="fv-alert error" style={{ marginTop: 10 }}><span>⚠</span><span>{err}</span></div>}
          <div style={{ display: "flex", justifyContent: "flex-end", gap: 6, marginTop: 12 }}>
            <button onClick={() => { setOpen(false); setErr(null); }} className="fv-btn fv-btn-secondary fv-btn-sm">Cancel</button>
            <button disabled={busy} onClick={run} className="fv-btn fv-btn-primary fv-btn-sm">
              {busy ? <><span className="fv-spinner" /> Starting…</> : "Start replay →"}
            </button>
          </div>
        </div>
      )}
    </div>
  );
}


function PipelineTimeline({ d }: { d: AppDetail }) {
  const anyDoc = d.documents.length > 0;
  const stages = [
    { key: "submit",     label: "Submit",     done: anyDoc },
    { key: "ocr",        label: "OCR",        done: anyDoc && d.documents.every(x => x.ocrDone) },
    { key: "classify",   label: "Classify",   done: anyDoc && d.documents.every(x => x.classifiedType != null) },
    { key: "extract",    label: "Extract",    done: anyDoc && d.documents.every(x => x.fieldsExtracted > 0) },
    { key: "compliance", label: "Compliance", done: d.compliance.length > 0 },
    { key: "crossdoc",   label: "Cross-doc",  done: d.crossDoc.length > 0 },
    { key: "fraud",      label: "Fraud",      done: d.fraud.length > 0 },
    { key: "risk",       label: "Report",     done: d.hasReport },
  ];
  const firstPending = stages.findIndex(s => !s.done);
  const doneCount = stages.filter(s => s.done).length;
  const pct = Math.round((doneCount / stages.length) * 100);
  const isComplete = doneCount === stages.length;

  return (
    <div className="fv-pipeline" style={{ marginBottom: 18 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 10 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 12 }}>
          <div className="fv-h-section" style={{ fontSize: 14 }}>Pipeline</div>
          <span className={`fv-tag ${isComplete ? "success" : "warn"}`}>
            <span className="dot" />
            {isComplete ? "complete" : "running"}
          </span>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span className="fv-mono" style={{ fontSize: 11, color: "var(--muted)" }}>
            {doneCount}/{stages.length} stages · {pct}%
          </span>
          <div style={{ width: 120, height: 4, background: "var(--paper-2)", borderRadius: 1, overflow: "hidden", border: "1px solid var(--rule)" }}>
            <div style={{
              height: "100%",
              width: `${pct}%`,
              background: isComplete ? "var(--positive)" : "linear-gradient(90deg, var(--navy), var(--gold))",
              transition: "width 0.5s",
            }} />
          </div>
        </div>
      </div>
      <div className="fv-pipeline-row">
        {stages.map((s, i) => {
          const isCurrent = i === firstPending;
          return (
            <div key={s.key} style={{ display: "flex", alignItems: "center", flex: 1 }}>
              <div className={`fv-pipe-step${s.done ? " done" : isCurrent ? " current" : ""}`}>
                <div className="dot">
                  {s.done ? (
                    <svg viewBox="0 0 20 20" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
                      <path d="m5 10 3.5 3.5L15 7" />
                    </svg>
                  ) : i + 1}
                </div>
                <div className="text">{s.label}</div>
              </div>
              {i < stages.length - 1 && (
                <div className={`fv-pipe-conn${s.done ? " done" : ""}`} />
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}


function DocsHeading({ d }: { d: AppDetail }) {
  const total = d.documents.length;
  const ocrDone = d.documents.filter(x => x.ocrDone).length;
  const classified = d.documents.filter(x => x.classifiedType != null).length;
  const totalFields = d.documents.reduce((s, x) => s + (x.fieldsExtracted || 0), 0);
  return (
    <div style={{ display: "flex", alignItems: "flex-end", justifyContent: "space-between", marginBottom: 12, gap: 12, flexWrap: "wrap" }}>
      <div>
        <div className="fv-eyebrow" style={{ marginBottom: 6 }}>
          <span>Documents</span>
          <span className="stamp">{total} TOTAL</span>
        </div>
        <h2 className="fv-h-display" style={{ fontSize: 22 }}>
          Inspect every <em>document</em>.
        </h2>
      </div>
      <div style={{ display: "flex", gap: 12, fontSize: 11, color: "var(--muted)", fontFamily: "'JetBrains Mono', monospace" }}>
        <span><span style={{ color: "var(--ink)", fontWeight: 500 }}>{ocrDone}</span> / {total} OCR</span>
        <span style={{ color: "var(--rule)" }}>·</span>
        <span><span style={{ color: "var(--ink)", fontWeight: 500 }}>{classified}</span> / {total} classified</span>
        <span style={{ color: "var(--rule)" }}>·</span>
        <span><span style={{ color: "var(--ink)", fontWeight: 500 }}>{totalFields}</span> fields extracted</span>
      </div>
    </div>
  );
}

function DocWorkspace({ appId, docs, activeDocId, setActiveDocId }: {
  appId: string;
  docs: Doc[];
  activeDocId: string | null;
  setActiveDocId: (id: string) => void;
}) {
  const activeDoc = activeDocId ? docs.find((d) => d.documentId === activeDocId) ?? docs[0] : docs[0];

  if (docs.length === 0) {
    return (
      <div className="fv-panel" style={{ padding: 60, textAlign: "center", marginBottom: 24 }}>
        <div className="fv-h-section" style={{ marginBottom: 6 }}>No documents uploaded</div>
        <div style={{ fontSize: 13, color: "var(--muted)" }}>Documents will appear here once the submission completes upload.</div>
      </div>
    );
  }

  return (
    <div className="fv-doc-workspace">

      <DocRail
        docs={docs}
        activeDocId={activeDoc?.documentId ?? null}
        onPick={setActiveDocId}
      />


      {activeDoc && (
        <DocStageAndFields
          key={activeDoc.documentId}
          appId={appId}
          doc={activeDoc}
        />
      )}
    </div>
  );
}

function DocRail({ docs, activeDocId, onPick }: {
  docs: Doc[];
  activeDocId: string | null;
  onPick: (id: string) => void;
}) {
  const completeCount = docs.filter((d) => d.ocrDone && d.classifiedType && d.fieldsExtracted > 0).length;
  return (
    <div className="fv-doc-rail">
      <div className="fv-doc-rail-head">
        <span className="h">Files</span>
        <span className="count">{completeCount} / {docs.length}</span>
      </div>
      <div className="fv-doc-rail-list">
        {docs.map((d) => {
          const active = d.documentId === activeDocId;
          const complete = d.ocrDone && !!d.classifiedType && d.fieldsExtracted > 0;
          return (
            <button
              key={d.documentId}
              onClick={() => onPick(d.documentId)}
              className={`fv-doc-rail-item${active ? " active" : ""}${complete ? " complete" : ""}`}
            >
              <span className="ico">
                {complete ? <CheckIcon /> : <DocIcon />}
              </span>
              <div className="body">
                <div className="name">
                  {prettyDocType(d.docType)}
                  {d.periodMonth && (
                    <span className="fv-mono" style={{ fontSize: 10, color: "var(--muted)", fontWeight: 400 }}>
                      · {d.periodMonth}
                    </span>
                  )}
                </div>
                <div className="filename" title={d.originalFilename}>{d.originalFilename}</div>
                <div className="meta">
                  <span className={`pip ${d.ocrDone ? "ok" : ""}`} title={d.ocrDone ? "OCR done" : "OCR pending"} />
                  <span className={`pip ${d.classifiedType ? "ok" : ""}`} title={d.classifiedType ? "Classified" : "Classify pending"} />
                  <span className={`pip ${d.fieldsExtracted > 0 ? "ok" : ""}`} title={`${d.fieldsExtracted} fields`} />
                  <span style={{ marginLeft: 4, color: "var(--muted)" }}>{d.fieldsExtracted} fields</span>
                </div>
              </div>
            </button>
          );
        })}
      </div>
    </div>
  );
}

function DocStageAndFields({ appId, doc }: { appId: string; doc: Doc }) {
  const [det, setDet] = useState<any>(null);
  const [previewUrl, setPreviewUrl] = useState<string | null>(null);
  const [previewErr, setPreviewErr] = useState<string | null>(null);

  useEffect(() => {
    let alive = true;
    setDet(null);
    setPreviewUrl(null);
    setPreviewErr(null);

    api.getDocDetails(appId, doc.documentId)
      .then((d) => { if (alive) setDet(d); })
      .catch(() => {  });

    let url: string | null = null;
    api.getDocFile(appId, doc.documentId)
      .then((blob) => {
        if (!alive) return;
        url = URL.createObjectURL(blob);
        setPreviewUrl(url);
      })
      .catch((e: any) => {
        if (!alive) return;


        const status: number | undefined = e?.status;
        const body: string = String(e?.body ?? e?.message ?? "preview unavailable");
        let detail = body;
        try {
          const parsed = JSON.parse(body);
          if (parsed?.detail) detail = String(parsed.detail);
        } catch {  }
        if (status === 410) setPreviewErr("File no longer in storage. " + detail.replace(/^File no longer in storage: ?/i, ""));
        else if (status === 404) setPreviewErr("Document not found.");
        else setPreviewErr(detail);
      });

    return () => {
      alive = false;
      if (url) URL.revokeObjectURL(url);
    };
  }, [appId, doc.documentId]);

  return (
    <>
      <DocStage doc={doc} previewUrl={previewUrl} previewErr={previewErr} />
      <DocFieldsPane
        appId={appId}
        doc={doc}
        det={det}
        onEdited={() => api.getDocDetails(appId, doc.documentId).then(setDet).catch(() => {})}
      />
    </>
  );
}

function DocStage({ doc, previewUrl, previewErr }: {
  doc: Doc;
  previewUrl: string | null;
  previewErr: string | null;
}) {
  return (
    <div className="fv-doc-stage">
      <div className="fv-doc-stage-head">
        <div className="label">
          <span style={{ width: 36, height: 36, background: "linear-gradient(135deg, var(--navy) 0%, var(--navy-deep) 100%)", color: "var(--paper)", display: "grid", placeItems: "center", borderRadius: 2, flexShrink: 0 }}>
            <DocIcon />
          </span>
          <div>
            <div className="name">{prettyDocType(doc.docType)}</div>
            <div className="filename" title={doc.originalFilename}>{doc.originalFilename}</div>
          </div>
        </div>
        <div className="actions">
          <Chip ok={doc.ocrDone} label={doc.ocrDone ? "OCR ✓" : "OCR ⏳"} />
          <Chip ok={!!doc.classifiedType} label={doc.classifiedType ? "Classified" : "Classify ⏳"} />
          <Chip ok={doc.fieldsExtracted > 0} label={`${doc.fieldsExtracted} fields`} />
          {previewUrl && (
            <a
              href={previewUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="fv-btn-inline"
              title="Open in new tab"
            >
              ↗ Open
            </a>
          )}
        </div>
      </div>
      <DocPreview url={previewUrl} err={previewErr} filename={doc.originalFilename} />
    </div>
  );
}

function DocPreview({ url, err, filename }: { url: string | null; err: string | null; filename: string }) {
  const ext = (filename.split(".").pop() || "").toLowerCase();
  const isImage = ["png", "jpg", "jpeg", "gif", "webp"].includes(ext);
  const isPdf = ext === "pdf" || (!isImage && !ext);

  if (err) {
    const isMissing = /no longer in storage|not found/i.test(err);
    return (
      <div className="fv-doc-stage-body">
        <div className="empty">
          <div style={{ maxWidth: 420 }}>
            <div style={{ width: 44, height: 44, margin: "0 auto 14px", border: "1px solid var(--rule)", display: "grid", placeItems: "center", color: isMissing ? "var(--gold)" : "var(--bordeaux)", borderColor: isMissing ? "var(--gold)" : "var(--bordeaux)" }}>
              {isMissing ? "!" : "✕"}
            </div>
            <div className="h">{isMissing ? "File missing from storage" : "Preview unavailable"}</div>
            <div style={{ fontSize: 12, color: "var(--muted)", lineHeight: 1.55, marginTop: 4 }}>{err}</div>
            {isMissing && (
              <div style={{ marginTop: 12, padding: "10px 14px", background: "rgba(184,149,74,0.08)", border: "1px solid rgba(184,149,74,0.3)", borderLeft: "3px solid var(--gold)", borderRadius: 1, fontSize: 11.5, color: "var(--ink)", textAlign: "left", lineHeight: 1.5 }}>
                <strong style={{ fontWeight: 500 }}>Why it happened:</strong> the S3 bucket was wiped (LocalStack runs ephemeral by default).{" "}
                <strong style={{ fontWeight: 500 }}>Fix:</strong> re-submit the application — the metadata stays, the file gets re-uploaded.
              </div>
            )}
          </div>
        </div>
      </div>
    );
  }
  if (!url) {
    return (
      <div className="fv-doc-stage-body">
        <div className="empty">
          <div>
            <span className="fv-spinner" style={{ color: "var(--muted)" }} /> Loading preview…
          </div>
        </div>
      </div>
    );
  }
  return (
    <div className="fv-doc-stage-body">
      {isImage ? (
        <div className="img-wrap">
          <img src={url} alt={filename} />
        </div>
      ) : isPdf ? (
        <iframe src={url} title={filename} />
      ) : (
        <div className="empty">
          <div>
            <div className="h">Cannot preview .{ext}</div>
            <div>Use "Open" above to view in a new tab.</div>
          </div>
        </div>
      )}
    </div>
  );
}


function DocFieldsPane({ appId, doc, det, onEdited }: {
  appId: string;
  doc: Doc;
  det: any;
  onEdited: () => void;
}) {
  const [tab, setTab] = useState<"fields" | "ocr" | "classify">("fields");

  return (
    <div className="fv-doc-fields-pane desktop">
      <div className="fv-doc-fields-head">
        <div className="row">
          <span className="title">Document data</span>
          <span className="count">{doc.fieldsExtracted} fields</span>
        </div>
        <div style={{ display: "flex", gap: 4 }}>
          <PaneTab active={tab === "fields"} onClick={() => setTab("fields")}>Fields</PaneTab>
          <PaneTab active={tab === "ocr"} onClick={() => setTab("ocr")}>OCR</PaneTab>
          <PaneTab active={tab === "classify"} onClick={() => setTab("classify")}>Classify</PaneTab>
        </div>
      </div>

      {!det && (
        <div style={{ padding: 18 }}>
          <div className="skeleton h-4 w-32" style={{ marginBottom: 8 }} />
          <div className="skeleton h-3 w-full" style={{ marginBottom: 6 }} />
          <div className="skeleton h-3 w-5/6" style={{ marginBottom: 6 }} />
          <div className="skeleton h-3 w-4/6" />
        </div>
      )}

      {det && tab === "fields" && (
        <FieldsList
          fields={det.fields || []}
          appId={appId}
          documentId={doc.documentId}
          onEdited={onEdited}
        />
      )}
      {det && tab === "ocr" && <OcrPane ocr={det.ocr} />}
      {det && tab === "classify" && <ClassifyPane classification={det.classification} />}
    </div>
  );
}

function PaneTab({ active, onClick, children }: { active: boolean; onClick: () => void; children: React.ReactNode }) {
  return (
    <button
      onClick={onClick}
      style={{
        flex: 1,
        padding: "6px 10px",
        fontSize: 11,
        fontFamily: "inherit",
        background: active ? "var(--ink)" : "var(--paper-2)",
        color: active ? "var(--paper)" : "var(--muted)",
        border: `1px solid ${active ? "var(--ink)" : "var(--rule)"}`,
        borderRadius: 1,
        cursor: "pointer",
        letterSpacing: "0.06em",
        textTransform: "uppercase",
        fontWeight: 500,
        transition: "all 0.12s",
      }}
    >
      {children}
    </button>
  );
}

function FieldsList({ fields, appId, documentId, onEdited }: {
  fields: any[]; appId: string; documentId: string; onEdited: () => void;
}) {
  const [query, setQuery] = useState("");
  const [editing, setEditing] = useState<string | null>(null);
  const [adding, setAdding] = useState(false);

  const filtered = useMemo(() => {
    if (!query) return fields;
    const q = query.toLowerCase();
    return fields.filter((f) => (f.name + " " + f.value).toLowerCase().includes(q));
  }, [fields, query]);

  if (fields.length === 0) {
    return (
      <div style={{ padding: 32, textAlign: "center", color: "var(--muted)" }}>
        <div className="fv-h-section" style={{ fontSize: 14, marginBottom: 4, color: "var(--ink)" }}>No fields extracted</div>
        <div style={{ fontSize: 12, marginBottom: 12 }}>The classifier didn't find structured fields in this document. Add them manually below, then replay the pipeline.</div>
        {adding ? (
          <FieldNewRow
            appId={appId}
            documentId={documentId}
            onCancel={() => setAdding(false)}
            onSaved={() => { setAdding(false); onEdited(); }}
          />
        ) : (
          <button onClick={() => setAdding(true)} className="fv-btn-inline" style={{ marginTop: 4 }}>
            + Add field
          </button>
        )}
      </div>
    );
  }

  return (
    <>
      <div className="fv-fields-search">
        <div className="fv-fields-search-wrap">
          <input
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            placeholder="Search fields…"
          />
        </div>
        <button
          onClick={() => setAdding(true)}
          className="fv-btn-inline"
          style={{ marginLeft: 8 }}
          disabled={adding}
        >
          + Add field
        </button>
      </div>
      <div className="fv-doc-fields-body">
        {adding && (
          <FieldNewRow
            appId={appId}
            documentId={documentId}
            onCancel={() => setAdding(false)}
            onSaved={() => { setAdding(false); onEdited(); }}
          />
        )}
        {filtered.length === 0 && !adding && (
          <div style={{ padding: 24, textAlign: "center", fontSize: 12, color: "var(--muted)" }}>
            No fields match "{query}"
          </div>
        )}
        {filtered.map((f) => (
          editing === f.name ? (
            <FieldEditRow
              key={f.name}
              field={f}
              appId={appId}
              documentId={documentId}
              onCancel={() => setEditing(null)}
              onSaved={() => { setEditing(null); onEdited(); }}
            />
          ) : (
            <FieldDisplayRow
              key={f.name}
              field={f}
              onEdit={() => setEditing(f.name)}
            />
          )
        ))}
      </div>
    </>
  );
}

function FieldNewRow({ appId, documentId, onCancel, onSaved }: {
  appId: string; documentId: string; onCancel: () => void; onSaved: () => void;
}) {
  const toast = useToast();
  const [name, setName] = useState("");
  const [value, setValue] = useState("");
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function save() {
    const fname = name.trim();
    if (fname.length < 1) { setErr("Field name required"); return; }
    if (reason.trim().length < 3) { setErr("Reason required (min 3 chars)"); return; }
    setBusy(true); setErr(null);
    try {
      await api.patchExtractedField(appId, fname, value, reason.trim(), documentId);
      toast.success("Field added", `${prettyFieldName(fname)} → "${value}". Replay to re-evaluate.`);
      onSaved();
    } catch (e: any) {
      setErr(e?.message || "add failed");
      toast.error("Add failed", e?.message);
    } finally { setBusy(false); }
  }

  return (
    <div className="fv-field-row editing">
      <div className="top">
        <span className="fv-tag warn" style={{ fontSize: 10 }}>
          <span className="dot" />
          adding
        </span>
      </div>
      <div className="fv-field-edit" style={{ display: "grid", gap: 6 }}>
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          className="value-input"
          placeholder="field name (e.g. monthly_income)"
          autoFocus
        />
        <input
          value={value}
          onChange={(e) => setValue(e.target.value)}
          className="value-input"
          placeholder="value"
        />
        <input
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Reason (audit trail) — min 3 chars"
          className="reason-input"
        />
        {err && <div className="fv-alert error" style={{ fontSize: 11, padding: "8px 12px" }}>{err}</div>}
        <div style={{ display: "flex", gap: 6 }}>
          <button onClick={save} disabled={busy} className="fv-btn-inline">
            {busy ? "Saving…" : "Save"}
          </button>
          <button onClick={onCancel} disabled={busy} className="fv-btn-inline">
            Cancel
          </button>
        </div>
      </div>
    </div>
  );
}

function FieldDisplayRow({ field, onEdit }: { field: any; onEdit: () => void }) {
  const c = field.confidence;
  const tone = c == null ? "" : c >= 0.85 ? " conf-high" : c >= 0.6 ? " conf-mid" : " conf-low";
  return (
    <div className={`fv-field-row${tone}`}>
      <div className="top">
        <span className="name">{prettyFieldName(field.name)}</span>
        <div className="actions">
          {field.value != null && String(field.value).length > 0 && (
            <CopyButton value={String(field.value)} label={field.name} />
          )}
          <button onClick={onEdit} className="fv-btn-inline" title="Edit field value">
            ✎ Edit
          </button>
        </div>
      </div>
      <div className="value">{String(field.value)}</div>
      <div className="footer">
        <ConfidenceBar v={field.confidence} />
        <span className="method">{methodLabel(field.method)}</span>
      </div>
    </div>
  );
}

function FieldEditRow({ field, appId, documentId, onCancel, onSaved }: {
  field: any; appId: string; documentId: string; onCancel: () => void; onSaved: () => void;
}) {
  const toast = useToast();
  const [value, setValue] = useState(field.value == null ? "" : String(field.value));
  const [reason, setReason] = useState("");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  async function save() {
    if (reason.trim().length < 3) { setErr("Reason required (min 3 chars)"); return; }
    setBusy(true); setErr(null);
    try {
      await api.patchExtractedField(appId, field.name, value, reason.trim(), documentId);
      toast.success("Field patched", `${prettyFieldName(field.name)} → "${value}". Replay to re-evaluate.`);
      onSaved();
    } catch (e: any) {
      setErr(e?.message || "patch failed");
      toast.error("Patch failed", e?.message);
    } finally { setBusy(false); }
  }

  return (
    <div className="fv-field-row editing">
      <div className="top">
        <span className="name">{prettyFieldName(field.name)}</span>
        <span className="fv-tag warn" style={{ fontSize: 10 }}>
          <span className="dot" />
          editing
        </span>
      </div>
      <div className="fv-field-edit">
        <input
          value={value}
          onChange={(e) => setValue(e.target.value)}
          className="value-input"
          placeholder="new value"
          autoFocus
        />
        <input
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          placeholder="Reason (audit trail) — min 3 chars"
          className="reason-input"
        />
        {err && <div className="fv-alert error" style={{ fontSize: 11, padding: "8px 12px" }}>
          <span>⚠</span><span>{err}</span>
        </div>}
        <div className="stage-note">Patch is staged — click Replay pipeline to re-evaluate.</div>
        <div className="actions">
          <button onClick={onCancel} className="fv-btn-inline">Cancel</button>
          <button disabled={busy} onClick={save} className="fv-btn-inline warn" style={{ background: "var(--gold)", color: "var(--ink)", borderColor: "var(--gold)" }}>
            {busy ? "Saving…" : "Save patch"}
          </button>
        </div>
      </div>
    </div>
  );
}

function ConfidenceBar({ v }: { v?: number | null }) {
  if (v == null) return <span style={{ color: "var(--muted)", fontSize: 11 }}>—</span>;
  const pct = Math.max(0, Math.min(1, v)) * 100;
  const tone = v >= 0.85 ? "ok" : v >= 0.6 ? "warn" : "crit";
  return (
    <div className="fv-confidence-bar" style={{ minWidth: 100 }}>
      <div className="track">
        <div className={`fill ${tone}`} style={{ width: `${pct}%` }} />
      </div>
      <span className="pct">{Math.round(pct)}%</span>
    </div>
  );
}

function OcrPane({ ocr }: { ocr: any }) {
  if (!ocr) return <div style={{ padding: 24, fontSize: 12, color: "var(--muted)" }}>OCR not run yet.</div>;
  return (
    <div style={{ padding: 18 }}>
      <div className="fv-doc-fields-stats" style={{ padding: 0, marginLeft: -18, marginRight: -18, marginTop: -6 }}>
        <div className="stat">
          <div className="lab">Engine</div>
          <div className="val" style={{ fontFamily: "inherit", fontSize: 12 }}>{ocr.provider ?? "—"}</div>
        </div>
        <div className="stat">
          <div className="lab">Latency</div>
          <div className="val">{ocr.latencyMs ?? "?"} ms</div>
        </div>
        <div className="stat">
          <div className="lab">Avg confidence</div>
          <div className="val">{ocr.avgConfidence != null ? `${(ocr.avgConfidence * 100).toFixed(0)}%` : "—"}</div>
        </div>
        <div className="stat">
          <div className="lab">Pages</div>
          <div className="val">{ocr.pageCount ?? "—"}</div>
        </div>
      </div>
      {ocr.rawTextPreview && (
        <details style={{ marginTop: 16 }}>
          <summary style={{ cursor: "pointer", fontSize: 11, color: "var(--muted)", fontWeight: 500, letterSpacing: "0.06em", textTransform: "uppercase" }}>
            Raw text preview ({ocr.rawTextLength?.toLocaleString?.() ?? 0} chars)
          </summary>
          <pre style={{ marginTop: 10, padding: 12, background: "var(--paper-2)", border: "1px solid var(--rule)", borderRadius: 1, fontFamily: "'JetBrains Mono', monospace", fontSize: 11, color: "var(--ink)", whiteSpace: "pre-wrap", wordBreak: "break-word", maxHeight: 300, overflow: "auto" }}>
            {(ocr.rawTextPreview || "").slice(0, 1500)}
          </pre>
        </details>
      )}
    </div>
  );
}

function ClassifyPane({ classification }: { classification: any }) {
  if (!classification) return <div style={{ padding: 24, fontSize: 12, color: "var(--muted)" }}>Classification not run yet.</div>;
  return (
    <div style={{ padding: 18 }}>
      <div style={{ fontSize: 12.5, color: "var(--ink)", lineHeight: 1.6 }}>
        Classified as{" "}
        <strong style={{ fontFamily: "'Fraunces', Georgia, serif", color: "var(--ink)" }}>
          {prettyDocType(classification.classifiedType)}
        </strong>
        {classification.confidence != null && (
          <> with <strong>{(classification.confidence * 100).toFixed(0)}%</strong> confidence.</>
        )}
      </div>
      {classification.reasoning && (
        <div style={{
          marginTop: 12,
          padding: 12,
          background: "var(--paper-2)",
          border: "1px solid var(--rule-soft)",
          borderRadius: 1,
          fontSize: 11.5,
          color: "var(--muted)",
          fontStyle: "italic",
          lineHeight: 1.55,
        }}>
          {classification.reasoning}
        </div>
      )}
    </div>
  );
}


function FindingsSection({ tab, setTab, d, report, onOverride }: {
  tab: FindingsTabKey;
  setTab: (t: FindingsTabKey) => void;
  d: AppDetail;
  report: any;
  onOverride: () => void;
}) {
  const failures = useMemo(() => {
    const items: DriverItem[] = [
      ...d.compliance.map((c) => ({ kind: "compliance" as const, name: c.name, status: c.status, details: c.details })),
      ...d.crossDoc.map((c) => ({ kind: "crossdoc" as const, name: c.ruleName, status: c.status, details: c.details })),
      ...d.fraud.map((f) => ({ kind: "fraud" as const, name: f.signalName, status: "fail", severity: f.severity, score: f.score, details: f.details })),
    ];
    return { fails: rejectionDrivers(items), warns: warningDrivers(items) };
  }, [d]);

  const hasIssues = failures.fails.length + failures.warns.length > 0;

  const items: { key: FindingsTabKey; label: string; n?: number }[] = [
    { key: "drivers",    label: "Decision drivers", n: hasIssues ? failures.fails.length + failures.warns.length : undefined },
    { key: "override",   label: "Override",    n: d.overrides.length },
    { key: "compliance", label: "Compliance",  n: d.compliance.length },
    { key: "crossdoc",   label: "Cross-doc",   n: d.crossDoc.length },
    { key: "fraud",      label: "Fraud",       n: d.fraud.length },
    { key: "report",     label: "Report" },
  ];

  return (
    <div className="fv-panel" id="fv-findings" style={{ scrollMarginTop: 80 }}>
      <div style={{ display: "flex", borderBottom: "1px solid var(--rule)", overflowX: "auto", padding: "0 16px" }}>
        {items.map(({ key, label, n }) => {
          const active = tab === key;
          return (
            <button
              key={key}
              onClick={() => setTab(key)}
              style={{
                padding: "14px 18px",
                fontSize: 12.5,
                fontFamily: "inherit",
                color: active ? "var(--ink)" : "var(--muted)",
                background: "transparent",
                border: "none",
                borderBottom: `2px solid ${active ? "var(--navy)" : "transparent"}`,
                marginBottom: -1,
                cursor: "pointer",
                whiteSpace: "nowrap",
                fontWeight: active ? 600 : 500,
                letterSpacing: "0.02em",
                transition: "color 0.12s, border-color 0.12s",
              }}
            >
              {label}
              {typeof n === "number" && (
                <span className="fv-mono" style={{ marginLeft: 6, fontSize: 10, color: active ? "var(--gold)" : "var(--muted)" }}>
                  ({n})
                </span>
              )}
            </button>
          );
        })}
      </div>

      <div style={{ padding: 22 }} key={tab} className="animate-fadeIn">
        {tab === "drivers" && (
          <DecisionDriversInline d={d} fails={failures.fails} warns={failures.warns} setTab={setTab} />
        )}
        {tab === "compliance" && (
          <ChecksSection
            title="Compliance checks"
            rows={d.compliance.map((c) => ({ name: c.name, status: c.status, details: c.details }))}
          />
        )}
        {tab === "crossdoc" && <CrossDocTab rows={d.crossDoc} />}
        {tab === "fraud" && <FraudTab rows={d.fraud} report={report} />}
        {tab === "report" && (
          d.hasReport && report
            ? <ReportPanel report={report} useCase={d.useCase} />
            : <ReportPendingCard d={d} />
        )}
        {tab === "override" && d.hasReport && <OverridePanel detail={d} onOverride={onOverride} />}
        {tab === "override" && !d.hasReport && (
          <div style={{ padding: 32, textAlign: "center", color: "var(--muted)" }}>
            <div className="fv-h-section" style={{ marginBottom: 4, color: "var(--ink)" }}>Override unavailable</div>
            <div style={{ fontSize: 12 }}>Wait for the pipeline to produce a report before overriding the decision.</div>
          </div>
        )}
      </div>
    </div>
  );
}

function DecisionDriversInline({ d, fails, warns, setTab }: {
  d: AppDetail;
  fails: DriverItem[];
  warns: DriverItem[];
  setTab: (t: FindingsTabKey) => void;
}) {
  if (fails.length === 0 && warns.length === 0) {
    if (d.hasReport) {
      return (
        <div style={{ display: "flex", alignItems: "center", gap: 14, padding: 16, background: "rgba(45,110,107,0.05)", border: "1px solid rgba(45,110,107,0.2)", borderRadius: 2 }}>
          <span style={{ width: 36, height: 36, background: "var(--positive)", color: "var(--paper)", display: "grid", placeItems: "center", borderRadius: 2, flexShrink: 0 }}>
            <svg viewBox="0 0 20 20" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
              <path d="m5 10 3.5 3.5L15 7" />
            </svg>
          </span>
          <div>
            <div style={{ fontFamily: "'Fraunces', Georgia, serif", fontSize: 16, color: "var(--positive)", fontWeight: 500 }}>
              All checks passed.
            </div>
            <div style={{ fontSize: 12.5, color: "var(--muted)", marginTop: 2 }}>
              No compliance issues, no cross-document mismatches, no fraud signals.
            </div>
          </div>
        </div>
      );
    }
    return (
      <div style={{ padding: 24, textAlign: "center", color: "var(--muted)" }}>
        <div className="fv-h-section" style={{ marginBottom: 4, color: "var(--ink)" }}>Pipeline still running</div>
        <div style={{ fontSize: 12 }}>Decision drivers will appear once compliance, cross-doc, and fraud stages complete.</div>
      </div>
    );
  }

  return (
    <div>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 14, flexWrap: "wrap", gap: 8 }}>
        <p className="fv-lede" style={{ fontSize: 12.5, maxWidth: 500 }}>
          Why the verification flagged this application — in plain English. Click any driver to inspect.
        </p>
        <div style={{ fontSize: 11, color: "var(--muted)" }}>
          {fails.length > 0 && <span style={{ color: "var(--bordeaux)", fontWeight: 500 }}>{fails.length} failure{fails.length === 1 ? "" : "s"}</span>}
          {fails.length > 0 && warns.length > 0 && <span style={{ margin: "0 6px", color: "var(--rule)" }}>·</span>}
          {warns.length > 0 && <span style={{ color: "var(--gold)", fontWeight: 500 }}>{warns.length} warning{warns.length === 1 ? "" : "s"}</span>}
        </div>
      </div>
      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        {fails.map((it, i) => <DriverRow key={`f-${i}`} item={it} setTab={setTab} severity="fail" />)}
        {warns.map((it, i) => <DriverRow key={`w-${i}`} item={it} setTab={setTab} severity="warn" />)}
      </div>
    </div>
  );
}

function DriverRow({ item, setTab, severity }: { item: DriverItem; setTab: (t: FindingsTabKey) => void; severity: "fail" | "warn" }) {
  const headline = ruleHeadline(item.name, item.status, item.details);
  const tabKey: FindingsTabKey = item.kind === "compliance" ? "compliance" : item.kind === "crossdoc" ? "crossdoc" : "fraud";
  const sourceLabelStr = item.kind === "compliance" ? "Compliance" : item.kind === "crossdoc" ? "Cross-document" : "Fraud signal";
  return (
    <button onClick={() => setTab(tabKey)} className={`fv-driver-row ${severity}`}>
      <span className="ico">{severity === "fail" ? "✕" : "!"}</span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap" }}>
          <span style={{ fontSize: 9.5, letterSpacing: "0.16em", textTransform: "uppercase", fontWeight: 700, color: severity === "fail" ? "var(--bordeaux)" : "var(--gold)" }}>
            {sourceLabelStr}
          </span>
          <span className="fv-h-section" style={{ fontSize: 13 }}>{ruleTitle(item.name)}</span>
        </div>
        <div style={{ fontSize: 12, color: "var(--ink)", marginTop: 4, lineHeight: 1.55 }}>{headline}</div>
      </div>
      <span className="arrow">View →</span>
    </button>
  );
}


function ChecksSection({ title, rows }: { title: string; rows: { name: string; status: string; details: Record<string, unknown> }[] }) {
  const counts = rows.reduce((a, r) => (a[r.status] = (a[r.status] || 0) + 1, a), {} as Record<string, number>);
  return (
    <div>
      <CountStrip label="Total" total={rows.length} pass={counts.pass || 0} fail={counts.fail || 0} warn={counts.warning || 0} />
      <div style={{ display: "flex", flexDirection: "column", gap: 8, marginTop: 16 }}>
        {rows.length === 0 && (
          <div style={{ padding: 24, textAlign: "center", fontSize: 13, color: "var(--muted)" }}>
            ({title.toLowerCase()} not yet run)
          </div>
        )}
        {rows.map((r, i) => <RuleCard key={i} name={r.name} status={r.status} details={r.details} />)}
      </div>
    </div>
  );
}

function CrossDocTab({ rows }: { rows: AppDetail["crossDoc"] }) {
  const counts = useMemo(() => {
    const c = { pass: 0, fail: 0, warning: 0 };
    rows.forEach(r => {
      if (r.status === "pass") c.pass++;
      else if (r.status === "fail") c.fail++;
      else c.warning++;
    });
    return c;
  }, [rows]);
  return (
    <div>
      <CountStrip label="Total" total={rows.length} pass={counts.pass} fail={counts.fail} warn={counts.warning} />
      <div style={{ display: "flex", flexDirection: "column", gap: 8, marginTop: 16 }}>
        {rows.length === 0 && (
          <div style={{ padding: 24, textAlign: "center", fontSize: 13, color: "var(--muted)" }}>
            (cross-doc validation not run yet)
          </div>
        )}
        {rows.map((c, i) => {
          const involved = (c.details as any)?.involved_doc_types || (c.details as any)?.docs || (c.details as any)?.involvedDocs;
          return (
            <RuleCard
              key={i}
              name={c.ruleName}
              status={c.status}
              details={c.details}
              involved={Array.isArray(involved) ? involved.map(sourceLabel).join(", ") : involved}
            />
          );
        })}
      </div>
    </div>
  );
}

function FraudTab({ rows, report }: { rows: AppDetail["fraud"]; report: any }) {
  const bySev = rows.reduce((a, r) => (a[r.severity] = (a[r.severity] || 0) + 1, a), {} as Record<string, number>);
  const fraudScore = report?.fraud?.overall_score;
  return (
    <div>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", border: "1px solid var(--rule)", borderRadius: 2, marginBottom: 18, overflow: "hidden" }}>
        <CountCell label="Total signals" value={rows.length} />
        <CountCell label="High" value={bySev.high || 0} tone="crit" border />
        <CountCell label="Medium" value={bySev.med || 0} tone="warn" border />
        <CountCell label="Low" value={bySev.low || 0} tone="muted" border />
      </div>
      {fraudScore != null && (
        <div style={{ background: "var(--paper-2)", border: "1px solid var(--rule)", borderRadius: 2, padding: 18, marginBottom: 16 }}>
          <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 8 }}>
            <span className="fv-h-section" style={{ fontSize: 14 }}>Aggregate fraud score</span>
            <span className="fv-mono" style={{ fontSize: 18, fontWeight: 500, color: "var(--ink)" }}>{Number(fraudScore).toFixed(2)}</span>
          </div>
          <ScoreBar value={fraudScore} max={1} reverse />
          <div style={{ fontSize: 11, color: "var(--muted)", marginTop: 8 }}>
            0 = clean · 1 = high probability of fraud
          </div>
        </div>
      )}
      <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
        {rows.length === 0 && (
          <div style={{ padding: 24, textAlign: "center", fontSize: 13, color: "var(--muted)" }}>
            (no fraud signals yet)
          </div>
        )}
        {rows.map((f, i) => <FraudCard key={i} row={f} />)}
      </div>
    </div>
  );
}

function CountStrip({ label, total, pass, fail, warn }: { label: string; total: number; pass: number; fail: number; warn: number }) {
  return (
    <div style={{ display: "grid", gridTemplateColumns: "repeat(4, 1fr)", border: "1px solid var(--rule)", borderRadius: 2, overflow: "hidden" }}>
      <CountCell label={label} value={total} />
      <CountCell label="Passed" value={pass} tone="ok" border />
      <CountCell label="Failed" value={fail} tone="crit" border />
      <CountCell label="Warnings" value={warn} tone="warn" border />
    </div>
  );
}

function CountCell({ label, value, tone, border }: { label: string; value: number; tone?: "ok" | "warn" | "crit" | "muted"; border?: boolean }) {
  const color = tone === "ok" ? "var(--positive)" : tone === "crit" ? "var(--bordeaux)" : tone === "warn" ? "var(--gold)" : tone === "muted" ? "var(--muted)" : "var(--ink)";
  return (
    <div style={{ padding: "14px 16px", borderLeft: border ? "1px solid var(--rule)" : "none", background: "var(--paper)" }}>
      <div style={{ fontSize: 10, letterSpacing: "0.14em", textTransform: "uppercase", color: "var(--muted)", fontWeight: 600 }}>{label}</div>
      <div style={{ fontFamily: "'Fraunces', Georgia, serif", fontSize: 24, color, marginTop: 4, fontWeight: 400 }}>{value}</div>
    </div>
  );
}

function ScoreBar({ value, max, reverse = false }: { value: number; max: number; reverse?: boolean }) {
  const pct = Math.max(0, Math.min(1, value / max)) * 100;
  const tone = reverse
    ? (pct >= 50 ? "var(--bordeaux)" : pct >= 25 ? "var(--gold)" : "var(--positive)")
    : (pct >= 50 ? "var(--positive)" : pct >= 25 ? "var(--gold)" : "var(--bordeaux)");
  return (
    <div style={{ width: "100%", height: 6, background: "var(--paper)", border: "1px solid var(--rule)", borderRadius: 1, overflow: "hidden" }}>
      <div style={{ height: "100%", width: `${pct}%`, background: tone, transition: "width 0.5s" }} />
    </div>
  );
}

function RuleCard({ name, status, details, involved }: {
  name: string; status: string; details: any; involved?: string;
}) {
  const [open, setOpen] = useState(false);
  const headline = ruleHeadline(name, status, details);
  const desc = ruleDescription(name);
  return (
    <button type="button" onClick={() => setOpen(!open)} className={`fv-rule-card${open ? " open" : ""}`}>
      <span className={`accent ${status}`} aria-hidden />
      <div className="body">
        <div className="top">
          <div style={{ minWidth: 0 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", marginBottom: 4 }}>
              <StatusBadge s={status} />
              <span className="title">{ruleTitle(name)}</span>
            </div>
            <div className="headline">{headline}</div>
            {involved && (
              <div className="involves">Involves: {involved}</div>
            )}
          </div>
          <span className="chev">▾</span>
        </div>
        {open && (
          <div className="expanded" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            {desc && (
              <div style={{ fontSize: 12, color: "var(--muted)", lineHeight: 1.55 }}>
                <strong style={{ color: "var(--ink)", fontWeight: 600 }}>What we check:</strong> {desc}
              </div>
            )}
            <RuleDetails name={name} status={status} details={details} />
            <details>
              <summary style={{ cursor: "pointer", fontSize: 11, color: "var(--muted)" }}>Raw JSON</summary>
              <pre className="fv-json-pre" style={{ marginTop: 8 }}>
                {JSON.stringify(details, null, 2)}
              </pre>
            </details>
          </div>
        )}
      </div>
    </button>
  );
}

function FraudCard({ row }: { row: AppDetail["fraud"][number] }) {
  const [open, setOpen] = useState(false);
  return (
    <button type="button" onClick={() => setOpen(!open)} className={`fv-rule-card${open ? " open" : ""}`}>
      <span className={`accent ${row.severity}`} aria-hidden />
      <div className="body">
        <div className="top">
          <div style={{ minWidth: 0 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 8, flexWrap: "wrap", marginBottom: 4 }}>
              <SevBadge s={row.severity} />
              <span className="title">{prettyFraudName(row.signalName)}</span>
              <span className="fv-mono" style={{ fontSize: 11, color: "var(--muted)" }}>score {row.score.toFixed(2)}</span>
            </div>
            <div className="headline">{fraudHeadline(row)}</div>
          </div>
          <span className="chev">▾</span>
        </div>
        {open && (
          <div className="expanded" style={{ display: "flex", flexDirection: "column", gap: 10 }}>
            <RuleDetails name={row.signalName} status={row.severity === "high" ? "fail" : row.severity === "med" ? "warning" : "pass"} details={row.details} />
            <details>
              <summary style={{ cursor: "pointer", fontSize: 11, color: "var(--muted)" }}>Raw JSON</summary>
              <pre className="fv-json-pre" style={{ marginTop: 8 }}>
                {JSON.stringify(row.details, null, 2)}
              </pre>
            </details>
          </div>
        )}
      </div>
    </button>
  );
}

function fraudHeadline(row: AppDetail["fraud"][number]): string {
  const s = row.severity;
  const sev = s === "high" ? "high-risk" : s === "med" ? "medium-risk" : "low-risk";
  const d: any = row.details ?? {};
  if (typeof d.reason === "string") return d.reason;
  if (typeof d.message === "string") return d.message;
  if (typeof d.summary === "string") return d.summary;
  return `Fraud signal flagged with ${sev} severity (score ${row.score.toFixed(2)}).`;
}

function ReportPanel({ report, useCase }: { report: any; useCase: "kyc" | "loan" }) {
  const fraudScore = report?.fraud?.overall_score;
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "center", flexWrap: "wrap", gap: 8 }}>
        <span className="fv-h-section">Verification report</span>
        <span style={{ fontSize: 10, letterSpacing: "0.16em", textTransform: "uppercase", color: "var(--muted)", fontWeight: 600 }}>
          {useCase === "kyc" ? "KYC" : "Loan origination"}
        </span>
      </div>

      <div style={{ display: "grid", gridTemplateColumns: useCase === "loan" ? "repeat(5, 1fr)" : "repeat(3, 1fr)", gap: 1, background: "var(--rule)", border: "1px solid var(--rule)", borderRadius: 2 }}>
        <Metric label="Recommendation" value={<RecBadge r={report.recommendation} />} />
        <Metric label="Overall score" value={report.overall_score != null ? Number(report.overall_score).toFixed(2) : "—"} mono />
        <Metric label="Fraud score" value={fraudScore != null ? Number(fraudScore).toFixed(2) : "—"} mono />
        {useCase === "loan" && (
          <>
            <Metric label="Credit score" value={report.credit_score ?? "—"} mono />
            <Metric label="DTI" value={report.debt?.dti_ratio != null ? `${(report.debt.dti_ratio * 100).toFixed(1)}%` : "—"} mono />
          </>
        )}
      </div>

      {useCase === "loan" && report.income && (
        <div>
          <div className="fv-h-section" style={{ fontSize: 14, marginBottom: 10 }}>Income</div>
          <div style={{ border: "1px solid var(--rule)", borderRadius: 2, overflow: "hidden" }}>
            <table style={{ width: "100%", fontSize: 13, borderCollapse: "collapse" }}>
              <tbody>
                {[
                  ["Declared monthly", inr(report.income.declared_monthly_inr)],
                  ["Declared annual", inr(report.income.declared_annual_inr)],
                  ["Annual (payslips)", inr(report.income.annual_from_payslip)],
                  ["Annual (bank)", inr(report.income.annual_from_bank)],
                  ["Annual (ITR)", inr(report.income.annual_from_itr)],
                  ["Existing EMI", inr(report.debt?.existing_emi_inr)],
                ].map(([k, v], i) => (
                  <tr key={k as string} style={{ background: i % 2 ? "var(--paper-2)" : "var(--paper)" }}>
                    <td style={{ padding: "10px 14px", color: "var(--muted)", borderBottom: "1px solid var(--rule-soft)" }}>{k}</td>
                    <td style={{ padding: "10px 14px", textAlign: "right", fontFamily: "'JetBrains Mono', monospace", color: "var(--ink)", borderBottom: "1px solid var(--rule-soft)", fontWeight: 500 }}>{v}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      )}

      <details style={{ border: "1px solid var(--rule)", borderRadius: 2 }}>
        <summary style={{ cursor: "pointer", fontSize: 13, fontWeight: 500, padding: 12 }}>
          Full report JSON
        </summary>
        <pre className="fv-json-pre" style={{ margin: 12, marginTop: 0, maxHeight: 400 }}>
          {JSON.stringify(report, null, 2)}
        </pre>
      </details>
    </div>
  );
}

function Metric({ label, value, mono }: { label: string; value: React.ReactNode; mono?: boolean }) {
  return (
    <div style={{ background: "var(--paper)", padding: "12px 16px" }}>
      <div style={{ fontSize: 9.5, letterSpacing: "0.14em", textTransform: "uppercase", color: "var(--muted)", fontWeight: 600 }}>{label}</div>
      <div style={{ marginTop: 4, fontFamily: mono ? "'JetBrains Mono', monospace" : "inherit", fontWeight: 500, color: "var(--ink)", fontSize: 14 }}>{value}</div>
    </div>
  );
}

function ReportPendingCard({ d }: { d: AppDetail }) {
  const stagesDone = [
    d.documents.length > 0,
    d.documents.length > 0 && d.documents.every(x => x.ocrDone),
    d.documents.length > 0 && d.documents.every(x => x.classifiedType != null),
    d.documents.length > 0 && d.documents.every(x => x.fieldsExtracted > 0),
    d.compliance.length > 0,
    d.crossDoc.length > 0,
    d.fraud.length > 0,
    d.hasReport,
  ].filter(Boolean).length;
  const pct = Math.round((stagesDone / 8) * 100);
  return (
    <div style={{ padding: 32, textAlign: "center" }}>
      <div className="fv-h-section" style={{ marginBottom: 4, fontSize: 16 }}>Pipeline running</div>
      <div style={{ fontSize: 13, color: "var(--muted)", marginBottom: 14 }}>The report will appear here once verification completes.</div>
      <div style={{ maxWidth: 320, margin: "0 auto" }}>
        <div style={{ height: 4, background: "var(--paper-2)", border: "1px solid var(--rule)", borderRadius: 1, overflow: "hidden" }}>
          <div style={{ height: "100%", width: `${pct}%`, background: "linear-gradient(90deg, var(--navy), var(--gold))", transition: "width 0.5s" }} />
        </div>
        <div className="fv-mono" style={{ fontSize: 11, color: "var(--muted)", marginTop: 8 }}>
          {stagesDone} / 8 stages · {pct}%
        </div>
      </div>
    </div>
  );
}

function OverridePanel({ detail, onOverride }: { detail: AppDetail; onOverride: () => void }) {
  const toast = useToast();
  const confirm = useConfirm();
  const [open, setOpen] = useState(false);
  const [reason, setReason] = useState("");
  const [newRec, setNewRec] = useState(detail.useCase === "kyc" ? "verified" : "approve");
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  const options = detail.useCase === "kyc"
    ? ["verified", "manual_review", "reject"]
    : ["approve", "manual_review", "reject"];

  async function apply() {
    if (reason.trim().length < 3) { setErr("Please give a reason (min 3 chars)."); return; }
    const isReject = newRec === "reject";
    const ok = await confirm({
      title: `Set decision to "${newRec.replace("_", " ")}"?`,
      body: `This overrides the automated decision for ${detail.applicantName} and is recorded in the audit trail.`,
      confirmLabel: "Apply override",
      destructive: isReject,
    });
    if (!ok) return;

    setBusy(true); setErr(null);
    try {
      await api.override(detail.applicationId, newRec, reason.trim());
      toast.success("Decision overridden", `New decision: ${newRec.replace("_", " ")}`);
      setOpen(false); setReason("");
      onOverride();
    } catch (e: any) {
      setErr(e.message || "override failed");
      toast.error("Override failed", e?.message);
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 12, flexWrap: "wrap" }}>
        <div>
          <div className="fv-h-section">Decision override</div>
          <p className="fv-lede" style={{ fontSize: 12.5, marginTop: 4 }}>Manually override the automated decision. Logged in the audit trail.</p>
        </div>
        <button onClick={() => setOpen(!open)} className="fv-btn fv-btn-secondary">
          {open ? "Cancel" : "Override decision →"}
        </button>
      </div>
      {open && (
        <div style={{ display: "flex", flexDirection: "column", gap: 12, padding: 18, background: "var(--paper-2)", border: "1px solid var(--rule)", borderRadius: 2 }}>
          <label className="block">
            <span className="fv-field-label">New decision</span>
            <select value={newRec} onChange={e => setNewRec(e.target.value)} className="fv-input" style={{ width: 240 }}>
              {options.map(o => <option key={o} value={o}>{o}</option>)}
            </select>
          </label>
          <label className="block">
            <span className="fv-field-label">Reason <span className="req">*</span> <span style={{ color: "var(--muted)", fontWeight: 400, textTransform: "none", letterSpacing: 0, marginLeft: 4 }}>— min 3 chars</span></span>
            <textarea value={reason} onChange={e => setReason(e.target.value)} rows={3} className="fv-input" />
          </label>
          {err && <div className="fv-alert error"><span>⚠</span><span>{err}</span></div>}
          <div>
            <button disabled={busy} onClick={apply} className="fv-btn fv-btn-primary">
              {busy ? <><span className="fv-spinner" /> Applying…</> : "Apply override →"}
            </button>
          </div>
        </div>
      )}
      {detail.overrides.length > 0 && (
        <div>
          <div className="fv-h-section" style={{ fontSize: 14, marginBottom: 10 }}>Override history</div>
          <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            {detail.overrides.map(o => (
              <div key={o.id} style={{ border: "1px solid var(--rule)", borderRadius: 2, padding: 14, background: "var(--paper-2)" }}>
                <div style={{ display: "flex", alignItems: "center", gap: 8, marginBottom: 6, flexWrap: "wrap" }}>
                  <RecBadge r={o.previousRecommendation} />
                  <span style={{ color: "var(--muted)" }}>→</span>
                  <RecBadge r={o.newRecommendation} />
                  <span style={{ marginLeft: "auto", fontSize: 11, color: "var(--muted)", fontFamily: "'JetBrains Mono', monospace" }}>
                    {new Date(o.createdAt).toLocaleString()} · {o.actorOrg ?? "?"}
                  </span>
                </div>
                <div style={{ fontSize: 12.5, color: "var(--ink)", lineHeight: 1.5 }}>{o.reason}</div>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}


function DetailSkeleton() {
  return (
    <div className="animate-fadeIn" style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <div className="fv-panel" style={{ padding: 24 }}>
        <div className="skeleton h-5 w-48" style={{ marginBottom: 8 }} />
        <div className="skeleton h-3 w-32" style={{ marginBottom: 16 }} />
        <div style={{ display: "flex", gap: 12 }}>
          <div className="skeleton h-14 w-40" />
          <div className="skeleton h-14 w-40" />
          <div className="skeleton h-14 w-40" />
        </div>
      </div>
      <div className="fv-panel" style={{ padding: 24 }}>
        <div className="skeleton h-9 w-full" />
      </div>
    </div>
  );
}

function Chip({ ok, label }: { ok: boolean; label: string }) {
  return <span className={`fv-tag ${ok ? "success" : "muted"}`}><span className="dot" />{label}</span>;
}

function DocIcon() {
  return (
    <svg viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <path d="M5 3h7l3 3v11a.5.5 0 0 1-.5.5h-9.5A.5.5 0 0 1 4.5 17V3.5A.5.5 0 0 1 5 3Z" />
      <path d="M12 3v3h3M7 11h6M7 14h4" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
      <path d="m5 10 3.5 3.5L15 7" />
    </svg>
  );
}

function prettyDocType(s: string | null | undefined): string {
  if (!s) return "—";
  return s.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}
function prettyFieldName(s: string): string {
  return s.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}
function prettyFraudName(s: string): string {
  return s.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}
function methodLabel(m: string | null | undefined): string {
  if (!m) return "—";
  const map: Record<string, string> = {
    docai: "Document AI",
    google_doc_ai: "Google Document AI",
    regex: "Regex",
    llm: "LLM",
    gemini: "Gemini",
    rule: "Rule-based",
    heuristic: "Heuristic",
    ocr: "OCR layout",
  };
  return map[m] ?? prettyFieldName(m);
}

function inr(x: number | null | undefined) {
  return x == null ? "—" : `₹ ${Number(x).toLocaleString("en-IN")}`;
}

function relativeTime(iso: string) {
  const ms = Date.now() - new Date(iso).getTime();
  if (ms < 0) return "just now";
  const s = Math.round(ms / 1000);
  if (s < 5) return "just now";
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  if (d < 7) return `${d}d ago`;
  return new Date(iso).toLocaleDateString();
}
