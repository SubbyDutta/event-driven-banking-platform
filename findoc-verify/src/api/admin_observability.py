"""Admin observability endpoints."""
from __future__ import annotations

import uuid
from datetime import date, datetime, time, timezone
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Query, status
from sqlalchemy import and_, func, select
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.schemas import (
    AdminApplicationListItem,
    AdminOverrideRequest,
    AdminOverrideResponse,
    AdminPage,
    ApplicationDetail,
    AuditLogItem,
    AuditLogPage,
    CheckSummary,
    CrossDocSummary,
    DocumentStatus,
    FraudSummary,
    OverrideSummary,
    PipelineEventItem,
    PresignedUrlResponse,
    StatsResponse,
    TimelinePage,
)
from pydantic import BaseModel

from src.auth import SCOPE_ADMIN, SCOPE_ADMIN_GLOBAL, AuthContext, require_scope
from src.db.models import (
    ApiKey,
    Application,
    ApplicationStatus,
    ComplianceCheck,
    CrossDocValidation,
    DecisionOverride,
    DocClassification,
    Document,
    ExtractedField,
    ExtractedFieldOverride,
    FraudResult,
    OcrResult,
    PipelineEvent,
    PipelineRun,
    PolicyThreshold,
    Recommendation,
    UseCase,
    VerificationReport,
)
from src.db.session import get_session
from src.logging_config import get_logger
from src.messaging.sns_publisher import get_publisher
from src.messaging.topics import EVT_APPLICATION_AGGREGATED, TOPIC_APPLICATION_AGGREGATED
from src.policy.thresholds import get_store
from src.storage.s3_storage import get_storage

logger = get_logger(__name__)

router = APIRouter(prefix="/api/v1/admin", tags=["admin-observability"])

_REC_MAP = {
    "approve": Recommendation.approve,
    "reject": Recommendation.reject,
    "manual_review": Recommendation.manual_review,
}
_STATUS_MAP = {
    Recommendation.approve: ApplicationStatus.approved,
    Recommendation.verified: ApplicationStatus.approved,
    Recommendation.reject: ApplicationStatus.rejected,
    Recommendation.manual_review: ApplicationStatus.needs_review,
}

async def _visible_api_key_ids(session: AsyncSession, auth: AuthContext) -> list[uuid.UUID] | None:
    if auth.has_scope(SCOPE_ADMIN_GLOBAL):
        return None
    rows = (
        await session.execute(select(ApiKey.id).where(ApiKey.org_name == auth.org_name))
    ).all()
    return [r[0] for r in rows]

@router.get("/applications", response_model=AdminPage)
async def list_applications(
    external_id: str | None = Query(default=None, alias="external_id"),
    use_case: str | None = Query(default=None, alias="use_case"),
    app_status: str | None = Query(default=None, alias="status"),
    from_date: date | None = Query(default=None, alias="from_date"),
    to_date: date | None = Query(default=None, alias="to_date"),
    page: int = Query(default=1, ge=1),
    page_size: int = Query(default=50, ge=1, le=500),
    auth: AuthContext = Depends(require_scope(SCOPE_ADMIN)),
    session: AsyncSession = Depends(get_session),
) -> AdminPage:
    filters = []
    visible = await _visible_api_key_ids(session, auth)
    if visible is not None:
        if not visible:
            return AdminPage(items=[], page=page, pageSize=page_size, total=0)
        filters.append(Application.submitted_by_api_key_id.in_(visible))
    if external_id:
        filters.append(Application.external_id == external_id)
    if use_case in ("kyc", "loan"):
        filters.append(Application.use_case == UseCase(use_case))
    if app_status:
        try:
            filters.append(Application.status == ApplicationStatus(app_status))
        except ValueError:
            raise HTTPException(status_code=400, detail=f"Invalid status: {app_status}")
    if from_date:
        filters.append(Application.created_at >= datetime.combine(from_date, time.min, tzinfo=timezone.utc))
    if to_date:
        filters.append(Application.created_at < datetime.combine(to_date, time.max, tzinfo=timezone.utc))

    where = and_(*filters) if filters else None
    total_stmt = select(func.count(Application.id))
    if where is not None:
        total_stmt = total_stmt.where(where)
    total = int((await session.execute(total_stmt)).scalar() or 0)

    stmt = select(Application)
    if where is not None:
        stmt = stmt.where(where)
    stmt = stmt.order_by(Application.created_at.desc()).limit(page_size).offset((page - 1) * page_size)
    apps = list((await session.execute(stmt)).scalars())
    if not apps:
        return AdminPage(items=[], page=page, pageSize=page_size, total=total)

    app_ids = [a.id for a in apps]
    reports = {
        r.application_id: r
        for r in (await session.execute(
            select(VerificationReport).where(VerificationReport.application_id.in_(app_ids))
        )).scalars()
    }
    latest_override_by_app: dict[uuid.UUID, DecisionOverride] = {}
    for o in (await session.execute(
        select(DecisionOverride)
        .where(DecisionOverride.application_id.in_(app_ids))
        .order_by(DecisionOverride.created_at.desc())
    )).scalars():
        latest_override_by_app.setdefault(o.application_id, o)
    key_ids = {a.submitted_by_api_key_id for a in apps if a.submitted_by_api_key_id}
    orgs: dict[uuid.UUID, str] = {}
    if key_ids:
        for k in (await session.execute(select(ApiKey).where(ApiKey.id.in_(key_ids)))).scalars():
            orgs[k.id] = k.org_name

    items: list[AdminApplicationListItem] = []
    for a in apps:
        r = reports.get(a.id)
        ov = latest_override_by_app.get(a.id)
        effective = ov.new_recommendation.value if ov else (r.recommendation.value if r else None)
        items.append(AdminApplicationListItem(
            applicationId=a.id, externalId=a.external_id, useCase=a.use_case.value,
            applicantName=a.applicant_name, email=a.email, phone=a.phone,
            status=a.status.value,
            recommendation=r.recommendation.value if r else None,
            effectiveRecommendation=effective,
            overallScore=r.overall_score if r else None,
            callerOrg=orgs.get(a.submitted_by_api_key_id) if a.submitted_by_api_key_id else None,
            createdAt=a.created_at,
            decidedAt=r.created_at if r else None,
        ))
    return AdminPage(items=items, page=page, pageSize=page_size, total=total)

async def _load_admin_app(session: AsyncSession, app_id: uuid.UUID, auth: AuthContext) -> Application:
    app = (
        await session.execute(select(Application).where(Application.id == app_id))
    ).scalar_one_or_none()
    if not app:
        raise HTTPException(status_code=404, detail="Application not found")
    visible = await _visible_api_key_ids(session, auth)
    if visible is not None and app.submitted_by_api_key_id not in set(visible):
        raise HTTPException(status_code=403, detail="Application belongs to another org")
    return app

@router.get("/applications/{application_id}", response_model=ApplicationDetail)
async def get_application(
    application_id: uuid.UUID,
    auth: AuthContext = Depends(require_scope(SCOPE_ADMIN)),
    session: AsyncSession = Depends(get_session),
) -> ApplicationDetail:
    app = await _load_admin_app(session, application_id, auth)

    submitter_org = None
    if app.submitted_by_api_key_id:
        k = (await session.execute(select(ApiKey).where(ApiKey.id == app.submitted_by_api_key_id))).scalar_one_or_none()
        submitter_org = k.org_name if k else None

    docs = list(
        (await session.execute(select(Document).where(Document.application_id == application_id))).scalars()
    )
    doc_ids = [d.id for d in docs]
    ocr: dict[uuid.UUID, OcrResult] = {}
    cls: dict[uuid.UUID, DocClassification] = {}
    fields_count: dict[uuid.UUID, int] = {}
    if doc_ids:
        for r in (await session.execute(select(OcrResult).where(OcrResult.document_id.in_(doc_ids)))).scalars():
            ocr[r.document_id] = r
        for r in (await session.execute(select(DocClassification).where(DocClassification.document_id.in_(doc_ids)))).scalars():
            cls[r.document_id] = r
        for f in (await session.execute(select(ExtractedField).where(ExtractedField.document_id.in_(doc_ids)))).scalars():
            fields_count[f.document_id] = fields_count.get(f.document_id, 0) + 1

    compliance = list(
        (await session.execute(select(ComplianceCheck).where(ComplianceCheck.application_id == application_id))).scalars()
    )
    crossdoc = list(
        (await session.execute(select(CrossDocValidation).where(CrossDocValidation.application_id == application_id))).scalars()
    )
    fraud = list(
        (await session.execute(select(FraudResult).where(FraudResult.application_id == application_id))).scalars()
    )
    report = (
        await session.execute(select(VerificationReport).where(VerificationReport.application_id == application_id))
    ).scalar_one_or_none()
    overrides = list(
        (await session.execute(
            select(DecisionOverride)
            .where(DecisionOverride.application_id == application_id)
            .order_by(DecisionOverride.created_at.desc())
        )).scalars()
    )

    effective = None
    if overrides:
        effective = overrides[0].new_recommendation.value
    elif report:
        effective = report.recommendation.value

    return ApplicationDetail(
        applicationId=app.id,
        externalId=app.external_id,
        useCase=app.use_case.value,
        applicantName=app.applicant_name,
        email=app.email,
        phone=app.phone,
        status=app.status.value,
        submittedByOrg=submitter_org,
        createdAt=app.created_at,
        updatedAt=app.updated_at,
        documents=[
            DocumentStatus(
                documentId=d.id, docType=d.doc_type.value, originalFilename=d.original_filename,
                uploadedAt=d.uploaded_at, ocrDone=d.id in ocr,
                classifiedType=cls[d.id].classified_type if d.id in cls else None,
                classificationConfidence=cls[d.id].confidence if d.id in cls else None,
                fieldsExtracted=fields_count.get(d.id, 0),
                periodMonth=d.period_month.isoformat() if d.period_month else None,
            )
            for d in docs
        ],
        compliance=[CheckSummary(name=c.check_name, status=c.status.value, details=c.details) for c in compliance],
        crossDoc=[CrossDocSummary(ruleName=c.rule_name, status=c.status.value, details=c.details) for c in crossdoc],
        fraud=[FraudSummary(signalName=f.signal_name, severity=f.severity.value, score=f.score, details=f.details) for f in fraud],
        hasReport=report is not None,
        recommendation=report.recommendation.value if report else None,
        effectiveRecommendation=effective,
        overrides=[
            OverrideSummary(
                id=o.id,
                previousRecommendation=o.previous_recommendation.value,
                newRecommendation=o.new_recommendation.value,
                reason=o.reason,
                actorOrg=o.actor_org,
                createdAt=o.created_at,
            )
            for o in overrides
        ],
    )

@router.get("/applications/{application_id}/timeline", response_model=TimelinePage)
async def get_timeline(
    application_id: uuid.UUID,
    page: int = Query(default=0, ge=0),
    size: int = Query(default=50, ge=1, le=200),
    auth: AuthContext = Depends(require_scope(SCOPE_ADMIN)),
    session: AsyncSession = Depends(get_session),
) -> TimelinePage:
    await _load_admin_app(session, application_id, auth)

    total = int((await session.execute(
        select(func.count(PipelineEvent.id)).where(PipelineEvent.application_id == application_id)
    )).scalar() or 0)

    rows = list(
        (await session.execute(
            select(PipelineEvent)
            .where(PipelineEvent.application_id == application_id)
            .order_by(PipelineEvent.created_at.asc())
            .offset(page * size)
            .limit(size)
        )).scalars()
    )
    items = [
        PipelineEventItem(
            id=r.id, stepName=r.step_name, stepStatus=r.step_status, documentId=r.document_id,
            startedAt=r.started_at, completedAt=r.completed_at, durationMs=r.duration_ms,
            details=r.details or {}, createdAt=r.created_at,
        )
        for r in rows
    ]
    total_pages = max(1, (total + size - 1) // size) if total else 1
    return TimelinePage(items=items, page=page, pageSize=size, total=total, totalPages=total_pages)

_ALLOWED_AUDIT_ACTIONS = {"decision_override", "field_override", "pipeline_run"}


@router.get("/audit-log", response_model=AuditLogPage)
async def list_audit_log(
    applicationId: uuid.UUID | None = Query(default=None),
    actor: str | None = Query(default=None),
    action: str | None = Query(default=None),
    fromDate: datetime | None = Query(default=None),
    toDate: datetime | None = Query(default=None),
    page: int = Query(default=0, ge=0),
    size: int = Query(default=20, ge=1, le=100),
    auth: AuthContext = Depends(require_scope(SCOPE_ADMIN)),
    session: AsyncSession = Depends(get_session),
) -> AuditLogPage:
    if action and action not in _ALLOWED_AUDIT_ACTIONS:
        raise HTTPException(status_code=400, detail=f"Invalid action: {action}")

    visible = await _visible_api_key_ids(session, auth)
    if visible is not None and not visible:
        return AuditLogPage(items=[], page=page, pageSize=size, total=0, totalPages=1)

    visible_app_ids: set[uuid.UUID] | None = None
    if visible is not None:
        rows = (await session.execute(
            select(Application.id).where(Application.submitted_by_api_key_id.in_(visible))
        )).all()
        visible_app_ids = {r[0] for r in rows}
        if not visible_app_ids:
            return AuditLogPage(items=[], page=page, pageSize=size, total=0, totalPages=1)

    collected: list[AuditLogItem] = []

    want_decision = action in (None, "decision_override")
    want_field = action in (None, "field_override")
    want_run = action in (None, "pipeline_run")

    if want_decision:
        stmt = select(DecisionOverride)
        conds = []
        if applicationId is not None:
            conds.append(DecisionOverride.application_id == applicationId)
        if visible_app_ids is not None:
            conds.append(DecisionOverride.application_id.in_(visible_app_ids))
        if fromDate is not None:
            conds.append(DecisionOverride.created_at >= fromDate)
        if toDate is not None:
            conds.append(DecisionOverride.created_at <= toDate)
        if actor:
            conds.append(DecisionOverride.actor_org.ilike(f"%{actor}%"))
        if conds:
            stmt = stmt.where(and_(*conds))
        for o in (await session.execute(stmt)).scalars():
            collected.append(AuditLogItem(
                id=f"decision_override:{o.id}",
                timestamp=o.created_at,
                actor=o.actor_org,
                action="decision_override",
                applicationId=o.application_id,
                before={"decision": o.previous_recommendation.value},
                after={"decision": o.new_recommendation.value},
                reason=o.reason,
            ))

    if want_field:
        stmt = select(ExtractedFieldOverride)
        conds = []
        if applicationId is not None:
            conds.append(ExtractedFieldOverride.application_id == applicationId)
        if visible_app_ids is not None:
            conds.append(ExtractedFieldOverride.application_id.in_(visible_app_ids))
        if fromDate is not None:
            conds.append(ExtractedFieldOverride.edited_at >= fromDate)
        if toDate is not None:
            conds.append(ExtractedFieldOverride.edited_at <= toDate)
        if actor:
            conds.append(ExtractedFieldOverride.edited_by.ilike(f"%{actor}%"))
        if conds:
            stmt = stmt.where(and_(*conds))
        for f in (await session.execute(stmt)).scalars():
            collected.append(AuditLogItem(
                id=f"field_override:{f.id}",
                timestamp=f.edited_at,
                actor=f.edited_by,
                action="field_override",
                applicationId=f.application_id,
                before={"field": f.field_name, "value": f.original_value},
                after={"field": f.field_name, "value": f.new_value},
                reason=f.reason,
            ))

    if want_run:
        stmt = select(PipelineRun)
        conds = []
        if applicationId is not None:
            conds.append(PipelineRun.application_id == applicationId)
        if visible_app_ids is not None:
            conds.append(PipelineRun.application_id.in_(visible_app_ids))
        if fromDate is not None:
            conds.append(PipelineRun.started_at >= fromDate)
        if toDate is not None:
            conds.append(PipelineRun.started_at <= toDate)
        if actor:
            conds.append(PipelineRun.triggered_by.ilike(f"%{actor}%"))
        if conds:
            stmt = stmt.where(and_(*conds))
        for r in (await session.execute(stmt)).scalars():
            collected.append(AuditLogItem(
                id=f"pipeline_run:{r.id}",
                timestamp=r.started_at,
                actor=r.triggered_by,
                action="pipeline_run",
                applicationId=r.application_id,
                before=None,
                after={"runNumber": r.run_number, "triggerKind": "replay" if r.run_number > 1 else "initial"},
                reason=r.reason,
            ))

    collected.sort(key=lambda i: i.timestamp, reverse=True)
    total = len(collected)
    start = page * size
    end = start + size
    items = collected[start:end]
    total_pages = max(1, (total + size - 1) // size) if total else 1
    return AuditLogPage(items=items, page=page, pageSize=size, total=total, totalPages=total_pages)


@router.get(
    "/applications/{application_id}/documents/{document_id}/presigned-url",
    response_model=PresignedUrlResponse,
)
async def presigned_url(
    application_id: uuid.UUID,
    document_id: uuid.UUID,
    auth: AuthContext = Depends(require_scope(SCOPE_ADMIN)),
    session: AsyncSession = Depends(get_session),
) -> PresignedUrlResponse:
    await _load_admin_app(session, application_id, auth)
    doc = (
        await session.execute(
            select(Document).where(Document.id == document_id, Document.application_id == application_id)
        )
    ).scalar_one_or_none()
    if not doc:
        raise HTTPException(status_code=404, detail="Document not found")
    url = get_storage().get_presigned_url(doc.file_key, expires=300)
    return PresignedUrlResponse(url=url, expiresInSeconds=300)

@router.get("/stats", response_model=StatsResponse)
async def stats(
    auth: AuthContext = Depends(require_scope(SCOPE_ADMIN)),
    session: AsyncSession = Depends(get_session),
) -> StatsResponse:
    visible = await _visible_api_key_ids(session, auth)
    base_filter = []
    if visible is not None:
        if not visible:
            return StatsResponse(
                applicationsToday=0, approvalRate=None, avgProcessingMs=None,
                byUseCase={}, byStatus={},
            )
        base_filter.append(Application.submitted_by_api_key_id.in_(visible))

    today_start = datetime.combine(date.today(), time.min, tzinfo=timezone.utc)
    today_filter = base_filter + [Application.created_at >= today_start]

    apps_today = int((await session.execute(
        select(func.count(Application.id)).where(and_(*today_filter))
        if today_filter else select(func.count(Application.id)).where(Application.created_at >= today_start)
    )).scalar() or 0)

    def _where(extra: list | None = None):
        where = list(base_filter) + (extra or [])
        return and_(*where) if where else None

    use_case_counts_rows = (await session.execute(
        select(Application.use_case, func.count(Application.id)).where(_where()).group_by(Application.use_case)
        if base_filter else select(Application.use_case, func.count(Application.id)).group_by(Application.use_case)
    )).all()
    by_use_case = {uc.value if hasattr(uc, "value") else str(uc): int(cnt) for uc, cnt in use_case_counts_rows}

    status_counts_rows = (await session.execute(
        select(Application.status, func.count(Application.id)).where(_where()).group_by(Application.status)
        if base_filter else select(Application.status, func.count(Application.id)).group_by(Application.status)
    )).all()
    by_status = {s.value if hasattr(s, "value") else str(s): int(cnt) for s, cnt in status_counts_rows}

    approved = by_status.get("approved", 0)
    decided = by_status.get("approved", 0) + by_status.get("rejected", 0)
    approval_rate = round(approved / decided, 4) if decided else None

    proc_stmt = (
        select(func.avg(
            func.extract("epoch", VerificationReport.created_at) - func.extract("epoch", Application.created_at)
        ))
        .select_from(VerificationReport)
        .join(Application, Application.id == VerificationReport.application_id)
    )
    if base_filter:
        proc_stmt = proc_stmt.where(and_(*base_filter))
    avg_secs = (await session.execute(proc_stmt)).scalar()
    avg_ms = int(float(avg_secs) * 1000) if avg_secs is not None else None

    return StatsResponse(
        applicationsToday=apps_today,
        approvalRate=approval_rate,
        avgProcessingMs=avg_ms,
        byUseCase=by_use_case,
        byStatus=by_status,
    )

@router.post(
    "/applications/{application_id}/override",
    response_model=AdminOverrideResponse,
    status_code=status.HTTP_201_CREATED,
)
async def admin_override(
    application_id: uuid.UUID,
    body: AdminOverrideRequest,
    auth: AuthContext = Depends(require_scope(SCOPE_ADMIN)),
    session: AsyncSession = Depends(get_session),
) -> AdminOverrideResponse:
    app = await _load_admin_app(session, application_id, auth)
    report = (
        await session.execute(select(VerificationReport).where(VerificationReport.application_id == application_id))
    ).scalar_one_or_none()
    if not report:
        raise HTTPException(status_code=409, detail="Cannot override — no report yet")

    new = _REC_MAP[body.decision]
    if app.use_case.value == "kyc" and new == Recommendation.approve:
        new = Recommendation.verified

    override = DecisionOverride(
        id=uuid.uuid4(),
        application_id=application_id,
        previous_recommendation=report.recommendation,
        new_recommendation=new,
        reason=body.reason,
        actor_api_key_id=auth.api_key_id,
        actor_org=body.overriddenBy or auth.org_name,
    )
    session.add(override)
    app.status = _STATUS_MAP[new]
    await session.commit()
    await session.refresh(override)

    if body.notify:
        from src.workers.result_publisher import publish_report
        try:
            await publish_report(application_id, override=override)
        except Exception:
            logger.exception("admin override republish failed",
                             extra={"application_id": str(application_id)})

    return AdminOverrideResponse(
        applicationId=application_id,
        previousDecision=override.previous_recommendation.value,
        newDecision=override.new_recommendation.value,
        overrideId=override.id,
    )

class ThresholdValue(BaseModel):
    key: str
    value: float

class ThresholdUpdate(BaseModel):
    value: float
    reason: str

@router.get("/policy/thresholds", response_model=list[ThresholdValue])
async def list_thresholds(
    auth: AuthContext = Depends(require_scope(SCOPE_ADMIN)),
    session: AsyncSession = Depends(get_session),
) -> list[ThresholdValue]:
    rows = list((await session.execute(select(PolicyThreshold).order_by(PolicyThreshold.key))).scalars())
    return [ThresholdValue(key=r.key, value=r.value) for r in rows]

@router.put("/policy/thresholds/{key}", response_model=ThresholdValue)
async def update_threshold(
    key: str,
    body: ThresholdUpdate,
    auth: AuthContext = Depends(require_scope(SCOPE_ADMIN)),
) -> ThresholdValue:
    if not body.reason or not body.reason.strip():
        raise HTTPException(status_code=400, detail="reason is required")
    actor = f"{auth.org_name}:{auth.label}: {body.reason.strip()}"
    await get_store().set(key, body.value, actor)
    return ThresholdValue(key=key, value=body.value)

class ExtractedFieldView(BaseModel):
    field: str
    documentId: uuid.UUID | None
    docType: str | None
    currentValue: str
    originalValue: str
    edited: bool
    source: str

class FieldOverrideRequest(BaseModel):
    field: str
    newValue: str
    reason: str
    documentId: uuid.UUID | None = None

class ReplayRequest(BaseModel):
    reason: str

class ReplayResponse(BaseModel):
    runId: int
    runNumber: int
    appliedOverrides: int

@router.get(
    "/applications/{application_id}/extracted-fields",
    response_model=list[ExtractedFieldView],
)
async def list_extracted_fields(
    application_id: uuid.UUID,
    auth: AuthContext = Depends(require_scope(SCOPE_ADMIN)),
    session: AsyncSession = Depends(get_session),
) -> list[ExtractedFieldView]:
    await _admin_check_visibility(session, application_id, auth)

    docs = list((await session.execute(
        select(Document).where(Document.application_id == application_id)
    )).scalars())
    doc_type_by_id = {d.id: d.doc_type.value for d in docs}
    if not docs:
        return []

    field_rows = list((await session.execute(
        select(ExtractedField).where(ExtractedField.document_id.in_([d.id for d in docs]))
    )).scalars())

    overrides = list((await session.execute(
        select(ExtractedFieldOverride)
        .where(ExtractedFieldOverride.application_id == application_id)
        .order_by(ExtractedFieldOverride.edited_at.asc())
    )).scalars())
    latest_override: dict[tuple[uuid.UUID | None, str], ExtractedFieldOverride] = {}
    for ov in overrides:
        latest_override[(ov.document_id, ov.field_name)] = ov

    out: list[ExtractedFieldView] = []
    for f in field_rows:
        ov = latest_override.get((f.document_id, f.field_name)) or latest_override.get((None, f.field_name))
        edited = ov is not None and ov.new_value != f.field_value
        out.append(ExtractedFieldView(
            field=f.field_name,
            documentId=f.document_id,
            docType=doc_type_by_id.get(f.document_id),
            currentValue=ov.new_value if ov else f.field_value,
            originalValue=f.field_value,
            edited=edited,
            source="manual" if edited else "extracted",
        ))
    return out

@router.patch(
    "/applications/{application_id}/extracted-fields",
    status_code=status.HTTP_201_CREATED,
)
async def patch_extracted_field(
    application_id: uuid.UUID,
    body: FieldOverrideRequest,
    auth: AuthContext = Depends(require_scope(SCOPE_ADMIN)),
    session: AsyncSession = Depends(get_session),
) -> dict:
    if not body.reason or not body.reason.strip():
        raise HTTPException(status_code=400, detail="reason is required")
    if not body.field or not body.field.strip():
        raise HTTPException(status_code=400, detail="field is required")
    await _admin_check_visibility(session, application_id, auth)

    original_value = await _resolve_original_field_value(
        session, application_id, body.field, body.documentId
    )

    row = ExtractedFieldOverride(
        application_id=application_id,
        document_id=body.documentId,
        field_name=body.field,
        original_value=original_value,
        new_value=body.newValue,
        reason=body.reason.strip(),
        edited_by=f"{auth.org_name}:{auth.label}",
    )
    session.add(row)
    await session.commit()
    await session.refresh(row)
    return {"id": row.id, "field": row.field_name, "appliedToRunId": None}

@router.post(
    "/applications/{application_id}/replay",
    response_model=ReplayResponse,
    status_code=status.HTTP_202_ACCEPTED,
)
async def replay_pipeline(
    application_id: uuid.UUID,
    body: ReplayRequest,
    auth: AuthContext = Depends(require_scope(SCOPE_ADMIN)),
    session: AsyncSession = Depends(get_session),
) -> ReplayResponse:
    if not body.reason or not body.reason.strip():
        raise HTTPException(status_code=400, detail="reason is required")
    app = await _admin_check_visibility(session, application_id, auth)

    existing_max = int((await session.execute(
        select(func.coalesce(func.max(PipelineRun.run_number), 0))
        .where(PipelineRun.application_id == application_id)
    )).scalar() or 0)
    # The initial submission consumes implicit run_number=1 in worker dedup keys
    # without inserting a PipelineRun row. Without this clamp, the first replay
    # would also pick run_number=1 and silently SKIP_OK at every worker stage.
    next_number = max(existing_max, 1) + 1

    run = PipelineRun(
        application_id=application_id,
        run_number=next_number,
        triggered_by=f"{auth.org_name}:{auth.label}",
        reason=body.reason.strip(),
    )
    session.add(run)
    await session.flush()

    applied = (await session.execute(
        ExtractedFieldOverride.__table__.update()
        .where(ExtractedFieldOverride.application_id == application_id)
        .where(ExtractedFieldOverride.applied_to_run_id.is_(None))
        .values(applied_to_run_id=run.id)
    )).rowcount or 0

    app.current_run_id = run.id
    app.status = ApplicationStatus.processing
    await session.commit()

    get_publisher().publish(
        topic_name=TOPIC_APPLICATION_AGGREGATED,
        event_type=EVT_APPLICATION_AGGREGATED,
        payload={
            "applicationId": str(application_id),
            "externalId": app.external_id,
            "useCase": app.use_case.value,
            "runNumber": next_number,
            "replay": True,
        },
        deduplication_id=str(uuid.uuid5(
            uuid.NAMESPACE_URL, f"findoc/replay/{application_id}/{next_number}"
        )),
    )
    logger.info(
        "admin replay started",
        extra={"application_id": str(application_id), "run_number": next_number,
               "applied_overrides": applied, "actor": f"{auth.org_name}:{auth.label}"},
    )
    return ReplayResponse(runId=run.id, runNumber=next_number, appliedOverrides=applied)

async def _admin_check_visibility(
    session: AsyncSession, application_id: uuid.UUID, auth: AuthContext
) -> Application:
    app = (await session.execute(
        select(Application).where(Application.id == application_id)
    )).scalar_one_or_none()
    if app is None:
        raise HTTPException(status_code=404, detail="Application not found")
    visible = await _visible_api_key_ids(session, auth)
    if visible is not None and app.submitted_by_api_key_id not in visible:
        raise HTTPException(status_code=403, detail="Not your application")
    return app

async def _resolve_original_field_value(
    session: AsyncSession,
    application_id: uuid.UUID,
    field_name: str,
    document_id: uuid.UUID | None,
) -> str | None:
    stmt = (
        select(ExtractedField.field_value)
        .join(Document, Document.id == ExtractedField.document_id)
        .where(Document.application_id == application_id)
        .where(ExtractedField.field_name == field_name)
    )
    if document_id is not None:
        stmt = stmt.where(ExtractedField.document_id == document_id)
    row = (await session.execute(stmt.limit(1))).scalar_one_or_none()
    return row
