import { useRef, useState } from "react";

type Props = {
  field: string;
  label: string;
  required?: boolean;
};

export function Dropzone({ field, label, required }: Props) {
  const inputRef = useRef<HTMLInputElement>(null);
  const [hover, setHover] = useState(false);
  const [file, setFile] = useState<File | null>(null);
  const [err, setErr] = useState<string | null>(null);

  function setFromFiles(files: FileList | null) {
    setErr(null);
    const f = files?.[0] ?? null;
    if (!f) { setFile(null); return; }
    const ok = /\.pdf$/i.test(f.name) || f.type.startsWith("image/");
    if (!ok) { setErr("Only PDF or image files are allowed."); return; }
    if (f.size > 25 * 1024 * 1024) { setErr("Max file size is 25 MB."); return; }
    setFile(f);
    if (inputRef.current) {
      const dt = new DataTransfer();
      dt.items.add(f);
      inputRef.current.files = dt.files;
    }
  }

  function clear(e: React.MouseEvent) {
    e.preventDefault();
    e.stopPropagation();
    setFile(null);
    setErr(null);
    if (inputRef.current) inputRef.current.value = "";
  }

  const cls = [
    "fv-dropzone",
    hover ? "dragging" : "",
    file ? "filled" : "",
    err ? "errored" : "",
  ].filter(Boolean).join(" ");

  return (
    <label
      onDragOver={(e) => { e.preventDefault(); setHover(true); }}
      onDragLeave={() => setHover(false)}
      onDrop={(e) => {
        e.preventDefault();
        setHover(false);
        setFromFiles(e.dataTransfer.files);
      }}
      className={cls}
    >
      <span className="ico">
        {err ? <XIcon /> : file ? <CheckIcon /> : <UploadIcon />}
      </span>

      <div className="info">
        <div className="label">
          {label}
          {required && <span style={{ color: "var(--bordeaux)" }}> *</span>}
        </div>
        <div className="hint">
          {err
            ? <span style={{ color: "var(--bordeaux)", fontWeight: 500 }}>{err}</span>
            : file
              ? <span className="filename">{file.name} · {(file.size / 1024).toFixed(0)} KB</span>
              : <>Drop a file or <span style={{ color: "var(--ink)", fontWeight: 600 }}>browse</span> · PDF or image</>}
        </div>
      </div>

      {file && (
        <button
          type="button"
          onClick={clear}
          className="fv-btn-inline danger"
          aria-label="Remove file"
        >
          Remove
        </button>
      )}

      <input
        ref={inputRef}
        type="file"
        name={field}
        required={required}
        accept=".pdf,image/*"
        className="hidden"
        onChange={(e) => setFromFiles(e.target.files)}
      />
    </label>
  );
}

function UploadIcon() {
  return (
    <svg viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <path d="M10 13V4M6 8l4-4 4 4M4 14v2.5A.5.5 0 0 0 4.5 17h11a.5.5 0 0 0 .5-.5V14" />
    </svg>
  );
}
function CheckIcon() {
  return (
    <svg viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round">
      <path d="m5 10 3.5 3.5L15 7" />
    </svg>
  );
}
function XIcon() {
  return (
    <svg viewBox="0 0 20 20" className="h-4 w-4" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="m6 6 8 8M14 6l-8 8" />
    </svg>
  );
}
