from __future__ import annotations

import mimetypes
import uuid
from datetime import date as _date, datetime as _datetime

from botocore.exceptions import ClientError
from fastapi import APIRouter, Depends, File, Form, HTTPException, Response, UploadFile, status
from fastapi.responses import JSONResponse
from sqlalchemy import func, select
from sqlalchemy.exc import IntegrityError
from sqlalchemy.ext.asyncio import AsyncSession

from src.api.schemas import (
    ApplicationDetail,
    ApplicationListItem,
    ApplicationListPage,
    CheckSummary,
    CrossDocSummary,
    DocumentStatus,
    FraudSummary,
    OverrideRequest,
    OverrideSummary,
    SubmitResponse,
)
from src.auth import AuthContext, require_caller
from src.db.models import (
    ApiKey,
    Application,
    ApplicationStatus,
    ComplianceCheck,
    CrossDocValidation,
    DecisionOverride,
    DocClassification,
    Document,
    DocType,
    ExtractedField,
    FraudResult,
    OcrResult,
    PipelineRun,
    Recommendation,
    UseCase,
    VerificationReport,
)
from src.db.session import get_session
from src.logging_config import get_logger
from src.messaging.sns_publisher import get_publisher
from src.messaging.topics import EVT_DOC_OCR_REQUESTED, TOPIC_DOC_OCR_REQUESTED
from src.pipeline.required_docs import (
    KYC_REQUIRED_SLOTS,
    LOAN_ID_SLOTS,
    LOAN_REQUIRED_SLOTS,
    validate_kyc_submission,
    validate_loan_submission,
)
from src.services.document_service import persist_document
from src.services.submission_service import create_application
from src.services.upload_validation import UploadValidationError, validate_uploads
from src.storage.s3_storage import get_storage

logger = get_logger(__name__)


def _compute_effective(
    report: VerificationReport | None,
    latest_override: DecisionOverride | None,
    latest_run_started_at: _datetime | None,
) -> str | None:
    """Pick the recommendation that should drive the dashboard badge.

    Replays supersede prior overrides: if the latest pipeline_run started after
    the latest decision_override, the report's recommendation wins (admin
    explicitly re-evaluated). Otherwise the override is still authoritative.
    """
    if not latest_override:
        return report.recommendation.value if report else None
    if not report:
        return latest_override.new_recommendation.value
    if latest_run_started_at and latest_run_started_at > latest_override.created_at:
        return report.recommendation.value
    return latest_override.new_recommendation.value


router = APIRouter(prefix="/api/v1", tags=["applications"])

loan_router = APIRouter(prefix="/api/v1/loan-origination", tags=["loan-origination"])

async def _publish_ocr(docs: list[Document], app: Application) -> None:
    publisher = get_publisher()
    for doc in docs:
        publisher.publish(
            topic_name=TOPIC_DOC_OCR_REQUESTED,
            event_type=EVT_DOC_OCR_REQUESTED,
            payload={
                "applicationId": str(app.id),
                "externalId": app.external_id,
                "useCase": app.use_case.value,
                "documentId": str(doc.id),
                "docType": doc.doc_type.value,
                "fileKey": doc.file_key,
            },
            deduplication_id=str(uuid.uuid5(uuid.NAMESPACE_URL, f"findoc/ocr/{doc.id}")),
        )

async def _read_uploads(slots: dict[str, UploadFile | None]) -> dict[str, tuple[bytes, str, str | None]]:
    out: dict[str, tuple[bytes, str, str | None]] = {}
    for field, upload in slots.items():
        if upload is None or not upload.filename:
            continue
        data = await upload.read()
        if not data:
            continue
        out[field] = (data, upload.filename, upload.content_type)
    return out

@router.post("/kyc/submit", response_model=SubmitResponse, status_code=status.HTTP_202_ACCEPTED)
async def submit_kyc(
    response: Response,
    applicant_name: str = Form(...),
    email: str = Form(...),
    phone: str = Form(...),
    external_id: str | None = Form(default=None),
    applicant_dob: str | None = Form(default=None),
    selfie: UploadFile | None = File(default=None),
    aadhaar: UploadFile | None = File(default=None),
    pan: UploadFile | None = File(default=None),
    auth: AuthContext = Depends(require_caller),
    session: AsyncSession = Depends(get_session),
):
    dob_parsed: _date | None = None
    if applicant_dob:
        for fmt in ("%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y"):
            try:
                dob_parsed = _datetime.strptime(applicant_dob, fmt).date()
                break
            except ValueError:
                continue
        if dob_parsed is None:
            return JSONResponse(
                status_code=400,
                content={"detail": f"applicant_dob is not a valid date: {applicant_dob}"},
            )
    if external_id:
        existing = (
            await session.execute(select(Application).where(Application.external_id == external_id))
        ).scalar_one_or_none()
        if existing is not None:
            logger.info(
                "idempotent_replay",
                extra={"external_id": external_id, "application_id": str(existing.id), "use_case": "kyc"},
            )
            response.status_code = status.HTTP_200_OK
            return await _existing_submit_response(session, existing)

    files = await _read_uploads({"aadhaar": aadhaar, "pan": pan})
    v = validate_kyc_submission({k: True for k in files})
    if not v.ok:
        return JSONResponse(status_code=400, content={"detail": "Incomplete KYC document set", "missingFields": v.missing})

    try:
        validate_uploads(files)
    except UploadValidationError as e:
        return JSONResponse(status_code=400, content={"detail": e.detail, "field": e.field})

    try:
        app = await create_application(
            session, UseCase.kyc, external_id, applicant_name, email, phone, auth.api_key_id,
            applicant_dob=dob_parsed,
        )
    except IntegrityError:
        await session.rollback()
        if not external_id:
            raise
        existing = (
            await session.execute(select(Application).where(Application.external_id == external_id))
        ).scalar_one()
        logger.info(
            "idempotent_replay_race",
            extra={"external_id": external_id, "application_id": str(existing.id), "use_case": "kyc"},
        )
        response.status_code = status.HTTP_200_OK
        return await _existing_submit_response(session, existing)

    created: list[Document] = []
    for field, (data, filename, ct) in files.items():
        doc_type = KYC_REQUIRED_SLOTS[field]
        created.append(await persist_document(session, app.id, doc_type, data, filename, ct))
    await session.commit()

    await _publish_ocr(created, app)
    return SubmitResponse(
        applicationId=app.id, externalId=app.external_id, useCase="kyc",
        status=app.status.value, documentsAccepted=len(created),
    )

async def _existing_submit_response(session: AsyncSession, app: Application) -> SubmitResponse:
    doc_count = (
        await session.execute(
            select(func.count(Document.id)).where(Document.application_id == app.id)
        )
    ).scalar() or 0
    return SubmitResponse(
        applicationId=app.id,
        externalId=app.external_id,
        useCase=app.use_case.value,
        status=app.status.value,
        documentsAccepted=doc_count,
        idempotentReplay=True,
    )

@loan_router.post("/submit", response_model=SubmitResponse, status_code=status.HTTP_202_ACCEPTED)
async def submit_loan(
    response: Response,
    applicant_name: str = Form(...),
    email: str = Form(...),
    phone: str = Form(...),
    external_id: str | None = Form(default=None),
    aadhaar: UploadFile | None = File(default=None),
    pan: UploadFile | None = File(default=None),
    bank_statement_1: UploadFile | None = File(default=None),
    bank_statement_2: UploadFile | None = File(default=None),
    bank_statement_3: UploadFile | None = File(default=None),
    payslip_1: UploadFile | None = File(default=None),
    payslip_2: UploadFile | None = File(default=None),
    payslip_3: UploadFile | None = File(default=None),
    employment_letter: UploadFile | None = File(default=None),
    itr: UploadFile | None = File(default=None),
    credit_report: UploadFile | None = File(default=None),
    auth: AuthContext = Depends(require_caller),
    session: AsyncSession = Depends(get_session),
):
    if external_id:
        existing = (
            await session.execute(select(Application).where(Application.external_id == external_id))
        ).scalar_one_or_none()
        if existing is not None:
            logger.info(
                "idempotent_replay",
                extra={"external_id": external_id, "application_id": str(existing.id)},
            )
            response.status_code = status.HTTP_200_OK
            return await _existing_submit_response(session, existing)

    slots = {
        "aadhaar": aadhaar, "pan": pan,
        "bank_statement_1": bank_statement_1, "bank_statement_2": bank_statement_2, "bank_statement_3": bank_statement_3,
        "payslip_1": payslip_1, "payslip_2": payslip_2, "payslip_3": payslip_3,
        "employment_letter": employment_letter, "itr": itr, "credit_report": credit_report,
    }
    files = await _read_uploads(slots)
    v = validate_loan_submission({k: True for k in files})
    if not v.ok:
        return JSONResponse(status_code=400, content={"detail": "Incomplete loan document set", "missingFields": v.missing})

    try:
        validate_uploads(files)
    except UploadValidationError as e:
        return JSONResponse(status_code=400, content={"detail": e.detail, "field": e.field})

    try:
        app = await create_application(
            session, UseCase.loan, external_id, applicant_name, email, phone, auth.api_key_id
        )
    except IntegrityError:
        await session.rollback()
        if not external_id:
            raise
        existing = (
            await session.execute(select(Application).where(Application.external_id == external_id))
        ).scalar_one()
        logger.info(
            "idempotent_replay_race",
            extra={"external_id": external_id, "application_id": str(existing.id)},
        )
        response.status_code = status.HTTP_200_OK
        return await _existing_submit_response(session, existing)

    created: list[Document] = []
    for field, (data, filename, ct) in files.items():
        doc_type = LOAN_REQUIRED_SLOTS.get(field) or LOAN_ID_SLOTS.get(field)
        if doc_type is None:
            continue
        created.append(await persist_document(session, app.id, doc_type, data, filename, ct))
    await session.commit()

    await _publish_ocr(created, app)
    return SubmitResponse(
        applicationId=app.id, externalId=app.external_id, useCase="loan",
        status=app.status.value, documentsAccepted=len(created),
    )

@router.get("/applications", response_model=ApplicationListPage)
async def list_applications(
    use_case: str | None = None,
    page: int = 0,
    page_size: int = 25,
    auth: AuthContext = Depends(require_caller),
    session: AsyncSession = Depends(get_session),
) -> ApplicationListPage:
    page = max(0, page)
    page_size = max(1, min(page_size, 100))

    base = select(Application).where(Application.submitted_by_api_key_id == auth.api_key_id)
    if use_case in ("kyc", "loan"):
        base = base.where(Application.use_case == UseCase(use_case))

    total = (
        await session.execute(
            select(func.count()).select_from(base.subquery())
        )
    ).scalar_one() or 0

    stmt = base.order_by(Application.created_at.desc()).offset(page * page_size).limit(page_size)
    apps = list((await session.execute(stmt)).scalars())

    items: list[ApplicationListItem] = []
    if apps:
        app_ids = [a.id for a in apps]
        reports = {
            r.application_id: r
            for r in (
                await session.execute(
                    select(VerificationReport).where(VerificationReport.application_id.in_(app_ids))
                )
            ).scalars()
        }
        overrides_by_app: dict[uuid.UUID, DecisionOverride] = {}
        for o in (
            await session.execute(
                select(DecisionOverride)
                .where(DecisionOverride.application_id.in_(app_ids))
                .order_by(DecisionOverride.created_at.desc())
            )
        ).scalars():
            overrides_by_app.setdefault(o.application_id, o)

        latest_run_at_by_app: dict[uuid.UUID, _datetime] = dict(
            (await session.execute(
                select(PipelineRun.application_id, func.max(PipelineRun.started_at))
                .where(PipelineRun.application_id.in_(app_ids))
                .group_by(PipelineRun.application_id)
            )).all()
        )

        for a in apps:
            r = reports.get(a.id)
            ov = overrides_by_app.get(a.id)
            effective = _compute_effective(r, ov, latest_run_at_by_app.get(a.id))
            items.append(ApplicationListItem(
                applicationId=a.id, externalId=a.external_id, useCase=a.use_case.value,
                applicantName=a.applicant_name, status=a.status.value,
                recommendation=r.recommendation.value if r else None,
                effectiveRecommendation=effective,
                overallScore=r.overall_score if r else None,
                submittedByOrg=auth.org_name,
                createdAt=a.created_at,
            ))

    total_pages = max(1, (total + page_size - 1) // page_size) if total else 1
    return ApplicationListPage(
        items=items, page=page, pageSize=page_size, total=total, totalPages=total_pages,
    )

async def _load_app_scoped(session: AsyncSession, application_id: uuid.UUID, auth: AuthContext) -> Application:
    app = (
        await session.execute(select(Application).where(Application.id == application_id))
    ).scalar_one_or_none()
    if not app:
        raise HTTPException(status_code=404, detail="Application not found")
    if app.submitted_by_api_key_id != auth.api_key_id:
        raise HTTPException(status_code=403, detail="Not your application")
    return app

@router.get("/applications/{application_id}", response_model=ApplicationDetail)
@loan_router.get("/{application_id}", response_model=ApplicationDetail)
async def get_application(
    application_id: uuid.UUID,
    auth: AuthContext = Depends(require_caller),
    session: AsyncSession = Depends(get_session),
) -> ApplicationDetail:
    app = await _load_app_scoped(session, application_id, auth)

    submitter_org = None
    if app.submitted_by_api_key_id:
        k = (await session.execute(select(ApiKey).where(ApiKey.id == app.submitted_by_api_key_id))).scalar_one_or_none()
        submitter_org = k.org_name if k else None

    docs = list(
        (await session.execute(select(Document).where(Document.application_id == application_id))).scalars()
    )
    doc_ids = [d.id for d in docs]
    ocr = {}
    cls = {}
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
            select(DecisionOverride).where(DecisionOverride.application_id == application_id).order_by(DecisionOverride.created_at.desc())
        )).scalars()
    )

    latest_run_at = (await session.execute(
        select(func.max(PipelineRun.started_at))
        .where(PipelineRun.application_id == application_id)
    )).scalar()
    effective = _compute_effective(report, overrides[0] if overrides else None, latest_run_at)

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

@router.get("/applications/{application_id}/report")
@loan_router.get("/{application_id}/report")
async def get_report(
    application_id: uuid.UUID,
    auth: AuthContext = Depends(require_caller),
    session: AsyncSession = Depends(get_session),
):
    await _load_app_scoped(session, application_id, auth)
    r = (
        await session.execute(select(VerificationReport).where(VerificationReport.application_id == application_id))
    ).scalar_one_or_none()
    if not r:
        raise HTTPException(status_code=404, detail="Report not yet available")
    return r.report_json

@router.get("/applications/{application_id}/documents/{document_id}/details")
@loan_router.get("/{application_id}/documents/{document_id}/details")
async def get_document_details(
    application_id: uuid.UUID,
    document_id: uuid.UUID,
    auth: AuthContext = Depends(require_caller),
    session: AsyncSession = Depends(get_session),
):
    await _load_app_scoped(session, application_id, auth)
    doc = (
        await session.execute(
            select(Document).where(Document.id == document_id, Document.application_id == application_id)
        )
    ).scalar_one_or_none()
    if not doc:
        raise HTTPException(status_code=404, detail="Document not found")
    ocr = (await session.execute(select(OcrResult).where(OcrResult.document_id == document_id))).scalar_one_or_none()
    cls = (await session.execute(select(DocClassification).where(DocClassification.document_id == document_id))).scalar_one_or_none()
    fields = list(
        (await session.execute(select(ExtractedField).where(ExtractedField.document_id == document_id))).scalars()
    )
    return {
        "document": {
            "id": str(doc.id),
            "docType": doc.doc_type.value,
            "filename": doc.original_filename,
            "sizeBytes": doc.size_bytes,
            "fileHash": doc.file_hash,
            "periodMonth": doc.period_month.isoformat() if doc.period_month else None,
            "uploadedAt": doc.uploaded_at.isoformat(),
        },
        "ocr": None if not ocr else {
            "provider": ocr.provider, "latencyMs": ocr.latency_ms,
            "avgConfidence": ocr.avg_confidence, "pageCount": len(ocr.page_texts or []),
            "rawTextPreview": (ocr.raw_text or "")[:2000],
            "rawTextLength": len(ocr.raw_text or ""),
        },
        "classification": None if not cls else {
            "classifiedType": cls.classified_type, "confidence": cls.confidence, "reasoning": cls.reasoning,
        },
        "fields": [
            {"name": f.field_name, "value": f.field_value, "confidence": f.confidence, "method": f.extraction_method.value}
            for f in fields
        ],
    }

@router.get("/applications/{application_id}/documents/{document_id}/download")
@loan_router.get("/{application_id}/documents/{document_id}/download")
async def download_document(
    application_id: uuid.UUID,
    document_id: uuid.UUID,
    auth: AuthContext = Depends(require_caller),
    session: AsyncSession = Depends(get_session),
):
    await _load_app_scoped(session, application_id, auth)
    doc = (
        await session.execute(
            select(Document).where(Document.id == document_id, Document.application_id == application_id)
        )
    ).scalar_one_or_none()
    if not doc:
        raise HTTPException(status_code=404, detail="Document not found")
    url = get_storage().get_presigned_url(doc.file_key, expires=300)
    return {"url": url, "expiresInSeconds": 300}


@router.get("/applications/{application_id}/documents/{document_id}/file")
@loan_router.get("/{application_id}/documents/{document_id}/file")
async def stream_document(
    application_id: uuid.UUID,
    document_id: uuid.UUID,
    auth: AuthContext = Depends(require_caller),
    session: AsyncSession = Depends(get_session),
):
    await _load_app_scoped(session, application_id, auth)
    doc = (
        await session.execute(
            select(Document).where(Document.id == document_id, Document.application_id == application_id)
        )
    ).scalar_one_or_none()
    if not doc:
        raise HTTPException(status_code=404, detail="Document not found")
    try:
        data = get_storage().get_document(doc.file_key)
    except ClientError as e:
        code = e.response.get("Error", {}).get("Code")
        if code in ("NoSuchKey", "404"):
            raise HTTPException(
                status_code=410,
                detail=f"File no longer in storage: key={doc.file_key!r}. "
                       "The S3 bucket was wiped (likely a LocalStack restart). "
                       "Re-submit the application to upload the file again.",
            ) from None
        raise HTTPException(status_code=502, detail=f"Storage error: {code or 'unknown'}") from None
    media_type, _ = mimetypes.guess_type(doc.original_filename)
    return Response(
        content=data,
        media_type=media_type or "application/octet-stream",
        headers={"Content-Disposition": f'inline; filename="{doc.original_filename}"'},
    )

_REC_MAP = {
    "approve": Recommendation.approve,
    "reject": Recommendation.reject,
    "manual_review": Recommendation.manual_review,
    "verified": Recommendation.verified,
}

_STATUS_MAP = {
    Recommendation.approve: ApplicationStatus.approved,
    Recommendation.verified: ApplicationStatus.approved,
    Recommendation.reject: ApplicationStatus.rejected,
    Recommendation.manual_review: ApplicationStatus.needs_review,
}

@router.post("/applications/{application_id}/override", response_model=OverrideSummary, status_code=status.HTTP_201_CREATED)
async def override_decision(
    application_id: uuid.UUID,
    body: OverrideRequest,
    auth: AuthContext = Depends(require_caller),
    session: AsyncSession = Depends(get_session),
) -> OverrideSummary:
    app = await _load_app_scoped(session, application_id, auth)
    report = (
        await session.execute(select(VerificationReport).where(VerificationReport.application_id == application_id))
    ).scalar_one_or_none()
    if not report:
        raise HTTPException(status_code=409, detail="Cannot override — no report yet")

    new = _REC_MAP[body.newRecommendation]
    if app.use_case.value == "kyc" and new == Recommendation.approve:
        new = Recommendation.verified
    if app.use_case.value == "loan" and new == Recommendation.verified:
        new = Recommendation.approve

    override = DecisionOverride(
        id=uuid.uuid4(),
        application_id=application_id,
        previous_recommendation=report.recommendation,
        new_recommendation=new,
        reason=body.reason,
        actor_api_key_id=auth.api_key_id,
        actor_org=auth.org_name,
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
            logger.exception("override republish failed",
                             extra={"application_id": str(application_id), "override_id": str(override.id)})

    return OverrideSummary(
        id=override.id,
        previousRecommendation=override.previous_recommendation.value,
        newRecommendation=override.new_recommendation.value,
        reason=override.reason,
        actorOrg=override.actor_org,
        createdAt=override.created_at,
    )
