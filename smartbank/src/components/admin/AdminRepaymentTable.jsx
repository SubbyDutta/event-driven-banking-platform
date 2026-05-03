import React, { useEffect, useMemo, useState } from "react";

function formatINR(v) {
  if (v == null || v === "") return "—";
  const num = Number(v);
  if (Number.isNaN(num)) return String(v);
  return "₹" + num.toLocaleString("en-IN", { maximumFractionDigits: 2 });
}

function formatVal(k, v) {
  if (v == null) return "—";
  if (
    k === "amount" ||
    k === "principal" ||
    k === "remaining" ||
    k === "outstanding" ||
    k === "monthlyEmi" ||
    k === "due_amount" ||
    k === "balance"
  ) {
    return formatINR(v);
  }
  if (k === "dueDate" || k === "nextDueDate" || k === "createdAt" || k === "updatedAt") {
    try {
      const d = new Date(v);
      if (!isNaN(d.getTime())) return d.toLocaleString("en-IN", { hour12: false });
    } catch {}
  }
  return String(v);
}

export default function AdminRepaymentTable({ repayments, load, page, setPage, totalPages }) {
  const [q, setQ] = useState("");

  useEffect(() => {
    load(page);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  const filtered = useMemo(() => {
    if (!q) return repayments || [];
    const lower = q.toLowerCase();
    return (repayments || []).filter((r) => JSON.stringify(r).toLowerCase().includes(lower));
  }, [repayments, q]);

  const tableKeys = (repayments && repayments[0]) ? Object.keys(repayments[0]) : [];

  return (
    <>
      <div className="sb-admin-page-head">
        <div>
          <div className="sb-admin-eyebrow">
            <span>Servicing</span>
            <span className="stamp">{(repayments || []).length} ENTRIES</span>
          </div>
          <h1 className="sb-h-display">Track every <em>repayment</em>, every cycle.</h1>
          <p className="sb-lede" style={{ marginTop: 10 }}>
            Monitor active loans, scheduled EMIs, and prepayment events across the entire book.
            Mark missed installments, trigger reminders, or initiate collections workflow from this surface.
          </p>
        </div>
      </div>

      <div className="sb-admin-filter-stack">
        <div className="head">
          <div className="h">⌕ Filter Repayments</div>
          <div className="meta">{filtered.length} of {(repayments || []).length} records</div>
        </div>
        <div className="sb-admin-search-row">
          <div className="sb-admin-search-input">
            <input
              type="text"
              placeholder="Search by user, loan ID, or amount…"
              value={q}
              onChange={(e) => setQ(e.target.value)}
            />
          </div>
        </div>
      </div>

      <div className="sb-table-section">
        <div className="sb-table-head">
          <div className="title-group">
            <span className="title">Repayment History</span>
            <span className="count">All cycles · sorted by id</span>
          </div>
        </div>

        <div style={{ overflowX: "auto" }}>
          <table className="sb-data-table">
            <thead>
              <tr>
                {tableKeys.map((k) => (
                  <th key={k}>{k}</th>
                ))}
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 && (
                <tr>
                  <td colSpan={Math.max(tableKeys.length, 1)}>
                    <div className="sb-admin-table-empty">
                      <div className="h">No repayments found</div>
                    </div>
                  </td>
                </tr>
              )}

              {filtered.map((r) => (
                <tr key={r.id}>
                  {tableKeys.map((k) => (
                    <td key={k} className={k === "id" || k.toLowerCase().includes("amount") ? "mono" : undefined}>
                      {formatVal(k, r[k])}
                    </td>
                  ))}
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

      <div className="sb-admin-footnote">
        <em>Servicing protocol ·</em> Reminders fire automatically at T-7, T-3, and T-1 days before
        each EMI. After 3 missed payments (DPD 90), the loan transitions to <strong>NPA</strong>{" "}
        status and is routed to the collections workflow per RBI norms.
      </div>
    </>
  );
}
