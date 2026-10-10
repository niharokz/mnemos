// main.js — samanote's app logic: sign-in, routing (#/n/<path>), opening notes,
// autosave with conflict handling, views (edit / split / preview), the sidebar and
// every file action. Server rules this relies on:
//   • a save carries the version it was based on; 409 = changed elsewhere
//   • delete = move to the vault's .trash folder
//   • nothing is locked: Obsidian, Syncthing, Omnimo and Kronos edit the same files

import { api, setSignedOutHandler, rawURL } from "./api.js";
import { h, prefs, toast, promptText, confirmSheet, menuSheet } from "./ui.js";
import { Tree } from "./tree.js";
import { Editor } from "./editor.js";
import { Preview } from "./preview.js";

const $ = (id) => document.getElementById(id);
const narrowSide = matchMedia("(max-width: 899px)");
const narrowSplit = matchMedia("(max-width: 819px)");

const cfg = { autosave_ms: 1000, max_bytes: 5 << 20, version: "" };

/** The open note. */
const doc = {
  path: "",        // "" = nothing open
  version: "",     // version the editor text is based on
  dirty: false,    // editor text differs from what was last saved/loaded
  saving: null,    // promise of the save in flight
  conflict: null,  // {current, version, deleted} while the conflict bar is up
  readOnly: false,
  isText: true,
};

let tree, editor, preview, autosaveTimer, started = false;

// ── routing ──────────────────────────────────────────────────────────────────
const noteHash = (p) => "#/n/" + p.split("/").map(encodeURIComponent).join("/");
function hashPath() {
  const m = location.hash.match(/^#\/n\/(.+)$/);
  if (!m) return "";
  try { return m[1].split("/").map(decodeURIComponent).join("/"); } catch { return ""; }
}
const go = (p) => { location.hash = p ? noteHash(p) : "#/"; };
const replaceHash = (p) => history.replaceState(null, "", p ? noteHash(p) : "#/");
const dirOf = (p) => (p.includes("/") ? p.slice(0, p.lastIndexOf("/")) : "");
const baseOf = (p) => p.slice(p.lastIndexOf("/") + 1);

async function route() {
  if (location.hash === "#/new") { // home-screen shortcut "New note"
    replaceHash(doc.path);
    if (!doc.path) showWelcome();
    newNote("");
    return;
  }
  const p = hashPath();
  if (p === doc.path && p) return;
  await openNote(p);
}

// ── status / views ───────────────────────────────────────────────────────────
const STATUS = { saved: "Saved", unsaved: "Edited", saving: "Saving…", conflict: "Conflict", error: "Not saved", readonly: "Read-only" };
function setStatus(s, detail = "") {
  const el = $("status");
  el.dataset.s = s;
  el.textContent = s ? STATUS[s] : "";
  el.title = detail || STATUS[s] || "";
}

function effectiveView() {
  if (!doc.isText) return "preview";
  const v = prefs.get("view", narrowSplit.matches ? "edit" : "split");
  return v === "split" && narrowSplit.matches ? "edit" : v;
}

function applyView() {
  const v = effectiveView();
  // CSS turns "split" into "edit" on narrow screens, so keep the preference as-is
  $("panes").dataset.view = doc.isText ? prefs.get("view", narrowSplit.matches ? "edit" : "split") : "preview";
  for (const b of $("views").querySelectorAll("button")) {
    b.setAttribute("aria-pressed", String(b.dataset.v === v));
    b.disabled = !doc.isText;
  }
  if (v !== "edit" && doc.isText && doc.path) preview.render(doc.path, editor.text());
}

function setView(v) {
  prefs.set("view", v);
  if (v !== "preview") prefs.set("editView", v);
  applyView();
  if (v !== "preview" && doc.isText) editor.focus();
}

function toggleView() {
  if (!doc.isText) return;
  setView(effectiveView() === "preview" ? prefs.get("editView", narrowSplit.matches ? "edit" : "split") : "preview");
}

const previewVisible = () => effectiveView() !== "edit";

// ── opening notes ────────────────────────────────────────────────────────────
function setCrumb(path) {
  const c = $("crumb");
  const name = baseOf(path);
  c.querySelector(".dir").textContent = dirOf(path) ? dirOf(path) + "/" : "";
  c.querySelector(".name").textContent = name.replace(/\.md$/i, "");
  c.title = path;
  document.title = (name.replace(/\.md$/i, "") || "samanote") + " · samanote";
}

/** Saves and asks before leaving a note with changes that could not be saved. */
async function leaveCurrent() {
  if (!doc.path) return true;
  clearTimeout(autosaveTimer);
  if (doc.dirty && !doc.conflict) await save();
  if (doc.dirty) return confirmSheet("Leave without saving?", "Your latest edits to this note are not saved.", "Leave", true);
  return true;
}

async function openNote(p) {
  if (!(await leaveCurrent())) { replaceHash(doc.path); return; }
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
  prefs.set("last", f.path);
  tree.setActive(f.path);
  closeDrawer();
}

function loadFile(f) {
  Object.assign(doc, { path: f.path, version: f.version, dirty: false, conflict: null, readOnly: !!f.readonly, isText: !f.binary && !f.too_large });
  $("conflict").hidden = true;
  $("welcome").hidden = true;
  $("panes").hidden = false;
  $("note-menu").hidden = false;
  $("views").hidden = false;
  setCrumb(f.path);
  if (doc.isText) {
    editor.load(f.content, { readOnly: doc.readOnly });
    preview.clear();
  } else {
    preview.showFile(f);
  }
  applyView();
  setStatus(doc.readOnly ? "readonly" : "saved", doc.readOnly && doc.isText ? `Bigger than ${Math.round(cfg.max_bytes / 1048576)} MB — open it in Obsidian to edit` : "");
  if (doc.isText && effectiveView() !== "preview" && !narrowSide.matches) editor.focus();
}

function showWelcome() {
  Object.assign(doc, { path: "", version: "", dirty: false, conflict: null, readOnly: false, isText: true });
  $("panes").hidden = true;
  $("conflict").hidden = true;
  $("welcome").hidden = false;
  $("note-menu").hidden = true;
  $("views").hidden = true;
  setCrumb("");
  $("crumb").querySelector(".name").textContent = "";
  setStatus("");
  tree.setActive("");
  renderRecent();
}

function renderRecent() {
  const files = tree.files().filter((f) => /\.(md|markdown|txt)$/i.test(f.name)).sort((a, b) => b.mtime - a.mtime).slice(0, 8);
  const ago = (ms) => {
    const s = (Date.now() - ms) / 1000;
    if (s < 3600) return Math.max(1, Math.round(s / 60)) + "m";
    if (s < 86400) return Math.round(s / 3600) + "h";
    return Math.round(s / 86400) + "d";
  };
  $("recent").replaceChildren(files.length ? h("div", {}, h("h2", {}, "Recently changed"),
    h("ul", {}, files.map((f) => h("li", {}, h("a", { href: noteHash(f.path) }, h("span.ellipsis", {}, f.name.replace(/\.md$/i, "")), h("small", {}, ago(f.mtime))))))) : "");
}

// ── saving ───────────────────────────────────────────────────────────────────
function onChange() {
  if (doc.readOnly || !doc.path) return;
  doc.dirty = true;
  if (doc.conflict) return;
  setStatus("unsaved");
  clearTimeout(autosaveTimer);
  autosaveTimer = setTimeout(save, cfg.autosave_ms);
  if (previewVisible()) preview.schedule(doc.path, editor.text());
}

/** Saves the open note if it changed. Resolves true when everything is saved. */
async function save({ keepalive = false } = {}) {
  clearTimeout(autosaveTimer);
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
        setStatus("saved");
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
    ? "This note was deleted or moved by another app. Your text is still here."
    : "This note was changed by another app (Obsidian, Syncthing …) while you were editing.";
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
  if (previewVisible()) preview.render(doc.path, c.current);
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
async function checkExternal() {
  if (!doc.path || !doc.isText || doc.conflict || doc.saving || document.hidden) return;
  try {
    const { version } = await api.version(doc.path);
    if (version === doc.version) return;
    if (doc.dirty) { save(); return; } // the save will raise the conflict bar
    const f = await api.read(doc.path);
    if (doc.dirty || f.path !== doc.path) return;
    doc.version = f.version;
    doc.readOnly = !!f.readonly;
    editor.reload(f.content, { readOnly: doc.readOnly });
    if (previewVisible()) preview.render(doc.path, f.content);
    toast("Updated — this note changed in another app");
  } catch (e) {
    if (e.status === 404) showConflict({ deleted: true, current: "", version: "" });
  }
}

// ── tree and file actions ────────────────────────────────────────────────────
async function loadTree() {
  try {
    tree.set(await api.tree());
    if (!doc.path && !$("welcome").hidden) renderRecent();
  } catch (e) {
    if (e.status !== 401) toast("Couldn't load the notes list: " + e.message, true);
  }
}

const folderFor = (dir) => (dir != null ? dir : dirOf(doc.path));

async function newNote(dir) {
  const prefix = folderFor(dir) ? folderFor(dir) + "/" : "";
  const v = await promptText("New note", {
    label: "Name", value: prefix, placeholder: "ideas/my note", ok: "Create",
    hint: "Use / for folders; they are created as needed. .md is added when there is no extension.",
    select: [prefix.length, prefix.length],
  });
  if (!v) return;
  try {
    const { path } = await api.create(v);
    await loadTree();
    if (prefs.get("view", "split") === "preview") prefs.set("view", prefs.get("editView", narrowSplit.matches ? "edit" : "split"));
    go(path);
  } catch (e) { toast(e.message, true); }
}

async function newFolder(dir) {
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

/** Is path the open note, or inside folder `node`? */
const affects = (node) => doc.path && (doc.path === node.path || (node.dir && doc.path.startsWith(node.path + "/")));

async function renameNode(node) {
  const name = baseOf(node.path);
  const dot = node.dir ? -1 : name.lastIndexOf(".");
  const start = node.path.length - name.length;
  const v = await promptText(node.dir ? "Rename or move folder" : "Rename or move note", {
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
      prefs.set("last", doc.path);
    }
    if (node.dir && tree.open.has(node.path)) tree.open.add(path);
    await loadTree();
    tree.setActive(doc.path);
    toast("Renamed");
  } catch (e) { toast(e.message, true); }
}

async function trashNode(node) {
  const ok = await confirmSheet("Move to trash?",
    `“${node.path}” will move to the .trash folder of your vault. You can restore it from there in Obsidian or a file manager.`,
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

async function nodeMenu(node) {
  const items = node.dir
    ? [
        { label: "New note here", icon: "note-plus", value: "note" },
        { label: "New folder here", icon: "folder-plus", value: "folder" },
        { label: "Rename or move", icon: "move", value: "rename" },
        { label: "Move to trash", icon: "trash", value: "trash", danger: true },
      ]
    : [
        { label: "Rename or move", icon: "move", value: "rename" },
        { label: "Open raw file", icon: "raw", value: "raw" },
        { label: "Copy path", icon: "copy", value: "copy" },
        { label: "Move to trash", icon: "trash", value: "trash", danger: true },
      ];
  const a = await menuSheet(node.path || "Vault", items);
  if (a === "note") newNote(node.path);
  else if (a === "folder") newFolder(node.path);
  else if (a === "rename") renameNode(node);
  else if (a === "trash") trashNode(node);
  else if (a === "raw") window.open(rawURL(node.path), "_blank", "noopener");
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

// ── sign-in ──────────────────────────────────────────────────────────────────
function showLogin() {
  $("app").hidden = true;
  $("login").hidden = false;
  $("login-err").textContent = started ? "Your session ended — sign in again. Unsaved text is kept." : "";
  $("login-form").user.focus();
}

async function onLogin(e) {
  e.preventDefault();
  const f = e.target, btn = f.querySelector("button");
  btn.disabled = true;
  $("login-err").textContent = "";
  try {
    await api.login(f.user.value, f.password.value);
    f.password.value = "";
    $("login").hidden = true;
    if (started) { $("app").hidden = false; if (doc.dirty) save(); loadTree(); }
    else await start();
  } catch (err) {
    $("login-err").textContent = err.message;
  } finally {
    btn.disabled = false;
  }
}

// ── start ────────────────────────────────────────────────────────────────────
async function start() {
  started = true;
  $("app").hidden = false;
  try { Object.assign(cfg, await api.config()); } catch { /* defaults */ }
  $("ver").textContent = cfg.version ? "v" + cfg.version : "";

  tree = new Tree($("tree"), { open: (p) => (p === doc.path ? closeDrawer() : go(p)), action: nodeMenu });
  editor = new Editor($("editor"), { change: onChange, save: () => save(), toggleView });
  preview = new Preview($("preview"), $("preview-pane"));

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
  editor.scroller.addEventListener("scroll", () => { if (effectiveView() === "split") preview.follow(editor.scroller); }, { passive: true });

  // keyboard (the editor handles Ctrl+S / Ctrl+E itself while focused)
  document.addEventListener("keydown", (e) => {
    const mod = e.ctrlKey || e.metaKey;
    if (mod && !e.altKey && e.key.toLowerCase() === "s") { e.preventDefault(); save(); }
    else if (mod && !e.altKey && e.key.toLowerCase() === "e") { e.preventDefault(); toggleView(); }
    else if (e.altKey && !mod && e.code === "KeyN") { e.preventDefault(); newNote(); }
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
  if (location.hash === "#/new") { await route(); return; }
  if (!hashPath()) {
    const last = prefs.get("last", "");
    if (last && tree.files().some((f) => f.path === last)) replaceHash(last);
  }
  if (hashPath()) await route(); else showWelcome();
}

async function boot() {
  applyTheme(prefs.get("theme", "auto"));
  setSignedOutHandler(showLogin);
  $("login-form").addEventListener("submit", onLogin);
  let me = { signed_in: false };
  try { me = await api.me(); } catch (e) { $("login-err").textContent = e.message; }
  if (me.signed_in) await start(); else showLogin();
}

boot();
