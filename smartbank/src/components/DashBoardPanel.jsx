import React, { useMemo, memo } from 'react';

const QUICK_ACTIONS = [
  { num: '01', title: 'Transfer', sub: 'Send funds instantly', action: 'transfer' },
  { num: '02', title: 'Add Funds', sub: 'UPI, card, or bank', action: 'addMoney' },
  { num: '03', title: 'Statements', sub: 'Full transaction log', action: 'tx' },
  { num: '04', title: 'Apply for Credit', sub: 'Personal · 6 months', action: 'loan' },
];

function formatCompact(amount) {
  const n = Number(amount) || 0;
  const abs = Math.abs(n);
  if (abs >= 1e7) return `₹${(n / 1e7).toFixed(2)}Cr`;
  if (abs >= 1e5) return `₹${(n / 1e5).toFixed(2)}L`;
  if (abs >= 1e3) return `₹${(n / 1e3).toFixed(1)}k`;
  return `₹${Math.round(n)}`;
}

function splitCurrency(value) {
  const n = Number(value) || 0;
  const [whole, decimal] = n.toFixed(2).split('.');
  const formatted = Number(whole).toLocaleString('en-IN');
  return { whole: formatted, decimal };
}

function DashboardPanel({ setActive, transactions = [], balance = 0, accountNumber, username }) {
  const stats = useMemo(() => {
    if (!transactions || transactions.length === 0) {
      return { totalTransactions: 0, totalVolume: 0, avgTransaction: 0, latest: null, inflow: 0, outflow: 0 };
    }
    let inflow = 0;
    let outflow = 0;
    transactions.forEach((tx) => {
      const amt = Number(tx.amount) || 0;
      if (amt >= 0) inflow += amt;
      else outflow += Math.abs(amt);
    });
    const totalVolume = transactions.reduce((sum, tx) => sum + Math.abs(Number(tx.amount) || 0), 0);
    return {
      totalTransactions: transactions.length,
      totalVolume,
      avgTransaction: totalVolume / transactions.length,
      latest: transactions[0]?.timestamp ? new Date(transactions[0].timestamp) : null,
      inflow,
      outflow,
    };
  }, [transactions]);

  const recentTransactions = useMemo(() => (transactions || []).slice(0, 6), [transactions]);
  const { whole, decimal } = splitCurrency(balance);

  const flowSeries = useMemo(() => {
    const days = [];
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    for (let i = 6; i >= 0; i -= 1) {
      const d = new Date(today);
      d.setDate(today.getDate() - i);
      days.push({
        key: d.toISOString().slice(0, 10),
        date: d,
        label: d.toLocaleDateString('en-IN', { weekday: 'short' }),
        dayNum: d.getDate(),
        received: 0,
        sent: 0,
        receivedCount: 0,
        sentCount: 0,
      });
    }
    const idx = new Map(days.map((d) => [d.key, d]));
    (transactions || []).forEach((tx) => {
      if (!tx.timestamp) return;
      const dt = new Date(tx.timestamp);
      if (Number.isNaN(dt.getTime())) return;
      const k = new Date(dt.getFullYear(), dt.getMonth(), dt.getDate())
        .toISOString()
        .slice(0, 10);
      const slot = idx.get(k);
      if (!slot) return;
      const amt = Number(tx.amount) || 0;
      if (amt >= 0) {
        slot.received += amt;
        slot.receivedCount += 1;
      } else {
        slot.sent += Math.abs(amt);
        slot.sentCount += 1;
      }
    });
    const max = days.reduce((m, d) => Math.max(m, d.received, d.sent), 0);
    const totalReceived = days.reduce((s, d) => s + d.received, 0);
    const totalSent = days.reduce((s, d) => s + d.sent, 0);
    const totalReceivedCount = days.reduce((s, d) => s + d.receivedCount, 0);
    const totalSentCount = days.reduce((s, d) => s + d.sentCount, 0);
    return { days, max, totalReceived, totalSent, totalReceivedCount, totalSentCount };
  }, [transactions]);

  const greet = (() => {
    const h = new Date().getHours();
    if (h < 12) return 'Good morning';
    if (h < 17) return 'Good afternoon';
    return 'Good evening';
  })();

  const lastReconciled = new Date().toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' });

  return (
    <div>
      <div className="sb-balance-hero" style={{ marginBottom: 20 }}>
        <div className="sb-balance-main">
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', position: 'relative', marginBottom: 18 }}>
            <div>
              <div className="sb-eyebrow" style={{ marginBottom: 6 }}>{greet}, {username || 'User'}</div>
              <div style={{ fontFamily: "'Fraunces', serif", fontSize: 22, fontWeight: 300, letterSpacing: '-0.02em', color: 'rgba(255,255,255,0.9)', position: 'relative' }}>
                A quiet day in <em style={{ fontStyle: 'italic', color: 'var(--accent-2)' }}>your</em> ledger.
              </div>
            </div>
            <div style={{ textAlign: 'right', fontSize: 10, color: 'rgba(255,255,255,0.4)', letterSpacing: '0.08em', textTransform: 'uppercase', position: 'relative' }}>
              Last reconciled
              <br />
              <span className="sb-mono" style={{ color: 'rgba(255,255,255,0.7)', textTransform: 'none', letterSpacing: 0 }}>{lastReconciled}</span>
            </div>
          </div>
          <div className="sb-eyebrow" style={{ marginBottom: 6 }}>Available Balance</div>
          <div className="sb-balance-amount">
            <span className="currency">₹</span>
            {whole}
            <span className="decimal">.{decimal}</span>
          </div>
          <div className="sb-balance-meta">
            <span>SAVINGS · INR</span>
            <span className="divider" />
            <span>Encrypted</span>
            {accountNumber && (
              <>
                <span className="divider" />
                <span className="sb-mono">{accountNumber}</span>
              </>
            )}
          </div>
        </div>
        <div className="sb-balance-stats">
          <div className="sb-stat-cell">
            <div className="label">Inflow · 30d</div>
            <div className="value sb-mono">{formatCompact(stats.inflow)}</div>
            <div className={`delta${stats.inflow > 0 ? ' up' : ''}`}>
              {stats.inflow > 0 ? '↑ activity' : '— no activity'}
            </div>
          </div>
          <div className="sb-stat-cell">
            <div className="label">Outflow · 30d</div>
            <div className="value sb-mono">{formatCompact(stats.outflow)}</div>
            <div className="delta">
              {stats.outflow > 0 ? '↓ activity' : '— no activity'}
            </div>
          </div>
          <div className="sb-stat-cell">
            <div className="label">Avg. Ticket</div>
            <div className="value sb-mono">{formatCompact(stats.avgTransaction)}</div>
            <div className="delta">{stats.totalTransactions > 0 ? `${stats.totalTransactions} txns` : 'Awaiting first txn'}</div>
          </div>
          <div className="sb-stat-cell">
            <div className="label">Last Activity</div>
            <div className="value">
              {stats.latest ? stats.latest.toLocaleDateString('en-IN', { day: '2-digit', month: 'short' }) : '—'}
            </div>
            <div className="delta">{stats.latest ? 'recorded' : 'No record'}</div>
          </div>
        </div>
      </div>

      <div className="sb-quick-actions" style={{ marginBottom: 20 }}>
        {QUICK_ACTIONS.map((a) => (
          <button key={a.action} className="sb-qa-cell" onClick={() => setActive(a.action)}>
            <div className="number">{a.num}</div>
            <div className="title">{a.title}</div>
            <div className="sub">{a.sub}</div>
            <div className="arrow">→</div>
          </button>
        ))}
      </div>

      <div className="sb-panel sb-cashflow" style={{ marginBottom: 20 }}>
        <div className="sb-panel-head">
          <div className="title">Cash Flow · Received vs Sent</div>
          <div className="meta">
            <span className="sb-cashflow-legend">
              <span className="dot rec" /> Received
            </span>
            <span className="sb-cashflow-legend">
              <span className="dot sent" /> Sent
            </span>
            <span style={{ color: 'var(--muted)', marginLeft: 6 }}>Last 7 days</span>
          </div>
        </div>
        <div className="sb-panel-body sb-cashflow-body">
          <div className="sb-cashflow-summary">
            <div className="cell">
              <div className="lbl">Received</div>
              <div className="val sb-mono rec">{formatCompact(flowSeries.totalReceived)}</div>
              <div className="sub">
                {flowSeries.totalReceivedCount} txn{flowSeries.totalReceivedCount === 1 ? '' : 's'}
              </div>
            </div>
            <div className="cell">
              <div className="lbl">Sent</div>
              <div className="val sb-mono sent">{formatCompact(flowSeries.totalSent)}</div>
              <div className="sub">
                {flowSeries.totalSentCount} txn{flowSeries.totalSentCount === 1 ? '' : 's'}
              </div>
            </div>
            <div className="cell">
              <div className="lbl">Net</div>
              <div
                className={`val sb-mono ${flowSeries.totalReceived - flowSeries.totalSent >= 0 ? 'rec' : 'sent'}`}
              >
                {flowSeries.totalReceived - flowSeries.totalSent >= 0 ? '+' : '−'}
                {formatCompact(Math.abs(flowSeries.totalReceived - flowSeries.totalSent))}
              </div>
              <div className="sub">net flow</div>
            </div>
          </div>
          {flowSeries.max > 0 ? (
            <div className="sb-cashflow-chart">
              {flowSeries.days.map((d) => {
                const recH = flowSeries.max > 0 ? (d.received / flowSeries.max) * 100 : 0;
                const sentH = flowSeries.max > 0 ? (d.sent / flowSeries.max) * 100 : 0;
                return (
                  <div className="col" key={d.key}>
                    <div className="bars">
                      <div
                        className="bar rec"
                        style={{ height: `${recH}%` }}
                        title={`Received ${formatCompact(d.received)} · ${d.receivedCount} txn`}
                      />
                      <div
                        className="bar sent"
                        style={{ height: `${sentH}%` }}
                        title={`Sent ${formatCompact(d.sent)} · ${d.sentCount} txn`}
                      />
                    </div>
                    <div className="lbl">
                      <div className="day">{d.label}</div>
                      <div className="num">{d.dayNum}</div>
                    </div>
                  </div>
                );
              })}
            </div>
          ) : (
            <div className="sb-empty-state" style={{ padding: '12px 0 4px' }}>
              <div className="icon-box">~</div>
              <div className="msg">No flow data yet</div>
              <div className="sub">Send or receive money to populate the 7-day chart</div>
            </div>
          )}
        </div>
      </div>

      <div className="sb-grid-2">
        <div className="sb-panel">
          <div className="sb-panel-head">
            <div className="title">Weekly Activity</div>
            <div className="meta">Last 7 days</div>
          </div>
          <div className="sb-panel-body">
            {stats.totalTransactions > 0 ? (
              <div style={{ width: '100%', textAlign: 'center' }}>
                <div className="sb-mono" style={{ fontSize: 32, color: 'var(--ink)' }}>
                  {formatCompact(stats.totalVolume)}
                </div>
                <div style={{ fontSize: 11, color: 'var(--muted)', letterSpacing: '0.08em', textTransform: 'uppercase', marginTop: 8 }}>
                  Volume across {stats.totalTransactions} txns
                </div>
              </div>
            ) : (
              <div className="sb-empty-state">
                <div className="icon-box">/</div>
                <div className="msg">No activity yet</div>
                <div className="sub">Make your first transfer to see your weekly trend</div>
              </div>
            )}
          </div>
        </div>

        <div className="sb-panel">
          <div className="sb-panel-head">
            <div className="title">Recent Movements</div>
            <div className="meta">
              Latest {recentTransactions.length} ·{' '}
              <button
                onClick={() => setActive('tx')}
                style={{ background: 'none', border: 'none', color: 'var(--ink)', cursor: 'pointer', fontSize: 11 }}
              >
                View all
              </button>
            </div>
          </div>
          <div className="sb-panel-body" style={{ padding: 0, alignItems: 'stretch' }}>
            {recentTransactions.length > 0 ? (
              <div style={{ width: '100%' }}>
                {recentTransactions.map((tx, i) => (
                  <div
                    key={tx.id || `${tx.timestamp}-${i}`}
                    style={{
                      display: 'flex',
                      alignItems: 'center',
                      justifyContent: 'space-between',
                      padding: '14px 24px',
                      borderBottom: i < recentTransactions.length - 1 ? '1px solid var(--rule-soft)' : 'none',
                    }}
                  >
                    <div style={{ minWidth: 0 }}>
                      <div className="sb-mono" style={{ fontSize: 12, color: 'var(--ink)' }}>
                        {tx.receiverAccount || tx.senderAccount || '—'}
                      </div>
                      <div style={{ fontSize: 10, color: 'var(--muted)', marginTop: 2 }}>
                        {tx.timestamp
                          ? new Date(tx.timestamp).toLocaleString('en-IN', {
                              day: '2-digit',
                              month: 'short',
                              hour: '2-digit',
                              minute: '2-digit',
                            })
                          : '—'}
                      </div>
                    </div>
                    <div className="sb-mono" style={{ fontSize: 13, color: 'var(--ink)', fontWeight: 500 }}>
                      {formatCompact(tx.amount || 0)}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <div className="sb-empty-state" style={{ padding: 28 }}>
                <div className="icon-box">·</div>
                <div className="msg">No transactions to show</div>
                <div className="sub">Your latest activity will appear here</div>
              </div>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

export default memo(DashboardPanel);
