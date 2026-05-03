import React from "react";

const NAV_GROUPS = [
  {
    heading: "Operations",
    items: [
      { key: "home", label: "Dashboard", icon: "bi-grid-1x2" },
      { key: "transactions", label: "Transactions", icon: "bi-arrow-left-right" },
      { key: "kyc_review", label: "KYC Review", icon: "bi-shield-check" },
      { key: "loans", label: "Loans", icon: "bi-cash-coin" },
    ],
  },
  {
    heading: "Administration",
    items: [
      { key: "users", label: "User Management", icon: "bi-people" },
      { key: "accounts", label: "Accounts", icon: "bi-credit-card-2-front" },
      { key: "repayments", label: "Loan Repayments", icon: "bi-receipt" },
    ],
  },
  {
    heading: "Governance",
    items: [
      { key: "audit", label: "Audit Logs", icon: "bi-journal-text" },
      { key: "thresholds", label: "Policy Thresholds", icon: "bi-sliders" },
    ],
  },
];

function formatDisplay(name) {
  if (!name) return "Admin";
  if (name.length > 14) return name.slice(0, 13) + "…";
  return name;
}

export default function AdminSidebar({
  active,
  setActive,
  onTransactions,
  onLoans,
  onUsers,
  onAccounts,
  adminUser,
  onLogout,
  isOpen,
}) {
  const username = adminUser?.username || "Operator";
  const display = formatDisplay(username);
  const initial = (username[0] || "A").toUpperCase();

  const handleClick = (key) => {
    if (key === "transactions") return onTransactions?.();
    if (key === "loans") return onLoans?.();
    if (key === "users") return onUsers?.();
    if (key === "accounts") return onAccounts?.();
    setActive(key);
  };

  return (
    <aside className={`sb-sidebar${isOpen ? " open" : ""}`}>
      <div className="sb-brand">
        <div className="sb-brand-mark">
          <div className="mark" />
          <div className="name">SubbyBank</div>
        </div>
        <div className="sb-brand-tag">Operator</div>
      </div>

      <div className="sb-user-card">
        <div className="sb-user-card-top">
          <div className="avatar-wrap">
            <div className="avatar">{initial}</div>
            <div className="status-dot" />
          </div>
          <div className="info">
            <div className="name" title={username}>{display}</div>
            <div className="role">ADMINISTRATOR · TIER-1</div>
          </div>
        </div>
        <div className="acct">
          <span className="lbl">SES</span>
          <span className="num">87A4-22C1</span>
        </div>
      </div>

      {NAV_GROUPS.map((group) => (
        <div className="sb-nav-section" key={group.heading}>
          <div className="heading">{group.heading}</div>
          {group.items.map((item) => (
            <button
              key={item.key}
              className={`sb-nav-item${active === item.key ? " active" : ""}`}
              onClick={() => handleClick(item.key)}
            >
              <i className={`bi ${item.icon} icon`} />
              <span>{item.label}</span>
            </button>
          ))}
        </div>
      ))}

      <div className="sb-sidebar-footer">
        <button className="sb-logout" onClick={onLogout}>
          <i className="bi bi-box-arrow-right" />
          Sign out · End session
        </button>
        v 4.2.1 · build 2026.05.02
        <br />RBI · Ref 274-A
      </div>
    </aside>
  );
}
