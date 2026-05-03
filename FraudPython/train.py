import numpy as np
import pandas as pd
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, confusion_matrix
from imblearn.over_sampling import SMOTE
from xgboost import XGBClassifier
import joblib

AMOUNT_THRESHOLD = 50_000        
BALANCE_EXEMPTION = 60_000
AVG_AMOUNT_EXEMPTION = 50_000
RELATIVE_TO_BALANCE_MULT = 1.2
RELATIVE_TO_AVG_MULT = 3.0
RANDOM_FRAUD_RATE = 0.01


CRITICAL_BALANCE_MULT = 0.5    
np.random.seed(42)
N = 2_000_000
n_users = 20_000

user_avg_txn = np.random.exponential(scale=25_000, size=n_users)
user_balance_avg = np.random.exponential(scale=80_000, size=n_users)

userId = np.random.randint(0, n_users, size=N)
avg_amount = user_avg_txn[userId]
balance = np.random.normal(loc=user_balance_avg[userId], scale=30_000, size=N)
balance = np.clip(balance, 50, None)

amount = np.random.exponential(scale=20_000, size=N)


large_indices = np.random.choice(N, size=int(N * 0.02), replace=False)
amount[large_indices] = amount[large_indices] + np.random.uniform(
    AMOUNT_THRESHOLD, AMOUNT_THRESHOLD * 5, size=large_indices.size
)

hour = np.random.randint(0, 24, size=N)
is_foreign = np.random.binomial(1, 0.05, size=N)
is_high_risk = np.random.binomial(1, 0.02, size=N)


is_large_amount = amount > AMOUNT_THRESHOLD
user_has_large_balance_exempt = balance >= BALANCE_EXEMPTION
user_has_large_avg_exempt = avg_amount >= AVG_AMOUNT_EXEMPTION
user_exempt = user_has_large_balance_exempt | user_has_large_avg_exempt


rule_critical_low_balance = (amount > AMOUNT_THRESHOLD) & (balance < CRITICAL_BALANCE_MULT * amount)

rule_foreign_highrisk_night = (
    (is_foreign == 1)
    & (is_high_risk == 1)
    & (amount > balance * RELATIVE_TO_BALANCE_MULT)
    & ((hour < 6) | (hour > 22))
)

rule_amount_vs_avg = (is_large_amount) & (amount > RELATIVE_TO_AVG_MULT * avg_amount)
rule_amount_gt_balance_low_bal = (amount > balance * 1.3) & (balance < 500)
rule_foreign_large_vs_avg = (is_foreign == 1) & (is_large_amount) & (amount > 2 * avg_amount)
rule_random = (np.random.rand(N) < RANDOM_FRAUD_RATE)


fraud = (
    rule_critical_low_balance
    | (rule_foreign_highrisk_night & (amount > balance * 1.5))
    | ((rule_amount_vs_avg | rule_foreign_large_vs_avg | rule_amount_gt_balance_low_bal) & (~user_exempt))
    | rule_random
).astype(int)


current_rate = fraud.mean()
if current_rate < 0.05:
    n_needed = int(0.05 * N - fraud.sum())
    zeros_idx = np.where(fraud == 0)[0]
    pick = np.random.choice(zeros_idx, n_needed, replace=False)
    fraud[pick] = 1


df = pd.DataFrame({
    "amount": amount,
    "hour": hour,
    "is_foreign": is_foreign,
    "is_high_risk": is_high_risk,
    "userId": userId,
    "balance": balance,
    "avg_amount": avg_amount,
    "label": fraud,
})


df["high_amount"] = (df["amount"] > df["balance"] * 1.5).astype(int)
df["night_transaction"] = ((df["hour"] < 6) | (df["hour"] > 22)).astype(int)
df["amount_hour_ratio"] = df["amount"] / (df["hour"] + 1)
df["foreign_high"] = df["is_foreign"] * df["high_amount"]
df["risk_high"] = df["is_high_risk"] * df["high_amount"]
df["amount_to_avg_ratio"] = df["amount"] / (df["avg_amount"] + 1)
df["balance_to_avg_ratio"] = df["balance"] / (df["avg_amount"] + 1)


df["critical_low_balance"] = (
    (df["amount"] > AMOUNT_THRESHOLD) & (df["balance"] < CRITICAL_BALANCE_MULT * df["amount"])
).astype(int)

features = [
    "amount", "hour", "is_foreign", "is_high_risk", "userId", "balance",
    "avg_amount", "high_amount", "night_transaction", "amount_hour_ratio",
    "foreign_high", "risk_high", "amount_to_avg_ratio", "balance_to_avg_ratio",
    "critical_low_balance", ]

X = df[features]
y = df["label"]

X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, stratify=y, random_state=42
)

sm = SMOTE(random_state=42)
X_train_res, y_train_res = sm.fit_resample(X_train, y_train)

scale_weight = (y_train == 0).sum() / max(1, (y_train == 1).sum())

clf = XGBClassifier(
    n_estimators=400,
    max_depth=8,
    learning_rate=0.05,
    subsample=0.8,
    colsample_bytree=0.8,
    scale_pos_weight=scale_weight,
    eval_metric="logloss",
    use_label_encoder=False,
    random_state=42,
)

clf.fit(X_train_res, y_train_res)

preds = clf.predict(X_test)
print("Confusion Matrix:\n", confusion_matrix(y_test, preds))
print("\nClassification Report:\n", classification_report(y_test, preds, digits=4))
print(f"Fraud rate in dataset: {df['label'].mean():.4f}")

joblib.dump(clf, "model.pkl")
print("✅ model.pkl saved successfully (feature names match FastAPI app)")
