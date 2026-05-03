function Pill({
  className,
  children,
  big = false,
}: {
  className: string;
  children: React.ReactNode;
  big?: boolean;
}) {
  return (
    <span className={`fv-tag ${className}${big ? " big" : ""}`}>
      <span className="dot" />
      {children}
    </span>
  );
}

export function StatusBadge({ s }: { s: string }) {
  if (s === "pass") return <Pill className="success">Passed</Pill>;
  if (s === "fail") return <Pill className="error">Failed</Pill>;
  return <Pill className="warn">Warning</Pill>;
}

export function SevBadge({ s }: { s: string }) {
  if (s === "high") return <Pill className="error">High</Pill>;
  if (s === "med") return <Pill className="warn">Medium</Pill>;
  return <Pill className="muted">Low</Pill>;
}

export function RecBadge({ r, big = false }: { r: string | null | undefined; big?: boolean }) {
  if (!r) return <span className="text-muted text-xs">—</span>;
  const label = r.replace("_", " ").toUpperCase();
  if (r === "approve" || r === "verified")
    return <Pill big={big} className="success">{label}</Pill>;
  if (r === "reject")
    return <Pill big={big} className="error">{label}</Pill>;
  return <Pill big={big} className="warn">{label}</Pill>;
}

export function UseCaseBadge({ u }: { u: "kyc" | "loan" }) {
  if (u === "kyc") return <Pill className="solid">KYC</Pill>;
  return <Pill className="muted">LOAN</Pill>;
}
