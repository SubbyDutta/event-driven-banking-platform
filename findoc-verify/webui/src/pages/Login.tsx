import { useState, type FormEvent } from "react";
import { useNavigate } from "react-router-dom";
import { useAuth } from "../AuthContext";

export default function Login() {
  const { signIn } = useAuth();
  const nav = useNavigate();
  const [key, setKey] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const [revealed, setRevealed] = useState(false);

  async function onSubmit(e: FormEvent) {
    e.preventDefault();
    if (!key.trim()) return;
    setBusy(true); setErr("");
    try {
      await signIn(key.trim());
      nav("/");
    } catch {
      setErr("That key isn't valid or has been revoked.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="fv-auth-shell">
      <div className="fv-auth-left">
        <div className="fv-auth-grid" />

        <div style={{ position: "relative", zIndex: 1, display: "flex", justifyContent: "space-between", alignItems: "center", marginBottom: 28 }}>
          <div className="fv-brand-mark">
            <span className="mark" />
            <span className="name">findoc<em>·verify</em></span>
          </div>
          <div
            style={{
              fontFamily: "'JetBrains Mono', monospace",
              fontSize: 10,
              letterSpacing: "0.14em",
              color: "rgba(255,255,255,0.6)",
              padding: "5px 10px",
              border: "1px solid rgba(255,255,255,0.08)",
              borderRadius: 2,
              display: "inline-flex",
              alignItems: "center",
              gap: 8,
            }}
          >
            <span style={{ width: 6, height: 6, borderRadius: "50%", background: "#5BB8A8" }} />
            PIPELINE LIVE
          </div>
        </div>

        <div style={{ position: "relative", zIndex: 1, marginBottom: 28 }}>
          <div
            style={{
              fontFamily: "'JetBrains Mono', monospace",
              fontSize: 10,
              letterSpacing: "0.18em",
              color: "var(--gold)",
              textTransform: "uppercase",
              display: "inline-flex",
              alignItems: "center",
              gap: 10,
              marginBottom: 18,
            }}
          >
            <span style={{ width: 24, height: 1, background: "var(--gold)" }} />
            Document verification
          </div>
          <h2
            style={{
              fontFamily: "'Fraunces', Georgia, serif",
              fontWeight: 300,
              fontSize: 38,
              lineHeight: 1.05,
              letterSpacing: "-0.025em",
              margin: "0 0 18px",
              color: "var(--paper)",
              maxWidth: 460,
            }}
          >
            Verify <em style={{ color: "var(--gold)" }}>before</em> you trust.
          </h2>
          <p
            style={{
              fontSize: 14,
              lineHeight: 1.65,
              color: "rgba(255,255,255,0.7)",
              margin: 0,
              maxWidth: 440,
            }}
          >
            OCR, classification, compliance, cross-document, and fraud — every stage of every
            application, surfaced in plain English with a full audit trail.
          </p>
        </div>

        <div className="fv-pipeline-feed" style={{ position: "relative", zIndex: 1, marginBottom: 24 }}>
          <div style={{ padding: "10px 14px", borderBottom: "1px solid rgba(255,255,255,0.05)", fontFamily: "'JetBrains Mono', monospace", fontSize: 9, letterSpacing: "0.18em", color: "rgba(255,255,255,0.45)", display: "flex", justifyContent: "space-between" }}>
            <span>RECENT PIPELINE EVENTS</span>
            <span>+05:30</span>
          </div>
          <FeedRow stage="OCR" name="Aadhaar · 0.96 conf · 142ms" tone="ok" />
          <FeedRow stage="CLASSIFY" name="bank_statement (94%)" tone="ok" />
          <FeedRow stage="CROSS-DOC" name="payslip ↔ bank match" tone="ok" />
          <FeedRow stage="FRAUD" name="signal · low risk · 0.18" tone="ok" />
          <FeedRow stage="REPORT" name="ready · approve" tone="ok" />
        </div>

        <div style={{ marginTop: "auto", position: "relative", zIndex: 1, paddingTop: 20, borderTop: "1px solid rgba(255,255,255,0.06)", fontFamily: "'JetBrains Mono', monospace", fontSize: 10, color: "rgba(255,255,255,0.4)", letterSpacing: "0.08em", display: "flex", justifyContent: "space-between", flexWrap: "wrap", gap: 10 }}>
          <span>findoc-verify · v1.0</span>
          <span>RESTRICTED · INTERNAL</span>
        </div>
      </div>

      <div className="fv-auth-right">
        <div className="fv-auth-card">
          <div className="fv-eyebrow" style={{ marginBottom: 14 }}>
            <span>Sign in</span>
            <span className="stamp">API KEY</span>
          </div>
          <h1 className="fv-h-display" style={{ fontSize: 38, marginBottom: 10 }}>
            Welcome <em>back</em>.
          </h1>
          <p className="fv-lede" style={{ marginBottom: 26, paddingBottom: 22, borderBottom: "1px solid var(--rule)" }}>
            Use your API key to sign in. Each key is bound to an organization and label, with full
            request audit logging.
          </p>

          <form onSubmit={onSubmit} className="space-y-4">
            <div>
              <label className="fv-field-label" htmlFor="login-key">
                API key <span className="req">*</span>
              </label>
              <div style={{ position: "relative" }}>
                <input
                  id="login-key"
                  type={revealed ? "text" : "password"}
                  autoFocus
                  placeholder="fdv_..."
                  value={key}
                  onChange={(e) => setKey(e.target.value)}
                  className="fv-input mono"
                  style={{ paddingRight: 80 }}
                />
                <button
                  type="button"
                  onClick={() => setRevealed((r) => !r)}
                  style={{
                    position: "absolute",
                    right: 8,
                    top: "50%",
                    transform: "translateY(-50%)",
                    padding: "4px 10px",
                    fontSize: 11,
                    border: "1px solid var(--rule)",
                    background: "var(--paper)",
                    color: "var(--muted)",
                    borderRadius: 2,
                    cursor: "pointer",
                    fontFamily: "inherit",
                  }}
                >
                  {revealed ? "Hide" : "Show"}
                </button>
              </div>
            </div>

            {err && (
              <div className="fv-alert error">
                <span>⚠</span>
                <span>{err}</span>
              </div>
            )}

            <button
              type="submit"
              disabled={busy}
              className="fv-btn fv-btn-primary"
              style={{ width: "100%", padding: "12px", fontSize: 13 }}
            >
              {busy ? <><span className="fv-spinner" /> Signing in…</> : "Sign in →"}
            </button>
          </form>

          <div
            style={{
              marginTop: 24,
              paddingTop: 20,
              borderTop: "1px solid var(--rule)",
              fontSize: 12,
              color: "var(--muted)",
              lineHeight: 1.6,
            }}
          >
            Don't have a key? Mint one with
            <code
              style={{
                display: "block",
                marginTop: 8,
                padding: "8px 12px",
                background: "var(--paper-2)",
                color: "var(--ink)",
                fontFamily: "'JetBrains Mono', monospace",
                fontSize: 11,
                border: "1px solid var(--rule)",
                borderRadius: 2,
                whiteSpace: "nowrap",
                overflow: "auto",
              }}
            >
              python -m scripts.generate_api_key --label &lt;name&gt; --org &lt;org&gt;
            </code>
          </div>
        </div>
      </div>
    </div>
  );
}

function FeedRow({ stage, name, tone }: { stage: string; name: string; tone: "ok" | "warn" }) {
  return (
    <div className="row">
      <span className="stage">{stage}</span>
      <span className="name">{name}</span>
      <span className={`badge ${tone}`}>{tone === "ok" ? "✓" : "!"}</span>
    </div>
  );
}
