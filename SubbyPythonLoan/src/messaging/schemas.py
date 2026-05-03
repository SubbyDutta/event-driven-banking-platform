from __future__ import annotations

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field

class LoanRiskFeatures(BaseModel):
    model_config = ConfigDict(extra="allow")

    monthly_income: float = Field(ge=0)
    credit_score: float = Field(ge=0)
    existing_emi: float | None = Field(default=0.0, ge=0)
    declared_income_annual: float | None = Field(default=None, ge=0)
    bank_avg_balance: float | None = Field(default=None, ge=0)
    employment_type: str | None = None
    age: int | None = Field(default=None, ge=0)
    dti_ratio: float | None = Field(default=None, ge=0)
    fraud_score: float | None = Field(default=None, ge=0, le=1)
    compliance_warnings_count: int | None = Field(default=None, ge=0)

class LoanRiskRequestedPayload(BaseModel):
    model_config = ConfigDict(extra="allow")

    loanAppId: str
    amountRequested: float = Field(ge=0)
    tenureMonths: int = Field(ge=1)
    features: LoanRiskFeatures

class LoanRiskRequestedEvent(BaseModel):
    model_config = ConfigDict(extra="allow")

    eventId: str
    occurredAt: str | None = None
    schemaVersion: int = 1
    eventType: Literal["LoanRiskRequested"] = "LoanRiskRequested"
    correlationId: str
    payload: LoanRiskRequestedPayload

class LoanRiskResultPayload(BaseModel):
    loanAppId: str
    decision: Literal["approve", "reject", "manual_review"]
    probability_of_default: float = Field(ge=0, le=1)
    risk_band: Literal["A", "B", "C", "D", "E"]
    modelVersion: str
    featuresUsed: list[str] = Field(default_factory=list)
    reason: str

class LoanRiskResultEvent(BaseModel):
    eventId: str
    occurredAt: str
    schemaVersion: int = 1
    eventType: Literal["LoanRiskResult"] = "LoanRiskResult"
    correlationId: str
    payload: LoanRiskResultPayload

class NonRetriableError(Exception):
    """Raised to signal a poison message that should skip SQS retries.

    The consumer will ACK the original message (so SQS does not redeliver) and
    forward it to the DLQ manually. Use for validation failures where retrying
    would only re-reject the same bad payload.
    """

    def __init__(self, reason: str, original: dict[str, Any] | None = None) -> None:
        super().__init__(reason)
        self.reason = reason
        self.original = original or {}
