import React, { useEffect, useState } from "react";
import { motion } from "framer-motion";
import { PiggyBank, RefreshCw, ArrowUpCircle, TrendingUp, DollarSign } from "lucide-react";
import API from "../../api";

export default function BankFundView({ showAlert, showPopup }) {
    const [balance, setBalance] = useState(null);
    const [amount, setAmount] = useState("");
    const [loading, setLoading] = useState(false);
    const [reloading, setReloading] = useState(false);

    const ACCENT_COLOR = "#e63946";
    const BG_COLOR = "#f8f9fa";
    const CARD_BG = "#ffffff";
    const TEXT_COLOR = "#1f2937";
    const BORDER_COLOR = "#e5e7eb";

    const fetchBalance = async () => {
        try {
            setReloading(true);
            const res = await API.get("/pool");
            setBalance(res.data);
        } catch (e) {
            showAlert?.("danger", "Failed to fetch bank pool balance");
            console.error(e);
        } finally {
            setReloading(false);
        }
    };

    const handleTopUp = async () => {
        if (!amount || isNaN(amount) || amount <= 0) {
            showAlert?.("warning", "Enter a valid positive amount");
            return;
        }

        if (!window.confirm(`Add ₹${amount} to central reserve?`)) return;

        try {
            setLoading(true);
            await API.post(`/pool/topup?amount=${amount}`);
            showPopup?.("success", `₹${amount} added to bank pool successfully`);
            setAmount("");
            await fetchBalance();
        } catch (e) {
            showAlert?.("danger", "Failed to add balance");
            console.error(e);
        } finally {
            setLoading(false);
        }
    };

    useEffect(() => {
        fetchBalance();
    }, []);

    return (
        <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.4, ease: "easeOut" }}
            style={{
                 width: '100%',
                    minWidth: 1200,
                    maxWidth: '100%',
                    top: -40,
                height: 'calc(100vh - 40px)',
                background: CARD_BG,
                borderRadius: 4,
                padding: 24,
                boxShadow: "0 1px 3px rgba(0,0,0,0.1)",
                border: `1px solid ${BORDER_COLOR}`,
                display: "flex",
                flexDirection: "column",
                justifyContent: "center",
                overflow: "hidden",
            }}
        >
           
            <div
                style={{
                    display: "flex",
                    alignItems: "center",
                    gap: 12,
                    marginBottom: 24,
                    paddingBottom: 16,
                    borderBottom: `1px solid ${BORDER_COLOR}`,
                }}
            >
                <motion.div
                    style={{
                        width: 56,
                        height: 56,
                        borderRadius: 4,
                        background: `linear-gradient(135deg, ${ACCENT_COLOR}, #b91c28)`,
                        display: "flex",
                        alignItems: "center",
                        justifyContent: "center",
                    }}
                    whileHover={{ scale: 1.05 }}
                >
                    <PiggyBank size={28} color="#fff" strokeWidth={2} />
                </motion.div>
                <div>
                    <h2
                        style={{
                            fontWeight: 700,
                            fontSize: "1.5rem",
                            color: TEXT_COLOR,
                            margin: 0,
                        }}
                    >
                        Central Bank Reserve
                    </h2>
                    <div
                        style={{
                            fontSize: "0.8rem",
                            color: "#6b7280",
                            marginTop: 2,
                            fontWeight: 500,
                        }}
                    >
                        Manage your central funding pool
                    </div>
                </div>
            </div>

           
            <motion.div
                initial={{ opacity: 0, scale: 0.95 }}
                animate={{ opacity: 1, scale: 1 }}
                transition={{ delay: 0.1 }}
                style={{
                    textAlign: "center",
                    marginBottom: 24,
                    padding: 24,
                    borderRadius: 4,
                    background: BG_COLOR,
                    border: `1px solid ${BORDER_COLOR}`,
                    position: "relative",
                    overflow: "hidden",
                }}
            >
                {reloading ? (
                    <motion.div
                        style={{
                            width: 40,
                            height: 40,
                            margin: "0 auto",
                            border: `3px solid ${ACCENT_COLOR}20`,
                            borderTopColor: ACCENT_COLOR,
                            borderRadius: "50%",
                        }}
                        animate={{ rotate: 360 }}
                        transition={{ duration: 1, repeat: Infinity, ease: "linear" }}
                    />
                ) : (
                    <motion.div
                        initial={{ y: 10, opacity: 0 }}
                        animate={{ y: 0, opacity: 1 }}
                    >
                        <div
                            style={{
                                fontSize: "0.7rem",
                                textTransform: "uppercase",
                                color: "#6b7280",
                                letterSpacing: "0.5px",
                                fontWeight: 600,
                                marginBottom: 8,
                            }}
                        >
                            Total Reserve Balance
                        </div>
                        <div
                            style={{
                                fontSize: "2.5rem",
                                fontWeight: 900,
                                background: `linear-gradient(135deg, ${ACCENT_COLOR}, #b91c28)`,
                                WebkitBackgroundClip: "text",
                                WebkitTextFillColor: "transparent",
                                backgroundClip: "text",
                            }}
                        >
                            ₹{balance?.toLocaleString("en-IN") ?? "—"}
                        </div>
                        <motion.div
                            style={{
                                display: "inline-flex",
                                alignItems: "center",
                                gap: 4,
                                marginTop: 8,
                                padding: "4px 10px",
                                borderRadius: 2,
                                background: "rgba(16, 185, 129, 0.1)",
                                color: "#10b981",
                                fontSize: "0.75rem",
                                fontWeight: 600,
                            }}
                            whileHover={{ scale: 1.05 }}
                        >
                            <TrendingUp size={12} />
                            Active & Secured
                        </motion.div>
                    </motion.div>
                )}
            </motion.div>

           
            <motion.div
                initial={{ opacity: 0, y: 10 }}
                animate={{ opacity: 1, y: 0 }}
                transition={{ delay: 0.2 }}
            >
                <div
                    style={{
                        fontSize: "0.7rem",
                        textTransform: "uppercase",
                        color: TEXT_COLOR,
                        letterSpacing: "0.3px",
                        marginBottom: 10,
                        fontWeight: 600,
                    }}
                >
                    Add Funds
                </div>
                <div
                    style={{
                        display: "flex",
                        gap: 8,
                        alignItems: "stretch",
                    }}
                >
                    <div style={{ position: "relative", flex: 1 }}>
                        <DollarSign
                            size={16}
                            style={{
                                position: "absolute",
                                left: 10,
                                top: "50%",
                                transform: "translateY(-50%)",
                                color: "#9ca3af",
                            }}
                        />
                        <input
                            type="number"
                            placeholder="Enter amount (₹)"
                            value={amount}
                            onChange={(e) => setAmount(e.target.value)}
                            style={{
                                width: "100%",
                                border: `1px solid ${BORDER_COLOR}`,
                                borderRadius: 4,
                                padding: "10px 10px 10px 34px",
                                outline: "none",
                                fontSize: "0.9rem",
                                fontWeight: 600,
                                background: CARD_BG,
                                color: TEXT_COLOR,
                                transition: "all 0.2s",
                            }}
                            onFocus={(e) => {
                                e.target.style.borderColor = ACCENT_COLOR;
                                e.target.style.boxShadow = `0 0 0 2px ${ACCENT_COLOR}20`;
                            }}
                            onBlur={(e) => {
                                e.target.style.borderColor = BORDER_COLOR;
                                e.target.style.boxShadow = "none";
                            }}
                        />
                    </div>

                    <motion.button
                        onClick={handleTopUp}
                        disabled={loading}
                        whileHover={loading ? {} : { scale: 1.05 }}
                        whileTap={{ scale: 0.95 }}
                        style={{
                            display: "flex",
                            alignItems: "center",
                            gap: 6,
                            padding: "10px 18px",
                            background: `linear-gradient(135deg, ${ACCENT_COLOR}, #b91c28)`,
                            color: "#fff",
                            fontWeight: 700,
                            border: "none",
                            borderRadius: 4,
                            cursor: loading ? "not-allowed" : "pointer",
                            opacity: loading ? 0.7 : 1,
                            transition: "all 0.2s",
                            fontSize: "0.9rem",
                        }}
                    >
                        <ArrowUpCircle size={18} />
                        {loading ? "Processing..." : "Add Funds"}
                    </motion.button>

                    <motion.button
                        onClick={fetchBalance}
                        disabled={reloading}
                        whileHover={reloading ? {} : { scale: 1.05 }}
                        whileTap={{ scale: 0.95 }}
                        style={{
                            background: "transparent",
                            border: `1px solid ${BORDER_COLOR}`,
                            cursor: reloading ? "not-allowed" : "pointer",
                            color: TEXT_COLOR,
                            padding: "10px 14px",
                            borderRadius: 4,
                            display: "flex",
                            alignItems: "center",
                            justifyContent: "center",
                            transition: "all 0.2s",
                        }}
                    >
                        <RefreshCw
                            size={18}
                            style={{
                                animation: reloading ? "spin 1s linear infinite" : "none",
                            }}
                        />
                    </motion.button>
                </div>
                <div
                    style={{
                        fontSize: "0.7rem",
                        color: "#9ca3af",
                        marginTop: 10,
                        fontStyle: "italic",
                    }}
                >
                    Note: Added funds will be immediately available for loan disbursements
                </div>
            </motion.div>

            <style>{`
        @keyframes spin {
          from { transform: rotate(0deg); }
          to { transform: rotate(360deg); }
        }
      `}</style>
        </motion.div>
    );
}
