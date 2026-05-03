
export const formatCurrencyINR = (v) =>
  typeof v === "number" ? v.toLocaleString("en-IN", { style: "currency", currency: "INR" }) : "—";
