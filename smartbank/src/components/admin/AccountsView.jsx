import React, { useEffect, useMemo, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import API from "../../api";

function formatINR(v) {
  if (v == null) return "—";
  return "₹" + Number(v).toLocaleString("en-IN", { maximumFractionDigits: 2 });
}

const getBlockedFlag = (acc) => {
  if (acc.blocked !== undefined) return acc.blocked;
  if (acc.isBlocked !== undefined) return acc.isBlocked;
  return false;
};

export default function AccountsView({ accounts, load, page, setPage, totalPages }) {
  const [query, setQuery] = useState("");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState("");
  const [confirmAction, setConfirmAction] = useState(null);

  useEffect(() => {
    load(page);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  const filtered = useMemo(() => {
    if (!query) return accounts || [];
    const q = query.toLowerCase();
    return (accounts || []).filter((a) => JSON.stringify(a).toLowerCase().includes(q));
  }, [accounts, query]);

  const totals = useMemo(() => {
    let active = 0;
    let blocked = 0;
    let aggregate = 0;
    (accounts || []).forEach((a) => {
      if (getBlockedFlag(a)) blocked += 1;
      else active += 1;
      aggregate += Number(a.balance || 0);
    });
    return { active, blocked, aggregate, total: (accounts || []).length };
  }, [accounts]);

  const performAction = async () => {
    if (!confirmAction) return;
    setBusy(true);
    setActionError("");
    try {
      if (confirmAction.type === "delete") {
        await API.delete(`/admin/accounts/${confirmAction.id}`);
      } else if (confirmAction.type === "toggle") {
        await API.patch(`/admin/block/${confirmAction.id}`);
      }
      await load(page);
      setConfirmAction(null);
    } catch (e) {
      console.error("Action failed", e);
      setActionError(e?.response?.data?.error || `Failed to ${confirmAction.type === "delete" ? "delete" : "toggle"} account`);
      setConfirmAction(null);
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <div className="sb-admin-page-head">
        <div>
          <div className="sb-admin-eyebrow">
            <span>Account Registry</span>
            <span className="stamp">{totals.total} ACCOUNTS</span>
          </div>
          <h1 className="sb-h-display">View, search, block, <em>and retire</em> bank accounts.</h1>
          <p className="sb-lede" style={{ marginTop: 10 }}>
            Block freezes inbound and outbound flows for compliance review without removing the
            record. Delete permanently retires the account — irreversible and requires Tier-1 clearance.
          </p>
        </div>
        <div className="right-meta">
          <div className="lab">Pool balance</div>
          <div className="val large">{formatINR(totals.aggregate)}</div>
          <div className="lab" style={{ marginTop: 8 }}>Active / Total</div>
          <div className="val">{totals.active} / {totals.total}</div>
        </div>
      </div>

      <div className="sb-admin-kpi-row">
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">A</div>
          <div className="label">Active</div>
          <div className="value mono ok">{totals.active}</div>
          <div className="footnote">SAVINGS · IN GOOD STANDING</div>
        </div>
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">B</div>
          <div className="label">Blocked</div>
          <div className={`value mono${totals.blocked > 0 ? " warn" : ""}`}>{totals.blocked}</div>
          <div className="footnote">UNDER COMPLIANCE HOLD</div>
        </div>
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">C</div>
          <div className="label">Closed · 30d</div>
          <div className="value mono muted">0</div>
          <div className="footnote">VOLUNTARY CLOSURES</div>
        </div>
        <div className="sb-admin-kpi-cell featured">
          <div className="corner-num">Σ</div>
          <div className="label">Aggregate Balance</div>
          <div className="value">
            <span className="unit">₹</span>
            {totals.aggregate.toLocaleString("en-IN", { maximumFractionDigits: 0 })}
          </div>
          <div className="footnote">ACROSS ALL ACTIVE ACCOUNTS</div>
        </div>
      </div>

      {actionError && (
        <div className="sb-alert sb-alert-error" style={{ marginBottom: 16 }}>
          <span>⚠</span>
          <span>{actionError}</span>
          <button
            type="button"
            className="sb-btn sb-btn-ghost"
            style={{ marginLeft: "auto", padding: 0 }}
            onClick={() => setActionError("")}
          >
            ✕
          </button>
        </div>
      )}

      <div className="sb-admin-filter-stack">
        <div className="head">
          <div className="h">⌕ Filter Accounts</div>
          <div className="meta">{filtered.length} of {totals.total} records</div>
        </div>
        <div className="sb-admin-search-row">
          <div className="sb-admin-search-input">
            <input
              type="text"
              placeholder="Search by account number, holder, or balance…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
        </div>
      </div>

      <div className="sb-table-section">
        <div className="sb-table-head">
          <div className="title-group">
            <span className="title">Bank Accounts</span>
            <span className="count">Sorted by ID descending</span>
          </div>
        </div>

        <div style={{ overflowX: "auto" }}>
          <table className="sb-data-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Account Number</th>
                <th>Type</th>
                <th>Holder</th>
                <th className="right">Balance</th>
                <th>Status</th>
                <th className="right">Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 && (
                <tr>
                  <td colSpan={7}>
                    <div className="sb-admin-table-empty">
                      <div className="h">No accounts found</div>
                    </div>
                  </td>
                </tr>
              )}

              {filtered.map((a) => {
                const blocked = getBlockedFlag(a);
                return (
                  <tr key={a.id}>
                    <td className="mono" style={{ color: "var(--muted)" }}>#{a.id}</td>
                    <td>
                      <span className="sb-mono" style={{ fontSize: 12 }}>{a.accountNumber}</span>
                    </td>
                    <td>
                      <span className="sb-tag-pill muted">
                        <span className="dot" />
                        {a.type || "Savings"}
                      </span>
                    </td>
                    <td>
                      <span className="sb-mono" style={{ fontSize: 11 }}>
                        {a.username || a.userName || "—"}
                      </span>
                    </td>
                    <td className="right">
                      <span style={{ fontFamily: "'Fraunces', serif", fontWeight: 500, fontSize: 14 }}>
                        {formatINR(a.balance)}
                      </span>
                    </td>
                    <td>
                      <span className={`sb-tag-pill ${blocked ? "error" : "success"}`}>
                        <span className="dot" />
                        {blocked ? "Blocked" : "Active"}
                      </span>
                    </td>
                    <td className="right">
                      <div className="sb-admin-action-group" style={{ gap: 6, justifyContent: "flex-end" }}>
                        <button
                          type="button"
                          className={`sb-admin-btn-inline ${blocked ? "success" : "warn"}`}
                          disabled={busy}
                          onClick={() =>
                            setConfirmAction({
                              type: "toggle",
                              id: a.id,
                              currentlyBlocked: blocked,
                              accountNumber: a.accountNumber,
                            })
                          }
                          title={blocked ? "Set active" : "Block account"}
                        >
                          {blocked ? "↻ Activate" : "§ Block"}
                        </button>
                        <button
                          type="button"
                          className="sb-admin-btn-inline danger"
                          disabled={busy}
                          onClick={() =>
                            setConfirmAction({
                              type: "delete",
                              id: a.id,
                              accountNumber: a.accountNumber,
                            })
                          }
                          title="Delete account"
                        >
                          ✕ Delete
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        <div className="sb-table-foot">
          <div>
            Page <span className="sb-mono">{page + 1}</span> of{" "}
            <span className="sb-mono">{Math.max(totalPages || 1, 1)}</span> ·{" "}
            {totals.total} records · {formatINR(totals.aggregate)} aggregate
          </div>
          <div className="sb-pager">
            <button disabled={page === 0 || busy} onClick={() => setPage(page - 1)}>
              ← Prev
            </button>
            <span className="sb-mono">
              PAGE {page + 1} / {Math.max(totalPages || 1, 1)}
            </span>
            <button disabled={page + 1 >= totalPages || busy} onClick={() => setPage(page + 1)}>
              Next →
            </button>
          </div>
        </div>
      </div>

      <div className="sb-admin-footnote">
        <em>Block vs Delete ·</em> Block freezes flows for compliance review without removing the
        record. Delete permanently retires the account after a 30-day cooling period — irreversible
        and requires Tier-1 clearance.
      </div>

      <AnimatePresence>
        {confirmAction && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="sb-modal-backdrop"
            onClick={() => !busy && setConfirmAction(null)}
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
                <span className="corner-mark">
                  REF · {confirmAction.type === "delete" ? "DEL" : "BLK"}-ACCT
                </span>
                <div
                  className="sb-seal"
                  style={
                    confirmAction.type === "delete"
                      ? { borderColor: "var(--accent-3)", color: "var(--accent-3)" }
                      : undefined
                  }
                >
                  {confirmAction.type === "delete" ? "✕" : "§"}
                </div>
                <h3>
                  {confirmAction.type === "delete"
                    ? <>Delete <em>account</em>?</>
                    : confirmAction.currentlyBlocked
                      ? <>Activate <em>account</em>?</>
                      : <>Block <em>account</em>?</>}
                </h3>
                <p>
                  {confirmAction.type === "delete" ? (
                    <>This permanently retires account <span className="sb-mono">{confirmAction.accountNumber}</span>. This action is logged immutably.</>
                  ) : confirmAction.currentlyBlocked ? (
                    <>Restore inbound and outbound flows on <span className="sb-mono">{confirmAction.accountNumber}</span>.</>
                  ) : (
                    <>Freeze flows on <span className="sb-mono">{confirmAction.accountNumber}</span> for compliance review.</>
                  )}
                </p>
              </div>
              <div className="sb-modal-body">
                <div className="sb-btn-row" style={{ paddingTop: 0 }}>
                  <button
                    type="button"
                    className="sb-btn sb-btn-secondary sb-btn-block"
                    onClick={() => setConfirmAction(null)}
                    disabled={busy}
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    className="sb-btn sb-btn-primary sb-btn-block"
                    style={
                      confirmAction.type === "delete"
                        ? { background: "var(--accent-3)" }
                        : undefined
                    }
                    onClick={performAction}
                    disabled={busy}
                  >
                    {busy ? (
                      <><span className="sb-spinner" /> Working…</>
                    ) : confirmAction.type === "delete" ? (
                      "Yes, delete"
                    ) : confirmAction.currentlyBlocked ? (
                      "Activate"
                    ) : (
                      "Block"
                    )}
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
