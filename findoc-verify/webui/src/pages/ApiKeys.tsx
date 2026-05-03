import { useEffect, useState, type FormEvent } from "react";
import { api, type ApiKeyRow } from "../api";
import { useToast, useConfirm } from "../components/Toast";
import { CopyButton } from "../components/CopyButton";

export default function ApiKeys() {
  const toast = useToast();
  const confirm = useConfirm();
  const [rows, setRows] = useState<ApiKeyRow[]>([]);
  const [label, setLabel] = useState("");
  const [org, setOrg] = useState("");
  const [minted, setMinted] = useState<{ key: string; label: string; org: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [revealed, setRevealed] = useState(false);
  const [copied, setCopied] = useState(false);

  async function load() {
    try { setRows(await api.listKeys()); } catch (e: any) { toast.error("Couldn't load keys", e?.message); }
  }
  useEffect(() => { load(); /* eslint-disable-next-line */ }, []);

  async function mint(e: FormEvent) {
    e.preventDefault();
    setBusy(true);
    try {
      const r = await api.createKey(label, org);
      setMinted({ key: r.key, label: r.label, org: r.org });
      setRevealed(false);
      setLabel(""); setOrg("");
      toast.success("API key minted", `${r.org} · ${r.label}`);
      load();
    } catch (e: any) {
      toast.error("Mint failed", e?.message);
    } finally {
      setBusy(false);
    }
  }

  async function revoke(row: ApiKeyRow) {
    const ok = await confirm({
      title: `Revoke "${row.label}"?`,
      body: `This key for ${row.org} will stop working immediately. This cannot be undone.`,
      confirmLabel: "Revoke key",
      cancelLabel: "Keep active",
      destructive: true,
    });
    if (!ok) return;
    try {
      await api.revokeKey(row.id);
      toast.success("Key revoked", `${row.org} · ${row.label}`);
      load();
    } catch (e: any) {
      toast.error("Revoke failed", e?.message);
    }
  }

  async function copyMinted() {
    if (!minted) return;
    try {
      await navigator.clipboard.writeText(minted.key);
      setCopied(true);
      toast.success("Key copied to clipboard");
      setTimeout(() => setCopied(false), 1500);
    } catch {
      toast.error("Couldn't copy", "Clipboard API blocked.");
    }
  }

  const active = rows.filter(r => !r.revokedAt).length;
  const revoked = rows.length - active;
  const maskedKey = minted ? minted.key.slice(0, 6) + "•".repeat(Math.max(8, minted.key.length - 10)) + minted.key.slice(-4) : "";

  return (
    <>
      <div className="fv-topbar">
        <div className="fv-breadcrumb">
          <span>Governance</span>
          <span className="sep">/</span>
          <span className="current">API keys</span>
        </div>
      </div>

      <main className="fv-page-pad">
        <div className="fv-page-head">
          <div>
            <div className="fv-eyebrow" style={{ marginBottom: 12 }}>
              <span>Credentials</span>
              <span className="stamp">{rows.length} TOTAL</span>
            </div>
            <h1 className="fv-h-display" style={{ fontSize: 32 }}>
              Mint and <em>revoke</em> API keys.
            </h1>
            <p className="fv-lede" style={{ marginTop: 10, maxWidth: 560 }}>
              Each key is bound to an organization and label. Keys are <strong>shown once at mint time</strong> —
              you cannot retrieve the secret afterward. Every API call is logged with the key's identity.
            </p>
          </div>
        </div>

        <div className="fv-kpi-row">
          <div className="fv-kpi-cell">
            <div className="corner">A</div>
            <div className="label">Total Keys</div>
            <div className="value mono">{rows.length}</div>
            <div className="footnote">ALL TIME</div>
          </div>
          <div className="fv-kpi-cell">
            <div className="corner">B</div>
            <div className="label">Active</div>
            <div className={`value mono${active > 0 ? " ok" : ""}`}>{active}</div>
            <div className="footnote">CURRENTLY VALID</div>
          </div>
          <div className="fv-kpi-cell">
            <div className="corner">C</div>
            <div className="label">Revoked</div>
            <div className={`value mono${revoked > 0 ? " muted" : ""}`}>{revoked}</div>
            <div className="footnote">PERMANENTLY DEAD</div>
          </div>
          <div className="fv-kpi-cell featured">
            <div className="corner">●</div>
            <div className="label">Status</div>
            <div className="value" style={{ color: "#6FB97D" }}>Live</div>
            <div className="footnote">KEY VALIDATION ACTIVE</div>
          </div>
        </div>

        <form
          onSubmit={mint}
          className="fv-panel"
          style={{ padding: 0, marginBottom: 18 }}
        >
          <div className="fv-panel-head">
            <div>
              <div className="title">Mint new key</div>
              <div className="meta">Bind to a label and organization</div>
            </div>
          </div>
          <div className="fv-panel-body" style={{ display: "grid", gridTemplateColumns: "1fr 1fr auto", gap: 14, alignItems: "end" }}>
            <label className="block">
              <span className="fv-field-label">Label</span>
              <input
                required
                value={label}
                onChange={e => setLabel(e.target.value)}
                placeholder="e.g. backend-prod"
                className="fv-input"
              />
            </label>
            <label className="block">
              <span className="fv-field-label">Organization</span>
              <input
                required
                value={org}
                onChange={e => setOrg(e.target.value)}
                placeholder="e.g. acme-bank"
                className="fv-input"
              />
            </label>
            <button
              disabled={busy}
              className="fv-btn fv-btn-primary"
            >
              {busy ? <><span className="fv-spinner" /> Minting…</> : "Mint key →"}
            </button>
          </div>
        </form>

        {minted && (
          <div
            style={{
              background: "rgba(45,95,63,0.04)",
              border: "1px solid rgba(45,95,63,0.2)",
              borderLeft: "3px solid var(--positive)",
              borderRadius: 2,
              padding: 18,
              marginBottom: 18,
            }}
          >
            <div style={{ display: "flex", justifyContent: "space-between", alignItems: "flex-start", gap: 12, marginBottom: 12 }}>
              <div>
                <div style={{ fontFamily: "'Fraunces', Georgia, serif", fontSize: 16, color: "var(--positive)", fontWeight: 500 }}>
                  Key minted — copy this <em style={{ fontStyle: "italic" }}>now</em>.
                </div>
                <div style={{ fontSize: 12, color: "var(--positive)", opacity: 0.85, marginTop: 2 }}>
                  {minted.org} · {minted.label} · You will not see it again.
                </div>
              </div>
              <button
                onClick={() => setMinted(null)}
                className="fv-btn-ghost"
                style={{ padding: 4 }}
                aria-label="Dismiss"
              >
                ✕
              </button>
            </div>
            <div style={{ display: "flex", alignItems: "stretch", gap: 8 }}>
              <pre
                style={{
                  margin: 0,
                  flex: 1,
                  padding: "10px 14px",
                  background: "var(--paper)",
                  border: "1px solid var(--rule)",
                  borderRadius: 2,
                  fontFamily: "'JetBrains Mono', monospace",
                  fontSize: 13,
                  color: "var(--ink)",
                  overflow: "auto",
                  userSelect: "all",
                }}
              >
                {revealed ? minted.key : maskedKey}
              </pre>
              <button
                type="button"
                onClick={() => setRevealed(v => !v)}
                className="fv-btn fv-btn-secondary fv-btn-sm"
              >
                {revealed ? "Hide" : "Reveal"}
              </button>
              <button
                onClick={copyMinted}
                className="fv-btn fv-btn-primary fv-btn-sm"
              >
                {copied ? "Copied ✓" : "Copy"}
              </button>
            </div>
          </div>
        )}

        <div className="fv-panel">
          <div className="fv-panel-head">
            <div>
              <div className="title">All keys</div>
              <div className="meta">Sorted by creation desc</div>
            </div>
          </div>
          <div style={{ overflowX: "auto" }}>
            <table className="fv-table">
              <thead>
                <tr>
                  <th>Label</th>
                  <th>Org</th>
                  <th>Created</th>
                  <th>Last used</th>
                  <th>Status</th>
                  <th />
                </tr>
              </thead>
              <tbody>
                {rows.length === 0 && (
                  <tr>
                    <td colSpan={6}>
                      <div className="fv-table-empty">
                        <div className="h">No API keys yet</div>
                        <div>Mint one above to start submitting applications.</div>
                      </div>
                    </td>
                  </tr>
                )}
                {rows.map(r => (
                  <tr key={r.id}>
                    <td>
                      <span style={{ display: "inline-flex", alignItems: "center", gap: 6, fontFamily: "'Fraunces', Georgia, serif", fontWeight: 500, fontSize: 14 }}>
                        {r.label}
                        <CopyButton value={r.label} label="label" />
                      </span>
                    </td>
                    <td className="mono" style={{ fontSize: 12 }}>{r.org}</td>
                    <td className="mono" style={{ fontSize: 11, color: "var(--muted)", whiteSpace: "nowrap" }}>
                      {new Date(r.createdAt).toLocaleString("en-IN", { hour12: false })}
                    </td>
                    <td className="mono" style={{ fontSize: 11, color: "var(--muted)", whiteSpace: "nowrap" }}>
                      {r.lastUsedAt ? new Date(r.lastUsedAt).toLocaleString("en-IN", { hour12: false }) : "—"}
                    </td>
                    <td>
                      {r.revokedAt
                        ? <span className="fv-tag error"><span className="dot" />revoked {new Date(r.revokedAt).toLocaleDateString()}</span>
                        : <span className="fv-tag success"><span className="dot" />active</span>}
                    </td>
                    <td className="right">
                      {!r.revokedAt && (
                        <button
                          onClick={() => revoke(r)}
                          className="fv-btn-inline danger"
                        >
                          Revoke
                        </button>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </div>
      </main>
    </>
  );
}
