// diff.js — line diff for comparing a note with its Syncthing conflict copy, and a
// merge helper that keeps shared lines once and marks only the differing parts.
// Pure functions, no DOM.

/**
 * Line diff (LCS). Returns ops: {t: "=", a, b} | {t: "-", a} | {t: "+", b}, where
 * a/b are line texts. Very large inputs fall back to "all removed, all added".
 */
export function diffLines(aText, bText) {
  const a = aText.replace(/\r\n/g, "\n").split("\n");
  const b = bText.replace(/\r\n/g, "\n").split("\n");
  // trim common head and tail so the table stays small
  let s = 0;
  while (s < a.length && s < b.length && a[s] === b[s]) s++;
  let ea = a.length, eb = b.length;
  while (ea > s && eb > s && a[ea - 1] === b[eb - 1]) { ea--; eb--; }
  const head = a.slice(0, s).map((l) => ({ t: "=", a: l, b: l }));
  const tail = a.slice(ea).map((l) => ({ t: "=", a: l, b: l }));
  const A = a.slice(s, ea), B = b.slice(s, eb);
  let mid;
  if (A.length * B.length > 4_000_000) {
    mid = [...A.map((l) => ({ t: "-", a: l })), ...B.map((l) => ({ t: "+", b: l }))];
  } else {
    const n = A.length, m = B.length;
    const L = Array.from({ length: n + 1 }, () => new Uint32Array(m + 1));
    for (let i = n - 1; i >= 0; i--) {
      for (let j = m - 1; j >= 0; j--) {
        L[i][j] = A[i] === B[j] ? L[i + 1][j + 1] + 1 : Math.max(L[i + 1][j], L[i][j + 1]);
      }
    }
    mid = [];
    let i = 0, j = 0;
    while (i < n && j < m) {
      if (A[i] === B[j]) { mid.push({ t: "=", a: A[i], b: B[j] }); i++; j++; }
      else if (L[i + 1][j] >= L[i][j + 1]) mid.push({ t: "-", a: A[i++] });
      else mid.push({ t: "+", b: B[j++] });
    }
    while (i < n) mid.push({ t: "-", a: A[i++] });
    while (j < m) mid.push({ t: "+", b: B[j++] });
  }
  return [...head, ...mid, ...tail];
}

/** Groups ops into rows for a side-by-side view: [{left, right, kind}] with kind "same" | "change". */
export function sideBySide(ops) {
  const rows = [];
  for (let k = 0; k < ops.length;) {
    if (ops[k].t === "=") { rows.push({ left: ops[k].a, right: ops[k].b, kind: "same" }); k++; continue; }
    const del = [], add = [];
    while (k < ops.length && ops[k].t !== "=") (ops[k].t === "-" ? del : add).push(ops[k++]);
    for (let x = 0; x < Math.max(del.length, add.length); x++) {
      rows.push({ left: del[x] ? del[x].a : null, right: add[x] ? add[x].b : null, kind: "change" });
    }
  }
  return rows;
}

/** Builds a merge draft: shared lines once, each differing block between markers. */
export function mergeDraft(ops, labelA = "original", labelB = "conflict copy") {
  const out = [];
  for (let k = 0; k < ops.length;) {
    if (ops[k].t === "=") { out.push(ops[k].a); k++; continue; }
    const del = [], add = [];
    while (k < ops.length && ops[k].t !== "=") (ops[k].t === "-" ? del : add).push(ops[k++]);
    if (!del.length) { out.push(...add.map((o) => o.b)); continue; } // only added: keep it
    if (!add.length) { out.push(...del.map((o) => o.a)); continue; } // only removed: keep it
    out.push(`<<<<<<< ${labelA}`, ...del.map((o) => o.a), "=======", ...add.map((o) => o.b), `>>>>>>> ${labelB}`);
  }
  return out.join("\n");
}

/** Counts changed lines on each side. */
export function stats(ops) {
  let del = 0, add = 0;
  for (const o of ops) { if (o.t === "-") del++; else if (o.t === "+") add++; }
  return { del, add };
}
