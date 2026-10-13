// table.js — CSV/TSV as a real table: sticky header, view-only sort and filter,
// cell editing, add/delete rows and column stats. Edits go back through the editor
// (hooks.change(text)), so saving, undo and conflicts work exactly as for notes.

import { parse, join, setCell, addRow, deleteRow, compareCells, columnStats, detectDelim } from "./csv.js";
import { h, icon, menuSheet, openSheet } from "./ui.js";

const MAX_ROWS = 3000; // rows drawn at once; filter to reach the rest

export class Table {
  /**
   * @param {HTMLElement} el
   * @param {object} hooks { change(text) }
   */
  constructor(el, hooks) {
    this.el = el;
    this.hooks = hooks;
    this.doc = null;
    this.path = "";
    this.readOnly = false;
    this.sort = null;   // {col, dir: 1 | -1}
    this.filter = "";
    this.header = true; // first row is the header
    this.lastText = null;
  }

  /** Forgets the current file (the next load always draws). */
  reset() { this.doc = null; this.path = ""; this.lastText = null; }

  /** Shows text as a table; does nothing when it already shows exactly this. */
  load(text, path, readOnly) {
    if (path === this.path && text === this.lastText && readOnly === this.readOnly && this.doc) return;
    if (path !== this.path) { this.sort = null; this.filter = ""; this.header = true; }
    this.path = path;
    this.readOnly = readOnly;
    this.lastText = text;
    this.doc = parse(text, detectDelim(text, path));
    this.render();
  }

  width() { return this.doc.records.reduce((w, r) => Math.max(w, r.fields.length), 0); }

  commit() {
    const text = join(this.doc);
    this.lastText = text;
    this.hooks.change(text);
  }

  /** Data row indexes (into doc.records) after filter and sort. */
  rows() {
    const from = this.header ? 1 : 0;
    let idx = [];
    const words = this.filter.toLowerCase().split(/\s+/).filter(Boolean);
    for (let i = from; i < this.doc.records.length; i++) {
      const r = this.doc.records[i];
      if (r.raw.trim() === "" && i === this.doc.records.length - 1) continue; // trailing blank line
      if (words.length) {
        const line = r.fields.join(" ").toLowerCase();
        if (!words.every((w) => line.includes(w))) continue;
      }
      idx.push(i);
    }
    if (this.sort) {
      const { col, dir } = this.sort;
      idx = idx.sort((a, b) => dir * compareCells(this.doc.records[a].fields[col] ?? "", this.doc.records[b].fields[col] ?? ""));
    }
    return idx;
  }

  render() {
    const doc = this.doc;
    const w = Math.max(1, this.width());
    const head = this.header && doc.records[0] ? doc.records[0].fields : [];
    const all = this.rows();
    const shown = all.slice(0, MAX_ROWS);
    const scroll = this.el.querySelector(".grid-wrap")?.scrollTop || 0;

    const filter = h("input.t-filter", {
      type: "search", placeholder: "Filter rows…", value: this.filter, "aria-label": "Filter rows",
      oninput: (e) => { this.filter = e.target.value; this.renderBody(); },
    });
    const bar = h("div.t-bar", {},
      filter,
      h("span.t-count.mono", {}, ""),
      h("label.t-head", { title: "Treat the first row as column names" },
        h("input", { type: "checkbox", checked: this.header, onchange: (e) => { this.header = e.target.checked; this.sort = null; this.render(); } }), "Header"),
      this.readOnly ? null : h("button.ghost.rw-only", { onclick: () => this.addRow(-1), title: "Add a row at the end" }, icon("note-plus"), "Row"));

    const ths = [h("th.rn", {}, "#")];
    for (let c = 0; c < w; c++) {
      const name = head[c] ?? colName(c);
      const mark = this.sort && this.sort.col === c ? (this.sort.dir > 0 ? " ▲" : " ▼") : "";
      ths.push(h("th", {}, h("button.th", { onclick: () => this.columnMenu(c, name), title: "Sort, stats" }, name + mark)));
    }
    this.table = h("table.grid", {}, h("thead", {}, h("tr", {}, ths)), h("tbody"));
    this.el.replaceChildren(bar, h("div.grid-wrap", {}, this.table), h("p.t-more", {}));
    this.renderBody(all, shown);
    this.el.querySelector(".grid-wrap").scrollTop = scroll;
  }

  renderBody(all = this.rows(), shown = all.slice(0, MAX_ROWS)) {
    const w = Math.max(1, this.width());
    const tb = this.table.tBodies[0];
    const frag = document.createDocumentFragment();
    for (const i of shown) {
      const r = this.doc.records[i];
      const tr = document.createElement("tr");
      tr.dataset.row = i;
      const rn = document.createElement("td");
      rn.className = "rn";
      rn.textContent = this.header ? i : i + 1;
      tr.append(rn);
      for (let c = 0; c < w; c++) {
        const td = document.createElement("td");
        td.dataset.col = c;
        td.textContent = r.fields[c] ?? "";
        tr.append(td);
      }
      frag.append(tr);
    }
    tb.replaceChildren(frag);
    tb.onclick = (e) => this.onCellClick(e);
    tb.ondblclick = (e) => this.onCellClick(e, true);
    tb.onkeydown = (e) => { if (e.key === "Enter" && e.target.tagName === "TD") this.onCellClick(e, true); };
    for (const td of tb.querySelectorAll("td:not(.rn)")) td.tabIndex = -1;
    const total = this.doc.records.length - (this.header ? 1 : 0);
    this.el.querySelector(".t-count").textContent = this.filter ? `${all.length} of ${total} rows` : `${total} rows`;
    this.el.querySelector(".t-more").textContent = all.length > shown.length ? `Showing the first ${shown.length.toLocaleString()} rows — filter to find the rest.` : "";
  }

  onCellClick(e, edit = false) {
    const td = e.target.closest("td");
    if (!td) return;
    const row = +td.parentElement.dataset.row;
    if (td.classList.contains("rn")) { this.rowMenu(row); return; }
    // one tap selects, a second tap (or double click / Enter) edits
    if (!edit && !td.classList.contains("sel")) {
      for (const x of this.table.querySelectorAll("td.sel")) x.classList.remove("sel");
      td.classList.add("sel");
      td.focus();
      return;
    }
    if (this.readOnly) return;
    this.editCell(td, row, +td.dataset.col);
  }

  editCell(td, row, col) {
    if (td.querySelector("textarea")) return;
    const old = this.doc.records[row].fields[col] ?? "";
    const ta = h("textarea.cell-ed", { rows: 1, value: old, "aria-label": "Cell value" });
    td.classList.add("editing");
    td.replaceChildren(ta);
    ta.focus();
    ta.select();
    let done = false;
    const finish = (keep) => {
      if (done) return;
      done = true;
      td.classList.remove("editing");
      const v = ta.value;
      td.textContent = keep ? v : old;
      if (keep && v !== old) { setCell(this.doc, row, col, v); this.commit(); }
      td.focus();
    };
    ta.addEventListener("keydown", (e) => {
      if (e.key === "Enter" && !e.shiftKey) { e.preventDefault(); finish(true); }
      else if (e.key === "Escape") { e.preventDefault(); e.stopPropagation(); finish(false); }
      else if (e.key === "Tab") {
        e.preventDefault();
        finish(true);
        const next = e.shiftKey ? td.previousElementSibling : td.nextElementSibling;
        if (next && !next.classList.contains("rn")) this.editCell(next, row, +next.dataset.col);
      }
    });
    ta.addEventListener("blur", () => finish(true));
  }

  addRow(after) {
    const at = addRow(this.doc, Math.max(1, this.width()), after);
    this.commit();
    this.render();
    const tr = this.table.querySelector(`tr[data-row="${at}"]`);
    if (tr) { tr.scrollIntoView({ block: "center" }); const td = tr.children[1]; if (td) this.editCell(td, at, 0); }
  }

  async rowMenu(row) {
    if (this.readOnly) return;
    const a = await menuSheet("Row " + (this.header ? row : row + 1), [
      { label: "Insert a row below", icon: "note-plus", value: "add" },
      { label: "Delete this row", icon: "trash", value: "del", danger: true },
    ]);
    if (a === "add") this.addRow(row);
    else if (a === "del") { deleteRow(this.doc, row); this.commit(); this.render(); }
  }

  async columnMenu(col, name) {
    const a = await menuSheet(name, [
      { label: "Sort ascending", icon: "caret", value: "asc" },
      { label: "Sort descending", icon: "caret", value: "desc" },
      { label: "File order", icon: "raw", value: "none" },
      { label: "Column stats", icon: "more", value: "stats" },
    ]);
    if (a === "asc" || a === "desc") { this.sort = { col, dir: a === "asc" ? 1 : -1 }; this.render(); }
    else if (a === "none") { this.sort = null; this.render(); }
    else if (a === "stats") this.stats(col, name);
  }

  stats(col, name) {
    const s = columnStats(this.doc, col, this.header ? 1 : 0);
    const fmt = (n) => (Number.isInteger(n) ? n.toLocaleString() : n.toLocaleString(undefined, { maximumFractionDigits: 2 }));
    const rows = [["Rows", s.count], ["Empty", s.empty], ["Distinct", s.unique]];
    if (s.sum !== undefined) rows.push(["Sum", s.sum], ["Min", s.min], ["Max", s.max], ["Average", s.avg]);
    openSheet(name, h("dl.stats", {}, rows.flatMap(([k, v]) => [h("dt", {}, k), h("dd.mono", {}, fmt(v))])), [{ label: "Close", value: null, cls: ".primary" }]);
  }
}

/** A, B … Z, AA … for files without a header row. */
function colName(c) {
  let s = "";
  for (c++; c > 0; c = Math.floor((c - 1) / 26)) s = String.fromCharCode(65 + ((c - 1) % 26)) + s;
  return s;
}
