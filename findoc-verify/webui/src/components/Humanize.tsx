

import type { ReactNode } from "react";

const safeJoin = (v: any, sep = ", "): string => {
  if (Array.isArray(v)) return v.join(sep);
  if (v == null) return "";
  return String(v);
};
const safeArr = <T,>(v: any): T[] => (Array.isArray(v) ? (v as T[]) : []);


const TITLES: Record<string, { title: string; what: string }> = {

  pan_format:                    { title: "PAN format check",                what: "PAN must match the IT-department format (5 letters · 4 digits · 1 letter)." },
  pan_format_category:           { title: "PAN holder category",             what: "The 4th character of a PAN encodes the holder type — must be a valid category code." },
  aadhaar_verhoeff:              { title: "Aadhaar Verhoeff checksum",       what: "Validates the 12-digit Aadhaar number using its built-in Verhoeff checksum." },
  name_pan_vs_aadhaar:           { title: "Name match · PAN ↔ Aadhaar",      what: "Names on PAN and Aadhaar should match (fuzzy ≥ 85%)." },
  dob_consistency:               { title: "Date of birth consistency",       what: "Date of birth must be identical across all ID documents." },
  itr_pan_matches_id:            { title: "ITR PAN matches ID PAN",          what: "PAN on the income-tax return must match the PAN card." },
  itr_ay_sanity:                 { title: "ITR assessment year",             what: "ITR should be from a recent assessment year." },
  payslip_period_coverage:       { title: "Payslip coverage",                what: "Last three months of payslips required." },
  bank_period_coverage:          { title: "Bank statement coverage",         what: "Last three months of bank statements required." },
  payslip_vs_bank_salary:        { title: "Payslip salary ↔ bank credit",    what: "Net salary on the payslip should land in the bank account each month." },
  employer_consistency:          { title: "Employer name consistency",       what: "Employer name should match across the employment letter and payslips." },
  employment_letter_recency:     { title: "Employment letter recency",       what: "Employment letter must be recent (≤ 90 days old)." },
  bank_holder_name_matches_id:   { title: "Bank account holder ↔ ID name",   what: "Bank account holder name must match the applicant's PAN/Aadhaar (fuzzy)." },
  credit_pan_matches_id:         { title: "Credit report PAN matches ID",    what: "PAN on the credit report should match the PAN card." },
  credit_score_threshold:        { title: "Credit score",                    what: "CIBIL / Experian score must clear the underwriting threshold." },
  income_consistency:            { title: "Income consistency",              what: "Annualised income should be consistent across payslips, bank credits and ITR." },
  address_consistency:           { title: "Address consistency",             what: "Addresses across documents should agree (fuzzy)." },
  ocr_quality:                   { title: "OCR text quality",                what: "OCR confidence should be high enough for reliable extraction." },


  name_matrix:           { title: "Cross-document name match",         what: "All sources of the applicant's name should agree (fuzzy ≥ 80%)." },
  pan_matrix:            { title: "Cross-document PAN match",          what: "PAN should be identical wherever it appears." },
  dob_matrix:            { title: "Cross-document DOB match",          what: "Date of birth should be identical across documents." },
  employer_matrix:       { title: "Cross-document employer match",     what: "Employer name should match across employment letter and payslips (fuzzy ≥ 90%)." },
  period_overlap:        { title: "Payslip ↔ bank period overlap",     what: "Every payslip month must have a matching bank statement." },
  payslip_bank_amount:   { title: "Payslip ↔ bank credit amount",      what: "For each month, payslip net salary should match the bank credit (within 5%)." },
  annualised_income_match: { title: "Annualised income consistency",   what: "Annualised payslip income, ITR and bank credits should agree (within 20%)." },
};

export function ruleTitle(name: string): string {
  return TITLES[name]?.title ?? prettify(name);
}

export function ruleDescription(name: string): string | null {
  return TITLES[name]?.what ?? null;
}

function prettify(s: string): string {
  return s.replace(/_/g, " ").replace(/\b\w/g, (c) => c.toUpperCase());
}


const SOURCE_LABEL: Record<string, string> = {
  declared:           "Declared by applicant",
  aadhaar:            "Aadhaar",
  pan:                "PAN card",
  itr:                "Income tax return",
  credit_report:      "Credit report",
  employment_letter:  "Employment letter",
  payslip_1:          "Payslip · month 1",
  payslip_2:          "Payslip · month 2",
  payslip_3:          "Payslip · month 3",
  bank_1:             "Bank statement · month 1",
  bank_2:             "Bank statement · month 2",
  bank_3:             "Bank statement · month 3",
  payslips:           "Payslips",
  bank_statements:    "Bank statements",
  bank:               "Bank statements",
};
export function sourceLabel(k: string): string {
  return SOURCE_LABEL[k] ?? prettify(k);
}


/**
 * Returns a single human-readable sentence summarising a rule's outcome.
 * Used in collapsed list views and rejection summaries.
 */
export function ruleHeadline(name: string, status: string, d: any): string {
  const t = (TITLES[name]?.title ?? prettify(name)).replace(/^[^a-zA-Z]+/, "");

  switch (name) {
    case "pan_format":
      return status === "pass"
        ? "PAN format is valid."
        : "PAN format is invalid (regex mismatch).";
    case "pan_format_category":
      return status === "pass"
        ? `4th character "${d?.fourth_char ?? "?"}" is a valid PAN category.`
        : `4th character "${d?.fourth_char ?? "?"}" is not a recognised PAN category.`;
    case "aadhaar_verhoeff":
      return status === "pass"
        ? `Aadhaar checksum verified (****${d?.last4 ?? "????"}).`
        : `Aadhaar checksum failed for ****${d?.last4 ?? "????"}.`;
    case "name_pan_vs_aadhaar":
      return `Name similarity ${pct(d?.score)} (threshold ${pct(d?.threshold ?? 85)}).`;
    case "dob_consistency": {
      const n = d?.dobs ? Object.keys(d.dobs).length : 0;
      return status === "pass"
        ? `Date of birth identical across ${n} sources.`
        : `Date of birth differs across ${n} sources.`;
    }
    case "itr_pan_matches_id":
      return status === "pass"
        ? "PAN on the ITR matches the PAN card."
        : `PAN mismatch — ID ends ****${d?.id_pan_last ?? "????"}, ITR ends ****${d?.itr_pan_last ?? "????"}.`;
    case "itr_ay_sanity":
      return status === "pass"
        ? `ITR assessment year ${d?.year ?? ""} is recent.`
        : `ITR assessment year ${d?.year ?? "?"} is too old (current ${d?.current_year ?? "?"}).`;
    case "payslip_period_coverage":
      return status === "pass"
        ? `${(Array.isArray(d?.months) ? d.months : []).length} consecutive months covered.`
        : `Missing months — found ${safeJoin(d?.found_months)}, required ${safeJoin(d?.required)}.`;
    case "bank_period_coverage":
      return status === "pass"
        ? `${(Array.isArray(d?.months) ? d.months : []).length} consecutive months covered.`
        : `Missing months — found ${safeJoin(d?.found_months)}, required ${safeJoin(d?.required)}.`;
    case "payslip_vs_bank_salary": {
      const rows = safeArr<any>(d?.per_month);
      const bad = rows.filter((r: any) => !r?.pass).length;
      if (status === "pass") return `All ${rows.length} months reconcile within tolerance.`;
      return `${bad} of ${rows.length} months do not reconcile within 5% tolerance.`;
    }
    case "employer_consistency":
      return `Employer-name similarity ${pct(d?.avg_score)} (threshold ${pct(d?.threshold ?? 90)}).`;
    case "employment_letter_recency":
      return status === "pass"
        ? `Letter is ${d?.age_days ?? "?"} days old (≤ ${d?.max_days ?? 90} required).`
        : `Letter is ${d?.age_days ?? "?"} days old, exceeds ${d?.max_days ?? 90}-day limit.`;
    case "bank_holder_name_matches_id":
      return `Bank holder ↔ ID name similarity ${pct(d?.avg_score)} (threshold ${pct(d?.threshold ?? 85)}).`;
    case "credit_pan_matches_id":
      return status === "pass"
        ? "PAN on credit report matches PAN card."
        : `PAN mismatch — ID ends ****${d?.id_pan_last ?? "????"}, credit report ends ****${d?.cr_pan_last ?? "????"}.`;
    case "credit_score_threshold":
      return status === "pass"
        ? `Credit score ${d?.score ?? "?"} (≥ ${d?.threshold ?? "?"} required).`
        : status === "warning"
          ? `Credit score ${d?.score ?? "?"} in caution band (${d?.band ?? ""}).`
          : `Credit score ${d?.score ?? "?"} below threshold ${d?.threshold ?? "?"}.`;
    case "income_consistency": {
      const probs = d?.problems ?? [];
      return status === "pass"
        ? "Annualised income from all sources agrees within tolerance."
        : `Income disagreement on ${probs.length} source${probs.length === 1 ? "" : "s"}.`;
    }
    case "address_consistency":
      return `Address similarity ${pct(d?.avg_score)} across ${(d?.sources ?? []).length} sources.`;
    case "ocr_quality":
      return status === "pass"
        ? "All documents OCRed with sufficient confidence."
        : `${(d?.low_confidence_docs ?? []).length} document(s) below confidence threshold.`;

    case "name_matrix": {
      return `Lowest pairwise name match ${pct(d?.min)} · average ${pct(d?.avg)}.`;
    }
    case "pan_matrix":
      return status === "pass"
        ? `Same PAN (****${d?.pan_last ?? "????"}) on every document.`
        : `Found ${d?.unique_count ?? "multiple"} different PANs across documents.`;
    case "dob_matrix":
      return status === "pass"
        ? `All sources agree on date of birth (${d?.dob ?? "?"}).`
        : `Date of birth differs across documents.`;
    case "employer_matrix":
      return `Lowest employer-name match ${pct(d?.min)} · average ${pct(d?.avg)}.`;
    case "period_overlap":
      return status === "pass"
        ? `Months covered: ${safeJoin(d?.months)}.`
        : `${safeArr(d?.payslip_months_missing_in_bank).length} payslip month(s) have no matching bank statement.`;
    case "payslip_bank_amount": {
      const rows = safeArr<any>(d?.per_month);
      const bad = rows.filter((r: any) => !r?.pass).length;
      if (status === "pass") return `All ${rows.length} months reconcile.`;
      return `${bad} of ${rows.length} months differ by more than 5%.`;
    }
    case "annualised_income_match": {
      const probs = safeArr<any>(d?.problems);
      return status === "pass"
        ? `Annualised income agrees: payslips ${inr(d?.annual_payslip)}, ITR ${inr(d?.annual_itr)}, bank ${inr(d?.annual_bank)}.`
        : `${probs.length} source${probs.length === 1 ? "" : "s"} disagree by more than 20%.`;
    }
  }


  if (status === "pass") return `${t}: passed.`;
  if (status === "fail") return `${t}: failed.`;
  return `${t}: warning.`;
}


/**
 * Renders the full structured breakdown — tables, side-by-side values, etc.
 * Falls back to a clean key/value grid for unknown rules.
 */
export function RuleDetails({ name, status, details }: {
  name: string; status: string; details: any;
}) {
  const d = details ?? {};


  if (name === "name_matrix" || name === "employer_matrix") {
    const pairs = d.pairs as { a: string; b: string; score: number }[] | undefined;
    return (
      <DetailWrap>
        <KV label="Average match" value={pct(d.avg)} />
        <KV label="Lowest match" value={pct(d.min)} tone={d.min < 80 ? "bad" : d.min < 90 ? "warn" : "good"} />
        {pairs && pairs.length > 0 && (
          <PairsTable
            pairs={pairs}
            renderName={sourceLabel}
            threshold={name === "employer_matrix" ? 90 : 80}
          />
        )}
      </DetailWrap>
    );
  }

  if (name === "dob_matrix" || name === "dob_consistency") {
    const map: Record<string, string> = d.dobs ?? (d.dob ? { all: d.dob } : {});
    return (
      <DetailWrap>
        <SourceValueGrid map={map} label="Date of birth" />
      </DetailWrap>
    );
  }

  if (name === "pan_matrix") {
    return (
      <DetailWrap>
        <KV label="PAN (last 4)" value={d.pan_last ? `****${d.pan_last}` : "—"} mono />
        {d.unique_count != null && <KV label="Unique PANs found" value={String(d.unique_count)} tone="bad" />}
      </DetailWrap>
    );
  }

  if (name === "payslip_vs_bank_salary" || name === "payslip_bank_amount") {
    const rows = (d.per_month ?? []) as { month: string; diff_pct: number; pass: boolean }[];
    return (
      <DetailWrap>
        {rows.length > 0 ? (
          <DataTable
            head={["Month", "Difference", "Status"]}
            rows={rows.map((r) => [
              <span className="font-mono">{r.month}</span>,
              <span className={`font-mono ${r.pass ? "text-emerald-700" : "text-red-700"} font-medium`}>
                {(r.diff_pct * 100).toFixed(1)}%
              </span>,
              r.pass
                ? <Tag tone="good">within 5%</Tag>
                : <Tag tone="bad">over 5%</Tag>,
            ])}
          />
        ) : <Empty>No reconcilable months were found.</Empty>}
      </DetailWrap>
    );
  }

  if (name === "annualised_income_match") {
    const probs = (d.problems ?? []) as { source: string; diff_pct: number }[];
    return (
      <DetailWrap>
        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2">
          <KV label="Payslips × 12" value={inr(d.annual_payslip)} mono />
          <KV label="ITR" value={inr(d.annual_itr)} mono />
          <KV label="Bank credits × 12" value={inr(d.annual_bank)} mono />
        </div>
        {probs.length > 0 && (
          <DataTable
            head={["Source", "Disagreement"]}
            rows={probs.map((p) => [
              <span>{sourceLabel(p.source)}</span>,
              <span className="font-mono text-red-700 font-medium">{(p.diff_pct * 100).toFixed(1)}% over 20% tolerance</span>,
            ])}
          />
        )}
      </DetailWrap>
    );
  }

  if (name === "income_consistency") {
    const probs = (d.problems ?? []) as { source: string; diff_pct?: number; reason?: string }[];
    return (
      <DetailWrap>
        {probs.length === 0
          ? <Empty>All income sources agreed.</Empty>
          : <DataTable
              head={["Source", "Issue"]}
              rows={probs.map((p) => [
                <span>{sourceLabel(p.source)}</span>,
                <span className="font-mono">{p.reason ?? `differs by ${((p.diff_pct ?? 0) * 100).toFixed(1)}%`}</span>,
              ])}
            />
        }
      </DetailWrap>
    );
  }

  if (name === "period_overlap") {
    const months = safeArr<string>(d.months);
    const missing = safeArr<string>(d.payslip_months_missing_in_bank);
    return (
      <DetailWrap>
        {months.length > 0 && <KV label="Months covered" value={months.join(", ")} mono />}
        {missing.length > 0 && (
          <div>
            <div className="text-xs font-semibold text-red-700 mb-1.5">Payslip months without a matching bank statement:</div>
            <div className="flex flex-wrap gap-1.5">
              {missing.map((m) => <span key={m} className="font-mono text-xs bg-red-50 text-red-700 border border-red-200 rounded px-2 py-0.5">{m}</span>)}
            </div>
          </div>
        )}
      </DetailWrap>
    );
  }

  if (name === "payslip_period_coverage" || name === "bank_period_coverage") {
    const found = safeArr<string>(d.months ?? d.found_months);
    const required = safeArr<string>(d.required);
    return (
      <DetailWrap>
        <KV label="Months found" value={found.length ? found.join(", ") : "—"} mono />
        {required.length > 0 && status !== "pass" && (
          <KV label="Months required" value={required.join(", ")} mono tone="bad" />
        )}
      </DetailWrap>
    );
  }

  if (name === "credit_score_threshold") {
    const isPass = status === "pass";
    return (
      <DetailWrap>
        <KV label="Score" value={String(d.score ?? "—")} mono tone={isPass ? "good" : status === "warning" ? "warn" : "bad"} />
        {d.threshold != null && <KV label="Required" value={`≥ ${d.threshold}`} mono />}
        {d.band != null && <KV label="Caution band" value={d.band} mono />}
      </DetailWrap>
    );
  }

  if (name === "name_pan_vs_aadhaar" || name === "bank_holder_name_matches_id" || name === "address_consistency" || name === "employer_consistency") {
    const score = d.score ?? d.avg_score;
    const threshold = d.threshold;
    return (
      <DetailWrap>
        <ScoreRow score={score} threshold={threshold} />
        {d.sources && Array.isArray(d.sources) && (
          <KV label="Compared sources" value={d.sources.map(sourceLabel).join(" · ")} />
        )}
      </DetailWrap>
    );
  }

  if (name === "employment_letter_recency") {
    return (
      <DetailWrap>
        {d.letter_date && <KV label="Letter date" value={d.letter_date} mono />}
        <KV label="Age" value={`${d.age_days ?? "?"} days`} mono tone={status === "pass" ? "good" : "bad"} />
        {d.max_days && <KV label="Maximum allowed" value={`${d.max_days} days`} mono />}
      </DetailWrap>
    );
  }

  if (name === "ocr_quality") {
    const low = (d.low_confidence_docs ?? []) as { doc_type: string; confidence?: number }[];
    return (
      <DetailWrap>
        {low.length === 0
          ? <Empty>All documents passed OCR confidence checks.</Empty>
          : <DataTable
              head={["Document", "Confidence"]}
              rows={low.map((x) => [
                <span>{prettify(x.doc_type)}</span>,
                <span className="font-mono text-amber-700">{x.confidence != null ? x.confidence.toFixed(2) : "—"}</span>,
              ])}
            />}
      </DetailWrap>
    );
  }

  if (name === "aadhaar_verhoeff") {
    return (
      <DetailWrap>
        <KV label="Aadhaar (last 4)" value={d.last4 ? `****${d.last4}` : "—"} mono />
        <KV label="Verhoeff checksum" value={status === "pass" ? "Valid" : "Invalid"} tone={status === "pass" ? "good" : "bad"} />
      </DetailWrap>
    );
  }

  if (name === "pan_format" || name === "pan_format_category") {
    return (
      <DetailWrap>
        {d.fourth_char && <KV label="Holder category code" value={d.fourth_char} mono />}
        {d.reason && <KV label="Reason" value={d.reason} tone="bad" />}
      </DetailWrap>
    );
  }

  if (name === "itr_pan_matches_id" || name === "credit_pan_matches_id") {
    return (
      <DetailWrap>
        {d.id_pan_last && <KV label="ID PAN" value={`****${d.id_pan_last}`} mono />}
        {d.itr_pan_last && <KV label="ITR PAN" value={`****${d.itr_pan_last}`} mono tone={status === "pass" ? "good" : "bad"} />}
        {d.cr_pan_last && <KV label="Credit report PAN" value={`****${d.cr_pan_last}`} mono tone={status === "pass" ? "good" : "bad"} />}
      </DetailWrap>
    );
  }

  if (name === "itr_ay_sanity") {
    return (
      <DetailWrap>
        {d.year && <KV label="Assessment year" value={String(d.year)} mono />}
        {d.current_year && <KV label="Current year" value={String(d.current_year)} mono />}
        {d.tax_year_raw && <KV label="Raw value" value={d.tax_year_raw} mono />}
      </DetailWrap>
    );
  }


  const entries = Object.entries(d).filter(([, v]) => v != null && (typeof v !== "object" || (Array.isArray(v) && v.length === 0) || Object.keys(v as any).length > 0));
  if (entries.length === 0) return null;
  return (
    <DetailWrap>
      <div className="grid grid-cols-1 sm:grid-cols-2 gap-2">
        {entries.map(([k, v]) => (
          <KV key={k} label={prettify(k)} value={fmtAny(v)} mono />
        ))}
      </div>
    </DetailWrap>
  );
}


function DetailWrap({ children }: { children: ReactNode }) {
  return <div className="space-y-3">{children}</div>;
}

function KV({ label, value, mono = false, tone }: {
  label: string; value: ReactNode; mono?: boolean; tone?: "good" | "warn" | "bad";
}) {
  const toneCls = tone === "good"
    ? "text-emerald-700"
    : tone === "warn" ? "text-amber-700"
    : tone === "bad" ? "text-red-700"
    : "text-black";
  return (
    <div className="bg-gray-50 border border-gray-200 rounded-lg px-3 py-2">
      <div className="text-[10.5px] font-semibold uppercase tracking-wider text-gray-500">{label}</div>
      <div className={`mt-0.5 text-sm font-semibold ${mono ? "font-mono" : ""} ${toneCls}`}>{value}</div>
    </div>
  );
}

function Tag({ tone, children }: { tone: "good" | "warn" | "bad" | "neutral"; children: ReactNode }) {
  const cls = tone === "good"
    ? "bg-emerald-50 text-emerald-700 border-emerald-200"
    : tone === "warn" ? "bg-amber-50 text-amber-800 border-amber-200"
    : tone === "bad" ? "bg-red-50 text-red-700 border-red-200"
    : "bg-gray-100 text-gray-700 border-gray-200";
  return <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-[11px] font-semibold border ${cls}`}>{children}</span>;
}

function Empty({ children }: { children: ReactNode }) {
  return <div className="text-xs text-gray-500 italic">{children}</div>;
}

function ScoreRow({ score, threshold }: { score: number | undefined; threshold?: number | undefined }) {
  if (score == null) return null;
  const passes = threshold == null ? true : score >= threshold;
  const pctVal = Math.max(0, Math.min(100, score));
  return (
    <div className="bg-gray-50 border border-gray-200 rounded-lg p-3">
      <div className="flex items-baseline justify-between mb-1.5">
        <span className="text-[11px] font-semibold uppercase tracking-wider text-gray-500">Match score</span>
        <span className={`font-mono text-base font-bold ${passes ? "text-emerald-700" : "text-red-700"}`}>{pct(score)}</span>
      </div>
      <div className="w-full h-1.5 rounded-full bg-gray-200 overflow-hidden">
        <div className={`h-full ${passes ? "bg-emerald-500" : "bg-red-500"}`} style={{ width: `${pctVal}%` }} />
      </div>
      {threshold != null && (
        <div className="mt-1.5 text-[11px] text-gray-500">Required ≥ <span className="font-mono font-semibold text-gray-700">{pct(threshold)}</span></div>
      )}
    </div>
  );
}

function SourceValueGrid({ map, label }: { map: Record<string, string>; label: string }) {
  const entries = Object.entries(map);
  const unique = new Set(entries.map(([, v]) => v));
  const allMatch = unique.size <= 1;
  return (
    <div>
      <div className="text-xs font-semibold text-gray-700 mb-1.5">{label} from each source:</div>
      <div className="rounded-lg border border-gray-200 overflow-hidden bg-white">
        {entries.map(([k, v], i) => (
          <div key={k} className={`flex items-center justify-between px-3 py-2 ${i > 0 ? "border-t border-gray-100" : ""}`}>
            <span className="text-sm text-gray-700">{sourceLabel(k)}</span>
            <span className={`font-mono text-sm font-semibold ${allMatch ? "text-emerald-700" : "text-red-700"}`}>{v}</span>
          </div>
        ))}
      </div>
    </div>
  );
}

function PairsTable({ pairs, renderName, threshold }: {
  pairs: { a: string; b: string; score: number }[];
  renderName: (k: string) => string;
  threshold: number;
}) {
  const sorted = [...pairs].sort((a, b) => a.score - b.score);
  return (
    <DataTable
      head={["Source A", "Source B", "Match"]}
      rows={sorted.map((p) => [
        <span>{renderName(p.a)}</span>,
        <span>{renderName(p.b)}</span>,
        <span className={`font-mono font-semibold ${p.score >= threshold ? "text-emerald-700" : p.score >= threshold - 15 ? "text-amber-700" : "text-red-700"}`}>
          {pct(p.score)}
        </span>,
      ])}
    />
  );
}

function DataTable({ head, rows }: { head: string[]; rows: ReactNode[][] }) {
  return (
    <div className="rounded-lg border border-gray-200 overflow-hidden bg-white">
      <table className="w-full text-sm">
        <thead className="bg-gray-50 border-b border-gray-200 text-[10.5px] uppercase tracking-wider text-gray-500">
          <tr>{head.map((h, i) => <th key={i} className="text-left px-3 py-2 font-semibold">{h}</th>)}</tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i} className={i > 0 ? "border-t border-gray-100" : ""}>
              {r.map((c, j) => <td key={j} className="px-3 py-2 text-sm">{c}</td>)}
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}


function pct(v: number | null | undefined): string {
  if (v == null || isNaN(v)) return "—";

  const n = v <= 1 ? v * 100 : v;
  return `${Math.round(n)}%`;
}

function inr(x: number | null | undefined): string {
  if (x == null) return "—";
  return `₹${Number(x).toLocaleString("en-IN", { maximumFractionDigits: 0 })}`;
}

function fmtAny(v: any): ReactNode {
  if (v == null) return "—";
  if (typeof v === "number") return Number.isInteger(v) ? String(v) : v.toFixed(3);
  if (typeof v === "string") return v;
  if (typeof v === "boolean") return v ? "Yes" : "No";
  if (Array.isArray(v)) return v.map((x) => typeof x === "string" || typeof x === "number" ? String(x) : JSON.stringify(x)).join(", ");
  return JSON.stringify(v);
}


export type DriverItem = { kind: "compliance" | "crossdoc" | "fraud"; name: string; status: string; severity?: string; score?: number; details: any };

export function rejectionDrivers(items: DriverItem[]): DriverItem[] {
  return items.filter((x) => {
    if (x.kind === "fraud") return (x.severity === "high") || (x.score ?? 0) >= 0.6;
    return x.status === "fail";
  });
}

export function warningDrivers(items: DriverItem[]): DriverItem[] {
  return items.filter((x) => {
    if (x.kind === "fraud") return x.severity === "med" || ((x.score ?? 0) >= 0.3 && (x.score ?? 0) < 0.6);
    return x.status === "warning";
  });
}
