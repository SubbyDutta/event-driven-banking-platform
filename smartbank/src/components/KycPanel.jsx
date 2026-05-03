import React, { useEffect, useRef, useState } from 'react';
import API from '../api';

const ACCEPT_TYPES = ['application/pdf', 'image/jpeg', 'image/png'];
const ACCEPT_EXT = '.pdf, .jpg, .jpeg, .png';
const MAX_BYTES = 10 * 1024 * 1024;

const PIPELINE_STEPS = [
  { key: 'ocr', label: 'OCR', icon: 'bi-eye' },
  { key: 'classify', label: 'Classify', icon: 'bi-file-earmark' },
  { key: 'compliance', label: 'Compliance', icon: 'bi-shield-check' },
  { key: 'fraud', label: 'Fraud', icon: 'bi-cpu' },
  { key: 'decision', label: 'Decision', icon: 'bi-check2-circle' },
];

function fmtSize(bytes) {
  if (bytes < 1024) return bytes + ' B';
  if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(1) + ' KB';
  return (bytes / (1024 * 1024)).toFixed(2) + ' MB';
}

function validateFile(file) {
  if (!file) return 'No file selected';
  if (!ACCEPT_TYPES.includes(file.type)) return 'Only PDF, JPG, or PNG accepted';
  if (file.size > MAX_BYTES) return `File too large (${fmtSize(file.size)} > 10 MB)`;
  return null;
}

const STATUS_MAP = {
  NONE: { label: 'Not started', tone: 'muted', sub: 'Upload Aadhaar + PAN to begin.' },
  KYC_SUBMITTED: { label: 'Submitted — in queue', tone: 'warn', sub: 'Waiting for the worker to pick up your documents.' },
  KYC_DOCS_UNDER_REVIEW: { label: 'Documents under review', tone: 'warn', sub: 'Pipeline is verifying your documents.' },
  KYC_APPROVED: { label: 'Approved', tone: 'success', sub: 'All checks passed. Banking unlocked.' },
  KYC_REJECTED: { label: 'Rejected — please re-upload', tone: 'error', sub: 'Please address the issue and re-submit.' },
  KYC_MANUAL_REVIEW: { label: 'Manual review', tone: 'warn', sub: 'Awaiting an admin decision.' },
};

export default function KycPanel({ initialStatus, onApproved }) {
  const [status, setStatus] = useState(initialStatus || 'NONE');
  const [reason, setReason] = useState('');
  const [aadhaar, setAadhaar] = useState(null);
  const [pan, setPan] = useState(null);
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState('');
  const pollRef = useRef(null);

  const inFlight = status === 'KYC_SUBMITTED' || status === 'KYC_DOCS_UNDER_REVIEW';
  const needsUpload = status === 'NONE' || status === 'KYC_REJECTED';

  useEffect(() => {
    if (!inFlight) return;
    pollRef.current = setInterval(refresh, 5000);
    return () => clearInterval(pollRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [inFlight]);

  useEffect(() => {
    if (status === 'KYC_APPROVED' && typeof onApproved === 'function') onApproved();
  }, [status, onApproved]);

  async function refresh() {
    try {
      const res = await API.get('/kyc/status');
      const next = res.data?.kycStatus || 'NONE';
      setStatus(next);
      if (res.data?.reason) setReason(res.data.reason);
    } catch {
      /* keep polling */
    }
  }

  async function submit(e) {
    e.preventDefault();
    setError('');
    if (!aadhaar || !pan) {
      setError('Please upload both Aadhaar and PAN.');
      return;
    }
    setSubmitting(true);
    try {
      const fd = new FormData();
      fd.append('aadhaar', aadhaar);
      fd.append('pan', pan);
      await API.post('/kyc/apply', fd, { headers: { 'Content-Type': 'multipart/form-data' } });
      setStatus('KYC_SUBMITTED');
    } catch (err) {
      const msg =
        err?.response?.data?.error ||
        err?.response?.data?.message ||
        (typeof err?.response?.data === 'string' ? err.response.data : null) ||
        err?.message ||
        'Upload failed';
      setError(String(msg));
    } finally {
      setSubmitting(false);
    }
  }

  const spec = STATUS_MAP[status] || STATUS_MAP.NONE;

  return (
    <>
      <div className="sb-loan-hero" style={{ marginBottom: 20, padding: '20px 28px' }}>
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', gap: 24, position: 'relative', zIndex: 1, flexWrap: 'wrap' }}>
          <div style={{ minWidth: 0, flex: 1 }}>
            <div className="tag" style={{ marginBottom: 10 }}>
              <i className="bi bi-shield-lock" /> Identity Verification · 5-stage pipeline
            </div>
            <h1 style={{ fontSize: 26, marginBottom: 6 }}>
              Complete your <em>KYC</em> to unlock banking.
            </h1>
            <p className="desc" style={{ fontSize: 12 }}>
              Aadhaar + PAN run through a 5-stage AI pipeline · most decisions finish under a minute.
            </p>
          </div>
          <div
            style={{
              display: 'grid',
              gridTemplateColumns: 'repeat(5, 1fr)',
              gap: 1,
              background: 'rgba(255,255,255,0.1)',
              border: '1px solid rgba(255,255,255,0.1)',
              minWidth: 320,
            }}
          >
            {PIPELINE_STEPS.map((step) => (
              <div
                key={step.key}
                style={{
                  background: 'rgba(255,255,255,0.03)',
                  padding: '10px 8px',
                  textAlign: 'center',
                }}
              >
                <i className={`bi ${step.icon}`} style={{ fontSize: 14, color: 'var(--accent-2)' }} />
                <div style={{ fontSize: 9, color: 'rgba(255,255,255,0.7)', marginTop: 4, letterSpacing: '0.04em' }}>
                  {step.label}
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="sb-form-section">
        <div className="sb-form-head">
          <div>
            <div className="sb-h-card" style={{ marginBottom: 6 }}>KYC Status</div>
            <div style={{ fontSize: 12, color: 'var(--muted)' }}>{reason || spec.sub}</div>
          </div>
          <span className={`sb-tag-pill ${spec.tone}`}>{spec.label}</span>
        </div>

        <div className="sb-form-body">
          {inFlight && (
            <div className="sb-alert sb-alert-warn">
              <span className="sb-spinner" />
              <strong>Pipeline running…</strong> verifying documents and computing decision.
              This page refreshes automatically.
            </div>
          )}

          {status === 'KYC_MANUAL_REVIEW' && (
            <div className="sb-alert sb-alert-warn">
              <i className="bi bi-hourglass-split" />
              <span>
                <strong>Awaiting manual review.</strong> Our team is reviewing your documents.
                You'll be notified by email once a decision is made.
              </span>
            </div>
          )}

          {status === 'KYC_APPROVED' && (
            <div className="sb-alert sb-alert-success">
              <i className="bi bi-check-circle-fill" />
              <span>
                <strong>KYC approved.</strong> You can now create a bank account and apply for loans.
              </span>
            </div>
          )}

          {needsUpload && (
            <form onSubmit={submit}>
              {error && (
                <div className="sb-alert sb-alert-error">
                  <i className="bi bi-exclamation-triangle-fill" /> {error}
                </div>
              )}

              <div className="sb-field-row">
                <FileSlot
                  label="Aadhaar Card"
                  hint="Front + back, single PDF preferred"
                  value={aadhaar}
                  onChange={setAadhaar}
                  onError={setError}
                />
                <FileSlot
                  label="PAN Card"
                  hint="Clear scan or PDF"
                  value={pan}
                  onChange={setPan}
                  onError={setError}
                />
              </div>

              <div style={{ fontSize: 11, color: 'var(--muted)', margin: '12px 0', letterSpacing: '0.04em' }}>
                <i className="bi bi-file-earmark" /> PDF, JPG, or PNG · max 10 MB each · documents are encrypted at rest
              </div>

              <div className="sb-btn-row">
                <button
                  className="sb-btn sb-btn-primary"
                  type="submit"
                  disabled={submitting || !aadhaar || !pan}
                  style={{ flex: 1 }}
                >
                  {submitting ? <><span className="sb-spinner" /> Uploading…</> : 'Submit for verification →'}
                </button>
              </div>
            </form>
          )}
        </div>
      </div>
    </>
  );
}

function FileSlot({ label, hint, value, onChange, onError }) {
  const [drag, setDrag] = useState(false);
  const inputRef = useRef(null);
  const id = `f-${label.replace(/\W+/g, '-').toLowerCase()}`;

  const accept = (file) => {
    const err = validateFile(file);
    if (err) {
      onError?.(`${label}: ${err}`);
      return;
    }
    onError?.('');
    onChange(file);
  };

  const onDrop = (e) => {
    e.preventDefault();
    setDrag(false);
    const file = e.dataTransfer.files?.[0];
    if (file) accept(file);
  };

  const isFilled = !!value;

  return (
    <div
      className={`sb-upload-cell ${isFilled ? 'filled' : ''}`}
      onDragOver={(e) => {
        e.preventDefault();
        if (!drag) setDrag(true);
      }}
      onDragLeave={() => setDrag(false)}
      onDrop={onDrop}
      style={{ minHeight: 120 }}
    >
      <div className="lbl">
        {label} <span style={{ color: 'var(--accent-3)' }}>*</span>
      </div>
      {isFilled ? (
        <>
          <div className="filename" title={value.name}>{value.name}</div>
          <div className="filemeta">
            <span>{fmtSize(value.size)}</span>
            <button
              type="button"
              className="remove"
              onClick={(e) => {
                e.preventDefault();
                onChange(null);
                if (inputRef.current) inputRef.current.value = '';
              }}
            >
              Remove
            </button>
          </div>
        </>
      ) : (
        <label htmlFor={id} className="hint" style={{ cursor: 'pointer' }}>
          <span className="arrow">↑</span> {drag ? 'Drop to upload' : hint || 'Click to upload'}
        </label>
      )}
      <input
        ref={inputRef}
        id={id}
        type="file"
        accept={ACCEPT_EXT}
        onChange={(e) => {
          const file = e.target.files?.[0];
          if (file) accept(file);
        }}
        style={{ display: 'none' }}
      />
      {!isFilled && (
        <label
          htmlFor={id}
          style={{ position: 'absolute', inset: 0, cursor: 'pointer' }}
          aria-label={`Upload ${label}`}
        />
      )}
    </div>
  );
}
