// csv.js — a CSV/TSV parser that remembers each record's exact source text, so
// saving after no edits is byte-identical and editing one cell rewrites only that
// record (same rule as the Android app). Pure functions, no DOM.

/** Picks the delimiter: tab for .tsv, else the most common of , ; tab | on the first line. */
export function detectDelim(text, path = "") {
  if (/\.tsv$/i.test(path)) return "\t";
  const first = text.slice(0, text.indexOf("\n") >= 0 ? text.indexOf("\n") : text.length);
  let best = ",", n = 0;
  for (const d of [",", ";", "\t", "|"]) {
    const c = first.split(d).length - 1;
    if (c > n) { best = d; n = c; }
  }
  return best;
}

/**
 * Parses text into records. Each record keeps:
 *   raw    — its exact source, including the line ending
 *   fields — unquoted values
 *   quoted — which fields were quoted in the source
 *   eol    — the line ending after it ("\n", "\r\n" or "" for the last line)
 * A leading BOM is kept in `bom` and is not part of the first field.
 */
export function parse(text, delim) {
  let i = 0;
  let bom = "";
  if (text.charCodeAt(0) === 0xfeff) { bom = "﻿"; i = 1; }
  const records = [];
  while (i < text.length) {
    const start = i;
    const fields = [], quoted = [];
    let field = "", q = false, inQuotes = false, eol = "";
    for (;;) {
      if (i >= text.length) { fields.push(field); quoted.push(q); break; }
      const c = text[i];
      if (inQuotes) {
        if (c === '"') {
          if (text[i + 1] === '"') { field += '"'; i += 2; continue; }
          inQuotes = false; i++; continue;
        }
        field += c; i++; continue;
      }
      if (c === '"' && field === "" && !q) { inQuotes = true; q = true; i++; continue; }
      if (c === delim) { fields.push(field); quoted.push(q); field = ""; q = false; i++; continue; }
      if (c === "\r" && text[i + 1] === "\n") { eol = "\r\n"; i += 2; fields.push(field); quoted.push(q); break; }
      if (c === "\n") { eol = "\n"; i++; fields.push(field); quoted.push(q); break; }
      field += c; i++;
    }
    records.push({ raw: text.slice(start, i), fields, quoted, eol });
  }
  return { bom, delim, records };
}

/** Writes one field, quoting it when it was quoted before or needs it now. */
function quote(v, wasQuoted, delim) {
  if (wasQuoted || v.includes(delim) || v.includes('"') || v.includes("\n") || v.includes("\r") || /^\s|\s$/.test(v) && v !== "") {
    return '"' + v.replaceAll('"', '""') + '"';
  }
  return v;
}

/** Rebuilds a record's source text from its (edited) fields. */
export function serializeRecord(rec, delim) {
  return rec.fields.map((v, k) => quote(v, rec.quoted[k], delim)).join(delim) + rec.eol;
}

/** Joins records back into text: untouched records use their original bytes. */
export function join(doc) {
  return doc.bom + doc.records.map((r) => r.raw).join("");
}

/** Sets one cell and re-serialises only that record. */
export function setCell(doc, row, col, value) {
  const rec = doc.records[row];
  while (rec.fields.length <= col) { rec.fields.push(""); rec.quoted.push(false); }
  rec.fields[col] = value;
  rec.raw = serializeRecord(rec, doc.delim);
}

/** The line ending to use for new rows: the file's own, or "\n". */
export function eolOf(doc) {
  const r = doc.records.find((x) => x.eol);
  return r ? r.eol : "\n";
}

/** Adds an empty row after `after` (-1 = at the end). */
export function addRow(doc, width, after = -1) {
  const eol = eolOf(doc);
  const last = doc.records[doc.records.length - 1];
  if (last && !last.eol) { last.eol = eol; last.raw += eol; } // the old last line now needs an ending
  const rec = { fields: Array(width).fill(""), quoted: Array(width).fill(false), eol };
  rec.raw = serializeRecord(rec, doc.delim);
  const at = after < 0 ? doc.records.length : after + 1;
  doc.records.splice(at, 0, rec);
  return at;
}

/** Removes a row; the file's trailing-newline state is kept. */
export function deleteRow(doc, row) {
  const wasLast = row === doc.records.length - 1;
  const [rec] = doc.records.splice(row, 1);
  if (wasLast && !rec.eol && doc.records.length) {
    const prev = doc.records[doc.records.length - 1];
    prev.raw = prev.raw.slice(0, prev.raw.length - prev.eol.length);
    prev.eol = "";
  }
}

/** Number-aware compare for view-only sorting. */
export function compareCells(a, b) {
  const na = Number(a.replace(/,/g, "")), nb = Number(b.replace(/,/g, ""));
  if (a !== "" && b !== "" && !Number.isNaN(na) && !Number.isNaN(nb)) return na - nb;
  return a.localeCompare(b, undefined, { numeric: true, sensitivity: "base" });
}

/** count, empty, unique and (for numbers) sum/min/max/avg of one column. */
export function columnStats(doc, col, from = 1) {
  const vals = doc.records.slice(from).map((r) => r.fields[col] ?? "");
  const nonEmpty = vals.filter((v) => v.trim() !== "");
  const nums = nonEmpty.map((v) => Number(v.replace(/,/g, ""))).filter((n) => !Number.isNaN(n));
  const out = { count: vals.length, empty: vals.length - nonEmpty.length, unique: new Set(nonEmpty).size };
  if (nums.length && nums.length === nonEmpty.length) {
    const sum = nums.reduce((a, b) => a + b, 0);
    Object.assign(out, { sum, min: Math.min(...nums), max: Math.max(...nums), avg: sum / nums.length });
  }
  return out;
}
