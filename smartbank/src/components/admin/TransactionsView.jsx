import React, { useEffect, useMemo, useState } from "react";

function formatINR(v) {
  if (v == null) return "—";
  return "₹" + Number(v).toLocaleString("en-IN", { maximumFractionDigits: 2 });
}

function formatProb(p) {
  if (p === undefined || p === null) return "—";
  const num = Number(p);
  if (Number.isNaN(num)) return String(p);
  return (num * 100).toFixed(1) + "%";
}

function formatHour(hour) {
  const h = Number(hour);
  if (Number.isNaN(h)) return "—";
  const display = ((h % 24) + 24) % 24;
  return `${String(display).padStart(2, "0")}:00`;
}

const isLateHour = (hour) => {
  const h = Number(hour);
  return h >= 22 || h <= 5;
};
const isFraudTransaction = (t) => t.isFraud === 1 || Number(t.fraudProbability ?? 0) > 0.9;
const isForeignTransaction = (t) => t.isForeign === 1;

export default function TransactionsView({ data, load, page, setPage, totalPages }) {
  const [query, setQuery] = useState("");
  const [chip, setChip] = useState("all");

  useEffect(() => {
    load(page);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  const filtered = useMemo(() => {
    let list = data || [];
    if (chip === "domestic") list = list.filter((t) => !isForeignTransaction(t));
    else if (chip === "foreign") list = list.filter((t) => isForeignTransaction(t));
    else if (chip === "flagged") list = list.filter((t) => isFraudTransaction(t));
    else if (chip === "latenight") list = list.filter((t) => isLateHour(t.hour));
    if (query) {
      const q = query.toLowerCase();
      list = list.filter((t) => JSON.stringify(t).toLowerCase().includes(q));
    }
    return list;
  }, [data, query, chip]);

  const counts = useMemo(() => {
    const all = (data || []).length;
    let domestic = 0;
    let foreign = 0;
    let flagged = 0;
    let latenight = 0;
    (data || []).forEach((t) => {
      if (isForeignTransaction(t)) foreign += 1;
      else domestic += 1;
      if (isFraudTransaction(t)) flagged += 1;
      if (isLateHour(t.hour)) latenight += 1;
    });
    return { all, domestic, foreign, flagged, latenight };
  }, [data]);

  return (
    <>
      <div className="sb-admin-page-head">
        <div>
          <div className="sb-admin-eyebrow">
            <span>Ledger</span>
            <span className="stamp">{counts.all} RECORDS</span>
          </div>
          <h1 className="sb-h-display">Every <em>movement</em> on the ledger.</h1>
          <p className="sb-lede" style={{ marginTop: 10 }}>
            Review every transaction with fraud, foreign-payment, and late-night alerts surfaced inline.
          </p>
        </div>
        <div className="right-meta">
          <div className="lab">Stream</div>
          <div className="val ok">● LIVE</div>
          <div className="lab" style={{ marginTop: 8 }}>Auto-refresh</div>
          <div className="val">on page change</div>
        </div>
      </div>

      <div className="sb-admin-filter-stack">
        <div className="head">
          <div className="h">⌕ Search & Filter</div>
          <div className="meta">
            {filtered.length} of {counts.all} records
            {query ? ` · Q: "${query}"` : ""}
          </div>
        </div>
        <div className="sb-admin-search-row" style={{ marginBottom: 14 }}>
          <div className="sb-admin-search-input">
            <input
              type="text"
              placeholder="Search by any field — account, amount, hash, or actor reference…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
        </div>
        <div className="sb-admin-chip-row">
          <button
            type="button"
            className={`sb-admin-chip${chip === "all" ? " active" : ""}`}
            onClick={() => setChip("all")}
          >
            All<span className="count">{counts.all}</span>
          </button>
          <button
            type="button"
            className={`sb-admin-chip${chip === "domestic" ? " active" : ""}`}
            onClick={() => setChip("domestic")}
          >
            Domestic<span className="count">{counts.domestic}</span>
          </button>
          <button
            type="button"
            className={`sb-admin-chip${chip === "foreign" ? " active" : ""}`}
            onClick={() => setChip("foreign")}
          >
            Foreign<span className="count">{counts.foreign}</span>
          </button>
          <button
            type="button"
            className={`sb-admin-chip${chip === "flagged" ? " active" : ""}`}
            onClick={() => setChip("flagged")}
          >
            Flagged<span className="count">{counts.flagged}</span>
          </button>
          <button
            type="button"
            className={`sb-admin-chip${chip === "latenight" ? " active" : ""}`}
            onClick={() => setChip("latenight")}
          >
            Late-night<span className="count">{counts.latenight}</span>
          </button>
        </div>
      </div>

      <div className="sb-table-section">
        <div className="sb-table-head">
          <div className="title-group">
            <span className="title">All Transactions</span>
            <span className="count">Live · auto-refresh</span>
          </div>
        </div>

        <div style={{ overflowX: "auto" }}>
          <table className="sb-data-table">
            <thead>
              <tr>
                <th>Timestamp</th>
                <th>From → To</th>
                <th className="right">Amount</th>
                <th>Hour</th>
                <th>Type</th>
                <th>Fraud</th>
                <th>Probability</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 && (
                <tr>
                  <td colSpan={7}>
                    <div className="sb-admin-table-empty">
                      <div className="h">No transactions match</div>
                      <div>Try a different filter or clear the search.</div>
                    </div>
                  </td>
                </tr>
              )}

              {filtered.map((t) => {
                const isFraud = isFraudTransaction(t);
                const isForeign = isForeignTransaction(t);
                const fraudProb = Number(t.fraudProbability ?? 0);

                return (
                  <tr key={t.id}>
                    <td>
                      <div className="mono" style={{ fontSize: 12, color: "var(--muted)" }}>
                        {t.timestamp ? new Date(t.timestamp).toLocaleString("en-IN", { hour12: false }) : "—"}
                      </div>
                    </td>
                    <td>
                      <div style={{ display: "flex", alignItems: "center", gap: 10 }}>
                        <span className="mono" style={{ fontSize: 12 }}>{t.senderAccount ?? "—"}</span>
                        <span style={{ color: "var(--muted)" }}>→</span>
                        <span className="mono" style={{ fontSize: 12 }}>{t.receiverAccount ?? "—"}</span>
                      </div>
                    </td>
                    <td className="right">
                      <span style={{ fontFamily: "'Fraunces', serif", fontWeight: 500, fontSize: 14 }}>
                        {formatINR(t.amount)}
                      </span>
                    </td>
                    <td>
                      <span className="mono" style={{ fontSize: 12 }}>{formatHour(t.hour)}</span>
                    </td>
                    <td>
                      <span className={`sb-tag-pill ${isForeign ? "info" : "success"}`}>
                        <span className="dot" />
                        {isForeign ? "Foreign" : "Domestic"}
                      </span>
                    </td>
                    <td>
                      <span className={`sb-tag-pill ${isFraud ? "error" : "muted"}`}>
                        <span className="dot" />
                        {isFraud ? "Yes" : "No"}
                      </span>
                    </td>
                    <td>
                      <div className="sb-admin-meter">
                        <div className="track">
                          <div
                            className={`fill${fraudProb > 0.85 ? " crit" : fraudProb > 0.5 ? " warn" : ""}`}
                            style={{ width: `${Math.min(100, fraudProb * 100)}%` }}
                          />
                        </div>
                        <span className={`num${fraudProb > 0.85 ? " crit" : fraudProb > 0.5 ? " warn" : ""}`}>
                          {formatProb(t.fraudProbability)}
                        </span>
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
            Showing <span className="sb-mono">{filtered.length}</span> of{" "}
            <span className="sb-mono">{counts.all}</span> records
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
        <em>Note ·</em> Transactions flagged as fraud-probable above the{" "}
        <span className="sb-mono">0.85</span> threshold are auto-held for 24h pending operator review.
        Adjust the ceiling under <strong>Governance · Policy Thresholds</strong>.
      </div>
    </>
  );
}
