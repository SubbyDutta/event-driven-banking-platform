from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from pydantic import BaseModel, ConfigDict, Field
import joblib
import pandas as pd

app = FastAPI(title="Fraud Detection Service")

AMOUNT_THRESHOLD = 50_000
CRITICAL_BALANCE_MULT = 0.5

model = joblib.load("model.pkl")


class TransactionFeatures(BaseModel):
    """Inbound row matching the training feature set in train.py. Derived
    features (high_amount, ratios, etc.) are computed downstream — these are
    the seven raw inputs the model was trained on."""

    model_config = ConfigDict(extra="forbid")

    amount: float = Field(gt=0)
    hour: int = Field(ge=0, le=23)
    is_foreign: int = Field(ge=0, le=1, default=0)
    is_high_risk: int = Field(ge=0, le=1, default=0)
    userId: int = Field(ge=0)
    balance: float = Field(ge=0)
    avg_amount: float = Field(ge=0)


class BatchRequest(BaseModel):
    transactions: list[TransactionFeatures]


@app.exception_handler(RequestValidationError)
async def validation_handler(request: Request, exc: RequestValidationError):
    return JSONResponse(
        status_code=400,
        content={"error": "invalid_features", "details": exc.errors()},
    )


def add_features(df: pd.DataFrame) -> pd.DataFrame:
    df["high_amount"] = (df["amount"] > df["balance"] * 1.5).astype(int)
    df["night_transaction"] = ((df["hour"] < 6) | (df["hour"] > 22)).astype(int)
    df["amount_hour_ratio"] = df["amount"] / (df["hour"] + 1)
    df["foreign_high"] = df["is_foreign"] * df["high_amount"]
    df["risk_high"] = df["is_high_risk"] * df["high_amount"]
    df["amount_to_avg_ratio"] = df["amount"] / (df["avg_amount"] + 1)
    df["balance_to_avg_ratio"] = df["balance"] / (df["avg_amount"] + 1)

    df["critical_low_balance"] = (
        (df["amount"] > AMOUNT_THRESHOLD)
        & (df["balance"] < CRITICAL_BALANCE_MULT * df["amount"])
    ).astype(int)

    return df


@app.post("/predict")
def predict(batch: BatchRequest):
    df = pd.DataFrame([t.model_dump() for t in batch.transactions])
    df = add_features(df)

    X = df[
        [
            "amount", "hour", "is_foreign", "is_high_risk", "userId", "balance",
            "avg_amount", "high_amount", "night_transaction", "amount_hour_ratio",
            "foreign_high", "risk_high", "amount_to_avg_ratio", "balance_to_avg_ratio",
            "critical_low_balance",
        ]
    ]

    probs = model.predict_proba(X)[:, 1]
    labels = (probs > 0.5).astype(int)

    mask_critical = (df["amount"] > AMOUNT_THRESHOLD) & (
        df["balance"] < CRITICAL_BALANCE_MULT * df["amount"]
    )
    mask_arr = mask_critical.to_numpy()

    probs[mask_arr] = 0.99
    labels[mask_arr] = 1

    results = []
    for i, row in df.iterrows():
        results.append(
            {
                "input": row.to_dict(),
                "fraud_probability": float(probs[i]),
                "is_fraud": int(labels[i]),
            }
        )

    return {"results": results}


@app.api_route("/health", methods=["GET", "HEAD"])
def health():
    return {"status": "ok"}
