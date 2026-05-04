import { useEffect, useState, type ReactNode } from "react";
import { Link, NavLink, Outlet, useLocation } from "react-router-dom";
import { useAuth } from "../AuthContext";

type NavItem = { to: string; label: string; icon: ReactNode; end?: boolean };

const NAV_GROUPS: { heading: string; items: NavItem[] }[] = [
  {
    heading: "Verification",
    items: [
      { to: "/", end: true, label: "Submissions", icon: <DashboardIcon /> },
      { to: "/new/kyc", label: "New KYC", icon: <UserIcon /> },
      { to: "/new/loan", label: "New Loan", icon: <FileIcon /> },
    ],
  },
  {
    heading: "Governance",
    items: [
      { to: "/admin/audit-log", label: "Audit log", icon: <ShieldIcon /> },
      { to: "/admin/keys", label: "API keys", icon: <KeyIcon /> },
    ],
  },
];

function formatDisplay(name: string | null | undefined): string {
  if (!name) return "Operator";
  return name;
}

export default function Layout() {
  const { me, signOut } = useAuth();
  const loc = useLocation();
  const [mobileOpen, setMobileOpen] = useState(false);

  useEffect(() => {
    setMobileOpen(false);
  }, [loc.pathname]);

  const initial = (me?.label || me?.org || "U").trim().slice(0, 1).toUpperCase();
  const displayLabel = formatDisplay(me?.label);
  const displayOrg = formatDisplay(me?.org);

  return (
    <div className="fv-shell">
      <button
        type="button"
        className={`fv-hamburger${mobileOpen ? " open" : ""}`}
        onClick={() => setMobileOpen((o) => !o)}
        aria-label="Toggle navigation"
      >
        {mobileOpen ? <XIcon /> : <MenuIcon />}
      </button>
      {mobileOpen && (
        <div
          className="fv-mobile-overlay open"
          onClick={() => setMobileOpen(false)}
        />
      )}

      <aside className={`fv-sidebar${mobileOpen ? " open" : ""}`}>
        <Link to="/" className="block fv-brand" style={{ textDecoration: "none" }}>
          <div className="fv-brand-mark">
            <span className="mark" />
            <span className="name">findoc<em>·verify</em></span>
          </div>
          <div className="fv-brand-tag">Verification Console</div>
        </Link>

        {me && (
          <div className="fv-user-card">
            <div className="top">
              <div className="av">{initial}</div>
              <div className="info">
                <div className="label" title={me.label || ""}>{displayLabel}</div>
                <div className="org" title={me.org || ""}>{displayOrg}</div>
              </div>
            </div>
            <div className="session">
              <span>SES · LIVE</span>
              <span className="live">Active</span>
            </div>
          </div>
        )}

        {NAV_GROUPS.map((group) => (
          <div className="fv-nav-section" key={group.heading}>
            <div className="heading">▸ {group.heading}</div>
            {group.items.map((item) => (
              <NavLink
                key={item.to}
                to={item.to}
                end={item.end}
                className={({ isActive }) => `fv-nav-item${isActive ? " active" : ""}`}
              >
                <span className="icn">{item.icon}</span>
                <span>{item.label}</span>
              </NavLink>
            ))}
          </div>
        ))}

        <div className="fv-sidebar-foot">
          <button className="fv-logout" onClick={signOut}>
            <ExitIcon />
            Sign out · End session
          </button>
          <div className="fv-build">
            v 1.0 · build 2026.05.02
            <br />
            findoc-verify operator
          </div>
        </div>
      </aside>

      <div className="fv-main">
        <Outlet />
      </div>
    </div>
  );
}

function DashboardIcon() {
  return (
    <svg viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="3" width="6.5" height="6.5" rx="1" />
      <rect x="10.5" y="3" width="6.5" height="6.5" rx="1" />
      <rect x="3" y="10.5" width="6.5" height="6.5" rx="1" />
      <rect x="10.5" y="10.5" width="6.5" height="6.5" rx="1" />
    </svg>
  );
}
function UserIcon() {
  return (
    <svg viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="10" cy="7" r="3" />
      <path d="M3.5 17a6.5 6.5 0 0 1 13 0" />
    </svg>
  );
}
function FileIcon() {
  return (
    <svg viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <path d="M5 3h7l3 3v11a.5.5 0 0 1-.5.5h-9.5A.5.5 0 0 1 4.5 17V3.5A.5.5 0 0 1 5 3Z" />
      <path d="M12 3v3h3M7 11h6M7 14h4" />
    </svg>
  );
}
function ShieldIcon() {
  return (
    <svg viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <path d="M10 2.5 4 4.5v5c0 4 2.5 6.5 6 8 3.5-1.5 6-4 6-8v-5L10 2.5Z" />
      <path d="m7.5 10 2 2 3.5-3.5" />
    </svg>
  );
}
function KeyIcon() {
  return (
    <svg viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="6" cy="14" r="2.5" />
      <path d="m8 12 7-7M13 8l2 2M11 6l2 2" />
    </svg>
  );
}
function ExitIcon() {
  return (
    <svg viewBox="0 0 20 20" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <path d="M11.5 4h-7v12h7M9 10h8M14 7l3 3-3 3" />
    </svg>
  );
}
function MenuIcon() {
  return (
    <svg viewBox="0 0 20 20" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 6h14M3 10h14M3 14h14" />
    </svg>
  );
}
function XIcon() {
  return (
    <svg viewBox="0 0 20 20" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="m6 6 8 8M14 6l-8 8" />
    </svg>
  );
}
