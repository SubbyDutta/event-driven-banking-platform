from __future__ import annotations

import uuid
from datetime import datetime
from typing import Any, Literal

from pydantic import BaseModel, Field

class SubmitResponse(BaseModel):
    applicationId: uuid.UUID
    externalId: str
    useCase: str
    status: str
    documentsAccepted: int
    idempotentReplay: bool = False

class DocumentStatus(BaseModel):
    documentId: uuid.UUID
    docType: str
    originalFilename: str
    uploadedAt: datetime
    ocrDone: bool
    classifiedType: str | None
    classificationConfidence: float | None
    fieldsExtracted: int
    periodMonth: str | None

class CheckSummary(BaseModel):
    name: str
    status: str
    details: dict[str, Any]

class CrossDocSummary(BaseModel):
    ruleName: str
    status: str
    details: dict[str, Any]

class FraudSummary(BaseModel):
    signalName: str
    severity: str
    score: float
    details: dict[str, Any]

class OverrideSummary(BaseModel):
    id: uuid.UUID
    previousRecommendation: str
    newRecommendation: str
    reason: str
    actorOrg: str | None
    createdAt: datetime

class ApplicationDetail(BaseModel):
    applicationId: uuid.UUID
    externalId: str
    useCase: str
    applicantName: str
    email: str
    phone: str
    status: str
    submittedByOrg: str | None
    createdAt: datetime
    updatedAt: datetime
    documents: list[DocumentStatus]
    compliance: list[CheckSummary]
    crossDoc: list[CrossDocSummary]
    fraud: list[FraudSummary]
    hasReport: bool
    recommendation: str | None
    effectiveRecommendation: str | None
    overrides: list[OverrideSummary]

class ApplicationListItem(BaseModel):
    applicationId: uuid.UUID
    externalId: str
    useCase: str
    applicantName: str
    status: str
    recommendation: str | None
    effectiveRecommendation: str | None
    overallScore: float | None
    submittedByOrg: str | None
    createdAt: datetime

class ApplicationListPage(BaseModel):
    """Paginated wrapper around `ApplicationListItem` for the dashboard."""
    items: list[ApplicationListItem]
    page: int
    pageSize: int
    total: int
    totalPages: int

class OverrideRequest(BaseModel):
    newRecommendation: Literal["approve", "reject", "manual_review", "verified"]
    reason: str = Field(min_length=3, max_length=500)
    notify: bool = False

class ApiKeyCreateRequest(BaseModel):
    label: str = Field(min_length=1, max_length=128)
    org: str = Field(min_length=1, max_length=128)
    scopes: list[str] = Field(default_factory=list)
    rateLimitPerMin: int = Field(default=60, ge=1, le=10_000)

class ApiKeyCreateResponse(BaseModel):
    id: uuid.UUID
    label: str
    org: str
    scopes: list[str]
    rateLimitPerMin: int
    key: str

class ApiKeyListItem(BaseModel):
    id: uuid.UUID
    label: str
    org: str
    scopes: list[str]
    rateLimitPerMin: int
    createdAt: datetime
    revokedAt: datetime | None
    lastUsedAt: datetime | None

class MeResponse(BaseModel):
    apiKeyId: uuid.UUID
    label: str
    org: str

class HealthResponse(BaseModel):
    status: str = "ok"
    version: str = "0.2.0"

class AdminApplicationListItem(BaseModel):
    applicationId: uuid.UUID
    externalId: str
    useCase: str
    applicantName: str
    email: str
    phone: str
    status: str
    recommendation: str | None
    effectiveRecommendation: str | None
    overallScore: float | None
    callerOrg: str | None
    createdAt: datetime
    decidedAt: datetime | None

class AdminPage(BaseModel):
    items: list[AdminApplicationListItem]
    page: int
    pageSize: int
    total: int

class PipelineEventItem(BaseModel):
    id: uuid.UUID
    stepName: str
    stepStatus: str
    documentId: uuid.UUID | None
    startedAt: datetime | None
    completedAt: datetime | None
    durationMs: int | None
    details: dict[str, Any]
    createdAt: datetime

class TimelinePage(BaseModel):
    """Paginated wrapper around `PipelineEventItem` for the admin timeline."""
    items: list[PipelineEventItem]
    page: int
    pageSize: int
    total: int
    totalPages: int

class AuditLogItem(BaseModel):
    id: str
    timestamp: datetime
    actor: str | None
    action: Literal["decision_override", "field_override", "pipeline_run"]
    applicationId: uuid.UUID
    before: dict[str, Any] | None
    after: dict[str, Any] | None
    reason: str | None

class AuditLogPage(BaseModel):
    items: list[AuditLogItem]
    page: int
    pageSize: int
    total: int
    totalPages: int

class PresignedUrlResponse(BaseModel):
    url: str
    expiresInSeconds: int

class StatsResponse(BaseModel):
    applicationsToday: int
    approvalRate: float | None
    avgProcessingMs: int | None
    byUseCase: dict[str, int]
    byStatus: dict[str, int]

class AdminOverrideRequest(BaseModel):
    decision: Literal["approve", "reject", "manual_review"]
    reason: str = Field(min_length=3, max_length=500)
    overriddenBy: str = Field(min_length=1, max_length=128)
    notify: bool = True

class AdminOverrideResponse(BaseModel):
    applicationId: uuid.UUID
    previousDecision: str
    newDecision: str
    overrideId: uuid.UUID
