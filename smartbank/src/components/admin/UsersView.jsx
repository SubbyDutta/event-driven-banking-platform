import React, { useEffect, useMemo, useState } from "react";
import { motion, AnimatePresence } from "framer-motion";
import API from "../../api";

const TABLE_KEYS = ["id", "username", "email", "role", "hasLoan", "loanAmount", "remaining", "dueDate"];
const KEY_LABEL = {
  id: "ID",
  username: "Username",
  email: "Email",
  role: "Role",
  hasLoan: "Loan",
  loanAmount: "Amount",
  remaining: "Balance",
  dueDate: "Due Date",
};

function formatINR(v) {
  if (v == null) return "—";
  return "₹" + Number(v).toLocaleString("en-IN", { maximumFractionDigits: 2 });
}

export default function UsersView({ users, load, page, setPage, totalPages }) {
  const [query, setQuery] = useState("");
  const [edit, setEdit] = useState(null);
  const [searchId, setSearchId] = useState("");
  const [searchError, setSearchError] = useState("");
  const [saveError, setSaveError] = useState("");
  const [historyUser, setHistoryUser] = useState(null);
  const [history, setHistory] = useState([]);
  const [loadingHistory, setLoadingHistory] = useState(false);
  const [confirmDel, setConfirmDel] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    load(page);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  const filtered = useMemo(() => {
    if (!query) return users || [];
    const q = query.toLowerCase();
    return (users || []).filter((u) => JSON.stringify(u).toLowerCase().includes(q));
  }, [users, query]);

  const searchUser = async () => {
    if (!searchId) return;
    setSearchError("");
    try {
      const r = await API.get(`/admin/user/${searchId}`);
      setEdit(r.data);
    } catch (e) {
      console.error("User search failed", e);
      setSearchError("User not found");
    }
  };

  const save = async () => {
    if (!edit || !edit.id) return;
    setSaveError("");
    setBusy(true);
    try {
      await API.put(`/admin/user/${edit.id}`, edit);
      await load(page);
      setEdit(null);
    } catch (e) {
      console.error("Save failed", e);
      setSaveError(e?.response?.data?.error || "Failed to update user");
    } finally {
      setBusy(false);
    }
  };

  const performDelete = async () => {
    if (!confirmDel) return;
    setBusy(true);
    try {
      await API.delete(`/admin/user/${confirmDel.id}`);
      await load(page);
      if (edit && edit.id === confirmDel.id) setEdit(null);
      setConfirmDel(null);
    } catch (e) {
      console.error("Delete failed", e);
      setSaveError(e?.response?.data?.error || "Failed to delete user");
      setConfirmDel(null);
    } finally {
      setBusy(false);
    }
  };

  const viewHistory = async (user) => {
    setHistoryUser(user);
    setLoadingHistory(true);
    setHistory([]);
    try {
      const r = await API.get(`/admin/loans/search?username=${user.username}`);
      setHistory(r.data.content || []);
    } catch (e) {
      console.error("Failed to load history", e);
    } finally {
      setLoadingHistory(false);
    }
  };

  const editableFields = (obj) => {
    if (!obj) return [];
    const excluded = ["password", "createdAt", "updatedAt"];
    return Object.keys(obj).filter(
      (key) => !excluded.includes(key) && obj[key] !== null && typeof obj[key] !== "object"
    );
  };

  return (
    <>
      <div className="sb-admin-page-head">
        <div>
          <div className="sb-admin-eyebrow">
            <span>Roster</span>
            <span className="stamp">{(users || []).length} VISIBLE</span>
          </div>
          <h1 className="sb-h-display">Search, edit, and <em>retire</em> registered users.</h1>
          <p className="sb-lede" style={{ marginTop: 10 }}>
            Each user can be edited or removed. History captures every administrative change with
            operator attribution. Edits to balances or due dates require Tier-1 clearance.
          </p>
        </div>
        <div className="right-meta">
          <div className="lab">Roster size</div>
          <div className="val large">{(users || []).length}</div>
        </div>
      </div>

      {saveError && (
        <div className="sb-alert sb-alert-error" style={{ marginBottom: 16 }}>
          <span>⚠</span>
          <span>{saveError}</span>
          <button
            type="button"
            className="sb-btn sb-btn-ghost"
            style={{ marginLeft: "auto", padding: 0 }}
            onClick={() => setSaveError("")}
          >
            ✕
          </button>
        </div>
      )}

      <div className="sb-admin-filter-stack">
        <div className="head">
          <div className="h">⌕ Search Roster</div>
          <div className="meta">{filtered.length} of {(users || []).length} records</div>
        </div>
        <div className="sb-admin-search-row">
          <div className="sb-admin-search-input">
            <input
              type="text"
              placeholder="Filter shown users…"
              value={query}
              onChange={(e) => setQuery(e.target.value)}
            />
          </div>
          <div className="sb-admin-search-input" style={{ maxWidth: 220 }}>
            <input
              type="text"
              placeholder="Lookup by ID"
              value={searchId}
              onChange={(e) => setSearchId(e.target.value)}
              onKeyDown={(e) => e.key === "Enter" && searchUser()}
            />
          </div>
          <button type="button" className="sb-btn sb-btn-primary" onClick={searchUser}>
            Go →
          </button>
        </div>
        {searchError && (
          <div className="sb-alert sb-alert-error" style={{ marginTop: 12, marginBottom: 0 }}>
            {searchError}
          </div>
        )}
      </div>

      <AnimatePresence>
        {edit && (
          <motion.div
            initial={{ opacity: 0, height: 0 }}
            animate={{ opacity: 1, height: "auto" }}
            exit={{ opacity: 0, height: 0 }}
            transition={{ duration: 0.25 }}
            style={{ overflow: "hidden", marginBottom: 24 }}
          >
            <div className="sb-form-section">
              <div className="sb-form-head">
                <div>
                  <div className="sb-eyebrow" style={{ marginBottom: 4 }}>Edit User</div>
                  <h3 className="sb-h-section">
                    User #{edit.id} · <span className="sb-mono">{edit.username}</span>
                  </h3>
                </div>
                <button type="button" className="sb-btn sb-btn-secondary" onClick={() => setEdit(null)}>
                  Close
                </button>
              </div>
              <div className="sb-form-body">
                <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 20 }}>
                  {editableFields(edit).map((key) => (
                    <div key={key} className="sb-field">
                      <label className="sb-field-label">{key}</label>
                      <input
                        className="sb-field-input"
                        value={edit[key] ?? ""}
                        onChange={(e) => setEdit({ ...edit, [key]: e.target.value })}
                      />
                    </div>
                  ))}
                </div>
                <div className="sb-btn-row">
                  <button
                    type="button"
                    className="sb-btn sb-btn-secondary"
                    onClick={() => setConfirmDel(edit)}
                    style={{ borderColor: "rgba(139,44,44,0.4)", color: "var(--accent-3)" }}
                    disabled={busy}
                  >
                    Delete user
                  </button>
                  <span style={{ marginLeft: "auto" }} />
                  <button
                    type="button"
                    className="sb-btn sb-btn-primary"
                    onClick={save}
                    disabled={busy}
                  >
                    {busy ? <><span className="sb-spinner" /> Saving…</> : "Save changes"}
                  </button>
                </div>
              </div>
            </div>
          </motion.div>
        )}
      </AnimatePresence>

      <div className="sb-table-section">
        <div className="sb-table-head">
          <div className="title-group">
            <span className="title">All Users</span>
            <span className="count">Sorted by ID descending</span>
          </div>
        </div>

        <div style={{ overflowX: "auto" }}>
          <table className="sb-data-table">
            <thead>
              <tr>
                {TABLE_KEYS.map((k) => (
                  <th key={k}>{KEY_LABEL[k] || k}</th>
                ))}
                <th>Actions</th>
              </tr>
            </thead>
            <tbody>
              {filtered.length === 0 && (
                <tr>
                  <td colSpan={TABLE_KEYS.length + 1}>
                    <div className="sb-admin-table-empty">
                      <div className="h">No users found</div>
                    </div>
                  </td>
                </tr>
              )}

              {filtered.map((u) => {
                const isAdmin = u.role === "ROLE_ADMIN" || u.role === "ADMIN";
                return (
                  <tr key={u.id} style={isAdmin ? { background: "rgba(184,149,74,0.04)" } : undefined}>
                    <td className="mono" style={{ color: "var(--muted)" }}>#{u.id}</td>
                    <td>
                      <span className="sb-mono" style={{ fontSize: 12 }}>{u.username}</span>
                    </td>
                    <td>
                      <span className="sb-mono" style={{ fontSize: 11 }}>{u.email}</span>
                    </td>
                    <td>
                      <span className={`sb-tag-pill ${isAdmin ? "solid" : "muted"}`}>
                        <span className="dot" />
                        {isAdmin ? "Admin" : "User"}
                      </span>
                    </td>
                    <td>
                      <span className={`sb-tag-pill ${u.hasLoan ? "warn" : "muted"}`}>
                        <span className="dot" />
                        {u.hasLoan ? "Yes" : "No"}
                      </span>
                    </td>
                    <td className="mono">{formatINR(u.loanAmount)}</td>
                    <td className="mono">{formatINR(u.remaining)}</td>
                    <td className="mono" style={{ color: "var(--muted)", fontSize: 12 }}>
                      {u.dueDate ? new Date(u.dueDate).toLocaleDateString("en-IN") : "—"}
                    </td>
                    <td>
                      <div className="sb-admin-action-group" style={{ gap: 8 }}>
                        <button
                          type="button"
                          className="sb-admin-btn-inline"
                          onClick={() => setEdit(u)}
                        >
                          Edit
                        </button>
                        <button
                          type="button"
                          className="sb-admin-btn-inline"
                          onClick={() => viewHistory(u)}
                        >
                          History
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
        {historyUser && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="sb-modal-backdrop"
            onClick={() => setHistoryUser(null)}
          >
            <motion.div
              initial={{ opacity: 0, scale: 0.96, y: 12 }}
              animate={{ opacity: 1, scale: 1, y: 0 }}
              exit={{ opacity: 0, scale: 0.96, y: 12 }}
              transition={{ type: "spring", damping: 24, stiffness: 260 }}
              className="sb-modal"
              style={{ maxWidth: 720 }}
              onClick={(e) => e.stopPropagation()}
            >
              <div className="sb-modal-head">
                <span className="corner-mark">REF · USR-{historyUser.id}</span>
                <div className="sb-seal">⏱</div>
                <h3>Loan <em>history</em></h3>
                <p>For <span className="sb-mono">{historyUser.username}</span></p>
              </div>
              <div className="sb-modal-body" style={{ padding: 0 }}>
                {loadingHistory ? (
                  <div style={{ padding: "48px 24px", textAlign: "center" }}>
                    <span className="sb-spinner" /> Fetching history…
                  </div>
                ) : history.length === 0 ? (
                  <div className="sb-admin-table-empty">
                    <div className="h">No applications</div>
                    <div>This user has not submitted any loans.</div>
                  </div>
                ) : (
                  <div style={{ maxHeight: 420, overflowY: "auto" }}>
                    <table className="sb-data-table">
                      <thead>
                        <tr>
                          <th>ID</th>
                          <th>Amount</th>
                          <th>Status</th>
                          <th>EMI</th>
                          <th>Remaining</th>
                          <th>Next due</th>
                        </tr>
                      </thead>
                      <tbody>
                        {history.map((h) => (
                          <tr key={h.id}>
                            <td className="mono" style={{ color: "var(--muted)" }}>#{h.id}</td>
                            <td>
                              <span style={{ fontFamily: "'Fraunces', serif", fontWeight: 500, fontSize: 14 }}>
                                {formatINR(h.amount)}
                              </span>
                            </td>
                            <td>
                              <span className={`sb-tag-pill ${
                                h.status === "APPROVED" ? "success" :
                                h.status === "REJECTED" ? "error" : "warn"
                              }`}>
                                <span className="dot" />
                                {h.status}
                              </span>
                            </td>
                            <td className="mono">{formatINR(h.monthlyEmi)}</td>
                            <td className="mono" style={{ color: "var(--positive)" }}>
                              {formatINR(h.due_amount)}
                            </td>
                            <td className="mono" style={{ color: "var(--muted)", fontSize: 12 }}>
                              {h.nextDueDate ? new Date(h.nextDueDate).toLocaleDateString("en-IN") : "—"}
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                )}
                <div style={{ padding: 24, borderTop: "1px solid var(--rule)" }}>
                  <button
                    type="button"
                    className="sb-btn sb-btn-primary sb-btn-block"
                    onClick={() => setHistoryUser(null)}
                  >
                    Close window
                  </button>
                </div>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      <AnimatePresence>
        {confirmDel && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            className="sb-modal-backdrop"
            onClick={() => !busy && setConfirmDel(null)}
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
                <span className="corner-mark">REF · DEL-USER</span>
                <div className="sb-seal" style={{ borderColor: "var(--accent-3)", color: "var(--accent-3)" }}>!</div>
                <h3>Delete <em>user</em>?</h3>
                <p>
                  This permanently retires user <span className="sb-mono">{confirmDel.username}</span>{" "}
                  and the associated bank account. This action is logged immutably.
                </p>
              </div>
              <div className="sb-modal-body">
                <div className="sb-btn-row" style={{ paddingTop: 0 }}>
                  <button
                    type="button"
                    className="sb-btn sb-btn-secondary sb-btn-block"
                    onClick={() => setConfirmDel(null)}
                    disabled={busy}
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    className="sb-btn sb-btn-primary sb-btn-block"
                    style={{ background: "var(--accent-3)" }}
                    onClick={performDelete}
                    disabled={busy}
                  >
                    {busy ? <><span className="sb-spinner" /> Deleting…</> : "Yes, delete"}
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
