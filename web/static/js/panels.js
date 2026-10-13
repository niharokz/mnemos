// panels.js — the sheets beside the editor: search, recent, trash, capture,
// templates, the Syncthing conflict list and the side-by-side conflict view.
// Everything app-wide comes in through ctx (see main.js), nothing is global here.

import { api } from "./api.js";
import { h, ago, size, debounce, openSheet, sheetClose, toast, promptText, menuSheet, confirmSheet } from "./ui.js";
import { diffLines, sideBySide, mergeDraft, stats } from "./diff.js";

// ── search ───────────────────────────────────────────────────────────────────
export function searchSheet(ctx, initial = "") {
  const input = h("input.s-in", {
    type: "search", value: initial, placeholder: "Name, words, #tag or status: open", "aria-label": "Search",
    autocomplete: "off", spellcheck: "false", autocapitalize: "off", enterkeyhint: "search",
  });
  const info = h("p.s-info.mono", {}, "");
  const list = h("ul.s-list", { role: "listbox" });
  let sel = 0, hits = [], ctrl = null;

  const pick = (hit) => { sheetClose(); ctx.open(hit.path, hit.line); };
  const mark = () => {
    [...list.children].forEach((li, i) => li.setAttribute("aria-selected", String(i === sel)));
    const cur = list.children[sel];
    if (cur) cur.scrollIntoView({ block: "nearest" });
  };
  const run = debounce(async () => {
    const q = input.value.trim();
    if (ctrl) ctrl.abort();
    if (!q) { list.replaceChildren(); info.textContent = "Tip: #tag finds tags, key: value finds properties."; return; }
    ctrl = new AbortController();
    try {
      const r = await api.search(q, ctrl.signal);
      hits = r.hits;
      sel = 0;
      info.textContent = r.total ? `${r.total} found · ${r.files} files · ${r.ms} ms` : "Nothing found.";
      list.replaceChildren(...hits.map((x, i) => h("li", { role: "option" },
        h("button.s-hit", { onclick: () => pick(x), onmouseenter: () => { sel = i; mark(); } },
          h("span.s-name", {}, h("strong.ellipsis", {}, x.name), h("small.ellipsis", {}, dir(x.path))),
          x.snippet ? h("span.s-snip", {}, x.line ? h("small.mono", {}, "L" + x.line + " ") : null, x.snippet) : null,
          x.tags && x.tags.length ? h("span.s-tags", {}, x.tags.map((t) => h("span.badge", {}, "#" + t))) : null))));
      mark();
    } catch (e) {
      if (e.name !== "AbortError") info.textContent = e.message;
    }
  }, 140);
  input.addEventListener("input", run);
  input.addEventListener("keydown", (e) => {
    if (e.key === "ArrowDown") { e.preventDefault(); sel = Math.min(sel + 1, hits.length - 1); mark(); }
    else if (e.key === "ArrowUp") { e.preventDefault(); sel = Math.max(sel - 1, 0); mark(); }
    else if (e.key === "Enter") { e.preventDefault(); if (hits[sel]) pick(hits[sel]); }
  });
  openSheet("Search", [input, info, list], [], { wide: true, onOpen: () => requestAnimationFrame(() => { input.focus(); input.select(); }) });
  run();
}

// ── recent ───────────────────────────────────────────────────────────────────
export function recentSheet(ctx) {
  const opened = ctx.recent().filter((p) => ctx.tree.files().some((f) => f.path === p)).slice(0, 15);
  const changed = ctx.tree.files().sort((a, b) => b.mtime - a.mtime).slice(0, 15);
  const row = (path, extra) => h("li", {}, h("button.s-hit", { onclick: () => { sheetClose(); ctx.open(path); } },
    h("span.s-name", {}, h("strong.ellipsis", {}, base(path)), h("small.ellipsis", {}, dir(path)), extra ? h("small.mono.r", {}, extra) : null)));
  openSheet("Recent", [
    h("h3.s-h", {}, "Opened here"),
    opened.length ? h("ul.s-list", {}, opened.map((p) => row(p))) : h("p", {}, "Nothing opened yet."),
    h("h3.s-h", {}, "Changed anywhere"),
    h("ul.s-list", {}, changed.map((f) => row(f.path, ago(f.mtime)))),
  ], [], { wide: true });
}

// ── trash ────────────────────────────────────────────────────────────────────
export async function trashSheet(ctx) {
  let items;
  try { items = await api.trashList(); } catch (e) { toast(e.message, true); return; }
  const body = items.length
    ? h("ul.s-list", {}, items.map((it) => h("li.t-item", {},
        h("span.s-name", {}, h("strong.ellipsis", {}, it.name), h("small.ellipsis", {}, it.original ? "from " + it.original : it.dir ? "folder" : size(it.size))),
        h("small.mono", {}, ago(it.mtime)),
        ctx.readOnly ? null : h("button.rw-only", { onclick: () => restore(ctx, it) }, "Restore"))))
    : h("p", {}, "The trash is empty.");
  openSheet("Trash", [h("p", {}, "Deleted files wait in the vault's .trash folder. Empty it from a file manager when you like."), body], [], { wide: true });
}

async function restore(ctx, it) {
  sheetClose();
  const to = await promptText("Restore", { label: "Restore to", value: it.original || it.name, ok: "Restore", hint: "Nothing is overwritten: pick another path if this one is taken." });
  if (!to) return;
  try {
    const { path } = await api.restore(it.name, to);
    await ctx.loadTree();
    toast("Restored to " + path);
    if (!it.dir) ctx.open(path);
  } catch (e) { toast(e.message, true); }
}

// ── capture ──────────────────────────────────────────────────────────────────
export function captureSheet(ctx, prefill = "") {
  const ta = h("textarea.cap", { rows: 5, value: prefill, placeholder: "A thought, a link, a todo…", "aria-label": "Text to capture" });
  const send = async () => {
    const text = ta.value.trim();
    if (!text) { ta.focus(); return; }
    if (ctx.readOnly) { toast("The demo is read-only — nothing is saved", true); return; }
    try {
      const { path } = await api.capture(text);
      sheetClose();
      toast("Added to " + path);
      ctx.loadTree();
      ctx.captured(path);
    } catch (e) { toast(e.message, true); }
  };
  ta.addEventListener("keydown", (e) => { if (e.key === "Enter" && (e.ctrlKey || e.metaKey)) { e.preventDefault(); send(); } });
  openSheet("Capture", [ta, h("p", {}, `Adds a time-stamped line to ${ctx.cfg.capture}. Ctrl+Enter adds.`)],
    [{ label: "Cancel", value: null, cls: ".ghost" }, { label: "Add", cls: ".primary", onclick: send, disabled: ctx.readOnly }],
    { onOpen: () => requestAnimationFrame(() => { ta.focus(); ta.setSelectionRange(ta.value.length, ta.value.length); }) });
}

// ── templates ────────────────────────────────────────────────────────────────
/** Picks a template and a name; resolves {path, template} or null. */
export async function pickTemplate(ctx, folder) {
  let t;
  try { t = await api.templates(); } catch (e) { toast(e.message, true); return null; }
  if (!t.templates.length) { toast(`No templates yet — put notes in ${t.folder}/`); return null; }
  const tpl = await menuSheet("New from template", t.templates.map((p) => ({ label: base(p).replace(/\.md$/i, ""), icon: "file", value: p })));
  if (!tpl) return null;
  const prefix = folder ? folder + "/" : "";
  const name = await promptText("New note", { label: "Name", value: prefix, ok: "Create", hint: "{{date}}, {{time}} and {{title}} in the template are filled in.", select: [prefix.length, prefix.length] });
  return name ? { path: name, template: tpl } : null;
}

// ── conflicts ────────────────────────────────────────────────────────────────
export async function conflictsSheet(ctx) {
  let list;
  try { list = await api.conflicts(); } catch (e) { toast(e.message, true); return; }
  openSheet("Sync conflicts", [
    h("p", {}, "Syncthing keeps both versions when two devices change a file at once. Compare them and keep one, or merge by hand; the other goes to the trash."),
    list.length ? h("ul.s-list", {}, list.map((c) => h("li", {}, h("button.s-hit", { onclick: () => { sheetClose(); ctx.openConflict(c.copy); } },
      h("span.s-name", {}, h("strong.ellipsis", {}, base(c.original)), h("small.ellipsis", {}, dir(c.original))),
      h("span.s-snip", {}, `${c.when} · device ${c.device}${c.exists ? "" : " · original is gone"}`)))))
      : h("p", {}, "No conflicts. 🎉"),
  ], [], { wide: true });
}

/** Draws the comparison of a conflict copy with its original into el. */
export async function renderConflict(el, ctx, copy) {
  el.replaceChildren(h("p.loading", {}, "Loading…"));
  const list = await api.conflicts().catch(() => []);
  const c = list.find((x) => x.copy === copy);
  if (!c) { el.replaceChildren(h("p.empty", {}, "This conflict is already resolved.")); return; }
  let a = "", b;
  try {
    b = (await api.read(c.copy)).content;
    if (c.exists) a = (await api.read(c.original)).content;
  } catch (e) { el.replaceChildren(h("p.empty", {}, e.message)); return; }
  const ops = diffLines(a, b);
  const st = stats(ops);
  const rows = sideBySide(ops);
  // long unchanged stretches fold to three lines of context each side
  const out = [];
  for (let i = 0; i < rows.length;) {
    if (rows[i].kind !== "same") { out.push(rows[i++]); continue; }
    let j = i;
    while (j < rows.length && rows[j].kind === "same") j++;
    if (j - i > 8) { out.push(...rows.slice(i, i + 3), { fold: j - i - 6 }, ...rows.slice(j - 3, j)); }
    else out.push(...rows.slice(i, j));
    i = j;
  }
  const cell = (t, side) => h("td" + (t === null ? ".gap" : "." + side), {}, t === null ? "" : t || " ");
  const table = h("table.diff", {},
    h("thead", {}, h("tr", {}, h("th", {}, "This device’s original"), h("th", {}, `Conflict copy · ${c.when}`))),
    h("tbody", {}, out.map((r) => r.fold
      ? h("tr.fold", {}, h("td", { colSpan: 2 }, `⋯ ${r.fold} unchanged lines`))
      : h("tr" + (r.kind === "change" ? ".chg" : ""), {}, cell(r.left, "del"), cell(r.right, "add")))));
  const act = async (keep, content = "") => {
    try {
      const { path } = await api.resolve(c.copy, keep, content);
      await ctx.loadTree();
      toast(keep === "copy" ? "Kept the conflict copy" : keep === "merged" ? "Merged" : "Kept the original");
      ctx.open(path);
    } catch (e) { toast(e.message, true); }
  };
  const merge = () => {
    const ta = h("textarea.merge", { value: mergeDraft(ops, "original", "conflict copy"), spellcheck: "false", "aria-label": "Merged text" });
    el.querySelector(".d-body").replaceChildren(
      h("p.muted", {}, "Shared lines appear once; each difference sits between <<<<<<< and >>>>>>> markers. Keep what you want and remove the markers."),
      ta);
    el.querySelector(".d-acts").replaceChildren(
      h("button.ghost", { onclick: () => renderConflict(el, ctx, copy) }, "Back to compare"),
      h("button.primary.rw-only", { onclick: () => {
        if (/^(<{7}|={7}|>{7})( |$)/m.test(ta.value)) { confirmSheet("Markers left", "The text still has conflict markers. Save it anyway?", "Save").then((y) => y && act("merged", ta.value)); return; }
        act("merged", ta.value);
      } }, "Save merged"));
    ta.focus();
  };
  el.replaceChildren(
    h("div.d-head", {},
      h("div.grow", {}, h("strong.ellipsis", {}, c.original), h("small.mono", {}, ` −${st.del} +${st.add} lines`)),
      h("div.d-acts", {},
        ctx.readOnly ? h("span.badge.warn", {}, "Demo: read-only") : null,
        h("button.rw-only", { onclick: () => act("original"), disabled: !c.exists }, "Keep original"),
        h("button.rw-only", { onclick: () => act("copy") }, "Keep copy"),
        h("button.primary", { onclick: merge }, "Merge by hand"))),
    h("div.d-body", {}, c.exists ? null : h("p.muted", {}, "The original no longer exists: keeping the copy puts it back."), table));
}

const base = (p) => p.slice(p.lastIndexOf("/") + 1);
const dir = (p) => (p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "");
