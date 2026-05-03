import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { api, type AppListItem } from "../api";
import { RecBadge, UseCaseBadge } from "../components/Badges";
import { Tooltip } from "../components/Tooltip";
import { CopyButton } from "../components/CopyButton";
import { useToast } from "../components/Toast";

const PAGE_SIZE = 25;

export default function Dashboard() {
  const toast = useToast();
  const [apps, setApps] = useState<AppListItem[]>([]);
  const [filter, setFilter] = useState<"all" | "kyc" | "loan">("all");
  const [page, setPage] = useState(0);
  const [total, setTotal] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [query, setQuery] = useState("");
  const [loading, setLoading] = useState(true);
  const [refreshing, setRefreshing] = useState(false);
  const searchRef = useRef<HTMLInputElement>(null);

  async function load(opts: { silent?: boolean } = {}) {
    try {
      if (opts.silent) setRefreshing(true);
      else setLoading(true);
      const r = await api.listApps(filter === "all" ? undefined : filter, page, PAGE_SIZE);
      setApps(r.items);
      setTotal(r.total);
      setTotalPages(r.totalPages);
    } catch (e: any) {
      toast.error("Couldn't load submissions", e?.message);
    } finally {
      setLoading(false);
      setRefreshing(false);
    }
  }

  useEffect(() => { setPage(0); }, [filter]);
  useEffect(() => { load(); /* eslint-disable-next-line */ }, [filter, page]);

  useEffect(() => {
    function onKey(e: KeyboardEvent) {
      const isMod = e.metaKey || e.ctrlKey;
      const inField = document.activeElement?.tagName === "INPUT" || document.activeElement?.tagName === "TEXTAREA";
      if ((isMod && e.key.toLowerCase() === "k") || (e.key === "/" && !inField)) {
        e.preventDefault();
        searchRef.current?.focus();
        searchRef.current?.select();
      }
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const filtered = useMemo(() => {
    if (!query) return apps;
    const q = query.toLowerCase();
    return apps.filter(a =>
      a.applicantName.toLowerCase().includes(q) ||
      a.externalId.toLowerCase().includes(q) ||
      a.status.toLowerCase().includes(q)
    );
  }, [apps, query]);

  const kpis = useMemo(() => {
    const k = { approve: 0, manual: 0, reject: 0 };
    apps.forEach(a => {
      const r = a.effectiveRecommendation;
      if (r === "approve" || r === "verified") k.approve++;
      else if (r === "reject") k.reject++;
      else if (r) k.manual++;
    });
    return k;
  }, [apps]);

  const isMac = typeof navigator !== "undefined" && /mac/i.test(navigator.platform);

  return (
    <>
      <div className="fv-topbar">
        <div className="fv-breadcrumb">
          <span>Verification</span>
          <span className="sep">/</span>
          <span className="current">Submissions</span>
        </div>
        <div className="fv-topbar-right">
          <span className="fv-status-pill">
            <span className="dot" />
            Pipeline live
          </span>
          <span className="fv-mono" style={{ fontSize: 11 }}>
            {new Date().toLocaleDateString("en-IN", { day: "2-digit", month: "short", year: "numeric" })}
          </span>
        </div>
      </div>

      <main className="fv-page-pad">
        <span className="fv-watermark">RESTRICTED · OPERATOR · FINDOC-VERIFY</span>

        <div className="fv-page-head">
          <div>
            <div className="fv-eyebrow" style={{ marginBottom: 12 }}>
              <span>Submissions</span>
              <span className="stamp">{total} TOTAL</span>
            </div>
            <h1 className="fv-h-display" style={{ fontSize: 32 }}>
              Every <em>verification</em>, in one ledger.
            </h1>
            <p className="fv-lede" style={{ marginTop: 10, maxWidth: 580 }}>
              All applications submitted with your API key — KYC and loan origination side by side.
              Use ⌘K to search, click any row to inspect the pipeline timeline.
            </p>
          </div>

          <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
            <div style={{ position: "relative" }}>
              <span style={{ position: "absolute", left: 12, top: "50%", transform: "translateY(-50%)", color: "var(--muted)", pointerEvents: "none" }}>
                <svg viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="9" cy="9" r="6" /><path d="m17 17-3.5-3.5" />
                </svg>
              </span>
              <input
                ref={searchRef}
                value={query}
                onChange={(e) => setQuery(e.target.value)}
                placeholder="Search applicants, IDs, or status…"
                className="fv-input"
                style={{ paddingLeft: 36, paddingRight: 64, width: 280 }}
              />
              {query ? (
                <button
                  onClick={() => setQuery("")}
                  className="fv-btn-ghost"
                  style={{ position: "absolute", right: 4, top: "50%", transform: "translateY(-50%)", padding: "4px 8px" }}
                  aria-label="Clear search"
                >
                  ✕
                </button>
              ) : (
                <span
                  className="fv-mono"
                  style={{
                    position: "absolute",
                    right: 8,
                    top: "50%",
                    transform: "translateY(-50%)",
                    fontSize: 10,
                    color: "var(--muted)",
                    background: "var(--paper-2)",
                    border: "1px solid var(--rule)",
                    borderRadius: 2,
                    padding: "2px 6px",
                    pointerEvents: "none",
                  }}
                >
                  {isMac ? "⌘K" : "Ctrl K"}
                </span>
              )}
            </div>
            <Tooltip label="Refresh">
              <button
                onClick={() => load({ silent: true })}
                disabled={refreshing || loading}
                className="fv-btn-icon"
                aria-label="Refresh"
              >
                <svg viewBox="0 0 20 20" className={`h-4 w-4 ${refreshing ? "animate-spin" : ""}`} fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
                  <path d="M3 10a7 7 0 0 1 12-5l2 2M17 10a7 7 0 0 1-12 5l-2-2" />
                  <path d="M17 3v4h-4M3 17v-4h4" />
                </svg>
              </button>
            </Tooltip>
            <Link to="/new/kyc" className="fv-btn fv-btn-secondary">+ New KYC</Link>
            <Link to="/new/loan" className="fv-btn fv-btn-primary">+ New loan</Link>
          </div>
        </div>

        <div className="fv-kpi-row">
          <KPICell num="01" label="Total on page" value={apps.length} sub={`Page ${page + 1} of ${Math.max(totalPages, 1)}`} tone="muted" />
          <KPICell num="02" label="Approved · verified" value={kpis.approve} sub="Auto-cleared" tone="ok" />
          <KPICell num="03" label="Manual review" value={kpis.manual} sub="Needs reviewer" tone="warn" />
          <KPICell num="04" label="Rejected" value={kpis.reject} sub="Decision: reject" tone="featured" />
        </div>

        <div style={{ display: "flex", gap: 8, alignItems: "center", marginBottom: 18, flexWrap: "wrap" }}>
          {(["all", "kyc", "loan"] as const).map((f) => (
            <button
              key={f}
              onClick={() => setFilter(f)}
              className={`fv-chip${filter === f ? " active" : ""}`}
            >
              {f === "all" ? "All" : f.toUpperCase()}
            </button>
          ))}
          {query && (
            <span style={{ marginLeft: "auto", fontSize: 11, color: "var(--muted)" }}>
              Filtering: <span style={{ color: "var(--ink)", fontWeight: 500 }}>{filtered.length}</span> of {apps.length}
              <button
                onClick={() => setQuery("")}
                style={{ marginLeft: 8, color: "var(--bordeaux)", background: "none", border: "none", cursor: "pointer", fontFamily: "inherit", fontSize: 11 }}
              >
                clear
              </button>
            </span>
          )}
        </div>

        <div className="fv-panel">
          <div className="fv-panel-head">
            <div>
              <div className="title">All Submissions</div>
              <div className="meta">{filter === "all" ? "Mixed feed" : filter.toUpperCase() + " only"} · sorted by submission desc</div>
            </div>
          </div>

          <div style={{ overflowX: "auto" }}>
            <table className="fv-table">
              <thead>
                <tr>
                  <th>Type</th>
                  <th>Applicant</th>
                  <th>External ID</th>
                  <th>Status</th>
                  <th>Decision</th>
                  <th>
                    <Tooltip label="Aggregate verification confidence (0–1, higher = stronger)">
                      <span className="cursor-help inline-flex items-center gap-1">Score <InfoMark /></span>
                    </Tooltip>
                  </th>
                  <th>Submitted</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {loading && Array.from({ length: 5 }).map((_, i) => (
                  <tr key={`skeleton-${i}`}>
                    {Array.from({ length: 8 }).map((__, j) => (
                      <td key={j}>
                        <div className={`skeleton h-3 ${j === 0 ? "w-12" : j === 1 ? "w-32" : j === 2 ? "w-40" : j === 7 ? "w-6 ml-auto" : "w-20"}`} />
                      </td>
                    ))}
                  </tr>
                ))}
                {!loading && filtered.length === 0 && (
                  <tr>
                    <td colSpan={8}>
                      <div className="fv-table-empty">
                        {query ? (
                          <>
                            <div className="h">No matches for "{query}"</div>
                            <button
                              onClick={() => setQuery("")}
                              className="fv-btn fv-btn-secondary"
                              style={{ marginTop: 12 }}
                            >
                              Clear search
                            </button>
                          </>
                        ) : (
                          <>
                            <div className="h">No submissions yet</div>
                            <div>Get started by creating your first KYC or loan submission.</div>
                            <div style={{ display: "inline-flex", gap: 8, marginTop: 14 }}>
                              <Link to="/new/kyc" className="fv-btn fv-btn-secondary">Start KYC</Link>
                              <Link to="/new/loan" className="fv-btn fv-btn-primary">Start loan</Link>
                            </div>
                          </>
                        )}
                      </div>
                    </td>
                  </tr>
                )}
                {!loading && filtered.map((a) => {
                  const wasOverridden = a.effectiveRecommendation && a.recommendation && a.effectiveRecommendation !== a.recommendation;
                  return (
                    <tr key={a.applicationId}>
                      <td><UseCaseBadge u={a.useCase} /></td>
                      <td>
                        <Link
                          to={`/app/${a.applicationId}`}
                          style={{
                            color: "var(--ink)",
                            textDecoration: "none",
                            fontWeight: 500,
                            fontFamily: "'Fraunces', Georgia, serif",
                            fontSize: 14,
                          }}
                        >
                          {a.applicantName}
                        </Link>
                      </td>
                      <td>
                        <span className="mono" style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 11, color: "var(--muted)" }}>
                          {a.externalId}
                          <CopyButton value={a.externalId} label="external ID" />
                        </span>
                      </td>
                      <td><StatusPill status={a.status} /></td>
                      <td>
                        <div style={{ display: "flex", alignItems: "center", gap: 8 }}>
                          <RecBadge r={a.effectiveRecommendation} />
                          {wasOverridden && (
                            <Tooltip label={`Auto-decision was ${a.recommendation}, then overridden`}>
                              <span className="mono" style={{ fontSize: 10, color: "var(--muted)", textDecoration: "line-through", cursor: "help" }}>
                                {a.recommendation}
                              </span>
                            </Tooltip>
                          )}
                        </div>
                      </td>
                      <td><ScoreCell score={a.overallScore} /></td>
                      <td>
                        <Tooltip label={new Date(a.createdAt).toLocaleString()}>
                          <span className="mono" style={{ fontSize: 11, color: "var(--muted)", cursor: "help" }}>
                            {relativeTime(a.createdAt)}
                          </span>
                        </Tooltip>
                      </td>
                      <td className="right">
                        <Link
                          to={`/app/${a.applicationId}`}
                          style={{
                            color: "var(--ink)",
                            textDecoration: "none",
                            fontSize: 11,
                            letterSpacing: "0.06em",
                            textTransform: "uppercase",
                            borderBottom: "1px solid var(--ink)",
                            paddingBottom: 1,
                          }}
                        >
                          Inspect →
                        </Link>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>

          <div className="fv-table-foot">
            <div>
              Page <span className="fv-mono">{page + 1}</span> of{" "}
              <span className="fv-mono">{Math.max(totalPages, 1)}</span>
              <span style={{ margin: "0 10px", color: "var(--rule)" }}>·</span>
              <span className="fv-mono">{total}</span> {total === 1 ? "application" : "applications"}
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
      </main>
    </>
  );
}

function KPICell({ num, label, value, sub, tone = "muted" }: {
  num: string;
  label: string;
  value: number;
  sub?: string;
  tone?: "ok" | "warn" | "crit" | "muted" | "featured";
}) {
  const valueClass = tone === "ok" ? " ok" : tone === "warn" ? " warn" : tone === "crit" ? " crit" : "";
  const featured = tone === "featured";
  return (
    <div className={`fv-kpi-cell${featured ? " featured" : ""}`}>
      <div className="corner">{num}</div>
      <div className="label">{label}</div>
      <div className={`value mono${featured ? "" : valueClass}`}>{value}</div>
      {sub && <div className="footnote">{sub}</div>}
    </div>
  );
}

function InfoMark() {
  return (
    <svg viewBox="0 0 20 20" className="h-3 w-3" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" style={{ color: "var(--muted)" }}>
      <circle cx="10" cy="10" r="7.25" /><path d="M10 6.75h.01M10 9.5v4" />
    </svg>
  );
}

function StatusPill({ status }: { status: string }) {
  const isTerminal = /^(report_ready|approved|rejected|verified|completed|done)$/i.test(status);
  const isFailed = /fail|error/i.test(status);
  const tone = isFailed ? "error" : isTerminal ? "success" : "muted";
  return (
    <span className={`fv-tag ${tone}`}>
      <span className="dot" />
      {status}
    </span>
  );
}

function ScoreCell({ score }: { score: number | null | undefined }) {
  if (score == null) return <span style={{ color: "var(--muted)", fontSize: 11 }}>—</span>;
  const pct = Math.max(0, Math.min(1, score)) * 100;
  const tone = score >= 0.5 ? "ok" : score >= 0.2 ? "warn" : "crit";
  return (
    <Tooltip label={`Overall score: ${score.toFixed(3)} / 1.000`}>
      <div className="fv-confidence-bar" style={{ minWidth: 100, cursor: "help" }}>
        <div className="track">
          <div className={`fill ${tone}`} style={{ width: `${pct}%` }} />
        </div>
        <span className="pct">{score.toFixed(2)}</span>
      </div>
    </Tooltip>
  );
}

function relativeTime(iso: string) {
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
