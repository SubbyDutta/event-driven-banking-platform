import { useState } from "react";
import { useToast } from "./Toast";

export function CopyButton({
  value,
  label,
  className = "",
  size = "sm",
}: {
  value: string;
  label?: string;
  className?: string;
  size?: "sm" | "md";
}) {
  const toast = useToast();
  const [done, setDone] = useState(false);

  async function copy(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    try {
      await navigator.clipboard.writeText(value);
      setDone(true);
      toast.success("Copied to clipboard", value.length > 60 ? value.slice(0, 60) + "…" : value);
      setTimeout(() => setDone(false), 1400);
    } catch {
      toast.error("Couldn't copy", "Clipboard API blocked.");
    }
  }

  const sizing = size === "md" ? "h-7 w-7" : "h-6 w-6";

  return (
    <button
      type="button"
      onClick={copy}
      aria-label={label ? `Copy ${label}` : "Copy"}
      title={label ? `Copy ${label}` : "Copy"}
      className={[
        "inline-grid place-items-center rounded text-muted hover:text-ink-900 hover:bg-paper-2 transition-colors",
        sizing,
        className,
      ].join(" ")}
      style={{ color: done ? "var(--positive)" : undefined }}
    >
      {done ? (
        <svg viewBox="0 0 20 20" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
          <path d="m5 10 3.5 3.5L15 7" />
        </svg>
      ) : (
        <svg viewBox="0 0 20 20" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
          <rect x="6" y="6" width="11" height="11" rx="1.5" />
          <path d="M14 6V4.5a1.5 1.5 0 0 0-1.5-1.5h-7A1.5 1.5 0 0 0 4 4.5v7A1.5 1.5 0 0 0 5.5 13H7" />
        </svg>
      )}
    </button>
  );
}
