
import pandas as pd
import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.preprocessing import StandardScaler
from sklearn.metrics import accuracy_score, classification_report, roc_auc_score
from xgboost import XGBClassifier
from imblearn.over_sampling import SMOTE
import joblib


np.random.seed(42)
n_samples = 5000

income = np.random.normal(loc=40000, scale=20000, size=n_samples).clip(8000, 150000)
balance = np.random.normal(loc=15000, scale=10000, size=n_samples).clip(0, 100000)
avg_transaction = np.random.normal(loc=2000, scale=1500, size=n_samples).clip(100, 20000)
credit_score = np.random.normal(loc=650, scale=100, size=n_samples).clip(300, 900)
requested_amount = np.random.normal(loc=20000, scale=15000, size=n_samples).clip(1000, 150000)


loan_to_income_ratio = requested_amount / (income + 1e-6)

eligible = (
    (credit_score > 500) & 
    (balance > 3000) &      
    (
       
        ((income < 20000) & (requested_amount <= 5000)) | 
        ((income >= 20000) & (income < 30000) & (requested_amount <= 15000)) |  
        ((income >= 30000) & (income < 50000) & (requested_amount <= 30000)) |
        ((income >= 50000) & (requested_amount <= 80000)) |                     
        ((income >= 80000) & (requested_amount <= 120000))                     
    )
).astype(int)


flip_indices = np.random.choice(n_samples, size=int(0.1 * n_samples), replace=False)
eligible[flip_indices] = 1 - eligible[flip_indices]

data = pd.DataFrame({
    "income": income,
    "balance": balance,
    "avg_transaction": avg_transaction,
    "credit_score": credit_score,
    "requested_amount": requested_amount,
    "eligible": eligible
})

print(" Data distribution:\n", data['eligible'].value_counts())


X = data.drop("eligible", axis=1)
y = data["eligible"]

X_train, X_test, y_train, y_test = train_test_split(
    X, y, test_size=0.2, random_state=42, stratify=y
)


smote = SMOTE(random_state=42)
X_train_res, y_train_res = smote.fit_resample(X_train, y_train)
print(" After SMOTE:", pd.Series(y_train_res).value_counts())


scaler = StandardScaler()
X_train_scaled = scaler.fit_transform(X_train_res)
X_test_scaled = scaler.transform(X_test)


model = XGBClassifier(
    n_estimators=250,
    max_depth=6,
    learning_rate=0.08,
    subsample=0.9,
    colsample_bytree=0.9,
    random_state=42,
    use_label_encoder=False,
    eval_metric="logloss"
)
model.fit(X_train_scaled, y_train_res)


y_pred = model.predict(X_test_scaled)
y_prob = model.predict_proba(X_test_scaled)[:, 1]

print("\n✅ Accuracy:", accuracy_score(y_test, y_pred))
print("✅ ROC-AUC:", roc_auc_score(y_test, y_prob))
print("\nClassification Report:\n", classification_report(y_test, y_pred))

joblib.dump(model, "loan_model.pkl")
joblib.dump(scaler, "scaler.pkl")

print("\n✅ Model and scaler saved successfully!")
