const KEY_STORAGE = "findoc.apiKey";

export function getApiKey(): string | null {
  return localStorage.getItem(KEY_STORAGE);
}
export function setApiKey(key: string) {
  localStorage.setItem(KEY_STORAGE, key);
}
export function clearApiKey() {
  localStorage.removeItem(KEY_STORAGE);
}

async function request(path: string, init: RequestInit = {}): Promise<Response> {
  const headers = new Headers(init.headers || {});
  const key = getApiKey();
  if (key) headers.set("X-API-Key", key);
  if (!(init.body instanceof FormData) && init.body !== undefined) {
    if (!headers.has("Content-Type")) headers.set("Content-Type", "application/json");
  }
  return fetch(path, { ...init, headers });
}

export async function apiGet<T>(path: string): Promise<T> {
  const r = await request(path, { method: "GET" });
  if (!r.ok) throw new ApiError(r.status, await r.text());
  return r.json() as Promise<T>;
}

export async function apiPostJson<T>(path: string, body: unknown): Promise<T> {
  const r = await request(path, { method: "POST", body: JSON.stringify(body) });
  if (!r.ok) throw new ApiError(r.status, await r.text());
  return r.json() as Promise<T>;
}

export async function apiPatchJson<T>(path: string, body: unknown): Promise<T> {
  const r = await request(path, { method: "PATCH", body: JSON.stringify(body) });
  if (!r.ok) throw new ApiError(r.status, await r.text());
  return r.json() as Promise<T>;
}

export async function apiPostForm<T>(path: string, form: FormData): Promise<T> {
  const r = await request(path, { method: "POST", body: form });
  if (!r.ok) {
    let msg: string;
    try { const j = await r.json(); msg = JSON.stringify(j); } catch { msg = await r.text(); }
    throw new ApiError(r.status, msg);
  }
  return r.json() as Promise<T>;
}

export async function apiDelete(path: string): Promise<void> {
  const r = await request(path, { method: "DELETE" });
  if (!r.ok) throw new ApiError(r.status, await r.text());
}

export class ApiError extends Error {
  status: number;
  body: string;
  constructor(status: number, body: string) {
    super(`HTTP ${status}: ${body.slice(0, 200)}`);
    this.status = status;
    this.body = body;
  }
}

export type Me = { apiKeyId: string; label: string; org: string };

export type AppListItem = {
  applicationId: string;
  externalId: string;
  useCase: "kyc" | "loan";
  applicantName: string;
  status: string;
  recommendation: string | null;
  effectiveRecommendation: string | null;
  overallScore: number | null;
  submittedByOrg: string | null;
  createdAt: string;
};

export type AppListPage = {
  items: AppListItem[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
};

export type DocStatus = {
  documentId: string;
  docType: string;
  originalFilename: string;
  uploadedAt: string;
  ocrDone: boolean;
  classifiedType: string | null;
  classificationConfidence: number | null;
  fieldsExtracted: number;
  periodMonth: string | null;
};

export type CheckRow = { name: string; status: string; details: Record<string, unknown> };
export type CrossDocRow = { ruleName: string; status: string; details: Record<string, unknown> };
export type FraudRow = { signalName: string; severity: string; score: number; details: Record<string, unknown> };
export type OverrideRow = {
  id: string;
  previousRecommendation: string;
  newRecommendation: string;
  reason: string;
  actorOrg: string | null;
  createdAt: string;
};

export type AppDetail = {
  applicationId: string;
  externalId: string;
  useCase: "kyc" | "loan";
  applicantName: string;
  email: string;
  phone: string;
  status: string;
  submittedByOrg: string | null;
  createdAt: string;
  updatedAt: string;
  documents: DocStatus[];
  compliance: CheckRow[];
  crossDoc: CrossDocRow[];
  fraud: FraudRow[];
  hasReport: boolean;
  recommendation: string | null;
  effectiveRecommendation: string | null;
  overrides: OverrideRow[];
};

export type ApiKeyRow = {
  id: string;
  label: string;
  org: string;
  createdAt: string;
  revokedAt: string | null;
  lastUsedAt: string | null;
};

export type AuditAction = "decision_override" | "field_override" | "pipeline_run";

export type AuditLogItem = {
  id: string;
  timestamp: string;
  actor: string | null;
  action: AuditAction;
  applicationId: string;
  before: Record<string, unknown> | null;
  after: Record<string, unknown> | null;
  reason: string | null;
};

export type AuditLogPage = {
  items: AuditLogItem[];
  page: number;
  pageSize: number;
  total: number;
  totalPages: number;
};

export type AuditLogFilters = {
  applicationId?: string;
  actor?: string;
  action?: AuditAction;
  fromDate?: string;
  toDate?: string;
};

export const api = {
  me: () => apiGet<Me>("/api/v1/admin/me"),
  listApps: (
    useCase?: "kyc" | "loan",
    page: number = 0,
    pageSize: number = 25,
  ) => {
    const params = new URLSearchParams({ page: String(page), page_size: String(pageSize) });
    if (useCase) params.set("use_case", useCase);
    return apiGet<AppListPage>(`/api/v1/applications?${params.toString()}`);
  },
  getApp: (id: string) => apiGet<AppDetail>(`/api/v1/applications/${id}`),
  getReport: (id: string) => apiGet<any>(`/api/v1/applications/${id}/report`),
  getDocDetails: (appId: string, docId: string) =>
    apiGet<any>(`/api/v1/applications/${appId}/documents/${docId}/details`),
  getDocDownloadUrl: (appId: string, docId: string) =>
    apiGet<{ url: string; expiresInSeconds: number }>(`/api/v1/applications/${appId}/documents/${docId}/download`),
  getDocFile: async (appId: string, docId: string): Promise<Blob> => {
    const r = await request(`/api/v1/applications/${appId}/documents/${docId}/file`, { method: "GET" });
    if (!r.ok) throw new ApiError(r.status, await r.text());
    return r.blob();
  },
  submitKyc: (fd: FormData) => apiPostForm<{applicationId:string;externalId:string;useCase:string;status:string;documentsAccepted:number}>(`/api/v1/kyc/submit`, fd),
  submitLoan: (fd: FormData) => apiPostForm<{applicationId:string;externalId:string;useCase:string;status:string;documentsAccepted:number;idempotentReplay?:boolean}>(`/api/v1/loan-origination/submit`, fd),
  override: (id: string, newRecommendation: string, reason: string) =>
    apiPostJson<OverrideRow>(`/api/v1/applications/${id}/override`, { newRecommendation, reason, notify: true }),
  replay: (id: string, reason: string) =>
    apiPostJson<{ runId: number; runNumber: number; appliedOverrides: number }>(
      `/api/v1/admin/applications/${id}/replay`, { reason }),
  patchExtractedField: (id: string, field: string, newValue: string, reason: string, documentId: string) =>
    apiPatchJson<{ id: number; field: string; appliedToRunId: number | null }>(
      `/api/v1/admin/applications/${id}/extracted-fields`,
      { field, newValue, reason, documentId }),
  listKeys: () => apiGet<ApiKeyRow[]>("/api/v1/admin/apikeys"),
  createKey: (label: string, org: string) =>
    apiPostJson<{ id: string; label: string; org: string; key: string }>(`/api/v1/admin/apikeys`, { label, org }),
  revokeKey: (id: string) => apiDelete(`/api/v1/admin/apikeys/${id}`),
  fetchAuditLog: (filters: AuditLogFilters, page: number, size: number) => {
    const p = new URLSearchParams({ page: String(page), size: String(size) });
    if (filters.applicationId) p.set("applicationId", filters.applicationId);
    if (filters.actor) p.set("actor", filters.actor);
    if (filters.action) p.set("action", filters.action);
    if (filters.fromDate) p.set("fromDate", filters.fromDate);
    if (filters.toDate) p.set("toDate", filters.toDate);
    return apiGet<AuditLogPage>(`/api/v1/admin/audit-log?${p.toString()}`);
  },
};
