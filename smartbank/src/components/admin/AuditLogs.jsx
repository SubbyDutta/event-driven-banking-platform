import React, { useEffect, useState } from "react";
import API from "../../api";
import { Filter } from "lucide-react";

const ACTION_TONE = (action = "") => {
  const a = String(action).toUpperCase();
  if (a.includes("ERROR") || a.includes("FAIL") || a.includes("REJECT")) return "error";
  if (a.includes("UPDATE") || a.includes("DELETE") || a.includes("BLOCK") || a.includes("OVERRIDE")) return "warn";
  if (a.includes("LOGIN") || a.includes("REGISTER")) return "info";
  return "success";
};

export default function AuditLogs() {
  const [logs, setLogs] = useState([]);
  const [loading, setLoading] = useState(false);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [actionFilter, setActionFilter] = useState("");
  const [error, setError] = useState(null);

  const fetchLogs = async (p = 0, action = "") => {
    setLoading(true);
    setError(null);
    try {
      let res;
      if (action) {
        res = await API.get(`/admin/logs/action?value=${action}&page=${p}&size=10`);
      } else {
        res = await API.get(`/admin/alllogs?page=${p}&size=10`);
      }
      setLogs(res.data.content || []);
      setTotalPages(res.data.totalPages || 0);
    } catch (err) {
      console.error("Failed to fetch logs:", err);
      setError("Failed to load audit logs. Please try again later.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    fetchLogs(page, actionFilter);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  const applyFilter = () => {
    setPage(0);
    fetchLogs(0, actionFilter);
  };

  const handleKeyPress = (e) => {
    if (e.key === "Enter") applyFilter();
  };

  return (
    <>
      <div className="sb-admin-page-head">
        <div>
          <div className="sb-admin-eyebrow">
            <span>Audit Trail</span>
            <span className="stamp">IMMUTABLE</span>
          </div>
          <h1 className="sb-h-display">Every action, <em>permanently recorded</em>.</h1>
          <p className="sb-lede" style={{ marginTop: 10 }}>
            Monitor all administrative and system actions in real time. Records are append-only,
            cryptographically chained, and exported to cold storage every 24 hours per RBI compliance protocol §4.2(c).
          </p>
        </div>
        <div className="right-meta">
          <div className="lab">Records · page</div>
          <div className="val large">{logs.length}</div>
          <div className="lab" style={{ marginTop: 8 }}>Mode</div>
          <div className="val">APPEND-ONLY · WORM</div>
        </div>
      </div>

      <div className="sb-admin-filter-stack">
        <div className="head">
          <div className="h">⌕ Filter Trail</div>
          <div className="meta">UTC offset +05:30</div>
        </div>
        <div className="sb-admin-search-row">
          <div className="sb-admin-icon-input">
            <Filter size={14} className="icn" />
            <input
              type="text"
              placeholder="Filter by action (e.g. LOGIN, LOAN_APPROVAL, BANK_ACCOUNT_CREATED)…"
              value={actionFilter}
              onChange={(e) => setActionFilter(e.target.value)}
              onKeyDown={handleKeyPress}
            />
          </div>
          <button type="button" className="sb-btn sb-btn-primary" onClick={applyFilter}>
            Search →
          </button>
        </div>
      </div>

      {error && (
        <div className="sb-alert sb-alert-error" style={{ marginBottom: 16 }}>
          <span>⚠</span><span>{error}</span>
        </div>
      )}

      <div className="sb-table-section">
        <div className="sb-table-head">
          <div className="title-group">
            <span className="title">Event Stream</span>
            <span className="count">Newest first</span>
          </div>
        </div>

        <div className="sb-admin-audit-head">
          <span>TIMESTAMP</span>
          <span>ACTOR</span>
          <span>ACTION</span>
          <span>DETAILS</span>
        </div>

        <div>
          {loading ? (
            <div className="sb-admin-table-empty">
              <span className="sb-spinner" /> Loading logs…
            </div>
          ) : logs.length === 0 ? (
            <div className="sb-admin-table-empty">
              <div className="h">No audit logs found</div>
              <div>Try clearing the action filter.</div>
            </div>
          ) : (
            logs.map((log) => (
              <div className="sb-admin-audit-row" key={log.id}>
                <span className="ts">
                  {log.timestamp ? new Date(log.timestamp).toLocaleString("en-IN", { hour12: false }) : "—"}
                </span>
                <span>
                  <span className="actor">{log.username || "system"}</span>
                </span>
                <span>
                  <span className={`sb-tag-pill ${ACTION_TONE(log.action)}`}>
                    <span className="dot" />
                    {log.action}
                  </span>
                </span>
                <span className="details" title={log.details}>
                  {log.details}
                </span>
              </div>
            ))
          )}
        </div>

        <div className="sb-table-foot">
          <div>
            Page <span className="sb-mono">{page + 1}</span> of{" "}
            <span className="sb-mono">{Math.max(totalPages, 1)}</span>
          </div>
          <div className="sb-pager">
            <button disabled={page === 0 || loading} onClick={() => setPage(page - 1)}>
              ← Previous
            </button>
            <span className="sb-mono">
              PAGE {page + 1} / {Math.max(totalPages, 1)}
            </span>
            <button disabled={page + 1 >= totalPages || loading} onClick={() => setPage(page + 1)}>
              Next →
            </button>
          </div>
        </div>
      </div>

      <div className="sb-admin-footnote">
        <em>Integrity ·</em> Every event is signed against the prior block's hash, forming a
        tamper-evident chain. Discrepancies are reported to the compliance officer within 60 seconds.
      </div>
    </>
  );
}
