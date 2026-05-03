import { createContext, useCallback, useContext, useEffect, useRef, useState, type ReactNode } from "react";

type ToastKind = "success" | "error" | "info";
type Toast = { id: number; kind: ToastKind; title: string; body?: string };

type ToastApi = {
  show: (t: Omit<Toast, "id">) => void;
  success: (title: string, body?: string) => void;
  error: (title: string, body?: string) => void;
  info: (title: string, body?: string) => void;
};

const Ctx = createContext<ToastApi | null>(null);

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const idRef = useRef(0);

  const show = useCallback((t: Omit<Toast, "id">) => {
    const id = ++idRef.current;
    setToasts(prev => [...prev, { ...t, id }]);
    setTimeout(() => setToasts(prev => prev.filter(x => x.id !== id)), 4200);
  }, []);

  const api: ToastApi = {
    show,
    success: (title, body) => show({ kind: "success", title, body }),
    error:   (title, body) => show({ kind: "error",   title, body }),
    info:    (title, body) => show({ kind: "info",    title, body }),
  };

  return (
    <Ctx.Provider value={api}>
      {children}
      <div className="pointer-events-none fixed top-4 right-4 z-[100] flex flex-col gap-2 w-[22rem] max-w-[calc(100vw-2rem)]">
        {toasts.map(t => <ToastCard key={t.id} t={t} onDismiss={() => setToasts(prev => prev.filter(x => x.id !== t.id))} />)}
      </div>
    </Ctx.Provider>
  );
}

export function useToast() {
  const v = useContext(Ctx);
  if (!v) throw new Error("useToast must be inside ToastProvider");
  return v;
}

function ToastCard({ t, onDismiss }: { t: Toast; onDismiss: () => void }) {
  const accent = {
    success: "var(--positive)",
    error:   "var(--bordeaux)",
    info:    "var(--info)",
  }[t.kind];
  const icon = {
    success: <CheckIcon />,
    error:   <AlertIcon />,
    info:    <InfoIcon />,
  }[t.kind];
  return (
    <div
      role="status"
      className="pointer-events-auto bg-paper border border-rule animate-fadeUp"
      style={{
        boxShadow: "0 12px 28px rgba(15,23,46,0.18), 0 0 0 1px rgba(10,14,26,0.04)",
        borderLeft: `3px solid ${accent}`,
        background: "var(--paper)",
        borderColor: "var(--rule)",
        borderRadius: 2,
      }}
    >
      <div className="p-3.5 flex gap-3 items-start">
        <span style={{ color: accent }} className="shrink-0 mt-0.5">{icon}</span>
        <div className="flex-1 min-w-0">
          <div className="text-sm font-semibold" style={{ color: "var(--ink)" }}>{t.title}</div>
          {t.body && <div className="text-xs mt-0.5 break-words" style={{ color: "var(--muted)" }}>{t.body}</div>}
        </div>
        <button
          onClick={onDismiss}
          className="-mr-1 -mt-0.5 p-1"
          style={{ color: "var(--muted)" }}
          aria-label="Dismiss"
        >
          <XIcon />
        </button>
      </div>
    </div>
  );
}

function AlertIcon() {
  return (
    <svg viewBox="0 0 20 20" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="10" cy="10" r="7.25" /><path d="M10 6.5v4M10 13.25h.01" />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg viewBox="0 0 20 20" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="10" cy="10" r="7.25" /><path d="m6.5 10 2.5 2.5L13.75 7.5" />
    </svg>
  );
}
function XIcon() {
  return (
    <svg viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="m6 6 8 8M14 6l-8 8" />
    </svg>
  );
}
function InfoIcon() {
  return (
    <svg viewBox="0 0 20 20" className="h-5 w-5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="10" cy="10" r="7.25" /><path d="M10 9v4M10 6.5h.01" />
    </svg>
  );
}

// ---------------- Confirm Dialog (promise-based) ----------------

type ConfirmOpts = {
  title: string;
  body?: string;
  confirmLabel?: string;
  cancelLabel?: string;
  destructive?: boolean;
};

type ConfirmApi = (opts: ConfirmOpts) => Promise<boolean>;

const ConfirmCtx = createContext<ConfirmApi | null>(null);

export function ConfirmProvider({ children }: { children: ReactNode }) {
  const [opts, setOpts] = useState<ConfirmOpts | null>(null);
  const resolverRef = useRef<((v: boolean) => void) | null>(null);

  const confirm: ConfirmApi = useCallback((o) => {
    setOpts(o);
    return new Promise<boolean>((resolve) => {
      resolverRef.current = resolve;
    });
  }, []);

  function close(value: boolean) {
    resolverRef.current?.(value);
    resolverRef.current = null;
    setOpts(null);
  }

  useEffect(() => {
    if (!opts) return;
    function onKey(e: KeyboardEvent) {
      if (e.key === "Escape") close(false);
      if (e.key === "Enter") close(true);
    }
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [opts]);

  return (
    <ConfirmCtx.Provider value={confirm}>
      {children}
      {opts && (
        <div className="fv-modal-backdrop" onClick={() => close(false)}>
          <div
            className="fv-modal"
            onClick={(e) => e.stopPropagation()}
            role="alertdialog"
            aria-modal="true"
          >
            <div className="fv-modal-head">
              <span className="corner">REF · CONFIRM</span>
              <div className={`seal${opts.destructive ? " danger" : ""}`}>
                {opts.destructive ? "!" : "?"}
              </div>
              <h3>{opts.title}</h3>
              {opts.body && <p>{opts.body}</p>}
            </div>
            <div className="fv-modal-body">
              <div className="fv-modal-actions">
                <button
                  onClick={() => close(false)}
                  className="fv-btn fv-btn-secondary"
                >
                  {opts.cancelLabel ?? "Cancel"}
                </button>
                <button
                  onClick={() => close(true)}
                  autoFocus
                  className={opts.destructive ? "fv-btn fv-btn-danger" : "fv-btn fv-btn-primary"}
                >
                  {opts.confirmLabel ?? "Confirm"}
                </button>
              </div>
            </div>
          </div>
        </div>
      )}
    </ConfirmCtx.Provider>
  );
}

export function useConfirm() {
  const v = useContext(ConfirmCtx);
  if (!v) throw new Error("useConfirm must be inside ConfirmProvider");
  return v;
}
