import React, { useState } from 'react';
import API from '../api';
import { safeUsername } from '../utils/auth';
import { v4 as uuidv4 } from 'uuid';

export default function AddMoney({ onSuccess }) {
  const [amount, setAmount] = useState('');
  const [loading, setLoading] = useState(false);
  const [msg, setMsg] = useState(null);

  const loadRazorpayScript = () =>
    new Promise((resolve) => {
      if (window.Razorpay) return resolve(true);
      const script = document.createElement('script');
      script.src = 'https://checkout.razorpay.com/v1/checkout.js';
      script.onload = () => resolve(true);
      script.onerror = () => resolve(false);
      document.body.appendChild(script);
    });

  async function handleAddMoney(e) {
    e.preventDefault();
    if (!amount || Number(amount) <= 0) {
      setMsg({ type: 'error', text: 'Enter a valid amount' });
      return;
    }
    setLoading(true);
    setMsg(null);
    const ok = await loadRazorpayScript();
    if (!ok) {
      setMsg({ type: 'error', text: 'Razorpay SDK failed to load.' });
      setLoading(false);
      return;
    }

    try {
      const orderRes = await API.post('/payment/create-order', { amount: Number(amount) });
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
              amount: Number(amount),
              username,
              key,
            });
            if (verifyRes.data.success) {
              setMsg({ type: 'success', text: 'Money added successfully.' });
              setAmount('');
              if (onSuccess) onSuccess();
            } else {
              setMsg({ type: 'error', text: 'Payment verification failed.' });
            }
          } catch (err) {
            console.error(err);
            setMsg({ type: 'error', text: 'Error verifying payment.' });
          }
        },
        prefill: { name: 'Demo User', email: 'demo@example.com', contact: '9999999999' },
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
  }

  return (
    <div className="sb-form-section">
      <div className="sb-form-head">
        <div>
          <div className="sb-h-card" style={{ marginBottom: 6 }}>Add Money</div>
          <div style={{ fontSize: 12, color: 'var(--muted)' }}>Top up via Razorpay · Cards, UPI, Netbanking</div>
        </div>
      </div>

      <form onSubmit={handleAddMoney}>
        <div className="sb-form-body">
          {msg && (
            <div className={`sb-alert ${msg.type === 'error' ? 'sb-alert-error' : 'sb-alert-success'}`}>
              <i className={`bi ${msg.type === 'error' ? 'bi-exclamation-triangle-fill' : 'bi-check-circle-fill'}`} />
              {msg.text}
            </div>
          )}

          <div className="sb-field" style={{ marginBottom: 24 }}>
            <div className="sb-field-label">
              Amount <span className="req">*</span>
              <span style={{ marginLeft: 'auto', color: 'var(--muted)', fontSize: 10 }}>INR · ₹</span>
            </div>
            <input
              className="sb-field-input mono"
              type="number"
              placeholder="0.00"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
            />
          </div>

          <div className="sb-field" style={{ marginBottom: 8 }}>
            <div className="sb-field-label">Quick Amounts</div>
            <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap' }}>
              {[500, 1000, 2000, 5000].map((amt) => (
                <button
                  key={amt}
                  type="button"
                  className="sb-btn sb-btn-secondary"
                  style={{ padding: '8px 14px', fontSize: 12 }}
                  onClick={() => setAmount(String(amt))}
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
              onClick={() => { setAmount(''); setMsg(null); }}
            >
              Clear
            </button>
            <div className="sb-trust-line">
              <i className="bi bi-shield-lock" /> Secure · Encrypted · Instant
            </div>
          </div>
        </div>
      </form>
    </div>
  );
}
