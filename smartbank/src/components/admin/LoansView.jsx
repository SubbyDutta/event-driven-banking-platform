import React, { useEffect, useMemo, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import API from "../../api";

function formatINR(v) {
  if (v == null) return "—";
  return "₹" + Number(v).toLocaleString("en-IN", { maximumFractionDigits: 2 });
}

export default function LoansView({ loans, load, page, setPage, totalPages }) {
  const [query, setQuery] = useState("");
  const [confirmApprove, setConfirmApprove] = useState(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");

  useEffect(() => {
    load(page);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  const filtered = useMemo(() => {
    if (!query) return loans || [];
    const q = query.toLowerCase();
    return (loans || []).filter((l) => JSON.stringify(l).toLowerCase().includes(q));
  }, [loans, query]);

  const tableKeys = (loans && loans[0]) ? Object.keys(loans[0]) : [];

  const performApprove = async () => {
    if (!confirmApprove) return;
    setBusy(true);
    setError("");
    try {
      await API.post(`/admin/approve/${confirmApprove.id}`);
      await load(page);
      setConfirmApprove(null);
    } catch (e) {
      console.error("Approve failed", e);
      setError(e?.response?.data?.error || "Failed to approve loan");
      setConfirmApprove(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <div className="sb-admin-page-head">
        <div>
          <div className="sb-admin-eyebrow">
            <span>Loan Requests</span>
            <span className="stamp">{(loans || []).length} PENDING</span>
          </div>
          <h1 className="sb-h-display">Pending <em>loan</em> requests.</h1>
          <p className="sb-lede" style={{ marginTop: 10 }}>
            Quick-approve simple loan applications. For full underwriting review with risk model and
            document trail, use the Loans surface in Operations.
          </p>
        </div>
      </div>

      {error && (
        <div className="sb-alert sb-alert-error" style={{ marginBottom: 16 }}>
          <span>⚠</span><span>{error}</span>
          <button
            type="button"
            className="sb-btn sb-btn-ghost"
            style={{ marginLeft: "auto", padding: 0 }}
            onClick={() => setError("")}
          >
            ✕
          </button>
        </div>
      )}

      <div className="sb-admin-filter-stack">
        <div className="head">
          <div className="h">⌕ Search</div>
          <div className="meta">{filtered.length} of {(loans || []).length} records</div>
        </div>
        <div className="sb-admin-search-row">
          <div className="sb-admin-search-input">
            <input
              type="text"
              placeholder="Search by any field…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
        </div>
      </div>

      <div className="sb-table-section">
        <div className="sb-table-head">
          <div className="title-group">
            <span className="title">Pending Loan Applications</span>
            <span className="count">{(loans || []).length} total</span>
          </div>
        </div>

        <div style={{ overflowX: "auto" }}>
          <table className="sb-data-table">
            <thead>
              <tr>
                {tableKeys.map((k) => (
                  <th key={k}>{k}</th>
                ))}
                <th>Action</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 && (
                <tr>
                  <td colSpan={tableKeys.length + 1}>
                    <div className="sb-admin-table-empty">
                      <div className="h">No loan requests</div>
                    </div>
                  </td>
                </tr>
              )}

              {filtered.map((l) => (
                <tr key={l.id}>
                  {tableKeys.map((k) => (
                    <td key={k} className={k === "id" ? "mono" : undefined}>
                      {k === "amount" || k === "remaining" || k === "loanAmount"
                        ? formatINR(l[k])
                        : String(l[k] ?? "—")}
                    </td>
                  ))}
                  <td>
                    <button
                      type="button"
                      className="sb-admin-btn-inline success"
                      onClick={() => setConfirmApprove(l)}
                    >
                      Approve
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="sb-table-foot">
          <div>
            Page <span className="sb-mono">{page + 1}</span> of{" "}
            <span className="sb-mono">{Math.max(totalPages || 1, 1)}</span>
          </div>
          <div className="sb-pager">
            <button disabled={page === 0} onClick={() => setPage(page - 1)}>
              ← Prev
            </button>
            <span className="sb-mono">
              PAGE {page + 1} / {Math.max(totalPages || 1, 1)}
            </span>
            <button disabled={page + 1 >= totalPages} onClick={() => setPage(page + 1)}>
              Next →
            </button>
          </div>
        </div>
      </div>

      <AnimatePresence>
        {confirmApprove && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="sb-modal-backdrop"
            onClick={() => !busy && setConfirmApprove(null)}
          >
            <motion.div
              initial={{ opacity: 0, scale: 0.96, y: 12 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.96, y: 12 }}
              transition={{ type: "spring", damping: 24, stiffness: 260 }}
              className="sb-modal"
              onClick={(e) => e.stopPropagation()}
            >
              <div className="sb-modal-head">
                <span className="corner-mark">REF · LOAN-{confirmApprove.id}</span>
                <div className="sb-seal">✓</div>
                <h3>Approve <em>loan</em>?</h3>
                <p>
                  Approve loan request <span className="sb-mono">#{confirmApprove.id}</span>.
                  Funds will be disbursed immediately upon approval.
                </p>
              </div>
              <div className="sb-modal-body">
                <div className="sb-btn-row" style={{ paddingTop: 0 }}>
                  <button
                    type="button"
                    className="sb-btn sb-btn-secondary sb-btn-block"
                    onClick={() => setConfirmApprove(null)}
                    disabled={busy}
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    className="sb-btn sb-btn-primary sb-btn-block"
                    onClick={performApprove}
                    disabled={busy}
                  >
                    {busy ? <><span className="sb-spinner" /> Approving…</> : "Yes, approve"}
                  </button>
                </div>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
}
