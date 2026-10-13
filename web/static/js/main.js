// main.js — mnemos's app logic: sign-in, routing, opening files, autosave with
// conflict handling, views (edit / split / preview or table), the sidebar and every
// file action. Routes:
//   #/n/<path>  a file        #/f/<path>  a folder as cards
//   #/c/<copy>  a sync conflict compared with its original
// Server rules this relies on:
//   • a save carries the version it was based on; 409 = changed elsewhere
//   • delete = move to the vault's .trash folder
//   • nothing is locked: Obsidian, Syncthing and the Android app edit the same files
//   • the demo user is read-only: the server refuses every change, the UI says so

import { api, setSignedOutHandler, rawURL } from "./api.js";
import { h, icon, prefs, toast, promptText, confirmSheet, menuSheet } from "./ui.js";
import { Tree } from "./tree.js";
import { Editor } from "./editor.js";
import { Preview } from "./preview.js";
import { Table } from "./table.js";
import { renderFolder } from "./cards.js";
import { pickImages, uploadImages } from "./images.js";
import { searchSheet, recentSheet, trashSheet, captureSheet, pickTemplate, conflictsSheet, renderConflict } from "./panels.js";

const $ = (id) => document.getElementById(id);
const narrowSide = matchMedia("(max-width: 899px)");
const narrowSplit = matchMedia("(max-width: 819px)");
const CONFLICT = /\.sync-conflict-\d{8}-\d{6}-[A-Z0-9]{7}(\.[^./]*)?$/;

const cfg = { autosave_ms: 1000, max_bytes: 5 << 20, max_upload_bytes: 20 << 20, version: "", user: "", read_only: false, capture: "inbox/note.md", templates: "templates" };

/** The open file. */
const doc = {
  path: "",        // "" = no file open
  version: "",     // version the editor text is based on
  dirty: false,    // editor text differs from what was last saved/loaded
  saving: null,    // promise of the save in flight
  conflict: null,  // {current, version, deleted} while the conflict bar is up
  readOnly: false, // too big to edit here
  isText: true,
  kind: "md",      // md | csv | log | text
};

let mode = "welcome"; // welcome | note | folder | diff
let tree, editor, preview, table, autosaveTimer, followTimer, tableTimer, pendingLine = 0, started = false;

// per-user settings (the owner and the demo may share a browser)
const upref = {
  get: (k, def) => prefs.get(k + "." + cfg.user, def),
  set: (k, v) => prefs.set(k + "." + cfg.user, v),
};

// ── routing ──────────────────────────────────────────────────────────────────
const enc = (p) => p.split("/").map(encodeURIComponent).join("/");
const noteHash = (p) => "#/n/" + enc(p);
function parseHash() {
  const m = location.hash.match(/^#\/([nfc])\/(.*)$/);
  if (!m) return { kind: "", path: "" };
  try { return { kind: m[1], path: m[2].split("/").map(decodeURIComponent).join("/") }; } catch { return { kind: "", path: "" }; }
}
const go = (p) => { location.hash = p ? noteHash(p) : "#/"; };
const goFolder = (p) => { location.hash = "#/f/" + enc(p); };
const goConflict = (c) => { location.hash = "#/c/" + enc(c); };
const replaceHash = (p) => history.replaceState(null, "", p ? noteHash(p) : "#/");
const dirOf = (p) => (p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "");
const baseOf = (p) => p.slice(p.lastIndexOf("/") + 1);

/** Hash for whatever is shown now (to undo a refused navigation). */
function currentHash() {
  if (mode === "note" && doc.path) return noteHash(doc.path);
  return location.hash;
}

async function route() {
  const special = { "#/new": () => newNote(""), "#/capture": () => captureSheet(ctx), "#/search": () => searchSheet(ctx) }[location.hash];
  if (special) { // home-screen shortcuts
    history.replaceState(null, "", mode === "note" ? noteHash(doc.path) : "#/");
    if (mode === "welcome" || !doc.path && mode === "note") showWelcome();
    special();
    return;
  }
  const r = parseHash();
  if (r.kind === "n" && r.path === doc.path && mode === "note") return;
  if (r.kind === "f") { await showFolder(r.path); return; }
  if (r.kind === "c") { await showDiff(r.path); return; }
  await openNote(r.kind === "n" ? r.path : "");
}

// ── status / views ───────────────────────────────────────────────────────────
const STATUS = { saved: "Saved", unsaved: "Edited", saving: "Saving…", conflict: "Conflict", error: "Not saved", readonly: "Read-only", demo: "Demo — not saved", follow: "Following" };
function setStatus(s, detail = "") {
  const el = $("status");
  el.dataset.s = s;
  el.textContent = s ? STATUS[s] : "";
  el.title = detail || STATUS[s] || "";
}

function kindOf(p) {
  const ext = p.slice(p.lastIndexOf(".") + 1).toLowerCase();
  if (ext === "md" || ext === "markdown") return "md";
  if (ext === "csv" || ext === "tsv") return "csv";
  if (ext === "log") return "log";
  return "text";
}

const viewKey = () => (doc.kind === "csv" ? "viewCsv" : "view");
const defView = () => (doc.kind === "csv" ? "preview" : narrowSplit.matches ? "edit" : "split");
const hasViews = () => doc.isText && (doc.kind === "md" || doc.kind === "csv");

function effectiveView() {
  if (!doc.isText) return "preview";
  if (!hasViews()) return "edit";
  const v = prefs.get(viewKey(), defView());
  return v === "split" && narrowSplit.matches ? "edit" : v;
}

function applyView() {
  const v = effectiveView();
  // CSS turns "split" into "edit" on narrow screens, so keep the preference as-is
  $("panes").dataset.view = !doc.isText ? "preview" : hasViews() ? prefs.get(viewKey(), defView()) : "edit";
  $("views").hidden = !hasViews();
  $("views").querySelector('[data-v="preview"]').textContent = doc.kind === "csv" ? "Table" : "Preview";
  for (const b of $("views").querySelectorAll("button")) b.setAttribute("aria-pressed", String(b.dataset.v === v));
  $("preview").hidden = doc.kind === "csv" && doc.isText;
  $("table").hidden = !(doc.kind === "csv" && doc.isText);
  if (v !== "edit" && doc.isText && doc.path) refreshSide(true);
}

/** Redraws the preview or the table from the editor text. */
function refreshSide(now = false) {
  if (doc.kind === "csv") {
    clearTimeout(tableTimer);
    const run = () => table.load(editor.text(), doc.path, doc.readOnly);
    if (now) run(); else tableTimer = setTimeout(run, 300);
  } else if (now) preview.render(doc.path, editor.text());
  else preview.schedule(doc.path, editor.text());
}

function setView(v) {
  prefs.set(viewKey(), v);
  if (v !== "preview") prefs.set(viewKey() + "Edit", v);
  applyView();
  if (v !== "preview" && doc.isText) editor.focus();
}

function toggleView() {
  if (!hasViews()) return;
  setView(effectiveView() === "preview" ? prefs.get(viewKey() + "Edit", narrowSplit.matches ? "edit" : "split") : "preview");
}

const sideVisible = () => effectiveView() !== "edit";

// ── sections ─────────────────────────────────────────────────────────────────
function show(section) {
  mode = section;
  $("panes").hidden = section !== "note";
  $("folder").hidden = section !== "folder";
  $("diff").hidden = section !== "diff";
  $("welcome").hidden = section !== "welcome";
  $("note-menu").hidden = section !== "note";
  if (section !== "note") { $("views").hidden = true; $("conflict").hidden = true; }
}

function setCrumb(path, label = "") {
  const c = $("crumb");
  const name = label || baseOf(path).replace(/\.md$/i, "");
  c.querySelector(".dir").textContent = dirOf(path) && !label ? dirOf(path) + "/" : "";
  c.querySelector(".name").textContent = name;
  c.title = path;
  document.title = (name || "mnemos") + (name ? " · mnemos" : "");
}

/** Resets the open-file state (after leaving it). */
function closeFile() {
  stopFollow();
  Object.assign(doc, { path: "", version: "", dirty: false, conflict: null, readOnly: false, isText: true, kind: "md" });
  setStatus("");
}

// ── opening files ────────────────────────────────────────────────────────────
/** Saves and asks before leaving a file with changes that could not be saved. */
async function leaveCurrent() {
  if (!doc.path) return true;
  clearTimeout(autosaveTimer);
  if (doc.dirty && !doc.conflict) await save();
  if (doc.dirty) return confirmSheet("Leave without saving?", "Your latest edits to this file are not saved.", "Leave", true);
  return true;
}

async function openNote(p) {
  if (!(await leaveCurrent())) { history.replaceState(null, "", currentHash()); return; }
  if (!p) { showWelcome(); return; }
  let f;
  try {
    f = await api.read(p);
  } catch (e) {
    if (e.status === 401) return;
    toast(e.message, true);
    if (doc.path === p || !doc.path) { replaceHash(""); showWelcome(); } else replaceHash(doc.path);
    return;
  }
  loadFile(f);
  upref.set("last", f.path);
  const rec = upref.get("recent", []).filter((x) => x !== f.path);
  upref.set("recent", [f.path, ...rec].slice(0, 30));
  tree.setActive(f.path);
  closeDrawer();
}

function loadFile(f) {
  stopFollow();
  Object.assign(doc, { path: f.path, version: f.version, dirty: false, conflict: null, readOnly: !!f.readonly, isText: !f.binary && !f.too_large, kind: kindOf(f.path) });
  show("note");
  setCrumb(f.path);
  renderPins();
  preview.allowTasks(doc.isText && !doc.readOnly && doc.kind === "md");
  if (doc.isText) {
    editor.load(f.content, { readOnly: doc.readOnly, markdown: doc.kind === "md" });
    preview.clear();
    table.reset();
  } else {
    preview.showFile(f);
  }
  applyView();
  setStatus(doc.readOnly ? "readonly" : cfg.read_only ? "" : "saved", doc.readOnly && doc.isText ? `Bigger than ${Math.round(cfg.max_bytes / 1048576)} MB — open it in Obsidian to edit` : "");
  if (pendingLine && doc.isText) { editor.gotoLine(pendingLine); if (effectiveView() !== "preview") editor.focus(); }
  else if (doc.kind === "log" && doc.isText) editor.toEnd();
  else if (doc.isText && effectiveView() !== "preview" && !narrowSide.matches) editor.focus();
  pendingLine = 0;
}

/** Opens a file, optionally at a line (search results). */
function openAt(path, line = 0) {
  if (path === doc.path && mode === "note") { if (line && doc.isText) editor.gotoLine(line); return; }
  pendingLine = line;
  go(path);
}

async function showWelcome() {
  closeFile();
  show("welcome");
  setCrumb("", "");
  tree.setActive("");
  renderRecent();
}

async function showFolder(p) {
  if (!(await leaveCurrent())) { history.replaceState(null, "", currentHash()); return; }
  closeFile();
  show("folder");
  setCrumb(p || "vault", p ? baseOf(p) : "Vault");
  tree.setActive("");
  closeDrawer();
  await renderFolder($("folder"), p, { folder: goFolder, note: (n) => go(n) });
}

async function showDiff(copy) {
  if (!(await leaveCurrent())) { history.replaceState(null, "", currentHash()); return; }
  closeFile();
  show("diff");
  setCrumb(copy, "Conflict · " + baseOf(copy));
  tree.setActive(copy);
  closeDrawer();
  await renderConflict($("diff"), ctx, copy);
}

function renderRecent() {
  const files = tree.files().filter((f) => /\.(md|markdown|txt|csv|tsv)$/i.test(f.name)).sort((a, b) => b.mtime - a.mtime).slice(0, 8);
  const ago = (ms) => {
    const s = (Date.now() - ms) / 1000;
    if (s < 3600) return Math.max(1, Math.round(s / 60)) + "m";
    if (s < 86400) return Math.round(s / 3600) + "h";
    return Math.round(s / 86400) + "d";
  };
  $("recent").replaceChildren(files.length ? h("div", {}, h("h2", {}, "Recently changed"),
    h("ul", {}, files.map((f) => h("li", {}, h("a", { href: noteHash(f.path) }, h("span.ellipsis", {}, f.name.replace(/\.md$/i, "")), h("small", {}, ago(f.mtime))))))) : "");
}

// ── images ───────────────────────────────────────────────────────────────────
/** Adds images to the open note: from the picker (files = null), a paste or a drop.
    The insertion point follows any typing done while they upload. */
async function addImages(files, pos) {
  if (!doc.path || doc.kind !== "md" || doc.readOnly) return;
  if (cfg.read_only) { demoRefuse(); return; }
  const spot = editor.track(pos);
  if (!files) files = await pickImages();
  if (!files.length) { editor.insertAt(spot, ""); return; }
  toast(files.length === 1 ? "Adding the image…" : `Adding ${files.length} images…`);
  const md = (await uploadImages(files, doc.path, cfg.max_upload_bytes)).join("\n\n");
  if (!md) { editor.insertAt(spot, ""); return; }
  loadTree();
  if (!editor.insertAt(spot, md)) { // another file was opened meanwhile: the images are saved, hand over the links
    try { await navigator.clipboard.writeText(md); toast("Images saved — their links are on the clipboard"); } catch { toast("Images saved in attachments/"); }
  }
}

// ── saving ───────────────────────────────────────────────────────────────────
function onChange() {
  if (!doc.path) return;
  if (sideVisible()) refreshSide();
  if (cfg.read_only) { setStatus("demo", "The demo is read-only: your edits stay in this tab"); return; }
  if (doc.readOnly) return;
  doc.dirty = true;
  if (doc.conflict) return;
  setStatus("unsaved");
  clearTimeout(autosaveTimer);
  autosaveTimer = setTimeout(save, cfg.autosave_ms);
}

/** Saves the open file if it changed. Resolves true when everything is saved. */
async function save({ keepalive = false } = {}) {
  clearTimeout(autosaveTimer);
  if (cfg.read_only) return true;
  if (!doc.path || !doc.dirty || doc.conflict || doc.readOnly || !doc.isText) return !doc.dirty;
  if (doc.saving) { await doc.saving; return save({ keepalive }); }
  const path = doc.path;
  const text = editor.text();
  setStatus("saving");
  doc.saving = (async () => {
    try {
      const r = await api.write(path, text, doc.version, false, keepalive);
      if (doc.path !== path) return true;
      doc.version = r.version;
      if (editor.text() === text) {
        doc.dirty = false;
        setStatus(followTimer ? "follow" : "saved");
      } else {
        setStatus("unsaved");
        autosaveTimer = setTimeout(save, cfg.autosave_ms);
      }
      return true;
    } catch (e) {
      if (doc.path !== path) return false;
      if (e.status === 409 && e.data.conflict) { showConflict(e.data); return false; }
      setStatus("error", e.message);
      if (e.status !== 401) {
        toast("Couldn't save: " + e.message, true, 9000);
        // network trouble is worth retrying; a refusal (403 permission, 413 size …) is not —
        // the next edit or Ctrl+S tries again
        if (e.status === 0 || e.status >= 500) autosaveTimer = setTimeout(save, 8000);
      }
      return false;
    } finally {
      doc.saving = null;
    }
  })();
  return doc.saving;
}

function showConflict(c) {
  doc.conflict = c;
  clearTimeout(autosaveTimer);
  setStatus("conflict");
  $("conflict-msg").textContent = c.deleted
    ? "This file was deleted or moved by another app. Your text is still here."
    : "This file was changed by another app (Obsidian, Syncthing …) while you were editing.";
  $("c-theirs").hidden = !!c.deleted;
  $("c-mine").textContent = c.deleted ? "Recreate it" : "Keep mine";
  $("conflict").hidden = false;
}

function resolved(version) {
  doc.conflict = null;
  doc.version = version;
  $("conflict").hidden = true;
}

async function loadTheirs() {
  const c = doc.conflict;
  if (!c || c.deleted) return;
  editor.reload(c.current, { readOnly: doc.readOnly });
  doc.dirty = false;
  resolved(c.version);
  setStatus("saved");
  if (sideVisible()) refreshSide(true);
}

async function keepMine() {
  const path = doc.path, text = editor.text();
  try {
    const r = await api.write(path, text, "", true);
    resolved(r.version);
    doc.dirty = editor.text() !== text;
    setStatus(doc.dirty ? "unsaved" : "saved");
    if (doc.dirty) autosaveTimer = setTimeout(save, cfg.autosave_ms);
    loadTree();
  } catch (e) { toast(e.message, true); }
}

async function saveMineAsCopy() {
  const c = doc.conflict, path = doc.path, text = editor.text();
  const d = new Date(), pad = (n) => String(n).padStart(2, "0");
  const stamp = `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}${pad(d.getMinutes())}`;
  const base = baseOf(path), dot = base.lastIndexOf(".");
  const name = (dot > 0 ? base.slice(0, dot) : base) + ` (conflict ${stamp})` + (dot > 0 ? base.slice(dot) : ".md");
  const target = (dirOf(path) ? dirOf(path) + "/" : "") + name;
  try {
    const { path: copy } = await api.create(target);
    const f = await api.read(copy);
    await api.write(copy, text, f.version);
    await loadTree();
    if (c.deleted) {
      doc.dirty = false;
      doc.conflict = null;
      go(copy);
    } else {
      await loadTheirs();
    }
    toast("Your version was saved as “" + name + "”", false, 5000);
  } catch (e) { toast(e.message, true); }
}

/** Notices edits made by other apps: reloads quietly when nothing is unsaved here. */
async function checkExternal({ quiet = false } = {}) {
  if (!doc.path || !doc.isText || doc.conflict || doc.saving || document.hidden) return;
  try {
    const { version } = await api.version(doc.path);
    if (version === doc.version) return;
    if (doc.dirty) { save(); return; } // the save will raise the conflict bar
    if (cfg.read_only && editor.text() !== "" && $("status").dataset.s === "demo") return; // keep the demo's own edits
    const f = await api.read(doc.path);
    if (doc.dirty || f.path !== doc.path) return;
    doc.version = f.version;
    doc.readOnly = !!f.readonly;
    editor.reload(f.content, { readOnly: doc.readOnly });
    if (followTimer) editor.toEnd();
    if (sideVisible()) refreshSide(true);
    if (!quiet) toast("Updated — this file changed in another app");
  } catch (e) {
    if (e.status === 404) showConflict({ deleted: true, current: "", version: "" });
  }
}

// ── follow a log ─────────────────────────────────────────────────────────────
function startFollow() {
  stopFollow();
  followTimer = setInterval(() => checkExternal({ quiet: true }), 2000);
  editor.toEnd();
  setStatus("follow", "Showing new lines as they are written");
  toast("Following new lines");
}
function stopFollow() {
  if (!followTimer) return;
  clearInterval(followTimer);
  followTimer = null;
  if (doc.path) setStatus(doc.readOnly ? "readonly" : cfg.read_only ? "" : "saved");
}

// ── tree and file actions ────────────────────────────────────────────────────
async function loadTree() {
  try {
    tree.set(await api.tree());
    if (mode === "welcome") renderRecent();
    const n = tree.files().filter((f) => CONFLICT.test(f.name)).length;
    $("cf-count").textContent = n;
    $("cf-count").hidden = !n;
    renderPins();
  } catch (e) {
    if (e.status !== 401) toast("Couldn't load the file list: " + e.message, true);
  }
}

const folderFor = (dir) => (dir != null ? dir : dirOf(doc.path));
const demoRefuse = () => { toast("The demo is read-only — sign in with your own account to change files", true); return true; };

async function newNote(dir) {
  if (cfg.read_only && demoRefuse()) return;
  const prefix = folderFor(dir) ? folderFor(dir) + "/" : "";
  const v = await promptText("New note", {
    label: "Name", value: prefix, placeholder: "ideas/my note", ok: "Create",
    hint: "Use / for folders; they are created as needed. .md is added when there is no extension.",
    select: [prefix.length, prefix.length],
  });
  if (!v) return;
  await create(v);
}

async function create(path, template = "") {
  try {
    const { path: p } = await api.create(path, false, template);
    await loadTree();
    if (prefs.get("view", "split") === "preview") prefs.set("view", prefs.get("viewEdit", narrowSplit.matches ? "edit" : "split"));
    go(p);
  } catch (e) { toast(e.message, true); }
}

async function newFromTemplate(dir) {
  if (cfg.read_only && demoRefuse()) return;
  const r = await pickTemplate(ctx, folderFor(dir));
  if (r) await create(r.path, r.template);
}

async function newFolder(dir) {
  if (cfg.read_only && demoRefuse()) return;
  const prefix = folderFor(dir) ? folderFor(dir) + "/" : "";
  const v = await promptText("New folder", { label: "Name", value: prefix, placeholder: "projects", ok: "Create", select: [prefix.length, prefix.length] });
  if (!v) return;
  try {
    const { path } = await api.create(v, true);
    for (let p = path; p; p = dirOf(p)) tree.open.add(p);
    prefs.set("open", [...tree.open]);
    await loadTree();
    toast("Folder created");
  } catch (e) { toast(e.message, true); }
}

/** Is path the open file, or inside folder `node`? */
const affects = (node) => doc.path && (doc.path === node.path || (node.dir && doc.path.startsWith(node.path + "/")));

async function renameNode(node) {
  const name = baseOf(node.path);
  const dot = node.dir ? -1 : name.lastIndexOf(".");
  const start = node.path.length - name.length;
  const v = await promptText(node.dir ? "Rename or move folder" : "Rename or move file", {
    label: "Path", value: node.path, ok: "Rename",
    hint: "Change the folder part to move it. Nothing is overwritten.",
    select: [start, dot > 0 ? start + dot : node.path.length],
  });
  if (!v || v === node.path) return;
  if (affects(node)) { await save(); if (doc.dirty) { toast("Save or resolve your edits first", true); return; } }
  try {
    const { path } = await api.move(node.path, v);
    if (affects(node)) {
      doc.path = node.dir ? path + doc.path.slice(node.path.length) : path;
      replaceHash(doc.path);
      setCrumb(doc.path);
      upref.set("last", doc.path);
    }
    const pins = upref.get("pins", []);
    upref.set("pins", pins.map((p) => (p === node.path ? path : node.dir && p.startsWith(node.path + "/") ? path + p.slice(node.path.length) : p)));
    if (node.dir && tree.open.has(node.path)) tree.open.add(path);
    await loadTree();
    tree.setActive(doc.path);
    toast("Renamed");
  } catch (e) { toast(e.message, true); }
}

async function trashNode(node) {
  const ok = await confirmSheet("Move to trash?",
    `“${node.path}” will move to the .trash folder of your vault. Restore it from Trash in the sidebar.`,
    "Move to trash", true);
  if (!ok) return;
  const current = affects(node);
  if (current && doc.dirty && !doc.conflict) await save();
  try {
    await api.trash(node.path);
    if (current) {
      doc.dirty = false;
      doc.conflict = null;
      replaceHash("");
      showWelcome();
    }
    await loadTree();
    toast("Moved to trash");
  } catch (e) { toast(e.message, true); }
}

function togglePin(path) {
  const pins = upref.get("pins", []);
  const on = pins.includes(path);
  upref.set("pins", on ? pins.filter((p) => p !== path) : [...pins, path]);
  renderPins();
  toast(on ? "Unpinned" : "Pinned");
}

function renderPins() {
  if (!tree) return;
  const known = new Set(tree.files().map((f) => f.path));
  const pins = upref.get("pins", []).filter((p) => known.has(p));
  $("pins").replaceChildren(...(pins.length ? [h("h3.side-label", {}, "Pinned"), h("ul", {}, pins.map((p) =>
    h("li", {}, h("div.row" + (p === doc.path ? ".on" : ""), {}, h("button.open", { onclick: () => go(p), title: p }, h("span.file-ic", {}, icon("pin")), h("span", {}, baseOf(p).replace(/\.md$/i, "")))))))] : []));
}

async function nodeMenu(node) {
  const rw = !cfg.read_only;
  const pinned = upref.get("pins", []).includes(node.path);
  const isLog = !node.dir && kindOf(node.path) === "log" && node.path === doc.path;
  const items = node.dir
    ? [
        { label: "Open as cards", icon: "folder", value: "cards" },
        rw && { label: "New note here", icon: "note-plus", value: "note" },
        rw && { label: "New from template", icon: "template", value: "tpl" },
        rw && { label: "New folder here", icon: "folder-plus", value: "folder" },
        rw && { label: "Rename or move", icon: "move", value: "rename" },
        rw && { label: "Move to trash", icon: "trash", value: "trash", danger: true },
      ]
    : [
        { label: pinned ? "Unpin" : "Pin to the sidebar", icon: "pin", value: "pin" },
        isLog && { label: followTimer ? "Stop following" : "Follow new lines", icon: "follow", value: "follow" },
        CONFLICT.test(node.name || baseOf(node.path)) && { label: "Compare with the original", icon: "alert", value: "compare" },
        rw && { label: "Rename or move", icon: "move", value: "rename" },
        { label: "Open raw file", icon: "raw", value: "raw" },
        { label: "Download", icon: "download", value: "download" },
        { label: "Copy path", icon: "copy", value: "copy" },
        rw && { label: "Move to trash", icon: "trash", value: "trash", danger: true },
      ];
  const a = await menuSheet(node.path || "Vault", items.filter(Boolean));
  if (a === "cards") goFolder(node.path);
  else if (a === "note") newNote(node.path);
  else if (a === "tpl") newFromTemplate(node.path);
  else if (a === "folder") newFolder(node.path);
  else if (a === "rename") renameNode(node);
  else if (a === "trash") trashNode(node);
  else if (a === "pin") togglePin(node.path);
  else if (a === "follow") (followTimer ? stopFollow() : startFollow());
  else if (a === "compare") goConflict(node.path);
  else if (a === "raw") window.open(rawURL(node.path), "_blank", "noopener");
  else if (a === "download") { const l = h("a", { href: rawURL(node.path, true), download: baseOf(node.path) }); document.body.append(l); l.click(); l.remove(); }
  else if (a === "copy") {
    try { await navigator.clipboard.writeText(node.path); toast("Path copied"); } catch { toast(node.path); }
  }
}

// ── sidebar, resizers, theme ─────────────────────────────────────────────────
function openDrawer() { $("side").classList.add("open"); $("scrim").hidden = false; }
function closeDrawer() { $("side").classList.remove("open"); $("scrim").hidden = true; }
function toggleSidebar() {
  if (narrowSide.matches) { $("side").classList.contains("open") ? closeDrawer() : openDrawer(); return; }
  const c = !$("app").classList.contains("collapsed");
  $("app").classList.toggle("collapsed", c);
  prefs.set("collapsed", c);
}

function dragResize(handle, onMove, onEnd) {
  handle.addEventListener("pointerdown", (e) => {
    e.preventDefault();
    handle.setPointerCapture(e.pointerId);
    handle.classList.add("drag");
    document.body.style.userSelect = "none";
    const move = (ev) => onMove(ev);
    const up = () => {
      handle.classList.remove("drag");
      document.body.style.userSelect = "";
      handle.removeEventListener("pointermove", move);
      handle.removeEventListener("pointerup", up);
      handle.removeEventListener("pointercancel", up);
      onEnd();
    };
    handle.addEventListener("pointermove", move);
    handle.addEventListener("pointerup", up);
    handle.addEventListener("pointercancel", up);
  });
}

const THEMES = ["auto", "light", "dark"];
function applyTheme(t) {
  if (t === "auto") delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = t;
}

// what the panels need from the app
const ctx = {
  cfg,
  get tree() { return tree; },
  get readOnly() { return cfg.read_only; },
  open: openAt,
  openConflict: goConflict,
  loadTree: () => loadTree(),
  recent: () => upref.get("recent", []),
  captured: (path) => { if (path === doc.path && !doc.dirty) checkExternal({ quiet: true }); },
};

// ── sign-in ──────────────────────────────────────────────────────────────────
function showLogin(me = {}) {
  $("app").hidden = true;
  $("login").hidden = false;
  $("login-err").textContent = started ? "Your session ended — sign in again. Unsaved text is kept." : "";
  if (me.demo) {
    $("demo-box").hidden = false;
    $("demo-user").textContent = me.demo.user;
    $("demo-pw").textContent = me.demo.password;
    $("demo-btn").onclick = () => signIn(me.demo.user, me.demo.password);
  }
  $("login-form").user.focus();
}

async function signIn(user, password) {
  const f = $("login-form");
  for (const b of f.querySelectorAll("button")) b.disabled = true;
  $("login-err").textContent = "";
  try {
    const r = await api.login(user, password);
    f.password.value = "";
    if (started && r.user !== cfg.user) { location.reload(); return; } // another account: start clean
    $("login").hidden = true;
    if (started) { $("app").hidden = false; if (doc.dirty) save(); loadTree(); }
    else await start();
  } catch (err) {
    $("login-err").textContent = err.message;
  } finally {
    for (const b of f.querySelectorAll("button")) b.disabled = false;
  }
}

// ── start ────────────────────────────────────────────────────────────────────
async function start() {
  started = true;
  $("app").hidden = false;
  try { Object.assign(cfg, await api.config()); } catch { /* defaults */ }
  $("ver").textContent = (cfg.user ? cfg.user + " · " : "") + (cfg.version ? "v" + cfg.version : "");
  document.body.classList.toggle("demo", !!cfg.read_only);
  $("demo-bar").hidden = !cfg.read_only;

  tree = new Tree($("tree"), { open: (p) => (p === doc.path && mode === "note" ? closeDrawer() : go(p)), action: nodeMenu });
  editor = new Editor($("editor"), { change: onChange, save: () => save(), toggleView, images: addImages });
  preview = new Preview($("preview"), $("preview-pane"), (n) => editor.toggleTask(n));
  table = new Table($("table"), { change: (text) => editor.setText(text) });

  // layout prefs
  $("app").style.setProperty("--side-w", prefs.get("sideW", 280) + "px");
  $("panes").style.setProperty("--split", prefs.get("split", 50) + "%");
  $("app").classList.toggle("collapsed", prefs.get("collapsed", false));

  // sidebar
  $("filter").addEventListener("input", (e) => tree.setFilter(e.target.value));
  $("filter").addEventListener("keydown", (e) => {
    if (e.key === "Escape") { e.target.value = ""; tree.setFilter(""); }
    if (e.key === "Enter") { const first = $("tree").querySelector(".row:not(.dir) .open"); if (first) first.click(); }
  });
  $("new-note").onclick = () => newNote();
  $("new-folder").onclick = () => newFolder();
  $("welcome-new").onclick = () => newNote("");
  $("welcome-search").onclick = () => searchSheet(ctx);
  $("welcome-cards").onclick = () => goFolder("");
  $("nav-search").onclick = () => { closeDrawer(); searchSheet(ctx); };
  $("nav-recent").onclick = () => { closeDrawer(); recentSheet(ctx); };
  $("nav-cards").onclick = () => goFolder(doc.path ? dirOf(doc.path) : "");
  $("nav-conflicts").onclick = () => { closeDrawer(); conflictsSheet(ctx); };
  $("nav-trash").onclick = () => { closeDrawer(); trashSheet(ctx); };
  $("nav-capture").onclick = () => { closeDrawer(); captureSheet(ctx); };
  $("nav-template").onclick = () => { closeDrawer(); newFromTemplate(); };
  $("collapse-btn").onclick = toggleSidebar;
  $("menu-btn").onclick = () => (narrowSide.matches ? openDrawer() : toggleSidebar());
  $("scrim").onclick = closeDrawer;
  $("theme-btn").onclick = () => {
    const t = THEMES[(THEMES.indexOf(prefs.get("theme", "auto")) + 1) % THEMES.length];
    prefs.set("theme", t);
    applyTheme(t);
    toast("Theme: " + t);
  };
  $("logout-btn").onclick = async () => {
    if (!(await leaveCurrent())) return;
    try { await api.logout(); } catch { /* ignore */ }
    location.replace("/app");
  };

  // top bar
  for (const b of $("views").querySelectorAll("button")) b.onclick = () => setView(b.dataset.v);
  $("note-menu").onclick = () => doc.path && nodeMenu({ path: doc.path, name: baseOf(doc.path), dir: false });
  $("c-theirs").onclick = loadTheirs;
  $("c-mine").onclick = keepMine;
  $("c-copy").onclick = saveMineAsCopy;
  narrowSplit.addEventListener("change", applyView);
  narrowSide.addEventListener("change", closeDrawer);

  // resizers
  dragResize($("side-resizer"), (e) => {
    const w = Math.min(480, Math.max(200, e.clientX));
    $("app").style.setProperty("--side-w", w + "px");
  }, () => prefs.set("sideW", parseInt(getComputedStyle($("app")).getPropertyValue("--side-w"), 10) || 280));
  dragResize($("split-resizer"), (e) => {
    const r = $("panes").getBoundingClientRect();
    const pct = Math.min(80, Math.max(20, ((e.clientX - r.left) / r.width) * 100));
    $("panes").style.setProperty("--split", pct.toFixed(1) + "%");
  }, () => prefs.set("split", parseFloat($("panes").style.getPropertyValue("--split")) || 50));

  // preview follows the editor in split view
  editor.scroller.addEventListener("scroll", () => { if (effectiveView() === "split" && doc.kind === "md") preview.follow(editor.scroller); }, { passive: true });

  // keyboard (the editor handles Ctrl+S / Ctrl+E / Ctrl+B/I/K itself while focused)
  document.addEventListener("keydown", (e) => {
    const mod = e.ctrlKey || e.metaKey;
    const k = e.key.toLowerCase();
    if (e.defaultPrevented) return;
    if (mod && !e.altKey && k === "s") { e.preventDefault(); save(); }
    else if (mod && !e.altKey && k === "e") { e.preventDefault(); toggleView(); }
    else if (mod && !e.altKey && (k === "k" || k === "p")) { e.preventDefault(); searchSheet(ctx); }
    else if (e.altKey && !mod && e.code === "KeyN") { e.preventDefault(); newNote(); }
    else if (e.altKey && !mod && e.code === "KeyC") { e.preventDefault(); captureSheet(ctx); }
    else if (e.altKey && !mod && e.code === "KeyR") { e.preventDefault(); recentSheet(ctx); }
    else if (e.altKey && !mod && e.code === "KeyB") { e.preventDefault(); toggleSidebar(); }
    else if (e.key === "Escape" && $("side").classList.contains("open")) closeDrawer();
  });

  // other apps edit the same files: check when we come back, and now and then
  window.addEventListener("focus", () => { checkExternal(); loadTree(); });
  document.addEventListener("visibilitychange", () => {
    if (document.hidden) { if (doc.dirty) save({ keepalive: true }); }
    else { checkExternal(); loadTree(); }
  });
  setInterval(checkExternal, 30000);
  window.addEventListener("beforeunload", (e) => {
    if (doc.dirty) { save({ keepalive: true }); e.preventDefault(); e.returnValue = ""; }
  });
  window.addEventListener("hashchange", route);

  await loadTree();

  // shared from another app (installed PWA share target): /app?title=…&text=…&url=…
  const q = new URLSearchParams(location.search);
  if (q.has("title") || q.has("text") || q.has("url")) {
    const shared = [q.get("title"), q.get("text"), q.get("url")].filter((x) => x && x.trim()).join("\n");
    history.replaceState(null, "", "/app" + location.hash);
    showWelcome();
    captureSheet(ctx, shared);
    return;
  }
  if (!parseHash().kind && !/^#\/(new|capture|search)$/.test(location.hash)) {
    const last = upref.get("last", "");
    if (last && tree.files().some((f) => f.path === last)) replaceHash(last);
  }
  if (location.hash && location.hash !== "#/") await route(); else showWelcome();
}

async function boot() {
  applyTheme(prefs.get("theme", "auto"));
  let me = { signed_in: false };
  setSignedOutHandler(() => showLogin(me));
  $("login-form").addEventListener("submit", (e) => { e.preventDefault(); signIn(e.target.user.value, e.target.password.value); });
  try { me = await api.me(); } catch (e) { $("login-err").textContent = e.message; }
  if (me.signed_in) await start(); else showLogin(me);
}

boot();
