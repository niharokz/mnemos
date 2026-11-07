// One error type for every backend, so the UI can react to the code.
//   CONFLICT   the file changed on disk since it was opened
//   NOT_FOUND  EXISTS  DENIED  TOO_LARGE  INVALID  AUTH  OFFLINE  IO
export class BackendError extends Error {
  constructor(code, message) {
    super(message || code);
    this.code = code;
  }
}

// Rust commands reject with "CODE: message".
export function fromRust(e) {
  const s = typeof e === 'string' ? e : e?.message || String(e);
  const m = /^([A-Z_]+): ?(.*)$/s.exec(s);
  return m ? new BackendError(m[1], m[2]) : new BackendError('IO', s);
}
