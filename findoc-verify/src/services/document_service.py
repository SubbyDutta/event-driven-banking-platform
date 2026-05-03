from __future__ import annotations

import hashlib
import uuid
from typing import Iterable

from sqlalchemy.ext.asyncio import AsyncSession

from src.db.models import Document, DocType
from src.storage.s3_storage import get_storage

def sha256_bytes(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()

async def persist_document(
    session: AsyncSession,
    application_id: uuid.UUID,
    doc_type: DocType,
    file_bytes: bytes,
    filename: str,
    content_type: str | None,
) -> Document:
    key = get_storage().put_document(application_id, doc_type.value, file_bytes, filename, content_type)
    row = Document(
        id=uuid.uuid4(),
        application_id=application_id,
        doc_type=doc_type,
        file_key=key,
        file_hash=sha256_bytes(file_bytes),
        original_filename=filename,
        size_bytes=len(file_bytes),
    )
    session.add(row)
    await session.flush()
    return row
