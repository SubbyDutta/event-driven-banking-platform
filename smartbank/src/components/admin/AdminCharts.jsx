import React, { memo, useMemo } from 'react';
import {
  AreaChart, Area,
  BarChart, Bar,
  PieChart, Pie, Cell,
  RadialBarChart, RadialBar,
  XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip,
  ResponsiveContainer,
} from 'recharts';

const INK = '#0A0E1A';
const ACCENT = '#1E3A2F';
const ACCENT_2 = '#B8954A';
const ACCENT_3 = '#8B2C2C';
const POSITIVE = '#2D5F3F';
const RULE = '#E0DDD3';
const RULE_SOFT = '#F2F1EC';
const MUTED = '#6B6B63';
const PAPER_2 = '#F2F1EC';

const CHART_PALETTE = [INK, ACCENT, ACCENT_2, ACCENT_3, POSITIVE, MUTED];

const STATUS_COLORS = {
  APPROVED: POSITIVE,
  KYC_APPROVED: POSITIVE,
  PENDING: ACCENT_2,
  KYC_SUBMITTED: ACCENT,
  KYC_PENDING: ACCENT_2,
  REJECTED: ACCENT_3,
  KYC_REJECTED: ACCENT_3,
  CLOSED: MUTED,
  ACTIVE: ACCENT,
  DEFAULT: INK,
};

function formatCompactINR(value) {
  if (value == null || isNaN(value)) return '₹0';
  const abs = Math.abs(value);
  if (abs >= 1e7) return `₹${(value / 1e7).toFixed(2)}Cr`;
  if (abs >= 1e5) return `₹${(value / 1e5).toFixed(2)}L`;
  if (abs >= 1e3) return `₹${(value / 1e3).toFixed(1)}k`;
  return `₹${Math.round(value)}`;
}

const TooltipBox = ({ active, payload, label }) => {
  if (!active || !payload || !payload.length) return null;
  return (
    <div
      style={{
        background: 'rgba(250,250,247,0.98)',
        backdropFilter: 'blur(8px)',
        border: `1px solid ${RULE}`,
        padding: '10px 14px',
        borderRadius: 2,
        boxShadow: '0 12px 32px rgba(10,14,26,0.08)',
        fontSize: 12,
        fontFamily: 'JetBrains Mono, monospace',
      }}
    >
      {label != null && (
        <div style={{ fontWeight: 500, color: INK, marginBottom: 6, letterSpacing: '0.04em' }}>{label}</div>
      )}
      {payload.map((entry, idx) => (
        <div key={idx} style={{ display: 'flex', alignItems: 'center', gap: 8, marginTop: 2 }}>
          <span
            style={{
              width: 8,
              height: 8,
              borderRadius: 1,
              background: entry.color || entry.fill,
              display: 'inline-block',
            }}
          />
          <span style={{ color: MUTED }}>{entry.name}</span>
          <span style={{ marginLeft: 'auto', fontWeight: 500, color: INK }}>
            {typeof entry.value === 'number' && entry.value > 999
              ? entry.value.toLocaleString('en-IN')
              : entry.value}
          </span>
        </div>
      ))}
    </div>
  );
};

function ChartShell({ children, height = 280 }) {
  return (
    <div style={{ width: '100%', height }}>
      <ResponsiveContainer width="100%" height="100%">
        {children}
      </ResponsiveContainer>
    </div>
  );
}

function bucketByDay(transactions, days = 7) {
  const today = new Date();
  today.setHours(0, 0, 0, 0);
  const buckets = [];
  for (let i = days - 1; i >= 0; i--) {
    const d = new Date(today);
    d.setDate(d.getDate() - i);
    buckets.push({
      key: d.toISOString().slice(0, 10),
      label: d.toLocaleDateString('en-US', { month: 'short', day: 'numeric' }),
      amount: 0,
      count: 0,
    });
  }
  const idx = Object.fromEntries(buckets.map((b, i) => [b.key, i]));
  (transactions || []).forEach((t) => {
    if (!t?.timestamp) return;
    const day = new Date(t.timestamp);
    if (isNaN(day)) return;
    const key = day.toISOString().slice(0, 10);
    const b = idx[key] != null ? buckets[idx[key]] : null;
    if (!b) return;
    b.amount += Number(t.amount) || 0;
    b.count += 1;
  });
  return buckets;
}

const TICK_STYLE = { fontSize: 10, fill: MUTED, fontFamily: 'JetBrains Mono, monospace' };

export const TransactionChart = memo(function TransactionChart({ data }) {
  const chartData = useMemo(() => bucketByDay(data, 7), [data]);
  return (
    <ChartShell height={280}>
      <AreaChart data={chartData} margin={{ top: 10, right: 16, left: 0, bottom: 0 }}>
        <defs>
          <linearGradient id="txGrad" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor={ACCENT} stopOpacity={0.18} />
            <stop offset="100%" stopColor={ACCENT} stopOpacity={0} />
          </linearGradient>
        </defs>
        <CartesianGrid vertical={false} stroke={RULE_SOFT} strokeDasharray="2 4" />
        <XAxis
          dataKey="label"
          axisLine={{ stroke: RULE }}
          tickLine={false}
          tick={TICK_STYLE}
        />
        <YAxis
          axisLine={false}
          tickLine={false}
          tick={TICK_STYLE}
          tickFormatter={formatCompactINR}
          width={56}
        />
        <RechartsTooltip content={<TooltipBox />} cursor={{ stroke: RULE }} />
        <Area
          type="monotone"
          dataKey="amount"
          name="Volume"
          stroke={INK}
          strokeWidth={2}
          fill="url(#txGrad)"
          isAnimationActive={false}
        />
      </AreaChart>
    </ChartShell>
  );
});

export const TransactionCountChart = memo(function TransactionCountChart({ data }) {
  const chartData = useMemo(() => bucketByDay(data, 7), [data]);
  return (
    <ChartShell height={220}>
      <BarChart data={chartData} margin={{ top: 10, right: 8, left: 0, bottom: 0 }}>
        <CartesianGrid vertical={false} stroke={RULE_SOFT} strokeDasharray="2 4" />
        <XAxis
          dataKey="label"
          axisLine={{ stroke: RULE }}
          tickLine={false}
          tick={TICK_STYLE}
        />
        <YAxis
          axisLine={false}
          tickLine={false}
          tick={TICK_STYLE}
          width={32}
          allowDecimals={false}
        />
        <RechartsTooltip content={<TooltipBox />} cursor={{ fill: 'rgba(10,14,26,0.03)' }} />
        <Bar dataKey="count" name="Transactions" fill={INK} isAnimationActive={false} />
      </BarChart>
    </ChartShell>
  );
});

export const LoanStatusChart = memo(function LoanStatusChart({ loans }) {
  const data = useMemo(() => {
    const counts = (loans || []).reduce((acc, loan) => {
      const status = (loan?.status || loan?.lifecycleStatus || 'UNKNOWN').toString().toUpperCase();
      acc[status] = (acc[status] || 0) + 1;
      return acc;
    }, {});
    return Object.entries(counts).map(([name, value]) => ({ name, value }));
  }, [loans]);

  if (!data.length) {
    return <EmptyState label="No loan data" />;
  }

  return (
    <div style={{ width: '100%', display: 'flex', flexDirection: 'column' }}>
      <ChartShell height={200}>
        <PieChart>
          <Pie
            data={data}
            innerRadius={56}
            outerRadius={84}
            paddingAngle={3}
            dataKey="value"
            stroke="none"
            isAnimationActive={false}
          >
            {data.map((entry, index) => (
              <Cell
                key={entry.name}
                fill={STATUS_COLORS[entry.name] || CHART_PALETTE[index % CHART_PALETTE.length]}
              />
            ))}
          </Pie>
          <RechartsTooltip content={<TooltipBox />} />
        </PieChart>
      </ChartShell>
      <div
        style={{
          display: 'flex',
          flexWrap: 'wrap',
          gap: 16,
          justifyContent: 'center',
          marginTop: 12,
          paddingTop: 12,
          borderTop: `1px solid ${RULE_SOFT}`,
          fontSize: 11,
          fontFamily: 'JetBrains Mono, monospace',
        }}
      >
        {data.map((entry, index) => (
          <div key={entry.name} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span
              style={{
                width: 8,
                height: 8,
                borderRadius: 1,
                background:
                  STATUS_COLORS[entry.name] || CHART_PALETTE[index % CHART_PALETTE.length],
              }}
            />
            <span style={{ color: INK }}>{entry.name}</span>
            <span style={{ color: MUTED }}>· {entry.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
});

export const TransactionTypeChart = memo(function TransactionTypeChart({ data }) {
  const chartData = useMemo(() => {
    let domestic = 0;
    let foreign = 0;
    (data || []).forEach((t) => {
      if (t?.isForeign === 1 || t?.foreign === 1 || t?.foreign === true) foreign += 1;
      else domestic += 1;
    });
    return [
      { name: 'Domestic', value: domestic, fill: INK },
      { name: 'Foreign', value: foreign, fill: ACCENT_2 },
    ];
  }, [data]);

  const total = chartData.reduce((s, d) => s + d.value, 0);
  if (!total) return <EmptyState label="No transactions yet" />;

  return (
    <div style={{ position: 'relative', width: '100%' }}>
      <ChartShell height={200}>
        <PieChart>
          <Pie
            data={chartData}
            innerRadius={60}
            outerRadius={82}
            paddingAngle={2}
            dataKey="value"
            stroke="none"
            isAnimationActive={false}
          >
            {chartData.map((entry) => (
              <Cell key={entry.name} fill={entry.fill} />
            ))}
          </Pie>
          <RechartsTooltip content={<TooltipBox />} />
        </PieChart>
      </ChartShell>
      <div
        style={{
          position: 'absolute',
          inset: 0,
          display: 'flex',
          flexDirection: 'column',
          alignItems: 'center',
          justifyContent: 'center',
          pointerEvents: 'none',
          paddingBottom: 24,
        }}
      >
        <div style={{ fontFamily: 'Fraunces, serif', fontSize: 30, fontWeight: 400, color: INK, letterSpacing: '-0.02em' }}>
          {total}
        </div>
        <div style={{ fontSize: 9, color: MUTED, fontFamily: 'JetBrains Mono, monospace', textTransform: 'uppercase', letterSpacing: '0.16em', marginTop: 2 }}>
          Total
        </div>
      </div>
      <div style={{ display: 'flex', justifyContent: 'center', gap: 16, fontSize: 11, marginTop: 8, paddingTop: 12, borderTop: `1px solid ${RULE_SOFT}`, fontFamily: 'JetBrains Mono, monospace' }}>
        {chartData.map((d) => (
          <div key={d.name} style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
            <span style={{ width: 8, height: 8, borderRadius: 1, background: d.fill }} />
            <span style={{ color: INK }}>{d.name}</span>
            <span style={{ color: MUTED }}>· {d.value}</span>
          </div>
        ))}
      </div>
    </div>
  );
});

export const TopAccountsChart = memo(function TopAccountsChart({ data }) {
  const chartData = useMemo(() => {
    const byAcct = {};
    (data || []).forEach((t) => {
      const sender = t?.senderAccount;
      if (!sender) return;
      byAcct[sender] = (byAcct[sender] || 0) + (Number(t.amount) || 0);
    });
    return Object.entries(byAcct)
      .map(([acct, amount]) => ({ acct: String(acct).slice(-6), amount }))
      .sort((a, b) => b.amount - a.amount)
      .slice(0, 5);
  }, [data]);

  if (!chartData.length) return <EmptyState label="No senders yet" />;

  return (
    <ChartShell height={220}>
      <BarChart
        data={chartData}
        layout="vertical"
        margin={{ top: 4, right: 12, left: 0, bottom: 0 }}
      >
        <CartesianGrid horizontal={false} stroke={RULE_SOFT} strokeDasharray="2 4" />
        <XAxis
          type="number"
          axisLine={false}
          tickLine={false}
          tick={TICK_STYLE}
          tickFormatter={formatCompactINR}
        />
        <YAxis
          dataKey="acct"
          type="category"
          axisLine={false}
          tickLine={false}
          tick={{ fontSize: 11, fill: INK, fontFamily: 'JetBrains Mono, monospace' }}
          width={70}
        />
        <RechartsTooltip content={<TooltipBox />} cursor={{ fill: 'rgba(10,14,26,0.03)' }} />
        <Bar dataKey="amount" name="Sent" fill={ACCENT} isAnimationActive={false} />
      </BarChart>
    </ChartShell>
  );
});

export const KycFunnelChart = memo(function KycFunnelChart({ users }) {
  const data = useMemo(() => {
    const buckets = { NONE: 0, KYC_SUBMITTED: 0, KYC_PENDING: 0, KYC_APPROVED: 0, KYC_REJECTED: 0 };
    (users || []).forEach((u) => {
      const k = (u?.kycStatus || 'NONE').toString().toUpperCase();
      if (buckets[k] != null) buckets[k] += 1;
      else buckets.NONE += 1;
    });
    return Object.entries(buckets).map(([name, value]) => ({ name, value }));
  }, [users]);

  return (
    <ChartShell height={220}>
      <BarChart data={data} margin={{ top: 10, right: 8, left: 0, bottom: 0 }}>
        <CartesianGrid vertical={false} stroke={RULE_SOFT} strokeDasharray="2 4" />
        <XAxis
          dataKey="name"
          axisLine={{ stroke: RULE }}
          tickLine={false}
          tick={{ ...TICK_STYLE, fontSize: 9 }}
          interval={0}
          tickFormatter={(v) => v.replace('KYC_', '')}
        />
        <YAxis
          axisLine={false}
          tickLine={false}
          tick={TICK_STYLE}
          width={32}
          allowDecimals={false}
        />
        <RechartsTooltip content={<TooltipBox />} cursor={{ fill: 'rgba(10,14,26,0.03)' }} />
        <Bar dataKey="value" name="Users" isAnimationActive={false}>
          {data.map((entry, idx) => (
            <Cell key={entry.name} fill={STATUS_COLORS[entry.name] || CHART_PALETTE[idx % CHART_PALETTE.length]} />
          ))}
        </Bar>
      </BarChart>
    </ChartShell>
  );
});

export const SystemUtilizationChart = memo(function SystemUtilizationChart({ value = 24, label = 'CPU' }) {
  const data = [{ name: label, value: Math.max(0, Math.min(100, value)) }];
  return (
    <ChartShell height={180}>
      <RadialBarChart
        cx="50%"
        cy="50%"
        innerRadius="70%"
        outerRadius="100%"
        barSize={14}
        data={data}
        startAngle={210}
        endAngle={-30}
      >
        <RadialBar background={{ fill: PAPER_2 }} dataKey="value" cornerRadius={2} fill={INK} isAnimationActive={false} />
      </RadialBarChart>
    </ChartShell>
  );
});

function EmptyState({ label }) {
  return (
    <div
      style={{
        height: 200,
        display: 'flex',
        flexDirection: 'column',
        alignItems: 'center',
        justifyContent: 'center',
        color: MUTED,
        fontSize: 13,
        fontFamily: 'Fraunces, serif',
        fontWeight: 400,
      }}
    >
      <div
        style={{
          width: 36,
          height: 36,
          border: `1px solid ${RULE}`,
          marginBottom: 12,
          display: 'flex',
          alignItems: 'center',
          justifyContent: 'center',
          color: MUTED,
        }}
      >
        <i className="bi bi-graph-up" />
      </div>
      {label}
    </div>
  );
}

export { formatCompactINR };
