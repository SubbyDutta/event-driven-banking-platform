import { useEffect, useRef, useState, type FormEvent } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import { Dropzone } from "../components/Dropzone";
import { useToast } from "../components/Toast";

type Slot = { field: string; label: string; required: boolean };

const KYC_SLOTS: Slot[] = [
  { field: "aadhaar", label: "Aadhaar", required: true },
  { field: "pan", label: "PAN", required: true },
];

const LOAN_SLOTS: Slot[] = [
  { field: "aadhaar", label: "Aadhaar", required: false },
  { field: "pan", label: "PAN", required: false },
  { field: "bank_statement_1", label: "Bank statement · month 1", required: true },
  { field: "bank_statement_2", label: "Bank statement · month 2", required: true },
  { field: "bank_statement_3", label: "Bank statement · month 3", required: true },
  { field: "payslip_1", label: "Payslip · month 1", required: true },
  { field: "payslip_2", label: "Payslip · month 2", required: true },
  { field: "payslip_3", label: "Payslip · month 3", required: true },
  { field: "employment_letter", label: "Employment letter", required: true },
  { field: "itr", label: "ITR (income tax return)", required: true },
  { field: "credit_report", label: "Credit report (CIBIL / Experian / …)", required: true },
];

const SAMPLE = {
  applicant_name: "Raj Kumar",
  email: "raj.kumar@example.com",
  phone: "9876543210",
};
const EMPTY = { applicant_name: "", email: "", phone: "" };

export default function NewSubmission() {
  const { kind } = useParams<{ kind: "kyc" | "loan" }>();
  const nav = useNavigate();
  const toast = useToast();
  const isKyc = kind === "kyc";
  const slots = isKyc ? KYC_SLOTS : LOAN_SLOTS;
  const formRef = useRef<HTMLFormElement>(null);

  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [progress, setProgress] = useState(0);
  const [fields, setFields] = useState<typeof SAMPLE>(EMPTY);
  const [, setTick] = useState(0);

  useEffect(() => { setFields(EMPTY); setErr(null); }, [kind]);

  useEffect(() => {
    function onChange(e: Event) {
      if ((e.target as HTMLElement)?.tagName === "INPUT") setTick((t) => t + 1);
    }
    const f = formRef.current;
    f?.addEventListener("change", onChange);
    return () => f?.removeEventListener("change", onChange);
  }, []);

  const requiredSlots = slots.filter(s => s.required);
  const completedRequired = requiredSlots.filter(s => {
    const i = formRef.current?.querySelector<HTMLInputElement>(`input[name="${s.field}"]`);
    return !!(i?.files && i.files.length > 0);
  }).length;
  const fieldsComplete = !!fields.applicant_name && !!fields.email && !!fields.phone;
  const ready = fieldsComplete && completedRequired === requiredSlots.length;

  async function onSubmit(e: FormEvent<HTMLFormElement>) {
    e.preventDefault();
    setBusy(true); setErr(null); setProgress(8);

    const tick = window.setInterval(() => {
      setProgress(p => (p < 88 ? p + Math.max(1, (88 - p) * 0.06) : p));
    }, 140);

    try {
      const fd = new FormData(e.currentTarget);
      for (const [k, v] of Array.from(fd.entries())) {
        if (v instanceof File && v.size === 0) fd.delete(k);
      }
      const r = isKyc ? await api.submitKyc(fd) : await api.submitLoan(fd);
      setProgress(100);
      toast.success("Submission accepted", "Pipeline starting now.");
      window.setTimeout(() => nav(`/app/${r.applicationId}`), 200);
    } catch (e: any) {
      setErr(e.message || "Submission failed");
      toast.error("Submission failed", e?.message ?? "");
    } finally {
      window.clearInterval(tick);
      setBusy(false);
    }
  }

  function fillSample() {
    setFields(SAMPLE);
    toast.info("Sample applicant data filled", "You still need to upload documents.");
  }

  return (
    <>
      <div className="fv-topbar">
        <div className="fv-breadcrumb">
          <Link to="/">Verification</Link>
          <span className="sep">/</span>
          <Link to="/">Submissions</Link>
          <span className="sep">/</span>
          <span className="current">New {isKyc ? "KYC" : "loan"}</span>
        </div>
        <div className="fv-topbar-right">
          <span className={`fv-status-pill ${ready ? "" : "warn"}`}>
            <span className="dot" />
            {ready ? "Ready to submit" : `${completedRequired} / ${requiredSlots.length} docs`}
          </span>
        </div>
      </div>

      <main className="fv-page-pad" style={{ maxWidth: 920 }}>
        <div className="fv-page-head">
          <div>
            <div className="fv-eyebrow" style={{ marginBottom: 12 }}>
              <span>{isKyc ? "Identity verification" : "Loan origination"}</span>
              <span className="stamp">NEW SUBMISSION</span>
            </div>
            <h1 className="fv-h-display" style={{ fontSize: 32 }}>
              New {isKyc ? <em>KYC</em> : <>loan <em>origination</em></>} submission.
            </h1>
            <p className="fv-lede" style={{ marginTop: 10, maxWidth: 560 }}>
              {isKyc
                ? "Upload Aadhaar and PAN. We'll OCR, cross-check name and DOB, and validate the Aadhaar Verhoeff checksum."
                : "Upload all required documents — pipeline runs OCR → classify → extract → compliance → cross-doc → fraud → risk."}
            </p>
          </div>
        </div>

        <form ref={formRef} onSubmit={onSubmit} style={{ display: "flex", flexDirection: "column", gap: 18 }}>
          <Section
            title="Applicant details"
            subtitle="Used to populate cross-document checks."
            right={
              <button
                type="button"
                onClick={fillSample}
                className="fv-btn-inline"
              >
                Fill sample
              </button>
            }
          >
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr 1fr", gap: 16 }}>
              <Field label="Applicant name" required>
                <input
                  required
                  name="applicant_name"
                  value={fields.applicant_name}
                  onChange={(e) => setFields({ ...fields, applicant_name: e.target.value })}
                  className="fv-input"
                />
              </Field>
              <Field label="Email" required>
                <input
                  required
                  type="email"
                  name="email"
                  value={fields.email}
                  onChange={(e) => setFields({ ...fields, email: e.target.value })}
                  className="fv-input"
                />
              </Field>
              <Field label="Phone" required>
                <input
                  required
                  name="phone"
                  inputMode="tel"
                  pattern="\d{10}"
                  title="10-digit phone number"
                  value={fields.phone}
                  onChange={(e) => setFields({ ...fields, phone: e.target.value.replace(/[^\d]/g, "").slice(0, 10) })}
                  className="fv-input"
                />
              </Field>
            </div>
            <div style={{ marginTop: 14 }}>
              <Field label="External ID" hint="your internal loan / customer id, optional">
                <input
                  name="external_id"
                  className="fv-input mono"
                  placeholder="auto-generated if blank"
                />
              </Field>
            </div>
          </Section>

          <Section
            title="Documents"
            subtitle={isKyc
              ? "Both Aadhaar and PAN are required."
              : "Bank statements, payslips, employment letter, ITR, and credit report are required."}
            right={
              <span style={{ fontSize: 12, color: "var(--muted)", fontFamily: "'JetBrains Mono', monospace" }}>
                <span style={{ color: "var(--ink)", fontWeight: 500 }}>{completedRequired}</span>
                {" / "}{requiredSlots.length} required
              </span>
            }
          >
            <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10 }}>
              {slots.map((s) => (
                <Dropzone key={s.field} field={s.field} label={s.label} required={s.required} />
              ))}
            </div>
            {!isKyc && (
              <div className="fv-alert warn" style={{ marginTop: 14 }}>
                <span>!</span>
                <span>At least one of Aadhaar or PAN is required for loan submissions.</span>
              </div>
            )}
          </Section>

          {err && (
            <div className="fv-alert error">
              <span>⚠</span>
              <span>{err}</span>
            </div>
          )}

          <div className="fv-panel" style={{ padding: "16px 20px", display: "flex", alignItems: "center", gap: 16, flexWrap: "wrap" }}>
            <div style={{ flex: 1, minWidth: 240 }}>
              {busy ? (
                <>
                  <div style={{ fontSize: 12, fontWeight: 500, color: "var(--ink)", marginBottom: 6 }}>
                    Uploading & queueing pipeline…
                  </div>
                  <div style={{ height: 4, background: "var(--paper-2)", borderRadius: 1, overflow: "hidden" }}>
                    <div
                      style={{
                        height: "100%",
                        width: `${progress}%`,
                        background: "linear-gradient(90deg, var(--navy), var(--gold))",
                        transition: "width 0.2s",
                      }}
                    />
                  </div>
                </>
              ) : (
                <div style={{ fontSize: 12, color: "var(--muted)" }}>
                  {ready
                    ? <span style={{ color: "var(--positive)", fontWeight: 500 }}>✓ Ready to submit</span>
                    : <>Fill applicant details and upload all required documents to enable submission.</>
                  }
                </div>
              )}
            </div>
            <Link to="/" className="fv-btn fv-btn-ghost">Cancel</Link>
            <button
              disabled={busy}
              type="submit"
              className="fv-btn fv-btn-primary"
            >
              {busy ? <><span className="fv-spinner" /> Uploading…</> : "Submit for verification →"}
            </button>
          </div>
        </form>
      </main>
    </>
  );
}

function Section({ title, subtitle, right, children }: {
  title: string; subtitle?: string; right?: React.ReactNode; children: React.ReactNode;
}) {
  return (
    <div className="fv-panel">
      <div className="fv-panel-head">
        <div>
          <div className="title">{title}</div>
          {subtitle && <div className="meta">{subtitle}</div>}
        </div>
        {right && <div>{right}</div>}
      </div>
      <div className="fv-panel-body">
        {children}
      </div>
    </div>
  );
}

function Field({ label, required, hint, children }: { label: string; required?: boolean; hint?: string; children: React.ReactNode }) {
  return (
    <label className="block">
      <span className="fv-field-label">
        {label} {required && <span className="req">*</span>}
        {hint && <span style={{ color: "var(--muted)", fontWeight: 400, textTransform: "none", letterSpacing: 0, marginLeft: 6 }}>— {hint}</span>}
      </span>
      <div>{children}</div>
    </label>
  );
}
