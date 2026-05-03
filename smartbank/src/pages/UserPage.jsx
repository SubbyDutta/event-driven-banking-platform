import React, { useEffect, useState } from 'react';
import { useNavigate } from 'react-router-dom';
import API from '../api';
import { decodeToken, isTokenExpired, clearToken, safeUsername, getRole } from '../utils/auth';
import Sidebar from '../components/Sidebar';
import DashboardPanel from '../components/DashBoardPanel';
import TransferPanel from '../components/TransferPanel';
import TransactionsPanel from '../components/TransactionPanel';
import ChatbotPanel from '../components/ChatbotPanel';
import LoanPanel from '../components/LoanPanel';
import LoanRepaymentPanel from '../components/MyLoans';
import KycPanel from '../components/KycPanel';

const VIEW_LABEL = {
  dashboard: 'Dashboard',
  transfer: 'Transfers',
  tx: 'Statements',
  chatbot: 'Advisor',
  loan: 'Apply for Loan',
  myloan: 'Active Loans',
  kyc: 'KYC',
  createAccount: 'Open Account',
};

const VIEW_GROUP = {
  dashboard: 'Workspace',
  transfer: 'Banking',
  tx: 'Banking',
  chatbot: 'Support',
  loan: 'Credit',
  myloan: 'Credit',
  kyc: 'Support',
  createAccount: 'Workspace',
};

export default function UserPage() {
  const navigate = useNavigate();
  const [user, setUser] = useState({ username: 'User', role: '' });
  const [active, setActive] = useState('dashboard');
  const [sidebarOpen, setSidebarOpen] = useState(false);

  const [hasAccount, setHasAccount] = useState(true);
  const [kycStatus, setKycStatus] = useState('UNKNOWN');
  const [accountNumber, setAccountNumber] = useState('');
  const [balanceRaw, setBalanceRaw] = useState(null);
  const [transactions, setTransactions] = useState([]);
  const [txLoading, setTxLoading] = useState(false);

  const [formType, setFormType] = useState('');
  const [creatingAccount, setCreatingAccount] = useState(false);
  const [createError, setCreateError] = useState('');
  const [showSuccess, setShowSuccess] = useState(false);

  useEffect(() => {
    const payload = decodeToken();
    if (!payload || isTokenExpired(payload)) {
      clearToken();
      return navigate('/login');
    }
    const usernameFromToken = safeUsername(payload);
    setUser({ username: usernameFromToken, role: getRole(payload) });
    checkAccountAndLoad(usernameFromToken);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [navigate]);

  async function checkAccountAndLoad(username) {
    try {
      let kyc = 'NONE';
      try {
        const kycRes = await API.get('/kyc/status');
        kyc = kycRes.data?.kycStatus || 'NONE';
      } catch (e) {
        console.error('Error fetching KYC status:', e);
      }
      setKycStatus(kyc);

      if (kyc !== 'KYC_APPROVED') {
        setHasAccount(false);
        setAccountNumber('');
        setActive('kyc');
        return;
      }

      const res = await API.get('/user/me/account');
      const acct =
        res.data?.accountNumber ??
        res.data?.account_number ??
        (typeof res.data === 'string' ? res.data : null);

      if (acct) {
        setHasAccount(true);
        setAccountNumber(acct);
        await fetchBalance();
        await fetchTransactions({ username, page: 0, size: 10 });
      } else {
        setHasAccount(false);
        setAccountNumber('');
        setActive('createAccount');
      }
    } catch (err) {
      if (err?.response?.status === 404) {
        setHasAccount(false);
        setAccountNumber('');
        setActive('createAccount');
      } else {
        console.error('Error checking account:', err);
      }
    }
  }

  async function fetchBalance() {
    try {
      const res = await API.get('/user/balance');
      const raw = res.data;
      const match = String(raw).match(/₹\s?([0-9,.]+)/);
      if (match) setBalanceRaw(parseFloat(match[1].replace(/,/g, '')));
      else if (typeof raw === 'number') setBalanceRaw(raw);
      else if (raw?.balance != null) setBalanceRaw(raw.balance);
    } catch (err) {
      console.error('Error fetching balance:', err);
    }
  }

  async function fetchTransactions(options = {}) {
    const { username = user.username, page = 0, size = 10, from, to, minAmount, maxAmount } = options;
    if (!username) return;
    setTxLoading(true);
    try {
      const params = { page, size };
      if (from) params.from = from;
      if (to) params.to = to;
      if (minAmount !== '' && minAmount != null) params.minAmount = minAmount;
      if (maxAmount !== '' && maxAmount != null) params.maxAmount = maxAmount;

      const res = await API.get(`/user/${encodeURIComponent(username)}/transactions`, { params });
      setTransactions(res.data?.content || []);
    } catch (err) {
      console.error('Error fetching transactions:', err);
    } finally {
      setTxLoading(false);
    }
  }

  function logout() {
    clearToken();
    navigate('/login');
  }

  const guardedSetActive = (view) => {
    const requiresAccount = ['dashboard', 'balance', 'transfer', 'tx', 'addMoney', 'loan', 'myloan'];
    if (kycStatus !== 'KYC_APPROVED' && requiresAccount.includes(view)) {
      setActive('kyc');
      return;
    }
    if (!hasAccount && requiresAccount.includes(view)) return;
    setActive(view);
  };

  const handleCreateAccount = async () => {
    if (!formType) {
      setCreateError('Please select an account type.');
      return;
    }
    if (!user.username) {
      setCreateError('User not found. Please login again.');
      return;
    }
    setCreatingAccount(true);
    setCreateError('');
    try {
      await API.post('/user/create-account', { username: user.username, type: formType });
      setShowSuccess(true);
      setTimeout(() => setShowSuccess(false), 3000);
      await checkAccountAndLoad(user.username);
      setActive('dashboard');
    } catch (err) {
      const msg = err?.response?.data?.error || err?.response?.data || 'Failed to create account. Please try again.';
      setCreateError(String(msg));
    } finally {
      setCreatingAccount(false);
    }
  };

  const today = new Date().toLocaleDateString('en-IN', {
    weekday: 'short',
    day: '2-digit',
    month: 'short',
    year: 'numeric',
  });

  return (
    <div className="sb-shell">
      <button
        className={`sb-mobile-hamburger${sidebarOpen ? ' open' : ''}`}
        onClick={() => setSidebarOpen(!sidebarOpen)}
        aria-label="Toggle menu"
      >
        <i className={`bi ${sidebarOpen ? 'bi-x-lg' : 'bi-list'}`} />
      </button>

      {sidebarOpen && (
        <div
          className="sb-mobile-overlay"
          onClick={() => setSidebarOpen(false)}
          aria-hidden
        />
      )}

      <Sidebar
        user={user}
        active={active}
        setActive={(view) => {
          guardedSetActive(view);
          if (window.innerWidth <= 768) setSidebarOpen(false);
        }}
        logout={logout}
        hasAccount={hasAccount}
        isOpen={sidebarOpen}
        accountNumber={accountNumber}
        onClose={() => setSidebarOpen(false)}
      />

      <main className="sb-main">
        <div className="sb-topbar">
          <div className="sb-breadcrumb">
            <span>{VIEW_GROUP[active] || 'Workspace'}</span>
            <span className="sep">/</span>
            <span className="current">{VIEW_LABEL[active] || 'Dashboard'}</span>
          </div>
          <div className="sb-topbar-right">
            <span className="sb-status-pill">
              <span className="dot" />
              All systems operational
            </span>
            <span className="sb-mono" style={{ fontSize: 11 }}>{today}</span>
          </div>
        </div>

        <div className="sb-page-pad">
          {active === 'dashboard' && hasAccount && (
            <DashboardPanel
              setActive={(view) => {
                if (view === 'addMoney') setActive('transfer');
                else guardedSetActive(view);
              }}
              transactions={transactions}
              balance={balanceRaw}
              accountNumber={accountNumber}
              username={user.username}
            />
          )}

          {active === 'transfer' && hasAccount && (
            <TransferPanel
              accountNumber={accountNumber}
              onComplete={() => {
                fetchBalance();
                fetchTransactions({ username: user.username, page: 0 });
              }}
            />
          )}

          {active === 'tx' && hasAccount && (
            <TransactionsPanel
              transactions={transactions}
              loading={txLoading}
              balance={balanceRaw}
              onReload={(params) => fetchTransactions({ username: user.username, ...params })}
            />
          )}

          {active === 'loan' && hasAccount && (
            <LoanPanel
              onLoanApplied={() => {
                fetchBalance();
                fetchTransactions({ username: user.username });
              }}
            />
          )}

          {active === 'myloan' && hasAccount && <LoanRepaymentPanel />}

          {active === 'kyc' && (
            <KycPanel
              initialStatus={kycStatus}
              onApproved={() => {
                setKycStatus('KYC_APPROVED');
                checkAccountAndLoad(user.username);
              }}
            />
          )}

          {active === 'chatbot' && <ChatbotPanel username={user.username} />}
        </div>
      </main>

      {kycStatus === 'KYC_APPROVED' && !hasAccount && (
        <div className="sb-modal-backdrop">
          <div className="sb-modal">
            <div className="sb-modal-head">
              <div className="corner-mark">REF · ACCT-OPEN</div>
              <div className="sb-seal" style={{ marginTop: 12 }}>
                <i className="bi bi-bank" />
              </div>
              <h3>Open Your Account</h3>
              <p>Pick an account type. Your KYC is already verified — this only takes a moment.</p>
            </div>
            <div className="sb-modal-body">
              {showSuccess && (
                <div className="sb-alert sb-alert-success">
                  <i className="bi bi-check-circle-fill" />
                  Account created successfully!
                </div>
              )}
              {createError && (
                <div className="sb-alert sb-alert-error">
                  <i className="bi bi-exclamation-triangle-fill" />
                  {createError}
                </div>
              )}

              <div className="sb-field" style={{ marginBottom: 20 }}>
                <div className="sb-field-label">
                  Account Type <span className="req">*</span>
                </div>
                <select
                  className="sb-field-input"
                  value={formType}
                  onChange={(e) => setFormType(e.target.value)}
                >
                  <option value="">Choose account type…</option>
                  <option value="savings">Savings Account</option>
                  <option value="current">Current Account</option>
                  <option value="salary">Salary Account</option>
                </select>
              </div>

              <div className="sb-txn-summary">
                <div className="row">
                  <span className="lbl">USERNAME</span>
                  <span className="val">{user.username}</span>
                </div>
                <div className="row">
                  <span className="lbl">KYC STATUS</span>
                  <span className="val">VERIFIED</span>
                </div>
              </div>

              <div className="sb-btn-row" style={{ borderTop: 'none', paddingTop: 0 }}>
                <button
                  className="sb-btn sb-btn-primary"
                  style={{ flex: 1 }}
                  disabled={creatingAccount}
                  onClick={handleCreateAccount}
                >
                  {creatingAccount ? (
                    <>
                      <span className="sb-spinner" /> Creating…
                    </>
                  ) : (
                    'Create Account'
                  )}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
