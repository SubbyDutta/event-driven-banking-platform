import React, { useState } from 'react';
import API from '../api';
import { safeUsername } from '../utils/auth';
import { v4 as uuidv4 } from 'uuid';

export default function TransferPanel({ onComplete, accountNumber }) {
  const [activeTab, setActiveTab] = useState('transfer');
  const [form, setForm] = useState({
    senderAccount: accountNumber || '',
    receiverAccount: '',
    amount: '',
    note: '',
  });
  const [password, setPassword] = useState('');
  const [loading, setLoading] = useState(false);
  const [msg, setMsg] = useState(null);
  const [showPasswordPopup, setShowPasswordPopup] = useState(false);

  const handleInitialSubmit = (e) => {
    e.preventDefault();
    setMsg(null);
    if (!form.receiverAccount || !form.amount) {
      setMsg({ type: 'error', text: 'Please fill all required fields.' });
      return;
    }
    if (Number(form.amount) <= 0) {
      setMsg({ type: 'error', text: 'Amount must be greater than 0.' });
      return;
    }
    setShowPasswordPopup(true);
  };

  const loadRazorpayScript = () =>
    new Promise((resolve) => {
      if (window.Razorpay) return resolve(true);
      const script = document.createElement('script');
      script.src = 'https://checkout.razorpay.com/v1/checkout.js';
      script.onload = () => resolve(true);
      script.onerror = () => resolve(false);
      document.body.appendChild(script);
    });

  const handleAddMoney = async (e) => {
    e.preventDefault();
    if (!form.amount || Number(form.amount) <= 0) {
      setMsg({ type: 'error', text: 'Enter a valid amount' });
      return;
    }
    setLoading(true);
    setMsg(null);
    const ok = await loadRazorpayScript();
    if (!ok) {
      setMsg({ type: 'error', text: 'Razorpay SDK failed to load. Are you online?' });
      setLoading(false);
      return;
    }

    try {
      const orderRes = await API.post('/payment/create-order', { amount: Number(form.amount) });
      const order = orderRes.data;

      const options = {
        key: order.key,
        amount: order.amount,
        currency: order.currency,
        order_id: order.orderId,
        handler: async function (response) {
          try {
            const username = safeUsername();
            const key = uuidv4();
            const verifyRes = await API.post('/payment/verify', {
              razorpay_order_id: response.razorpay_order_id,
              razorpay_payment_id: response.razorpay_payment_id,
              razorpay_signature: response.razorpay_signature,
              amount: Number(form.amount),
              username,
              key,
            });
            if (verifyRes.data.success) {
              setMsg({ type: 'success', text: 'Money added successfully.' });
              setForm((p) => ({ ...p, amount: '' }));
              onComplete?.();
            } else {
              setMsg({ type: 'error', text: 'Payment verification failed.' });
            }
          } catch (err) {
            console.error(err);
            setMsg({ type: 'error', text: 'Error verifying payment.' });
          }
        },
        prefill: { name: 'User', email: 'user@example.com', contact: '9999999999' },
        theme: { color: '#0A0E1A' },
      };
      const paymentObject = new window.Razorpay(options);
      paymentObject.open();
    } catch (err) {
      console.error(err);
      setMsg({ type: 'error', text: 'Error initiating payment' });
    } finally {
      setLoading(false);
    }
  };

  const handleTransfer = async () => {
    setLoading(true);
    setMsg(null);
    try {
      await API.post('/transfer/transfer', {
        key: uuidv4(),
        senderAccount: accountNumber,
        receiverAccount: form.receiverAccount,
        amount: Number(form.amount),
        password,
      });
      setMsg({ type: 'success', text: 'Transfer completed successfully.' });
      setForm({ senderAccount: accountNumber, receiverAccount: '', amount: '', note: '' });
      setPassword('');
      setShowPasswordPopup(false);
      onComplete?.();
    } catch (err) {
      console.error(err);
      const errorMsg =
        err.response?.data?.message || err.response?.data?.error || 'Something went wrong';
      setMsg({ type: 'error', text: errorMsg });
      setShowPasswordPopup(false);
    } finally {
      setLoading(false);
    }
  };

  const formattedAmount = form.amount
    ? Number(form.amount).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 })
    : '0.00';

  return (
    <>
      <div className="sb-form-section">
        <div className="sb-form-head">
          <div>
            <div className="sb-h-card" style={{ marginBottom: 6 }}>
              {activeTab === 'transfer' ? 'Outbound Transfer' : 'Add Money'}
            </div>
            <div style={{ fontSize: 12, color: 'var(--muted)' }}>
              {activeTab === 'transfer'
                ? 'Step 1 of 2 — Specify recipient and amount'
                : 'Top up via secure payment gateway'}
            </div>
          </div>
          <div className="sb-seg-toggle">
            <button
              className={activeTab === 'transfer' ? 'active' : ''}
              onClick={() => { setActiveTab('transfer'); setMsg(null); }}
            >
              Transfer
            </button>
            <button
              className={activeTab === 'addMoney' ? 'active' : ''}
              onClick={() => { setActiveTab('addMoney'); setMsg(null); }}
            >
              Add Money
            </button>
          </div>
        </div>

        <form onSubmit={activeTab === 'transfer' ? handleInitialSubmit : handleAddMoney}>
          <div className="sb-form-body">
            {msg && (
              <div className={`sb-alert ${msg.type === 'error' ? 'sb-alert-error' : 'sb-alert-success'}`}>
                <i className={`bi ${msg.type === 'error' ? 'bi-exclamation-triangle-fill' : 'bi-check-circle-fill'}`} />
                {msg.text}
              </div>
            )}

            {activeTab === 'transfer' ? (
              <>
                <div className="sb-field" style={{ marginBottom: 24 }}>
                  <div className="sb-field-label">Sender Account · Auto-detected</div>
                  <input
                    className="sb-field-input mono readonly"
                    value={accountNumber || ''}
                    readOnly
                  />
                </div>
                <div className="sb-field-row">
                  <div className="sb-field">
                    <div className="sb-field-label">
                      Receiver Account <span className="req">*</span>
                    </div>
                    <input
                      className="sb-field-input mono"
                      placeholder="00000000-XXX"
                      value={form.receiverAccount}
                      onChange={(e) => setForm({ ...form, receiverAccount: e.target.value })}
                    />
                    <div className="sb-field-help">
                      SecureBank account ID. We verify the name before confirming.
                    </div>
                  </div>
                  <div className="sb-field">
                    <div className="sb-field-label">
                      Amount <span className="req">*</span>
                      <span style={{ marginLeft: 'auto', color: 'var(--muted)', fontSize: 10 }}>
                        INR · ₹
                      </span>
                    </div>
                    <input
                      className="sb-field-input mono"
                      type="number"
                      placeholder="0.00"
                      value={form.amount}
                      onChange={(e) => setForm({ ...form, amount: e.target.value })}
                    />
                    <div className="sb-field-help">
                      Daily limit: ₹5,00,000 · Per-txn: ₹2,00,000
                    </div>
                  </div>
                </div>

                <div className="sb-field" style={{ marginBottom: 8 }}>
                  <div className="sb-field-label">Note · Optional</div>
                  <input
                    className="sb-field-input"
                    placeholder="What is this transfer for?"
                    value={form.note}
                    onChange={(e) => setForm({ ...form, note: e.target.value })}
                  />
                </div>

                <div className="sb-btn-row">
                  <button className="sb-btn sb-btn-primary" type="submit" disabled={loading}>
                    Continue Transfer →
                  </button>
                  <button
                    type="button"
                    className="sb-btn sb-btn-secondary"
                    onClick={() => {
                      setForm({ senderAccount: accountNumber, receiverAccount: '', amount: '', note: '' });
                      setMsg(null);
                    }}
                  >
                    Cancel
                  </button>
                  <div className="sb-trust-line">
                    <i className="bi bi-shield-lock" /> 100% Secure · End-to-End Encrypted
                  </div>
                </div>
              </>
            ) : (
              <>
                <div className="sb-field" style={{ marginBottom: 24 }}>
                  <div className="sb-field-label">
                    Amount to Add <span className="req">*</span>
                    <span style={{ marginLeft: 'auto', color: 'var(--muted)', fontSize: 10 }}>
                      INR · ₹
                    </span>
                  </div>
                  <input
                    className="sb-field-input mono"
                    type="number"
                    placeholder="0.00"
                    value={form.amount}
                    onChange={(e) => setForm({ ...form, amount: e.target.value })}
                  />
                  <div className="sb-field-help">Powered by Razorpay · Cards, UPI, Netbanking</div>
                </div>

                <div className="sb-field" style={{ marginBottom: 8 }}>
                  <div className="sb-field-label">Quick Amounts</div>
                  <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
                    {[500, 1000, 2000, 5000, 10000].map((amt) => (
                      <button
                        key={amt}
                        type="button"
                        className="sb-btn sb-btn-secondary"
                        style={{ padding: '8px 14px', fontSize: 12 }}
                        onClick={() => setForm({ ...form, amount: String(amt) })}
                      >
                        ₹{amt.toLocaleString('en-IN')}
                      </button>
                    ))}
                  </div>
                </div>

                <div className="sb-btn-row">
                  <button className="sb-btn sb-btn-primary" type="submit" disabled={loading}>
                    {loading ? <><span className="sb-spinner" /> Processing…</> : 'Add Funds →'}
                  </button>
                  <button
                    type="button"
                    className="sb-btn sb-btn-secondary"
                    onClick={() => {
                      setForm({ ...form, amount: '' });
                      setMsg(null);
                    }}
                  >
                    Clear
                  </button>
                  <div className="sb-trust-line">
                    <i className="bi bi-lightning-charge" /> Instant credit on success
                  </div>
                </div>
              </>
            )}
          </div>
        </form>
      </div>

      {showPasswordPopup && (
        <div className="sb-modal-backdrop" onClick={() => !loading && setShowPasswordPopup(false)}>
          <div className="sb-modal" onClick={(e) => e.stopPropagation()}>
            <div className="sb-modal-head">
              <div className="corner-mark">REF · TXN-{Date.now().toString().slice(-8)}</div>
              <div className="sb-seal" style={{ marginTop: 12 }}>
                <i className="bi bi-shield-lock" />
              </div>
              <h3>Authorise Transfer</h3>
              <p>Enter your password to confirm and sign this transaction.<br />This action cannot be undone.</p>
            </div>
            <div className="sb-modal-body">
              <div className="sb-txn-summary">
                <div className="row">
                  <span className="lbl">FROM</span>
                  <span className="val">{accountNumber}</span>
                </div>
                <div className="row">
                  <span className="lbl">TO</span>
                  <span className="val">{form.receiverAccount}</span>
                </div>
                <div className="row amt">
                  <span className="lbl">AMOUNT</span>
                  <span className="val">₹ {formattedAmount}</span>
                </div>
              </div>

              <div className="sb-field" style={{ marginBottom: 20 }}>
                <div className="sb-field-label">
                  Password <span className="req">*</span>
                </div>
                <input
                  className="sb-field-input"
                  type="password"
                  placeholder="••••••••••••"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  disabled={loading}
                  onKeyDown={(e) => e.key === 'Enter' && !loading && password && handleTransfer()}
                  autoFocus
                />
                <div className="sb-field-help">3 attempts remaining before lockout</div>
              </div>

              {msg && msg.type === 'error' && (
                <div className="sb-alert sb-alert-error">
                  <i className="bi bi-exclamation-triangle-fill" />
                  {msg.text}
                </div>
              )}

              <div className="sb-btn-row" style={{ borderTop: 'none', paddingTop: 0 }}>
                <button
                  className="sb-btn sb-btn-primary"
                  style={{ flex: 1 }}
                  onClick={handleTransfer}
                  disabled={loading || !password}
                >
                  {loading ? <><span className="sb-spinner" /> Processing…</> : 'Confirm & Sign'}
                </button>
                <button
                  className="sb-btn sb-btn-secondary"
                  onClick={() => {
                    setPassword('');
                    setShowPasswordPopup(false);
                    setMsg(null);
                  }}
                  disabled={loading}
                >
                  Cancel
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </>
  );
}
