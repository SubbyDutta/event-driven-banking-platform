import React, { useEffect, useState } from 'react';

const PAGE_SIZE = 10;

function fmtAmount(v) {
  if (typeof v !== 'number') return v ?? '—';
  return v.toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
}

function fmtBalance(b) {
  if (typeof b !== 'number') return '₹0.00';
  return b.toLocaleString('en-IN', { style: 'currency', currency: 'INR', maximumFractionDigits: 2 });
}

export default function TransactionsPanel({ transactions, loading, balance, onReload }) {
  const [page, setPage] = useState(0);
  const [lastFetchedCount, setLastFetchedCount] = useState(0);
  const [from, setFrom] = useState('');
  const [to, setTo] = useState('');
  const [minAmount, setMinAmount] = useState('');
  const [maxAmount, setMaxAmount] = useState('');
  const [showFilters, setShowFilters] = useState(false);

  useEffect(() => {
    if (onReload) {
      onReload({
        page,
        size: PAGE_SIZE,
        from: from || undefined,
        to: to || undefined,
        minAmount: minAmount || undefined,
        maxAmount: maxAmount || undefined,
      });
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [page]);

  useEffect(() => {
    setLastFetchedCount(transactions?.length || 0);
  }, [transactions]);

  const hasPrev = page > 0;
  const hasNext = lastFetchedCount === PAGE_SIZE;

  const applyFilters = () => {
    setPage(0);
    onReload({
      page: 0,
      size: PAGE_SIZE,
      from: from || undefined,
      to: to || undefined,
      minAmount: minAmount || undefined,
      maxAmount: maxAmount || undefined,
    });
  };

  const clearFilters = () => {
    setFrom(''); setTo(''); setMinAmount(''); setMaxAmount('');
    setShowFilters(false);
    setPage(0);
    onReload({ page: 0, size: PAGE_SIZE });
  };

  const handleReload = () => {
    setPage(0);
    onReload({
      page: 0,
      size: PAGE_SIZE,
      from: from || undefined,
      to: to || undefined,
      minAmount: minAmount || undefined,
      maxAmount: maxAmount || undefined,
    });
  };

  const month = new Date().toLocaleDateString('en-IN', { month: 'short', year: 'numeric' }).toUpperCase();

  return (
    <div>
      <div className="sb-stats-row">
        <div className="sb-stat-cell primary">
          <div className="label">Total Balance</div>
          <div className="value sb-mono">{fmtBalance(balance)}</div>
          <div className="delta">
            Reconciled · {new Date().toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' })}
          </div>
        </div>
        <div className="sb-stat-cell">
          <div className="label">Records</div>
          <div className="value sb-mono">{transactions?.length || 0}</div>
        </div>
        <div className="sb-stat-cell">
          <div className="label">Page</div>
          <div className="value sb-mono">{page + 1}</div>
        </div>
        <div className="sb-stat-cell">
          <div className="label">Status</div>
          <div className="value" style={{ fontSize: 14, color: 'var(--positive)' }}>
            {loading ? 'Loading…' : 'All clear'}
          </div>
        </div>
      </div>

      <div className="sb-table-section">
        <div className="sb-table-head">
          <div className="title-group">
            <div className="title">Transaction History</div>
            <div className="count">{transactions?.length || 0} RECORDS · {month}</div>
          </div>
          <div className="sb-table-tools">
            <button className="sb-filter-btn" onClick={() => setShowFilters((v) => !v)}>
              <i className="bi bi-funnel" /> {showFilters ? 'Close' : 'Filter'}
            </button>
            <button className="sb-filter-btn" onClick={handleReload} disabled={loading}>
              <i className="bi bi-arrow-clockwise" /> Refresh
            </button>
          </div>
        </div>

        {showFilters && (
          <div style={{ padding: '20px 28px', borderBottom: '1px solid var(--rule)', background: 'var(--paper-2)' }}>
            <div className="sb-field-row" style={{ marginBottom: 16 }}>
              <div className="sb-field">
                <div className="sb-field-label">From Date</div>
                <input
                  type="date"
                  className="sb-field-input mono"
                  value={from}
                  onChange={(e) => setFrom(e.target.value)}
                />
              </div>
              <div className="sb-field">
                <div className="sb-field-label">To Date</div>
                <input
                  type="date"
                  className="sb-field-input mono"
                  value={to}
                  onChange={(e) => setTo(e.target.value)}
                />
              </div>
            </div>
            <div className="sb-field-row" style={{ marginBottom: 0 }}>
              <div className="sb-field">
                <div className="sb-field-label">Min Amount</div>
                <input
                  type="number"
                  className="sb-field-input mono"
                  placeholder="₹ 0"
                  value={minAmount}
                  onChange={(e) => setMinAmount(e.target.value)}
                />
              </div>
              <div className="sb-field">
                <div className="sb-field-label">Max Amount</div>
                <input
                  type="number"
                  className="sb-field-input mono"
                  placeholder="₹ ∞"
                  value={maxAmount}
                  onChange={(e) => setMaxAmount(e.target.value)}
                />
              </div>
            </div>
            <div style={{ display: 'flex', gap: 8, marginTop: 16 }}>
              <button className="sb-btn sb-btn-primary" onClick={applyFilters}>
                Apply Filters
              </button>
              <button className="sb-btn sb-btn-secondary" onClick={clearFilters}>
                Reset
              </button>
            </div>
          </div>
        )}

        {!loading && (!transactions || transactions.length === 0) ? (
          <div className="sb-table-empty">
            <div className="sb-seal" style={{ width: 48, height: 48 }}>
              <i className="bi bi-inbox" />
            </div>
            <div className="msg">The ledger is quiet.</div>
            <div className="sub">
              When transactions occur, they will appear here in chronological order with full audit trails.
            </div>
          </div>
        ) : (
          <table className="sb-data-table">
            <thead>
              <tr>
                <th>Date</th>
                <th>From</th>
                <th>To</th>
                <th className="right">Amount</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {transactions?.map((t, i) => (
                <tr key={t.id || i}>
                  <td>
                    <div style={{ fontSize: 12, color: 'var(--ink)' }}>
                      {t.timestamp
                        ? new Date(t.timestamp).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })
                        : '—'}
                    </div>
                    <div style={{ fontSize: 10, color: 'var(--muted)', marginTop: 2 }}>
                      {t.timestamp
                        ? new Date(t.timestamp).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' })
                        : '—'}
                    </div>
                  </td>
                  <td className="mono">{t.senderAccount || t.from || '—'}</td>
                  <td className="mono">{t.receiverAccount || t.to || '—'}</td>
                  <td className="right">
                    <span className="sb-mono" style={{ fontWeight: 500 }}>
                      ₹{fmtAmount(t.amount)}
                    </span>
                  </td>
                  <td>
                    <span className="sb-tag-pill success">
                      <i className="bi bi-check-circle-fill" /> Success
                    </span>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        )}

        <div className="sb-table-foot">
          <div>
            Showing <span className="sb-mono">{transactions?.length || 0}</span> records
          </div>
          <div className="sb-pager">
            <button disabled={!hasPrev || loading} onClick={() => setPage((p) => p - 1)}>
              ← Previous
            </button>
            <span className="sb-mono" style={{ color: 'var(--ink)' }}>PAGE {page + 1}</span>
            <button disabled={!hasNext || loading} onClick={() => setPage((p) => p + 1)}>
              Next →
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
