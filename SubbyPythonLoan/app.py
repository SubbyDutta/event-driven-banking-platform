
from fastapi import FastAPI
from pydantic import BaseModel
import joblib
import pandas as pd

app = FastAPI()


model = joblib.load("loan_model.pkl")
scaler = joblib.load("scaler.pkl")

class LoanRequest(BaseModel):
    income: float
    pan: str
    adhar: str
    credit_score: float
    requested_amount: float
    balance: float
    avg_transaction: float


@app.api_route("/health", methods=["GET", "HEAD"])
def health():
    return "OK"

@app.post("/predictloan")
def predict_loan(data: LoanRequest):
   
    df = pd.DataFrame([{
        "income": data.income,
        "balance": data.balance,
        "avg_transaction": data.avg_transaction,
        "credit_score": data.credit_score,
        "requested_amount": data.requested_amount
    }])

    df_scaled = scaler.transform(df)
    pred = model.predict(df_scaled)[0]
    prob = model.predict_proba(df_scaled)[0][1]

    return {
        "eligible": bool(pred),
        "probability": float(prob)
    }
