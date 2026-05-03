from __future__ import annotations

import uuid
from datetime import date

from sqlalchemy.ext.asyncio import AsyncSession

from src.db.models import Application, ApplicationStatus, UseCase

async def create_application(
    session: AsyncSession,
    use_case: UseCase,
    external_id: str | None,
    applicant_name: str,
    email: str,
    phone: str,
    submitted_by_api_key_id: uuid.UUID | None,
    applicant_dob: date | None = None,
) -> Application:
    ext = external_id or f"auto-{uuid.uuid4().hex[:16]}"
    app = Application(
        id=uuid.uuid4(),
        external_id=ext,
        use_case=use_case,
        applicant_name=applicant_name,
        email=email,
        phone=phone,
        applicant_dob=applicant_dob,
        status=ApplicationStatus.received,
        submitted_by_api_key_id=submitted_by_api_key_id,
    )
    session.add(app)
    await session.flush()
    return app
