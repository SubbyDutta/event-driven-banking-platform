import { useEffect, useState } from "react";
import { api, type AuditAction, type AuditLogItem, type AuditLogFilters } from "../api";
import { CopyButton } from "../components/CopyButton";
import { Tooltip } from "../components/Tooltip";
import { useToast } from "../components/Toast";

const ACTIONS: { value: AuditAction | ""; label: string }[] = [
  { value: "", label: "All actions" },
  { value: "decision_override", label: "Decision override" },
  { value: "field_override", label: "Field override" },
  { value: "pipeline_run", label: "Pipeline run" },
];

const SIZES = [10, 20, 50, 100];

export default function AuditLog() {
  const toast = useToast();
  const [items, setItems] = useState<AuditLogItem[]>([]);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [page, setPage] = useState(0);
  const [size, setSize] = useState(20);
  const [loading, setLoading] = useState(true);

  const [applicationId, setApplicationId] = useState("");
  const [actor, setActor] = useState("");
  const [action, setAction] = useState<AuditAction | "">("");
  const [fromDate, setFromDate] = useState("");
  const [toDate, setToDate] = useState("");

  async function load() {
    setLoading(true);
    try {
      const filters: AuditLogFilters = {};
      if (applicationId) filters.applicationId = applicationId;
      if (actor) filters.actor = actor;
      if (action) filters.action = action;
      if (fromDate) filters.fromDate = new Date(fromDate).toISOString();
      if (toDate) filters.toDate = new Date(toDate).toISOString();
      const r = await api.fetchAuditLog(filters, page, size);
      setItems(r.items);
      setTotal(r.total);
      setTotalPages(r.totalPages);
    } catch (e: any) {
      toast.error("Couldn't load audit log", e?.message);
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { load(); /* eslint-disable-next-line */ }, [page, size]);

  function applyFilters() {
    setPage(0);
    load();
  }

  function resetFilters() {
    setApplicationId("");
    setActor("");
    setAction("");
    setFromDate("");
    setToDate("");
    setPage(0);
    setTimeout(load, 0);
  }

  return (
    <>
      <div className="fv-topbar">
        <div className="fv-breadcrumb">
          <span>Governance</span>
          <span className="sep">/</span>
          <span className="current">Audit log</span>
        </div>
        <div className="fv-topbar-right">
          <span className="fv-status-pill">
            <span className="dot" />
            Append-only · WORM
          </span>
        </div>
      </div>

      <main className="fv-page-pad">
        <div className="fv-page-head">
          <div>
            <div className="fv-eyebrow" style={{ marginBottom: 12 }}>
              <span>Audit Trail</span>
              <span className="stamp">IMMUTABLE</span>
            </div>
            <h1 className="fv-h-display" style={{ fontSize: 32 }}>
              Every action, <em>permanently recorded</em>.
            </h1>
            <p className="fv-lede" style={{ marginTop: 10, maxWidth: 580 }}>
              Decision overrides, field edits, and pipeline replays — every state-changing operation
              with actor attribution and full before/after JSON.
            </p>
          </div>
          <div className="right-meta">
            <div className="lab">Records · total</div>
            <div className="val" style={{ fontFamily: "'Fraunces', Georgia, serif", fontSize: 22, color: "var(--ink)" }}>{total}</div>
          </div>
        </div>

        <div className="fv-panel" style={{ padding: 0, marginBottom: 18 }}>
          <div className="fv-panel-head">
            <div>
              <div className="title">Filter trail</div>
              <div className="meta">Narrow by application, actor, action, or date range</div>
            </div>
          </div>
          <div className="fv-panel-body">
            <div style={{ display: "grid", gridTemplateColumns: "repeat(6, 1fr)", gap: 12 }} className="audit-filter-grid">
              <FilterField label="Application ID">
                <input
                  value={applicationId}
                  onChange={(e) => setApplicationId(e.target.value)}
                  placeholder="UUID"
                  className="fv-input mono"
                />
              </FilterField>
              <FilterField label="Actor">
                <input
                  value={actor}
                  onChange={(e) => setActor(e.target.value)}
                  placeholder="org or user"
                  className="fv-input"
                />
              </FilterField>
              <FilterField label="Action">
                <select
                  value={action}
                  onChange={(e) => setAction(e.target.value as AuditAction | "")}
                  className="fv-input"
                >
                  {ACTIONS.map((a) => <option key={a.value} value={a.value}>{a.label}</option>)}
                </select>
              </FilterField>
              <FilterField label="From">
                <input
                  type="date"
                  value={fromDate}
                  onChange={(e) => setFromDate(e.target.value)}
                  className="fv-input"
                />
              </FilterField>
              <FilterField label="To">
                <input
                  type="date"
                  value={toDate}
                  onChange={(e) => setToDate(e.target.value)}
                  className="fv-input"
                />
              </FilterField>
              <FilterField label="Page size">
                <select
                  value={size}
                  onChange={(e) => { setSize(Number(e.target.value)); setPage(0); }}
                  className="fv-input"
                >
                  {SIZES.map((s) => <option key={s} value={s}>{s} / page</option>)}
                </select>
              </FilterField>
            </div>
            <div style={{ marginTop: 16, display: "flex", gap: 8 }}>
              <button onClick={applyFilters} className="fv-btn fv-btn-primary fv-btn-sm">Apply filters</button>
              <button onClick={resetFilters} className="fv-btn fv-btn-secondary fv-btn-sm">Reset</button>
            </div>
          </div>
        </div>

        <div className="fv-panel">
          <div className="fv-panel-head">
            <div>
              <div className="title">Event Stream</div>
              <div className="meta">{total} record{total === 1 ? "" : "s"} · newest first</div>
            </div>
          </div>

          <div style={{ overflowX: "auto" }}>
            <table className="fv-table">
              <thead>
                <tr>
                  <th>Timestamp</th>
                  <th>Actor</th>
                  <th>Action</th>
                  <th>Application</th>
                  <th>Reason</th>
                  <th>Before / After</th>
                </tr>
              </thead>
              <tbody>
                {loading && Array.from({ length: 5 }).map((_, i) => (
                  <tr key={`sk-${i}`}>
                    {Array.from({ length: 6 }).map((__, j) => (
                      <td key={j}>
                        <div className="skeleton h-3 w-24" />
                      </td>
                    ))}
                  </tr>
                ))}
                {!loading && items.length === 0 && (
                  <tr>
                    <td colSpan={6}>
                      <div className="fv-table-empty">
                        <div className="h">No audit entries match these filters</div>
                        <div>Try clearing or broadening the filter range.</div>
                      </div>
                    </td>
                  </tr>
                )}
                {!loading && items.map((it) => <AuditRow key={it.id} item={it} />)}
              </tbody>
            </table>
          </div>

          <div className="fv-table-foot">
            <div>
              Page <span className="fv-mono">{page + 1}</span> of{" "}
              <span className="fv-mono">{Math.max(totalPages, 1)}</span>
              <span style={{ margin: "0 10px", color: "var(--rule)" }}>·</span>
              <span className="fv-mono">{total}</span> {total === 1 ? "entry" : "entries"}
            </div>
            <div className="fv-pager">
              <button disabled={page === 0 || loading} onClick={() => setPage((p) => Math.max(0, p - 1))}>
                ← Prev
              </button>
              <span className="page">PAGE {page + 1} / {Math.max(totalPages, 1)}</span>
              <button disabled={page + 1 >= totalPages || loading} onClick={() => setPage((p) => p + 1)}>
                Next →
              </button>
            </div>
          </div>
        </div>

        <div className="fv-footnote">
          <em>Integrity ·</em> Every event is signed against the prior block's hash, forming a
          tamper-evident chain. Discrepancies are reported to the compliance officer within 60 seconds.
        </div>
      </main>
    </>
  );
}

function FilterField({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="fv-field-label">{label}</span>
      <div>{children}</div>
    </label>
  );
}

function AuditRow({ item }: { item: AuditLogItem }) {
  const [open, setOpen] = useState(false);
  return (
    <tr style={{ verticalAlign: "top" }}>
      <td className="mono" style={{ fontSize: 11, color: "var(--muted)", whiteSpace: "nowrap" }}>
        <Tooltip label={new Date(item.timestamp).toLocaleString()}>
          <span style={{ cursor: "help" }}>{relTime(item.timestamp)}</span>
        </Tooltip>
      </td>
      <td>
        {item.actor || <span style={{ color: "var(--muted)" }}>—</span>}
      </td>
      <td><ActionBadge action={item.action} /></td>
      <td>
        <span style={{ display: "inline-flex", alignItems: "center", gap: 6, fontFamily: "'JetBrains Mono', monospace", fontSize: 11, color: "var(--muted)" }}>
          <span title={item.applicationId}>{item.applicationId.slice(0, 8)}…</span>
          <CopyButton value={item.applicationId} label="application ID" />
        </span>
      </td>
      <td style={{ maxWidth: 280 }} title={item.reason || ""}>
        <div style={{ fontSize: 12.5, color: "var(--ink)", overflow: "hidden", textOverflow: "ellipsis", display: "-webkit-box", WebkitLineClamp: 2, WebkitBoxOrient: "vertical" }}>
          {item.reason || <span style={{ color: "var(--muted)" }}>—</span>}
        </div>
      </td>
      <td>
        <button
          onClick={() => setOpen((v) => !v)}
          className="fv-btn-inline"
        >
          {open ? "Hide" : "Show"} JSON
        </button>
        {open && (
          <div style={{ marginTop: 10, display: "grid", gridTemplateColumns: "1fr 1fr", gap: 6, maxWidth: 500 }}>
            <JsonBlock title="Before" value={item.before} />
            <JsonBlock title="After" value={item.after} />
          </div>
        )}
      </td>
    </tr>
  );
}

function JsonBlock({ title, value }: { title: string; value: Record<string, unknown> | null }) {
  return (
    <div style={{ border: "1px solid var(--rule)", background: "var(--paper-2)", padding: 8, borderRadius: 2 }}>
      <div style={{ fontSize: 9.5, fontWeight: 600, textTransform: "uppercase", letterSpacing: "0.14em", color: "var(--muted)", marginBottom: 6 }}>
        {title}
      </div>
      <pre style={{ margin: 0, fontSize: 10.5, color: "var(--ink)", fontFamily: "'JetBrains Mono', monospace", whiteSpace: "pre-wrap", wordBreak: "break-word" }}>
        {value ? JSON.stringify(value, null, 2) : "—"}
      </pre>
    </div>
  );
}

function ActionBadge({ action }: { action: AuditAction }) {
  const tone: Record<AuditAction, "warn" | "info" | "success"> = {
    decision_override: "warn",
    field_override: "info",
    pipeline_run: "success",
  };
  const label: Record<AuditAction, string> = {
    decision_override: "decision override",
    field_override: "field override",
    pipeline_run: "pipeline run",
  };
  return (
    <span className={`fv-tag ${tone[action]}`}>
      <span className="dot" />
      {label[action]}
    </span>
  );
}

function relTime(iso: string) {
  const ms = Date.now() - new Date(iso).getTime();
  const s = Math.round(ms / 1000);
  if (s < 60) return `${s}s ago`;
  const m = Math.round(s / 60);
  if (m < 60) return `${m}m ago`;
  const h = Math.round(m / 60);
  if (h < 24) return `${h}h ago`;
  const d = Math.round(h / 24);
  if (d < 7) return `${d}d ago`;
  return new Date(iso).toLocaleDateString();
}
