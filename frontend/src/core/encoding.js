// Byte <-> text conversion with the encodings a notepad needs: UTF-8 (with or
// without BOM), UTF-16 LE/BE and ANSI (Windows-1252). Line endings are detected
// here and kept per document; the editor always works with "\n".

export const ENCODINGS = ['UTF-8', 'UTF-8 BOM', 'UTF-16 LE', 'UTF-16 BE', 'ANSI'];
export const EOLS = { LF: '\n', CRLF: '\r\n', CR: '\r' };

// Windows-1252 bytes 0x80-0x9F; the rest of the range maps 1:1 to Latin-1.
const CP1252 = [
  0x20ac, 0xfffd, 0x201a, 0x0192, 0x201e, 0x2026, 0x2020, 0x2021, 0x02c6, 0x2030, 0x0160, 0x2039, 0x0152, 0xfffd, 0x017d, 0xfffd,
  0xfffd, 0x2018, 0x2019, 0x201c, 0x201d, 0x2022, 0x2013, 0x2014, 0x02dc, 0x2122, 0x0161, 0x203a, 0x0153, 0xfffd, 0x017e, 0x0178,
];
const CP1252_REV = new Map(CP1252.map((cp, i) => [cp, 0x80 + i]).filter(([cp]) => cp !== 0xfffd));

function decodeAnsi(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i++) {
    const b = bytes[i];
    s += String.fromCharCode(b >= 0x80 && b <= 0x9f ? CP1252[b - 0x80] : b);
  }
  return s;
}

function encodeAnsi(text) {
  const out = new Uint8Array(text.length);
  let n = 0;
  for (const ch of text) {
    const cp = ch.codePointAt(0);
    if (cp < 0x80 || (cp >= 0xa0 && cp <= 0xff)) out[n++] = cp;
    else out[n++] = CP1252_REV.get(cp) ?? 0x3f; // "?" for anything ANSI cannot hold
  }
  return out.subarray(0, n);
}

function decodeUtf16(bytes, le) {
  const n = bytes.length >> 1;
  const codes = new Array(n);
  for (let i = 0; i < n; i++) {
    const a = bytes[2 * i], b = bytes[2 * i + 1];
    codes[i] = le ? a | (b << 8) : (a << 8) | b;
  }
  let s = '';
  for (let i = 0; i < n; i += 8192) s += String.fromCharCode.apply(null, codes.slice(i, i + 8192));
  return s;
}

function encodeUtf16(text, le) {
  const out = new Uint8Array(text.length * 2);
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    out[2 * i + (le ? 0 : 1)] = c & 0xff;
    out[2 * i + (le ? 1 : 0)] = c >> 8;
  }
  return out;
}

// Binary when there is a NUL in the first 8 KB and no UTF-16 BOM.
export function looksBinary(bytes) {
  if (bytes.length >= 2 && ((bytes[0] === 0xff && bytes[1] === 0xfe) || (bytes[0] === 0xfe && bytes[1] === 0xff))) return false;
  const n = Math.min(bytes.length, 8192);
  for (let i = 0; i < n; i++) if (bytes[i] === 0) return true;
  return false;
}

export function detectEol(text) {
  let crlf = 0, lf = 0, cr = 0;
  for (let i = 0; i < text.length; i++) {
    const c = text.charCodeAt(i);
    if (c === 13) { if (text.charCodeAt(i + 1) === 10) { crlf++; i++; } else cr++; } else if (c === 10) lf++;
  }
  if (!crlf && !lf && !cr) return null;
  if (crlf >= lf && crlf >= cr) return 'CRLF';
  return lf >= cr ? 'LF' : 'CR';
}

// Decodes bytes. `force` re-reads with a chosen encoding ("Reopen with").
export function decode(bytes, force = null) {
  let enc = force;
  if (!enc) {
    if (bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf) enc = 'UTF-8 BOM';
    else if (bytes[0] === 0xff && bytes[1] === 0xfe) enc = 'UTF-16 LE';
    else if (bytes[0] === 0xfe && bytes[1] === 0xff) enc = 'UTF-16 BE';
    else {
      try {
        new TextDecoder('utf-8', { fatal: true }).decode(bytes);
        enc = 'UTF-8';
      } catch {
        enc = 'ANSI';
      }
    }
  }
  let text;
  switch (enc) {
    case 'UTF-8 BOM': text = new TextDecoder('utf-8').decode(bytes[0] === 0xef && bytes[1] === 0xbb && bytes[2] === 0xbf ? bytes.subarray(3) : bytes); break;
    case 'UTF-16 LE': text = decodeUtf16(bytes[0] === 0xff && bytes[1] === 0xfe ? bytes.subarray(2) : bytes, true); break;
    case 'UTF-16 BE': text = decodeUtf16(bytes[0] === 0xfe && bytes[1] === 0xff ? bytes.subarray(2) : bytes, false); break;
    case 'ANSI': text = decodeAnsi(bytes); break;
    default: enc = 'UTF-8'; text = new TextDecoder('utf-8').decode(bytes);
  }
  return { text, encoding: enc, eol: detectEol(text) };
}

// Encodes editor text ("\n" lines) with the document's encoding and line ending.
export function encode(text, encoding = 'UTF-8', eol = 'LF') {
  const t = eol === 'LF' ? text : text.replace(/\n/g, EOLS[eol] || '\n');
  switch (encoding) {
    case 'UTF-8 BOM': return concat(new Uint8Array([0xef, 0xbb, 0xbf]), new TextEncoder().encode(t));
    case 'UTF-16 LE': return concat(new Uint8Array([0xff, 0xfe]), encodeUtf16(t, true));
    case 'UTF-16 BE': return concat(new Uint8Array([0xfe, 0xff]), encodeUtf16(t, false));
    case 'ANSI': return encodeAnsi(t);
    default: return new TextEncoder().encode(t);
  }
}

function concat(a, b) {
  const out = new Uint8Array(a.length + b.length);
  out.set(a); out.set(b, a.length);
  return out;
}

// True when text holds characters ANSI cannot store (used to warn before converting).
export function ansiLossy(text) {
  for (const ch of text) {
    const cp = ch.codePointAt(0);
    if (!(cp < 0x80 || (cp >= 0xa0 && cp <= 0xff) || CP1252_REV.has(cp))) return true;
  }
  return false;
}

export function toBase64(bytes) {
  let s = '';
  for (let i = 0; i < bytes.length; i += 0x8000) s += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
  return btoa(s);
}

export function fromBase64(b64) {
  const s = atob(b64);
  const out = new Uint8Array(s.length);
  for (let i = 0; i < s.length; i++) out[i] = s.charCodeAt(i);
  return out;
}
