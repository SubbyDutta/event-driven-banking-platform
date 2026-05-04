import React from 'react';

function formatDisplayName(name) {
  if (!name) return 'User';
  const idx = name.indexOf('_');
  if (idx > 0 && idx <= 16) return name.slice(0, idx);
  return name;
}

const NAV_GROUPS = [
  {
    heading: 'Banking',
    items: [
      { key: 'dashboard', label: 'Dashboard', icon: 'bi-house-door' },
      { key: 'transfer', label: 'Wallet Actions', icon: 'bi-arrow-left-right' },
      { key: 'tx', label: 'Statements', icon: 'bi-receipt' },
    ],
  },
  {
    heading: 'Credit',
    items: [
      { key: 'loan', label: 'Apply for Loan', icon: 'bi-file-earmark-text' },
      { key: 'myloan', label: 'Active Loans', icon: 'bi-credit-card-2-front' },
    ],
  },
  {
    heading: 'Support',
    items: [
      { key: 'chatbot', label: 'Advisor', icon: 'bi-chat-dots' },
      { key: 'kyc', label: 'KYC', icon: 'bi-shield-check' },
    ],
  },
];

export default function Sidebar({ user, active, setActive, logout, hasAccount, isOpen, accountNumber }) {
  const fullName = user?.username || 'User';
  const displayName = formatDisplayName(fullName);
  return (
    <aside className={`sb-sidebar${isOpen ? ' open' : ''}`}>
      <div className="sb-brand">
        <div className="sb-brand-mark">
          <div className="mark" />
          <div className="name">SubbyBank</div>
        </div>
        <div className="sb-brand-tag">Private Banking</div>
      </div>

      <div className="sb-user-card">
        <div className="sb-user-card-top">
          <div className="avatar-wrap">
            <div className="avatar">{fullName.charAt(0)}</div>
            <div className="status-dot" />
          </div>
          <div className="info">
            <div className="name" title={fullName}>{displayName}</div>
            <div className="role">{String(user?.role || 'USER').toUpperCase()} · ACTIVE</div>
          </div>
        </div>
        {accountNumber && (
          <div className="acct" title={accountNumber}>
            <span className="lbl">A/C</span>
            <span className="num">{accountNumber}</span>
          </div>
        )}
      </div>

      {NAV_GROUPS.map((group) => (
        <div className="sb-nav-section" key={group.heading}>
          <div className="heading">{group.heading}</div>
          {group.items.map((item) => {
            const requiresAccount = item.key !== 'kyc' && item.key !== 'chatbot';
            const disabled = requiresAccount && !hasAccount;
            return (
              <button
                key={item.key}
                className={`sb-nav-item${active === item.key ? ' active' : ''}`}
                onClick={() => !disabled && setActive(item.key)}
                disabled={disabled}
              >
                <i className={`bi ${item.icon} icon`} />
                <span>{item.label}</span>
                {item.key === 'myloan' && hasAccount && <span className="badge">0</span>}
              </button>
            );
          })}
        </div>
      ))}

      <div className="sb-sidebar-footer">
        <button className="sb-logout" onClick={logout}>
          <i className="bi bi-box-arrow-right" />
          Sign out
        </button>
        SubbyBank Insured · Member SecureBank
        <br />© 2026 SubbyBank Capital Ltd.
      </div>
    </aside>
  );
}
