import React from "react";

const VIEW_LABEL = {
  home: "Dashboard",
  transactions: "Transactions",
  kyc_review: "KYC Review",
  loans: "Loan Origination",
  users: "User Management",
  accounts: "Accounts",
  repayments: "Loan Repayments",
  audit: "Audit Logs",
  thresholds: "Policy Thresholds",
};

const VIEW_GROUP = {
  home: "Operator",
  transactions: "Operations",
  kyc_review: "Operations",
  loans: "Operations",
  users: "Administration",
  accounts: "Administration",
  repayments: "Administration",
  audit: "Governance",
  thresholds: "Governance",
};

export default function AdminHeader({ view = "home", onMobileToggle }) {
  const today = new Date().toLocaleDateString("en-IN", {
    weekday: "short",
    day: "2-digit",
    month: "short",
    year: "numeric",
  });

  return (
    <div className="sb-topbar">
      <div className="sb-breadcrumb">
        <span>{VIEW_GROUP[view] || "Operator"}</span>
        <span className="sep">/</span>
        <span className="current">{VIEW_LABEL[view] || "Dashboard"}</span>
      </div>
      <div className="sb-topbar-right">
        <span className="sb-status-pill">
          <span className="dot" />
          All systems operational
        </span>
        <span className="sb-mono" style={{ fontSize: 11 }}>{today}</span>
      </div>
    </div>
  );
}
