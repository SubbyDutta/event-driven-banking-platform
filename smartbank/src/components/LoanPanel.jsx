import React, { useMemo, useState } from 'react';
import API from '../api';
import LoanStatusPanel from './LoanStatusPanel';

const PURPOSES = [
  { value: 'MEDICAL', label: 'Medical' },
  { value: 'EDUCATION', label: 'Education' },
  { value: 'HOME_RENOVATION', label: 'Home renovation' },
  { value: 'OTHER', label: 'Other' },
];

const DOC_GROUPS = [
  {
    title: 'Bank Statements',
    desc: 'LAST THREE MONTHS · PDF, CSV',
    icon: '₹',
    fields: [
      { key: 'bank_statement_1', label: 'Month 1' },
      { key: 'bank_statement_2', label: 'Month 2' },
      { key: 'bank_statement_3', label: 'Month 3' },
    ],
  },
  {
    title: 'Payslips',
    desc: 'LAST THREE MONTHS · PDF',
    icon: '¶',
    fields: [
      { key: 'payslip_1', label: 'Month 1' },
      { key: 'payslip_2', label: 'Month 2' },
      { key: 'payslip_3', label: 'Month 3' },
    ],
  },
  {
    title: 'Employment & Income',
    desc: 'LETTER AND TAX RECORDS',
    icon: '✦',
    fields: [
      { key: 'employment_letter', label: 'Employment Letter' },
      { key: 'itr', label: 'Income Tax Return · FY24' },
    ],
  },
  {
    title: 'Credit',
    desc: 'BUREAU PULL · CIBIL OR EXPERIAN',
    icon: '⌖',
    fields: [{ key: 'credit_report', label: 'Credit Report' }],
  },
];

const DOC_FIELDS = DOC_GROUPS.flatMap((g) => g.fields);

const MAX_BYTES = 10 * 1024 * 1024;
const ALLOWED = ['application/pdf', 'image/png', 'image/jpeg', 'image/jpg'];
const MIN_AMOUNT = 10000;
const MAX_AMOUNT = 1000000;

function fmtSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(0) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
}

function indicativeEMI(amount) {
  const n = Number(amount) || 0;
  if (!n) return null;
  const r = 0.12 / 12;
  const t = 6;
  const emi = (n * r * Math.pow(1 + r, t)) / (Math.pow(1 + r, t) - 1);
  return Math.round(emi);
}

export default function LoanPanel() {
  const [amount, setAmount] = useState('');
  const [purpose, setPurpose] = useState('MEDICAL');
  const [terms, setTerms] = useState(false);
  const [files, setFiles] = useState({});
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const [loanAppId, setLoanAppId] = useState(null);

  const uploadedCount = Object.keys(files).length;
  const totalCount = DOC_FIELDS.length;
  const emi = useMemo(() => indicativeEMI(amount), [amount]);

  if (loanAppId) {
    return <LoanStatusPanel loanAppId={loanAppId} onBack={() => setLoanAppId(null)} />;
  }

  const onFile = (key, file) => {
    if (!file) return;
    if (file.size > MAX_BYTES) {
      setError(`${key}: file exceeds 10 MB`);
      return;
    }
    if (!ALLOWED.includes(file.type.toLowerCase())) {
      setError(`${key}: must be PDF, PNG, or JPG`);
      return;
    }
    setError('');
    setFiles((prev) => ({ ...prev, [key]: file }));
  };

  const removeFile = (key) => {
    setFiles((prev) => {
      const next = { ...prev };
      delete next[key];
      return next;
    });
  };

  const validate = () => {
    const n = Number(amount);
    if (!n || n < MIN_AMOUNT || n > MAX_AMOUNT) {
      return `Amount must be between ₹${MIN_AMOUNT.toLocaleString('en-IN')} and ₹${MAX_AMOUNT.toLocaleString('en-IN')}`;
    }
    if (!purpose) return 'Select a purpose';
    if (!terms) return 'You must accept the terms to proceed';
    for (const f of DOC_FIELDS) {
      if (!files[f.key]) return `Upload required: ${f.label}`;
    }
    return null;
  };

  const submit = async () => {
    const err = validate();
    if (err) {
      setError(err);
      return;
    }
    setError('');
    setSubmitting(true);
    try {
      const fd = new FormData();
      fd.append('amount', String(Number(amount)));
      fd.append('purpose', purpose);
      fd.append('terms_accepted', 'true');
      for (const f of DOC_FIELDS) fd.append(f.key, files[f.key]);

      const idemKey =
        window.crypto && window.crypto.randomUUID
          ? window.crypto.randomUUID()
          : `${Date.now()}-${Math.random().toString(16).slice(2)}`;

      const res = await API.post('/loans/apply', fd, {
        headers: {
          'Content-Type': 'multipart/form-data',
          'Idempotency-Key': idemKey,
        },
      });
      setLoanAppId(res.data.loanAppId);
    } catch (e) {
      const msg =
        e?.response?.data?.error ||
        e?.response?.data?.message ||
        'Submission failed. Please try again.';
      setError(e?.response?.status === 403 ? `${msg} You may need to complete KYC first.` : msg);
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <>
      <div className="sb-loan-hero">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 24, position: 'relative', zIndex: 1, flexWrap: 'wrap' }}>
          <div style={{ minWidth: 0, flex: 1 }}>
            <div className="tag" style={{ marginBottom: 10 }}>
              <i className="bi bi-lightning-charge-fill" /> Event-driven approval · Few minutes
            </div>
            <h1 style={{ fontSize: 28, marginBottom: 6 }}>
              Apply for a <em>personal loan</em>.
            </h1>
            <p className="desc" style={{ fontSize: 12 }}>
              Nine documents · OCR + identity + compliance + risk scoring · decision in minutes.
            </p>
          </div>
          <div className="sb-terms-grid" style={{ minWidth: 280 }}>
            <div className="sb-term-cell" style={{ padding: 12 }}>
              <div className="lbl">Tenure</div>
              <div className="val" style={{ fontSize: 16 }}>6 mo.</div>
            </div>
            <div className="sb-term-cell" style={{ padding: 12 }}>
              <div className="lbl">Min</div>
              <div className="val" style={{ fontSize: 16 }}>₹{(MIN_AMOUNT / 1000).toFixed(0)}K</div>
            </div>
            <div className="sb-term-cell" style={{ padding: 12 }}>
              <div className="lbl">Max</div>
              <div className="val" style={{ fontSize: 16 }}>₹{(MAX_AMOUNT / 100000).toFixed(0)}L</div>
            </div>
            <div className="sb-term-cell" style={{ padding: 12 }}>
              <div className="lbl">Decision</div>
              <div className="val" style={{ fontSize: 16 }}>Few mins</div>
            </div>
          </div>
        </div>
      </div>

      <div className="sb-loan-grid" style={{ marginBottom: 20 }}>
        <div className="sb-loan-form">
          <div className="sb-loan-form-head" style={{ padding: '18px 24px' }}>
            <div className="sb-h-card" style={{ marginBottom: 4 }}>Loan Details</div>
            <div style={{ fontSize: 11, color: 'var(--muted)' }}>Amount, purpose, and tenure</div>
          </div>

          <div className="sb-form-body">
            {error && (
              <div className="sb-alert sb-alert-error">
                <i className="bi bi-exclamation-triangle-fill" /> {error}
              </div>
            )}

            <div className="sb-field-row">
              <div className="sb-field">
                <div className="sb-field-label">
                  Loan Amount <span className="req">*</span>
                </div>
                <input
                  type="number"
                  className="sb-field-input mono"
                  placeholder={`₹ ${MIN_AMOUNT.toLocaleString('en-IN')} – ${MAX_AMOUNT.toLocaleString('en-IN')}`}
                  min={MIN_AMOUNT}
                  max={MAX_AMOUNT}
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                />
                <div className="sb-field-help">Within approved bracket</div>
              </div>
              <div className="sb-field">
                <div className="sb-field-label">
                  Purpose <span className="req">*</span>
                </div>
                <select
                  className="sb-field-input"
                  value={purpose}
                  onChange={(e) => setPurpose(e.target.value)}
                >
                  {PURPOSES.map((p) => (
                    <option key={p.value} value={p.value}>{p.label}</option>
                  ))}
                </select>
              </div>
            </div>

            <div className="sb-field-row" style={{ marginBottom: 0 }}>
              <div className="sb-field">
                <div className="sb-field-label">Tenure</div>
                <input className="sb-field-input mono readonly" value="6 months · Fixed for personal loans" readOnly />
              </div>
              <div className="sb-field">
                <div className="sb-field-label">Indicative EMI</div>
                <input
                  className="sb-field-input mono readonly"
                  value={emi ? `₹${emi.toLocaleString('en-IN')} / mo` : '— Set after risk scoring'}
                  readOnly
                />
              </div>
            </div>

            <div className="sb-commit-row">
              <input
                type="checkbox"
                className="sb-checkbox"
                checked={terms}
                onChange={(e) => setTerms(e.target.checked)}
                id="loan-terms"
              />
              <label htmlFor="loan-terms" className="text" style={{ cursor: 'pointer' }}>
                I confirm that the documents I'm uploading are mine and belong to this account. I authorise{' '}
                <strong>SubbyBank</strong> and its underwriting partners to verify them, run a CIBIL pull,
                and accept the loan terms presented at offer stage.
              </label>
            </div>
          </div>
        </div>

        <div className="sb-summary-card">
          <div className="sb-summary-head">
            <div>
              <div className="sb-h-card" style={{ marginBottom: 4 }}>Application Summary</div>
              <div style={{ fontSize: 11, color: 'var(--muted)', letterSpacing: '0.04em' }}>
                LIVE PREVIEW
              </div>
            </div>
          </div>
          <div className="sb-summary-list">
            <div className="row">
              <span className="lbl">Amount</span>
              <span className="val">{amount ? `₹${Number(amount).toLocaleString('en-IN')}` : '—'}</span>
            </div>
            <div className="row">
              <span className="lbl">Purpose</span>
              <span className="val" style={{ fontFamily: 'inherit' }}>
                {PURPOSES.find((p) => p.value === purpose)?.label || '—'}
              </span>
            </div>
            <div className="row">
              <span className="lbl">Tenure</span>
              <span className="val">6 months</span>
            </div>
            <div className="row">
              <span className="lbl">Indicative EMI</span>
              <span className="val">{emi ? `₹${emi.toLocaleString('en-IN')}` : '—'}</span>
            </div>
            <div className="row">
              <span className="lbl">Documents</span>
              <span className={`val ${uploadedCount === totalCount ? 'ok' : 'alert'}`}>
                {uploadedCount} / {totalCount}
              </span>
            </div>
          </div>
          <div className="sb-verify-box">
            <div className="h">What we verify</div>
            <ul>
              <li>Document authenticity · OCR + classifier</li>
              <li>Identity match against your KYC</li>
              <li>Income consistency across statements</li>
              <li>Bureau score and recent enquiries</li>
            </ul>
          </div>
        </div>
      </div>

      <div style={{ marginBottom: 20 }}>
        <div style={{ marginBottom: 16, display: 'flex', justifyContent: 'space-between', alignItems: 'center', flexWrap: 'wrap', gap: 16 }}>
          <div>
            <div className="sb-eyebrow" style={{ marginBottom: 4 }}>Documents</div>
            <h2 className="sb-h-section" style={{ fontSize: 18 }}>Supporting documents</h2>
          </div>
          <div style={{ textAlign: 'right' }}>
            <div className="sb-eyebrow" style={{ marginBottom: 2 }}>Progress</div>
            <div style={{ fontFamily: "'Fraunces', serif", fontSize: 22, fontWeight: 400 }}>
              {uploadedCount} / {totalCount}
            </div>
          </div>
        </div>

        {DOC_GROUPS.map((group) => {
          const filledInGroup = group.fields.filter((f) => files[f.key]).length;
          const allFilled = filledInGroup === group.fields.length;
          const bodyClass =
            group.fields.length === 1 ? 'sb-doc-body one'
            : group.fields.length === 2 ? 'sb-doc-body two'
            : 'sb-doc-body';
          return (
            <div className="sb-doc-section" key={group.title}>
              <div className="sb-doc-head">
                <div className="title-row">
                  <div className="ico">{group.icon}</div>
                  <div>
                    <div className="name">{group.title}</div>
                    <div className="sub">{group.desc}</div>
                  </div>
                </div>
                <div className={`sb-progress-pill ${allFilled ? 'complete' : filledInGroup > 0 ? 'partial' : ''}`}>
                  {filledInGroup} / {group.fields.length}
                </div>
              </div>
              <div className={bodyClass}>
                {group.fields.map((field) => (
                  <DocSlot
                    key={field.key}
                    field={field}
                    file={files[field.key]}
                    onFile={(file) => onFile(field.key, file)}
                    onRemove={() => removeFile(field.key)}
                  />
                ))}
              </div>
            </div>
          );
        })}
      </div>

      <div style={{ display: 'flex', gap: 12, justifyContent: 'flex-end', flexWrap: 'wrap' }}>
        <button
          className="sb-btn sb-btn-primary"
          onClick={submit}
          disabled={submitting}
          style={{ minWidth: 220 }}
        >
          {submitting ? <><span className="sb-spinner" /> Submitting…</> : 'Submit application →'}
        </button>
      </div>
    </>
  );
}

function DocSlot({ field, file, onFile, onRemove }) {
  const id = `loan-${field.key}`;
  const filled = !!file;
  return (
    <div className={`sb-upload-cell ${filled ? 'filled' : ''}`}>
      <div className="lbl">{field.label}</div>
      {filled ? (
        <>
          <div className="filename" title={file.name}>{file.name}</div>
          <div className="filemeta">
            <span>{fmtSize(file.size)}</span>
            <button type="button" className="remove" onClick={onRemove}>
              Remove
            </button>
          </div>
        </>
      ) : (
        <label htmlFor={id} className="hint" style={{ cursor: 'pointer' }}>
          <span className="arrow">↑</span> Click to upload · Max 10MB
        </label>
      )}
      <input
        id={id}
        type="file"
        accept=".pdf,.png,.jpg,.jpeg"
        onChange={(e) => onFile(e.target.files?.[0])}
        style={{ display: 'none' }}
      />
      {!filled && (
        <label
          htmlFor={id}
          style={{ position: 'absolute', inset: 0, cursor: 'pointer' }}
          aria-label={`Upload ${field.label}`}
        />
      )}
    </div>
  );
}
