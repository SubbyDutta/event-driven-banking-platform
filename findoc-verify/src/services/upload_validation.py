"""Server-side guards on uploaded documents.

Enforces per-file and per-submission size limits, and MIME type allowlist.
Runs before any file is persisted so we fail fast on obviously-bad submissions.
"""
from __future__ import annotations

MAX_FILE_BYTES = 10 * 1024 * 1024
MAX_SUBMISSION_BYTES = 100 * 1024 * 1024
ALLOWED_MIME = {
    "application/pdf",
    "image/png",
    "image/jpeg",
    "image/jpg",
}

class UploadValidationError(Exception):
    def __init__(self, detail: str, field: str | None = None) -> None:
        super().__init__(detail)
        self.detail = detail
        self.field = field

def validate_uploads(files: dict[str, tuple[bytes, str, str | None]]) -> None:
    """Files: {slot_name: (bytes, filename, content_type)}.

    Raises UploadValidationError on the first violation.
    """
    total = 0
    for field, (data, filename, ct) in files.items():
        size = len(data)
        total += size
        if size > MAX_FILE_BYTES:
            raise UploadValidationError(
                f"{field}: file too large ({size} bytes > {MAX_FILE_BYTES})",
                field=field,
            )
        norm = (ct or "").split(";", 1)[0].strip().lower()
        if norm and norm not in ALLOWED_MIME:
            raise UploadValidationError(
                f"{field}: unsupported content type '{ct}' (allowed: PDF, PNG, JPG)",
                field=field,
            )
    if total > MAX_SUBMISSION_BYTES:
        raise UploadValidationError(
            f"submission too large ({total} bytes > {MAX_SUBMISSION_BYTES})"
        )

def scan_for_viruses(file_bytes: bytes) -> None:  # noqa: ARG001
    """Stub virus scan hook. Real implementation would call ClamAV / VirusTotal
    / a managed AV service. Leaving as a no-op so the interface is already
    wired in for when we add that integration.
    """
    return None
