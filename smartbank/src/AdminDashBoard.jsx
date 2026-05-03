import React, { useState, useEffect, useCallback, useMemo } from "react";
import { motion, AnimatePresence } from "framer-motion";
import API from "./api";
import { decodeToken, logout } from "./utils/auth";

import AdminSidebar from "./components/admin/AdminSidebar";
import AdminHeader from "./components/admin/AdminHeader";

import TransactionsView from "./components/admin/TransactionsView";
import UsersView from "./components/admin/UsersView";
import AccountsView from "./components/admin/AccountsView";
import LoanReviewView from "./components/admin/LoanReviewView";
import KycUsersView from "./components/admin/KycUsersView";
import AdminRepaymentTable from "./components/admin/AdminRepaymentTable";
import AuditLogs from "./components/admin/AuditLogs";
import ThresholdsView from "./components/admin/ThresholdsView";
import {
  TransactionChart,
  TransactionCountChart,
  LoanStatusChart,
  TransactionTypeChart,
  TopAccountsChart,
  KycFunnelChart,
  formatCompactINR,
} from "./components/admin/AdminCharts";

const PAGE = 0;
const SIZE = 10;

const fadeUp = {
  initial: { opacity: 0, y: 12 },
  animate: { opacity: 1, y: 0 },
  exit: { opacity: 0, y: -8 },
  transition: { duration: 0.25, ease: [0.22, 1, 0.36, 1] },
};

export default function AdminDashboard() {
  const [view, setView] = useState("home");

  const [transactions, setTransactions] = useState([]);
  const [transPage, setTransPage] = useState(PAGE);
  const [transTotalPages, setTransTotalPages] = useState(0);

  const [users, setUsers] = useState([]);
  const [userPage, setUserPage] = useState(PAGE);
  const [userTotalPages, setUserTotalPages] = useState(0);

  const [accounts, setAccounts] = useState([]);
  const [accPage, setAccPage] = useState(PAGE);
  const [accTotalPages, setAccTotalPages] = useState(0);

  const [loans, setLoans] = useState([]);

  const [repayments, setRepayments] = useState([]);
  const [repayPage, setRepayPage] = useState(PAGE);
  const [repayTotalPages, setRepayTotalPages] = useState(0);

  const [bankPoolBalance, setBankPoolBalance] = useState(0);
  const [showTopupModal, setShowTopupModal] = useState(false);
  const [topupAmount, setTopupAmount] = useState("");
  const [topupBusy, setTopupBusy] = useState(false);
  const [topupError, setTopupError] = useState("");

  const [stats, setStats] = useState({
    totalUsers: 0,
    totalAccounts: 0,
    totalTransactions: 0,
  });

  const [adminUser, setAdminUser] = useState(null);
  const [mobileNavOpen, setMobileNavOpen] = useState(false);

  useEffect(() => {
    const decoded = decodeToken();
    if (decoded) setAdminUser(decoded);
  }, []);

  const extractPageData = useCallback(
    (r) => ({
      content: r?.data?.content || [],
      totalPages: r?.data?.totalPages || 0,
    }),
    []
  );

  const loadTransactions = useCallback(
    async (p = 0) => {
      try {
        const r = await API.get(`/transactions/transactions?page=${p}&size=${SIZE}`);
        const { content, totalPages } = extractPageData(r);
        setTransactions(content);
        setTransTotalPages(totalPages);
      } catch (err) {
        console.warn("Failed to load transactions", err?.message);
      }
    },
    [extractPageData]
  );

  const loadUsers = useCallback(
    async (p = 0) => {
      try {
        const r = await API.get(`/admin/users?page=${p}&size=${SIZE}`);
        const { content, totalPages } = extractPageData(r);
        setUsers(content);
        setUserTotalPages(totalPages);
      } catch (err) {
        console.warn("Failed to load users", err?.message);
      }
    },
    [extractPageData]
  );

  const loadAccounts = useCallback(
    async (p = 0) => {
      try {
        const r = await API.get(`/admin/bankaccounts?page=${p}&size=${SIZE}`);
        const { content, totalPages } = extractPageData(r);
        setAccounts(content);
        setAccTotalPages(totalPages);
      } catch (err) {
        console.warn("Failed to load accounts", err?.message);
      }
    },
    [extractPageData]
  );

  const loadLoans = useCallback(
    async (p = 0) => {
      try {
        const r = await API.get(`/loan/pending?page=${p}&size=${SIZE}`);
        const { content } = extractPageData(r);
        setLoans(content);
      } catch (err) {
        console.warn("Failed to load loans", err?.message);
      }
    },
    [extractPageData]
  );

  const loadRepayments = useCallback(
    async (p = 0) => {
      try {
        const r = await API.get(`/repay?page=${p}&size=${SIZE}`);
        const { content, totalPages } = extractPageData(r);
        setRepayments(content);
        setRepayTotalPages(totalPages);
      } catch (err) {
        console.warn("Failed to load repayments", err?.message);
      }
    },
    [extractPageData]
  );

  const loadBankPool = useCallback(async () => {
    try {
      const res = await API.get("/pool");
      setBankPoolBalance(Number(res.data) || 0);
    } catch (err) {
      console.warn("Failed to load bank pool:", err?.message);
    }
  }, []);

  const loadStats = useCallback(async () => {
    try {
      const res = await API.get("/admin/analytics/stats");
      setStats(res.data || { totalUsers: 0, totalAccounts: 0, totalTransactions: 0 });
    } catch (err) {
      setStats((prev) => ({
        totalUsers: prev.totalUsers || 0,
        totalAccounts: prev.totalAccounts || 0,
        totalTransactions: prev.totalTransactions || 0,
      }));
    }
  }, []);

  useEffect(() => {
    loadTransactions(0);
    loadUsers(0);
    loadLoans(0);
    loadAccounts(0);
    loadBankPool();
    loadStats();
  }, [loadTransactions, loadUsers, loadLoans, loadAccounts, loadBankPool, loadStats]);

  useEffect(() => {
    setMobileNavOpen(false);
  }, [view]);

  const handleLogout = useCallback(() => logout("/login"), []);

  const handleTopup = useCallback(async () => {
    const amount = parseFloat(topupAmount);
    if (!amount || amount <= 0) return;
    setTopupBusy(true);
    setTopupError("");
    try {
      await API.post("/pool/topup", null, { params: { amount } });
      setBankPoolBalance((prev) => prev + amount);
      setShowTopupModal(false);
      setTopupAmount("");
    } catch (err) {
      setTopupError(err?.response?.data?.error || "Top-up failed");
    } finally {
      setTopupBusy(false);
    }
  }, [topupAmount]);

  const txKpis = useMemo(() => {
    const totalVolume = (transactions || []).reduce(
      (s, t) => s + (Number(t?.amount) || 0),
      0
    );
    return { totalVolume };
  }, [transactions]);

  const todayLabel = useMemo(() => {
    const d = new Date();
    return (
      d.toLocaleDateString("en-IN", { day: "2-digit", month: "short" }) +
      " · " +
      d.toLocaleTimeString("en-IN", { hour: "2-digit", minute: "2-digit", hour12: false })
    );
  }, []);

  const HomeDashboard = () => (
    <>
      <div className="sb-admin-page-head">
        <div>
          <div className="sb-admin-eyebrow">
            <span>Banking Management</span>
            <span className="stamp">LIVE</span>
          </div>
          <h1 className="sb-h-display">Platform <em>integrity</em>, at a glance.</h1>
          <p className="sb-lede" style={{ marginTop: 10 }}>
            Real-time view of platform health, transaction volume, KYC pipeline, and loan distribution.
            All metrics refresh automatically via the operations bus.
          </p>
        </div>
        <div className="right-meta">
          <div className="lab">Reconciled</div>
          <div className="val">{todayLabel}</div>
          {adminUser?.username && (
            <>
              <div className="lab" style={{ marginTop: 8 }}>Operator</div>
              <div className="val">{adminUser.username.toUpperCase()}</div>
            </>
          )}
        </div>
      </div>

      <div className="sb-admin-kpi-row">
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">01</div>
          <div className="label">Total Users</div>
          <div className="value mono">{(stats.totalUsers || 0).toLocaleString("en-IN")}</div>
          <div className="footnote">REGISTERED</div>
        </div>
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">02</div>
          <div className="label">Active Accounts</div>
          <div className="value mono">{(stats.totalAccounts || 0).toLocaleString("en-IN")}</div>
          <div className="footnote">VERIFIED PROFILES</div>
        </div>
        <div className="sb-admin-kpi-cell">
          <div className="corner-num">03</div>
          <div className="label">Transactions</div>
          <div className="value mono">{(stats.totalTransactions || 0).toLocaleString("en-IN")}</div>
          <div className="footnote">PROCESSED</div>
        </div>
        <div className="sb-admin-kpi-cell featured clickable" onClick={() => setShowTopupModal(true)}>
          <div className="corner-num">04</div>
          <div className="label">Treasury Pool</div>
          <div className="value">
            <span className="unit">₹</span>
            {formatCompactINR(bankPoolBalance).replace("₹", "")}
          </div>
          <div className="footnote">REGULATORY RESERVE</div>
          <span className="topup-link">↑ Top-up reserve</span>
        </div>
      </div>

      <div className="sb-admin-grid-2-uneven">
        <div className="sb-panel">
          <div className="sb-panel-head">
            <div>
              <div className="title">Transaction Volume</div>
              <div className="meta">Last 7 days · daily aggregated value</div>
            </div>
            <div className="sb-admin-section-meta">
              ↑ {formatCompactINR(txKpis.totalVolume)} loaded
            </div>
          </div>
          <div style={{ padding: "16px 16px 8px" }}>
            <TransactionChart data={transactions} />
          </div>
        </div>

        <div className="sb-panel">
          <div className="sb-panel-head">
            <div>
              <div className="title">System Health</div>
              <div className="meta">Live indicators</div>
            </div>
            <div className="sb-admin-section-meta">99.99% UPTIME</div>
          </div>
          <div>
            {[
              { label: "api_gateway", latency: "42ms", status: "Healthy", tone: "success" },
              { label: "fraud_engine", latency: "112ms", status: "Healthy", tone: "success" },
              { label: "database_primary", latency: "8ms", status: "Healthy", tone: "success" },
              { label: "settlement_svc", latency: "340ms", status: "Degraded", tone: "warn" },
              { label: "auth_provider", latency: "61ms", status: "Healthy", tone: "success" },
            ].map((row) => (
              <div className="sb-admin-health-row" key={row.label}>
                <span className="lbl">{row.label}</span>
                <span className="latency">{row.latency}</span>
                <span className={`sb-tag-pill ${row.tone}`}>
                  <span className="dot" />
                  {row.status}
                </span>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="sb-admin-grid-3">
        <div className="sb-panel">
          <div className="sb-panel-head">
            <div>
              <div className="title">Daily Transactions</div>
              <div className="meta">Count · last 7 days</div>
            </div>
          </div>
          <div style={{ padding: "16px" }}>
            <TransactionCountChart data={transactions} />
          </div>
        </div>

        <div className="sb-panel">
          <div className="sb-panel-head">
            <div>
              <div className="title">Domestic / Foreign</div>
              <div className="meta">Transaction mix</div>
            </div>
          </div>
          <div style={{ padding: "16px" }}>
            <TransactionTypeChart data={transactions} />
          </div>
        </div>

        <div className="sb-panel">
          <div className="sb-panel-head">
            <div>
              <div className="title">Loan Distribution</div>
              <div className="meta">By status</div>
            </div>
          </div>
          <div style={{ padding: "16px" }}>
            <LoanStatusChart loans={loans} />
          </div>
        </div>
      </div>

      <div className="sb-admin-grid-2-uneven">
        <div className="sb-panel">
          <div className="sb-panel-head">
            <div>
              <div className="title">Recent Transactions</div>
              <div className="meta">Latest 5 entries</div>
            </div>
            <button className="sb-admin-action-link" onClick={() => setView("transactions")}>
              View all →
            </button>
          </div>
          <div>
            {(transactions || []).slice(0, 5).map((t) => (
              <div
                className="sb-admin-recent-tx-row"
                key={t.id || `${t.senderAccount}-${t.timestamp}`}
              >
                <span className="ts">
                  {t?.timestamp
                    ? new Date(t.timestamp).toLocaleTimeString("en-IN", {
                        hour: "2-digit",
                        minute: "2-digit",
                        second: "2-digit",
                        hour12: false,
                      })
                    : "—"}
                </span>
                <span className="from">{t?.senderAccount ?? "—"}</span>
                <div className="to">
                  <span className="arrow">→</span>
                  <span className="id" title={t?.receiverAccount}>
                    {t?.receiverAccount ?? "—"}
                  </span>
                </div>
                <span className="sb-tag-pill success">
                  <span className="dot" />
                  Cleared
                </span>
                <span className="amt">{formatCompactINR(Number(t?.amount) || 0)}</span>
              </div>
            ))}
            {(!transactions || transactions.length === 0) && (
              <div className="sb-admin-table-empty">
                <div className="h">No recent transactions</div>
                <div>Activity will appear here as it flows.</div>
              </div>
            )}
          </div>
        </div>

        <div className="sb-panel">
          <div className="sb-panel-head">
            <div>
              <div className="title">Top Senders</div>
              <div className="meta">By total volume</div>
            </div>
          </div>
          <div style={{ padding: "16px" }}>
            <TopAccountsChart data={transactions} />
          </div>
        </div>
      </div>

      <div className="sb-grid-2">
        <div className="sb-panel">
          <div className="sb-panel-head">
            <div>
              <div className="title">KYC Funnel</div>
              <div className="meta">User verification breakdown</div>
            </div>
          </div>
          <div style={{ padding: "16px" }}>
            <KycFunnelChart users={users} />
          </div>
        </div>

        <div className="sb-panel">
          <div className="sb-panel-head">
            <div>
              <div className="title">Quick Actions</div>
              <div className="meta">Most-used admin tasks</div>
            </div>
          </div>
          <div style={{ padding: 16, display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
            {[
              { label: "Review KYC", view: "kyc_review", icon: "bi-shield-check" },
              { label: "Review Loans", view: "loans", icon: "bi-cash-coin" },
              { label: "Audit Logs", view: "audit", icon: "bi-journal-text" },
              { label: "Thresholds", view: "thresholds", icon: "bi-sliders" },
            ].map((a) => (
              <button
                key={a.view}
                className="sb-qa-cell"
                onClick={() => setView(a.view)}
                style={{ borderRadius: 2, border: "1px solid var(--rule)" }}
              >
                <div className="number">▸</div>
                <div className="title">
                  <i className={`bi ${a.icon}`} style={{ marginRight: 8, fontSize: 14 }} />
                  {a.label}
                </div>
                <div className="sub">Open in operator console</div>
                <span className="arrow">→</span>
              </button>
            ))}
          </div>
        </div>
      </div>
    </>
  );

  return (
    <div className="sb-shell">
      <button
        type="button"
        className={`sb-mobile-hamburger${mobileNavOpen ? " open" : ""}`}
        aria-label="Toggle navigation"
        onClick={() => setMobileNavOpen((o) => !o)}
      >
        <i className={`bi ${mobileNavOpen ? "bi-x-lg" : "bi-list"}`} />
      </button>
      {mobileNavOpen && (
        <div className="sb-mobile-overlay" onClick={() => setMobileNavOpen(false)} />
      )}

      <AdminSidebar
        active={view}
        setActive={setView}
        onTransactions={() => setView("transactions")}
        onLoans={() => setView("loans")}
        onUsers={() => setView("users")}
        onAccounts={() => setView("accounts")}
        adminUser={adminUser}
        onLogout={handleLogout}
        isOpen={mobileNavOpen}
      />

      <div className="sb-main">
        <AdminHeader view={view} onMobileToggle={() => setMobileNavOpen((o) => !o)} />

        <main className="sb-page-pad" style={{ position: "relative" }}>
          <span className="sb-admin-watermark">
            RESTRICTED · INTERNAL USE ONLY · SUBBYBANK CAPITAL
          </span>

          <AnimatePresence mode="wait">
            {view === "home" && (
              <motion.div key="home" {...fadeUp}>
                <HomeDashboard />
              </motion.div>
            )}

            {view === "transactions" && (
              <motion.div key="transactions" {...fadeUp}>
                <TransactionsView
                  data={transactions}
                  load={loadTransactions}
                  page={transPage}
                  setPage={setTransPage}
                  totalPages={transTotalPages}
                />
              </motion.div>
            )}

            {view === "users" && (
              <motion.div key="users" {...fadeUp}>
                <UsersView
                  users={users}
                  load={loadUsers}
                  page={userPage}
                  setPage={setUserPage}
                  totalPages={userTotalPages}
                />
              </motion.div>
            )}

            {view === "accounts" && (
              <motion.div key="accounts" {...fadeUp}>
                <AccountsView
                  accounts={accounts}
                  load={loadAccounts}
                  page={accPage}
                  setPage={setAccPage}
                  totalPages={accTotalPages}
                />
              </motion.div>
            )}

            {view === "loans" && (
              <motion.div key="loans" {...fadeUp}>
                <LoanReviewView />
              </motion.div>
            )}

            {view === "kyc_review" && (
              <motion.div key="kyc_review" {...fadeUp}>
                <KycUsersView />
              </motion.div>
            )}

            {view === "repayments" && (
              <motion.div key="repayments" {...fadeUp}>
                <AdminRepaymentTable
                  repayments={repayments}
                  load={loadRepayments}
                  page={repayPage}
                  setPage={setRepayPage}
                  totalPages={repayTotalPages}
                />
              </motion.div>
            )}

            {view === "audit" && (
              <motion.div key="audit" {...fadeUp}>
                <AuditLogs />
              </motion.div>
            )}

            {view === "thresholds" && (
              <motion.div key="thresholds" {...fadeUp}>
                <ThresholdsView />
              </motion.div>
            )}
          </AnimatePresence>
        </main>
      </div>

      <AnimatePresence>
        {showTopupModal && (
          <motion.div
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            role="dialog"
            aria-modal="true"
            aria-label="Treasury reserve top-up"
            className="sb-modal-backdrop"
            onClick={() => !topupBusy && setShowTopupModal(false)}
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
                <span className="corner-mark">REF · TR-{Date.now().toString().slice(-6)}</span>
                <div className="sb-seal">₹</div>
                <h3>Top-up <em>treasury</em></h3>
                <p>
                  Current balance: <span className="sb-mono">{formatCompactINR(bankPoolBalance)}</span>.
                  Funds become immediately available for loan disbursements.
                </p>
              </div>
              <div className="sb-modal-body">
                <label className="sb-admin-field-label" htmlFor="topup-amount">
                  Amount to add <span className="req">*</span>
                </label>
                <div className="sb-admin-amt-field">
                  <span className="currency">₹</span>
                  <input
                    id="topup-amount"
                    type="number"
                    inputMode="decimal"
                    min="0"
                    step="0.01"
                    placeholder="0"
                    value={topupAmount}
                    autoFocus
                    onChange={(e) => setTopupAmount(e.target.value)}
                  />
                </div>

                {topupError && <div className="sb-alert sb-alert-error">{topupError}</div>}

                <div className="sb-btn-row" style={{ paddingTop: 8 }}>
                  <button
                    type="button"
                    className="sb-btn sb-btn-secondary sb-btn-block"
                    onClick={() => setShowTopupModal(false)}
                    disabled={topupBusy}
                  >
                    Cancel
                  </button>
                  <button
                    type="button"
                    className="sb-btn sb-btn-primary sb-btn-block"
                    onClick={handleTopup}
                    disabled={topupBusy || !topupAmount || parseFloat(topupAmount) <= 0}
                  >
                    {topupBusy ? <><span className="sb-spinner" /> Adding…</> : "Add Funds"}
                  </button>
                </div>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>
    </div>
  );
}
