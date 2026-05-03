import React, { useEffect, useMemo, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import API from "../../api";

export default function ThresholdsView() {
  const [rows, setRows] = useState([]);
  const [drafts, setDrafts] = useState({});
  const [filter, setFilter] = useState("");
  const [busy, setBusy] = useState({});
  const [toast, setToast] = useState(null);
  const [loading, setLoading] = useState(false);
  const [reasonPrompt, setReasonPrompt] = useState(null); // { key, value, reason }

  const load = async () => {
    setLoading(true);
    try {
      const res = await API.get("/admin/thresholds");
      const list = Array.isArray(res.data) ? res.data : [];
      setRows(list);
      const next = {};
      list.forEach((r) => {
        next[r.key] = String(r.value);
      });
      setDrafts(next);
    } catch (e) {
      setToast({ type: "error", msg: e?.response?.data?.error || "Failed to load thresholds" });
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
  }, []);

  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 3500);
    return () => clearTimeout(t);
  }, [toast]);

  const filtered = useMemo(() => {
    if (!filter) return rows;
    const f = filter.toLowerCase();
    return rows.filter((r) => r.key.toLowerCase().includes(f));
  }, [rows, filter]);

  const dirtyCount = useMemo(
    () => rows.filter((r) => String(drafts[r.key] ?? "") !== String(r.value)).length,
    [rows, drafts]
  );

  const requestSave = (key) => {
    const raw = drafts[key];
    const value = Number(raw);
    if (Number.isNaN(value)) {
      setToast({ type: "error", msg: `"${raw}" is not a number` });
      return;
    }
    setReasonPrompt({ key, value, reason: "" });
  };

  const performSave = async () => {
    if (!reasonPrompt) return;
    const { key, value, reason } = reasonPrompt;
    if (!reason.trim()) {
      setToast({ type: "error", msg: "Reason is required" });
      return;
    }
    setBusy((b) => ({ ...b, [key]: true }));
    setReasonPrompt(null);
    try {
      await API.put(`/admin/thresholds/${encodeURIComponent(key)}`, {
        value,
        reason: reason.trim(),
      });
      setToast({ type: "success", msg: `Saved ${key} = ${value}` });
      await load();
    } catch (e) {
      setToast({ type: "error", msg: e?.response?.data?.error || `Failed to update ${key}` });
    } finally {
      setBusy((b) => ({ ...b, [key]: false }));
    }
  };

  const reset = (key, value) => setDrafts((d) => ({ ...d, [key]: String(value) }));

  return (
    <>
      <div className="sb-admin-page-head">
        <div>
          <div className="sb-admin-eyebrow">
            <span>Policy Engine</span>
            <span className="stamp">{rows.length} KEYS</span>
          </div>
          <h1 className="sb-h-display">Risk parameters, <em>tuned with care</em>.</h1>
          <p className="sb-lede" style={{ marginTop: 10 }}>
            Compliance and risk thresholds shared by every loan origination and KYC verification
            pipeline. Changes propagate within 60 seconds at the next pipeline run. All edits are
            logged with operator attribution and require Tier-1 clearance.
          </p>
        </div>
        <div className="right-meta">
          <span className="sb-admin-clearance-pill crit">
            <span className="dot" />
            TIER-1 CLEARANCE
          </span>
        </div>
      </div>

      <div className="sb-admin-kpi-row">
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">A</div>
          <div className="label">Total Keys</div>
          <div className="value mono">{rows.length}</div>
          <div className="footnote">ACTIVE PARAMETERS</div>
        </div>
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">B</div>
          <div className="label">Visible</div>
          <div className="value mono">{filtered.length}</div>
          <div className="footnote">SHOWN AT THIS CLEARANCE</div>
        </div>
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">C</div>
          <div className="label">Pending</div>
          <div className={`value mono${dirtyCount > 0 ? " warn" : ""}`}>{dirtyCount}</div>
          <div className="footnote">UNSAVED CHANGES</div>
        </div>
        <div className="sb-admin-kpi-cell featured">
          <div className="corner-num">●</div>
          <div className="label">Status</div>
          <div className="value" style={{ color: loading ? "#ECC9A8" : "#6FB97D" }}>
            {loading ? "Syncing" : "Live"}
          </div>
          <div className="footnote">{loading ? "REFRESHING" : "PROPAGATED"}</div>
        </div>
      </div>

      <div className="sb-table-section">
        <div className="sb-table-head">
          <div className="title-group">
            <span className="title">Active Thresholds</span>
            <span className="count">Sorted alphabetically</span>
          </div>
          <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
            <div className="sb-admin-search-input compact" style={{ maxWidth: 240 }}>
              <input
                value={filter}
                onChange={(e) => setFilter(e.target.value)}
                placeholder="Filter by key…"
              />
            </div>
            <button
              type="button"
              className="sb-btn sb-btn-secondary"
              onClick={load}
              disabled={loading}
            >
              {loading ? <><span className="sb-spinner" /> Refreshing…</> : "↻ Refresh"}
            </button>
          </div>
        </div>

        <div className="sb-admin-thresh-row head">
          <span>KEY · POLICY PARAMETER</span>
          <span>CURRENT</span>
          <span>NEW VALUE</span>
          <span>UNIT</span>
          <span style={{ textAlign: "right" }}>ACTION</span>
        </div>

        {filtered.length === 0 && !loading && (
          <div className="sb-admin-table-empty">
            <div className="h">No thresholds match your filter</div>
          </div>
        )}

        {loading && rows.length === 0 && (
          <div style={{ padding: 24 }}>
            {Array.from({ length: 6 }).map((_, i) => (
              <div
                key={i}
                style={{
                  height: 44,
                  marginBottom: 8,
                  background: "var(--paper-2)",
                  borderRadius: 2,
                }}
              />
            ))}
          </div>
        )}

        {filtered.map((r) => {
          const draft = drafts[r.key] ?? "";
          const dirty = String(r.value) !== String(draft);
          const saving = !!busy[r.key];
          return (
            <div
              key={r.key}
              className={`sb-admin-thresh-row${dirty ? " dirty" : ""}`}
            >
              <div className="sb-admin-thresh-key">{r.key}</div>
              <div className="sb-admin-thresh-current">{r.value}</div>
              <input
                type="number"
                step="any"
                value={draft}
                onChange={(e) => setDrafts({ ...drafts, [r.key]: e.target.value })}
                className={`sb-admin-thresh-input${dirty ? " dirty" : ""}`}
              />
              <span className={`sb-admin-thresh-meta${dirty ? " dirty" : ""}`}>
                {dirty ? "UNSAVED" : "SAVED"}
              </span>
              <div className="sb-admin-thresh-actions">
                {dirty && !saving && (
                  <button
                    type="button"
                    className="sb-admin-thresh-reset"
                    onClick={() => reset(r.key, r.value)}
                  >
                    Reset
                  </button>
                )}
                <button
                  type="button"
                  onClick={() => requestSave(r.key)}
                  disabled={!dirty || saving}
                  className={`sb-admin-thresh-save${dirty && !saving ? " dirty" : ""}${saving ? " busy" : ""}`}
                >
                  {saving ? "Saving…" : dirty ? "Save →" : "Saved"}
                </button>
              </div>
            </div>
          );
        })}
      </div>

      <div className="sb-admin-footnote">
        <em>Editing protocol ·</em> Each threshold takes effect at the next pipeline run, typically
        within 60 seconds. Changes affect <strong>all in-flight applications</strong> — coordinate
        with underwriting before raising or lowering risk gates. Audit entries are written
        immediately to the immutable trail.
      </div>

      <AnimatePresence>
        {reasonPrompt && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="sb-modal-backdrop"
            onClick={() => setReasonPrompt(null)}
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
                <span className="corner-mark">REF · POLICY-EDIT</span>
                <div className="sb-seal">§</div>
                <h3>Confirm <em>policy change</em></h3>
                <p>
                  Updating <span className="sb-mono">{reasonPrompt.key}</span> to{" "}
                  <span className="sb-mono">{reasonPrompt.value}</span>. A reason is required and
                  recorded in the audit trail.
                </p>
              </div>
              <div className="sb-modal-body">
                <div className="sb-field" style={{ marginBottom: 16 }}>
                  <label className="sb-admin-field-label">
                    Reason <span className="req">*</span>
                  </label>
                  <textarea
                    rows={3}
                    className="sb-field-input"
                    placeholder="Why is this threshold being changed?"
                    autoFocus
                    value={reasonPrompt.reason}
                    onChange={(e) =>
                      setReasonPrompt((p) => ({ ...p, reason: e.target.value }))
                    }
                  />
                </div>
                <div className="sb-btn-row" style={{ paddingTop: 0 }}>
                  <button
                    type="button"
                    className="sb-btn sb-btn-secondary sb-btn-block"
                    onClick={() => setReasonPrompt(null)}
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    className="sb-btn sb-btn-primary sb-btn-block"
                    onClick={performSave}
                    disabled={!reasonPrompt.reason.trim()}
                  >
                    Save change
                  </button>
                </div>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      <AnimatePresence>
        {toast && (
          <motion.div
            initial={{ opacity: 0, y: 12, scale: 0.96 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 12, scale: 0.96 }}
            transition={{ type: "spring", damping: 22, stiffness: 260 }}
            role="status"
            className={`sb-admin-toast${toast.type === "error" ? " error" : ""}`}
          >
            <span className="ico">{toast.type === "success" ? "✓" : "!"}</span>
            <div style={{ flex: 1 }}>{toast.msg}</div>
          </motion.div>
        )}
      </AnimatePresence>
    </>
  );
}
