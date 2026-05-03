import { useState, type ReactNode } from "react";

export function Tooltip({
  label,
  children,
  side = "top",
}: {
  label: ReactNode;
  children: ReactNode;
  side?: "top" | "bottom";
}) {
  const [show, setShow] = useState(false);
  return (
    <span
      className="relative inline-flex"
      onMouseEnter={() => setShow(true)}
      onMouseLeave={() => setShow(false)}
      onFocus={() => setShow(true)}
      onBlur={() => setShow(false)}
    >
      {children}
      {show && (
        <span
          role="tooltip"
          className={[
            "pointer-events-none absolute z-50 left-1/2 -translate-x-1/2 whitespace-nowrap",
            "px-2.5 py-1 rounded text-[11px] font-medium animate-fadeIn",
            side === "top" ? "bottom-full mb-1.5" : "top-full mt-1.5",
          ].join(" ")}
          style={{
            background: "var(--ink)",
            color: "var(--paper)",
            boxShadow: "0 8px 20px rgba(10,14,26,0.25)",
            letterSpacing: "0.02em",
          }}
        >
          {label}
        </span>
      )}
    </span>
  );
}
