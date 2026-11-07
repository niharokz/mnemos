// The app controller: open documents, the one editor view, saving, autosave,
// hot exit (session + backups of unsaved edits) and disk-change detection.
// UI pieces (menu, tree, tabs, status bar, panels) talk to it, never to each other.
import { EditorView } from '@codemirror/view';
import { EditorSelection, Text } from '@codemirror/state';
import { createState, reconfigure, setLanguage, setUpdateHandler } from '../editor/extensions.js';
import { trimTrailingChanges } from '../editor/lineops.js';
import { decode, encode, looksBinary, ansiLossy } from '../core/encoding.js';
import { detect, byNameOf, PLAIN, isMarkdown } from '../core/languages.js';
import { basename, dirname, extname, join, isInside, pretty, relative, validName } from '../core/paths.js';
import { TRASH_DIR } from '../core/settings.js';
import { confirm, prompt, sheet, toast, alert } from './dialogs.js';
import { debounce, h } from './dom.js';

const IMAGE_EXT = new Set(['.png', '.jpg', '.jpeg', '.gif', '.webp', '.bmp', '.ico', '.avif', '.svg']);
const MAX_OPEN = 50 * 1024 * 1024; // refuse beyond this
const READONLY_ABOVE = 20 * 1024 * 1024; // open read-only beyond this
const CLOSED_KEEP = 20;

const uid = () => Math.random().toString(36).slice(2, 10) + Date.now().toString(36).slice(-4);

export class App {
  constructor(backend, settings) {
    this.backend = backend;
    this.settings = settings;
    this.docs = [];
    this.active = null;
    this.extraRoots = []; // folders opened next to the notes folder (desktop)
    this.closed = []; // recently closed paths, for Ctrl+Shift+T
    this.ui = { menuCollapsed: false, sidebar: true, sidebarWidth: 270, sidebarView: 'files', preview: false, previewWidth: 46, zoom: 0, expanded: [] };
    this.listeners = new Map();
    this.mobile = matchMedia('(max-width: 759px)').matches || backend.kind === 'android';
    this.untitledCounter = 0;

    this.saveSession = debounce(() => this.writeSession(), 600);
    this.autosaveTimers = new Map();
    this.backupTimers = new Map();

    setUpdateHandler((u) => this.onEditorUpdate(u));
    this.view = new EditorView({ parent: h('div.editor-host') });
    settings.onChange((keys) => this.onSettings(keys));
  }

  // ── events ───────────────────────────────────────────────
  on(ev, fn) { if (!this.listeners.has(ev)) this.listeners.set(ev, new Set()); this.listeners.get(ev).add(fn); }
  emit(ev, arg) { for (const fn of this.listeners.get(ev) || []) fn(arg); }

  // ── queries ──────────────────────────────────────────────
  get roots() {
    const out = [];
    if (this.backend.notesRoot != null) out.push({ path: this.backend.notesRoot, name: this.backend.notesName || basename(this.backend.notesRoot) || 'notes', pinned: true });
    for (const p of this.extraRoots) if (!out.some((r) => r.path === p)) out.push({ path: p, name: basename(p) || p });
    return out;
  }
  dirty(doc) { return !!doc && doc.kind === 'text' && !doc.state.doc.eq(doc.savedDoc); }
  displayName(doc) { return doc.path ? basename(doc.path) : doc.untitled; }
  displayDir(doc) {
    if (!doc.path) return '';
    const d = dirname(doc.path);
    if (this.backend.kind === 'web') return d ? `${this.backend.notesName}/${d}` : this.backend.notesName;
    return pretty(d, this.backend.home);
  }
  inNotes(path) { return this.backend.notesRoot != null && path != null && isInside(path, this.backend.notesRoot); }
  get settingsPath() { return this.settings.path; }
  findDoc(path) { return this.docs.find((d) => d.path && d.path === path); }
  fontSize() { return (this.mobile ? this.settings.get('mobileFontSize') : this.settings.get('fontSize')) + this.ui.zoom; }

  // ── settings ─────────────────────────────────────────────
  onSettings(keys) {
    this.applyLook();
    if (keys.some((k) => !['theme', 'fontSize', 'mobileFontSize', 'lineHeight'].includes(k))) {
      for (const d of this.docs) d.configStale = true;
      if (this.active?.kind === 'text') this.reconfigureActive();
    }
    this.emit('settings', keys);
  }

  applyLook() {
    const s = this.settings.values;
    const root = document.documentElement;
    if (s.theme === 'system') root.removeAttribute('data-theme'); else root.dataset.theme = s.theme;
    root.style.setProperty('--ed-font-size', this.fontSize() + 'px');
    root.style.setProperty('--ed-line-height', String(s.lineHeight));
    this.view.requestMeasure();
  }

  reconfigureActive() {
    const d = this.active;
    this.view.dispatch({ effects: reconfigure(this.settings.values, d) });
    d.state = this.view.state;
    d.configStale = false;
  }

  // ── editor updates ───────────────────────────────────────
  onEditorUpdate(u) {
    const d = this.active;
    if (!d || d.kind !== 'text' || u.view !== this.view) return;
    d.state = u.state;
    if (u.docChanged) {
      const wasDirty = d.wasDirty;
      d.wasDirty = this.dirty(d);
      if (wasDirty !== d.wasDirty) this.emit('docs');
      this.scheduleAutosave(d);
      this.scheduleBackup(d);
      this.emit('content', d);
    }
    if (u.docChanged || u.selectionSet) { this.emit('cursor', d); this.saveSession(); }
  }

  // ── opening ──────────────────────────────────────────────
  newDoc({ text = '', focus = true } = {}) {
    this.untitledCounter++;
    const d = this.makeTextDoc({ path: null, untitled: `new ${this.untitledCounter}`, text, encoding: 'UTF-8', eol: this.settings.get('newFileEol') });
    this.docs.splice(this.active ? this.docs.indexOf(this.active) + 1 : this.docs.length, 0, d);
    this.activate(d, { focus });
    return d;
  }

  makeTextDoc({ path, untitled, text, encoding, eol, mtime = null, lang = null, readOnly = false, id = uid() }) {
    const d = { id, path, untitled, kind: 'text', encoding, eol: eol || 'LF', mtime, readOnly, langName: lang, conflict: null, scroll: null, mobile: this.mobile };
    d.state = createState(text, this.settings.values, d, null);
    d.savedDoc = d.state.doc;
    d.wasDirty = false;
    this.loadLanguage(d, lang);
    return d;
  }

  async loadLanguage(d, name = d.langName) {
    const desc = name ? byNameOf(name) : detect(d.path || d.untitled, d.state.doc.line(1).text);
    d.langName = desc ? desc.name : PLAIN;
    if (!desc) return this.applyLanguage(d, null);
    try {
      const support = desc.support || (await desc.load());
      this.applyLanguage(d, support);
    } catch (e) {
      console.warn('language load failed', desc.name, e);
    }
    this.emit('cursor', d);
  }

  applyLanguage(d, support) {
    if (this.active === d) { this.view.dispatch({ effects: setLanguage(support) }); d.state = this.view.state; } else d.state = d.state.update({ effects: setLanguage(support) }).state;
  }

  async setLanguageName(name) {
    const d = this.active;
    if (d?.kind !== 'text') return;
    d.langName = name;
    d.langNameManual = true;
    await this.loadLanguage(d, name === PLAIN ? null : name);
    if (name === PLAIN) { d.langName = PLAIN; this.applyLanguage(d, null); }
    this.emit('cursor', d);
    this.emit('docs');
    this.saveSession();
  }

  // Opens a file (or focuses it when already open). Options: line, col, focus, force (encoding).
  async open(path, opts = {}) {
    const existing = this.findDoc(path);
    if (existing && !opts.reload) {
      this.activate(existing, opts);
      if (opts.line) this.gotoLine(opts.line, opts.col);
      return existing;
    }
    let st;
    try {
      st = await this.backend.stat(path);
    } catch (e) { toast(`Cannot open ${basename(path)}: ${e.message}`, { error: true }); return null; }
    if (!st) { toast(`${basename(path)} no longer exists`, { error: true }); return null; }
    if (st.dir) { this.addRoot(path); return null; }
    if (st.size > MAX_OPEN) { alert('File too large', `${basename(path)} is ${(st.size / 1048576).toFixed(0)} MB. mnemostxt opens files up to 50 MB.`); return null; }
    let r;
    try { r = await this.backend.read(path); } catch (e) { toast(`Cannot read ${basename(path)}: ${e.message}`, { error: true }); return null; }
    const doc = this.docFromBytes(path, r, opts);
    if (existing) { // reload in place
      const i = this.docs.indexOf(existing);
      this.docs[i] = doc;
      doc.id = existing.id;
      if (this.active === existing) this.active = null;
    } else {
      const at = this.active ? this.docs.indexOf(this.active) + 1 : this.docs.length;
      this.docs.splice(at, 0, doc);
    }
    if (opts.focus !== false || !this.active) this.activate(doc, opts);
    if (opts.line) this.gotoLine(opts.line, opts.col);
    this.emit('docs');
    this.checkConflictCopies(doc);
    if (isMarkdown(path) && this.settings.get('markdownPreviewOnOpen') && !this.mobile) this.setPreview(true);
    this.saveSession();
    return doc;
  }

  docFromBytes(path, r, opts = {}) {
    const ext = extname(path);
    if (!opts.asText && IMAGE_EXT.has(ext)) return { id: uid(), path, kind: 'image', bytes: r.bytes, mtime: r.mtime, size: r.size };
    if (!opts.asText && ext === '.pdf') return { id: uid(), path, kind: 'pdf', bytes: r.bytes, mtime: r.mtime, size: r.size };
    if (!opts.asText && !opts.encoding && looksBinary(r.bytes)) return { id: uid(), path, kind: 'binary', bytes: r.bytes, mtime: r.mtime, size: r.size };
    const { text, encoding, eol } = decode(r.bytes, opts.encoding || null);
    const readOnly = r.size > READONLY_ABOVE || (this.backend.readOnly ?? false);
    const d = this.makeTextDoc({ path, text, encoding, eol: eol || this.settings.get('newFileEol'), mtime: r.mtime, lang: opts.lang || null, readOnly });
    if (readOnly && r.size > READONLY_ABOVE) toast('Large file: opened read-only');
    return d;
  }

  async openAsText(doc) {
    const r = { bytes: doc.bytes, mtime: doc.mtime, size: doc.size };
    const d = this.docFromBytes(doc.path, r, { asText: true });
    d.id = doc.id;
    this.docs[this.docs.indexOf(doc)] = d;
    this.active = null;
    this.activate(d);
    this.emit('docs');
  }

  activate(doc, { focus = true } = {}) {
    if (!doc) return;
    const prev = this.active;
    if (prev && prev.kind === 'text' && prev !== doc) {
      prev.state = this.view.state;
      prev.scroll = { top: this.view.scrollDOM.scrollTop, left: this.view.scrollDOM.scrollLeft };
    }
    this.active = doc;
    if (doc.kind === 'text') {
      if (prev !== doc || this.view.state !== doc.state) this.view.setState(doc.state);
      if (doc.configStale) this.reconfigureActive();
      if (doc.scroll) { const s = doc.scroll; requestAnimationFrame(() => { this.view.scrollDOM.scrollTop = s.top; this.view.scrollDOM.scrollLeft = s.left; }); }
      if (focus && !this.mobile) requestAnimationFrame(() => this.view.focus());
    }
    this.emit('active', doc);
    this.emit('cursor', doc);
    this.updateTitle();
    this.saveSession();
  }

  gotoLine(line, col = 1) {
    const d = this.active;
    if (d?.kind !== 'text') return;
    const doc = this.view.state.doc;
    const l = doc.line(Math.max(1, Math.min(line, doc.lines)));
    const pos = Math.min(l.to, l.from + Math.max(0, col - 1));
    this.view.dispatch({ selection: EditorSelection.cursor(pos), effects: EditorView.scrollIntoView(pos, { y: 'center' }) });
    this.view.focus();
  }

  nextTab(dir) {
    if (!this.docs.length) return;
    const i = this.docs.indexOf(this.active);
    this.activate(this.docs[(i + dir + this.docs.length) % this.docs.length]);
  }

  moveTab(from, to) {
    const [d] = this.docs.splice(from, 1);
    this.docs.splice(to, 0, d);
    this.emit('docs');
    this.saveSession();
  }

  updateTitle() {
    const d = this.active;
    const t = d ? `${this.dirty(d) ? '● ' : ''}${this.displayName(d)}${d.path ? ' — ' + this.displayDir(d) : ''} — mnemostxt` : 'mnemostxt';
    this.backend.setTitle?.(t);
  }

  // ── saving ───────────────────────────────────────────────
  textOf(d) {
    if (this.active === d) d.state = this.view.state;
    return d.state.doc.toString();
  }

  // Applies save-time cleanups as a normal (undoable) edit.
  tidyBeforeSave(d) {
    const s = this.settings.values;
    const changes = [];
    if (s.trimTrailingWhitespaceOnSave) changes.push(...trimTrailingChanges(d.state));
    if (s.insertFinalNewlineOnSave && d.state.doc.length && !d.state.doc.sliceString(d.state.doc.length - 1).endsWith('\n')) changes.push({ from: d.state.doc.length, insert: '\n' });
    if (!changes.length) return;
    const spec = { changes, userEvent: 'input.tidy' };
    if (this.active === d) { this.view.dispatch(spec); d.state = this.view.state; } else d.state = d.state.update(spec).state;
  }

  async save(d = this.active, { auto = false, overwrite = false } = {}) {
    if (!d || d.kind !== 'text' || d.readOnly) return false;
    if (!d.path) return auto ? false : this.saveAs(d);
    if (!auto) this.tidyBeforeSave(d);
    const snapshot = d.state.doc;
    if (d.encoding === 'ANSI' && ansiLossy(snapshot.toString()) && !auto) {
      if (!(await confirm('Characters will be lost', 'This file is ANSI, which cannot hold some characters you typed (they become "?"). Convert it to UTF-8 instead?', { ok: 'Convert to UTF-8', cancel: 'Save as ANSI' }))) { /* keep ANSI */ } else d.encoding = 'UTF-8';
    }
    d.saving = true;
    this.emit('saving', d);
    try {
      const r = await this.backend.write(d.path, encode(snapshot.toString(), d.encoding, d.eol), overwrite ? null : d.mtime);
      d.mtime = r.mtime;
      d.savedDoc = snapshot;
      d.conflict = null;
      d.wasDirty = this.dirty(d);
      this.backend.backupDelete(d.id).catch(() => {});
      if (d.path === this.settingsPath) await this.settings.load();
      this.emit('saved', d);
      return true;
    } catch (e) {
      if (e.code === 'CONFLICT') {
        d.conflict = 'changed';
        this.emit('conflict', d);
        if (!auto) return this.resolveConflict(d);
        return false;
      }
      toast(`Not saved: ${e.message}`, { error: true });
      this.emit('save-failed', d);
      return false;
    } finally {
      d.saving = false;
      this.emit('docs');
      this.updateTitle();
    }
  }

  async resolveConflict(d) {
    const v = await confirm('Changed on disk', `${this.displayName(d)} was changed by another program or device since you opened it.`, { ok: 'Overwrite', cancel: 'Cancel', third: 'Reload theirs', danger: true });
    if (v === true) return this.save(d, { overwrite: true });
    if (v === 'third') { await this.reload(d, { force: true }); return false; }
    return false;
  }

  async saveAs(d = this.active) {
    if (!d || d.kind !== 'text') return false;
    const name = d.path ? basename(d.path) : d.untitled.replace(/\s+/g, '-') + '.txt';
    let target;
    if (this.backend.pickSavePath && this.backend.canOpenAnywhere) {
      const base = d.path ? dirname(d.path) : this.backend.notesRoot ?? this.backend.home;
      target = await this.backend.pickSavePath(join(base, name));
    } else {
      const rel = await prompt('Save as', { label: `Path inside ${this.backend.notesName}`, value: d.path ? relative(d.path, this.backend.notesRoot) : name, ok: 'Save', selectStem: true });
      if (!rel) return false;
      const clean = rel.trim().replace(/^\/+/, '');
      if (!clean.split('/').every(validName)) { toast('That is not a valid file name', { error: true }); return false; }
      target = join(this.backend.notesRoot, clean);
    }
    if (!target) return false;
    const st = await this.backend.stat(target).catch(() => null);
    if (st && !(await confirm('Replace file?', `${basename(target)} already exists. Replace it?`, { ok: 'Replace', danger: true }))) return false;
    const other = this.findDoc(target);
    if (other && other !== d) this.closeDoc(other, { force: true });
    await this.backend.mkdirAll(dirname(target)).catch(() => {});
    const oldPath = d.path;
    d.path = target;
    d.untitled = null;
    d.mtime = null;
    const ok = await this.save(d, { overwrite: true });
    if (!ok) { d.path = oldPath; return false; }
    if (!oldPath) this.loadLanguage(d, null);
    this.emit('docs');
    this.emit('tree-changed', dirname(target));
    this.saveSession();
    return true;
  }

  async saveAll() {
    for (const d of this.docs) if (this.dirty(d)) await this.save(d);
  }

  scheduleAutosave(d) {
    const mode = this.settings.get('autosave');
    if (mode === 'off' || !d.path || d.readOnly) return;
    if (mode === 'notes' && !this.inNotes(d.path)) return;
    clearTimeout(this.autosaveTimers.get(d.id));
    this.autosaveTimers.set(d.id, setTimeout(() => {
      if (this.dirty(d) && this.docs.includes(d) && !d.conflict && !d.saving) this.save(d, { auto: true });
    }, this.settings.get('autosaveDelayMs')));
  }

  scheduleBackup(d) {
    clearTimeout(this.backupTimers.get(d.id));
    this.backupTimers.set(d.id, setTimeout(() => this.backupNow(d), 700));
  }

  backupNow(d) {
    clearTimeout(this.backupTimers.get(d.id));
    if (!this.docs.includes(d) || d.kind !== 'text') return Promise.resolve();
    return (this.dirty(d) ? this.backend.backupWrite(d.id, this.textOf(d)) : this.backend.backupDelete(d.id)).catch(() => {});
  }

  // ── reload, encoding, line endings ───────────────────────
  async reload(d = this.active, { force = false, encoding = null } = {}) {
    if (!d?.path) return;
    if (!force && this.dirty(d) && !(await confirm('Reload from disk?', `Your unsaved changes to ${this.displayName(d)} will be lost.`, { ok: 'Reload', danger: true }))) return;
    let r;
    try { r = await this.backend.read(d.path); } catch (e) { toast(e.message, { error: true }); return; }
    if (d.kind !== 'text') { Object.assign(d, { bytes: r.bytes, mtime: r.mtime, size: r.size }); this.emit('active', d); return; }
    const { text, encoding: enc, eol } = decode(r.bytes, encoding);
    const sel = d.state.selection.main.head;
    const spec = { changes: { from: 0, to: d.state.doc.length, insert: text }, selection: EditorSelection.cursor(Math.min(sel, text.length)), userEvent: 'reload' };
    if (this.active === d) { this.view.dispatch(spec); d.state = this.view.state; } else d.state = d.state.update(spec).state;
    Object.assign(d, { savedDoc: d.state.doc, mtime: r.mtime, encoding: enc, eol: eol || d.eol, conflict: null, wasDirty: false });
    this.backend.backupDelete(d.id).catch(() => {});
    this.emit('docs');
    this.emit('cursor', d);
    this.emit('content', d);
    this.updateTitle();
  }

  // "Convert to" keeps the text and saves it in another encoding.
  convertEncoding(enc) {
    const d = this.active;
    if (d?.kind !== 'text' || d.encoding === enc) return;
    d.encoding = enc;
    d.savedDoc = Text.of(['\u0000changed']); // forces dirty: the bytes on disk differ now
    this.markChanged(d);
  }

  setEol(eol) {
    const d = this.active;
    if (d?.kind !== 'text' || d.eol === eol) return;
    d.eol = eol;
    d.savedDoc = Text.of(['\u0000changed']);
    this.markChanged(d);
  }

  markChanged(d) {
    d.wasDirty = true;
    this.emit('docs');
    this.emit('cursor', d);
    this.scheduleAutosave(d);
    this.scheduleBackup(d);
    this.updateTitle();
  }

  // ── closing ──────────────────────────────────────────────
  async closeDoc(d = this.active, { force = false } = {}) {
    if (!d) return true;
    if (!force && this.dirty(d)) {
      this.activate(d);
      const v = await confirm('Save changes?', `Save changes to ${this.displayName(d)} before closing?`, { ok: 'Save', cancel: 'Cancel', third: "Don't save" });
      if (v === false) return false;
      if (v === true && !(await this.save(d))) return false;
    }
    clearTimeout(this.autosaveTimers.get(d.id));
    clearTimeout(this.backupTimers.get(d.id));
    this.backend.backupDelete(d.id).catch(() => {});
    if (d.path) { this.closed = [d.path, ...this.closed.filter((p) => p !== d.path)].slice(0, CLOSED_KEEP); }
    const i = this.docs.indexOf(d);
    this.docs.splice(i, 1);
    if (this.active === d) {
      this.active = null;
      const next = this.docs[Math.min(i, this.docs.length - 1)];
      if (next) this.activate(next); else { this.emit('active', null); this.updateTitle(); }
    }
    this.emit('docs');
    this.saveSession();
    return true;
  }

  async closeMany(list) {
    for (const d of list) if (!(await this.closeDoc(d))) return false;
    return true;
  }
  closeAll() { return this.closeMany([...this.docs]); }
  closeOthers(keep = this.active) { return this.closeMany(this.docs.filter((d) => d !== keep)); }
  closeRight(of = this.active) { return this.closeMany(this.docs.slice(this.docs.indexOf(of) + 1)); }

  async reopenClosed() {
    while (this.closed.length) {
      const p = this.closed.shift();
      if (await this.open(p)) return;
    }
    toast('No recently closed files');
  }

  // ── files and folders ────────────────────────────────────
  addRoot(path) {
    if (this.roots.some((r) => r.path === path)) { this.emit('reveal', path); return; }
    this.extraRoots.push(path);
    this.emit('roots');
    this.saveSession();
  }

  removeRoot(path) {
    this.extraRoots = this.extraRoots.filter((p) => p !== path);
    this.emit('roots');
    this.saveSession();
  }

  async createIn(dir, kind) {
    const name = await prompt(kind === 'dir' ? 'New folder' : 'New file', { label: 'Name', value: kind === 'dir' ? '' : 'untitled.md', ok: 'Create', selectStem: true });
    if (!name) return;
    const parts = name.trim().split('/').filter(Boolean);
    if (!parts.length || !parts.every(validName)) { toast('That is not a valid name', { error: true }); return; }
    const path = join(dir, ...parts);
    try {
      if (kind === 'dir') await this.backend.mkdirAll(path);
      else { await this.backend.mkdirAll(dirname(path)); await this.backend.createFile(path); }
    } catch (e) {
      toast(e.code === 'EXISTS' ? `${parts.at(-1)} already exists` : e.message, { error: true });
      return;
    }
    this.emit('tree-changed', dirname(path));
    if (kind !== 'dir') await this.open(path);
    else this.emit('reveal', path);
  }

  async renamePath(path) {
    const name = await prompt('Rename', { label: 'New name', value: basename(path), ok: 'Rename', selectStem: true });
    if (!name || name === basename(path)) return;
    if (!validName(name)) { toast('That is not a valid name', { error: true }); return; }
    const to = join(dirname(path), name);
    await this.movePath(path, to);
  }

  async movePath(path, to) {
    try {
      await this.backend.rename(path, to);
    } catch (e) {
      toast(e.code === 'EXISTS' ? `${basename(to)} already exists there` : e.message, { error: true });
      return false;
    }
    for (const d of this.docs) {
      if (d.path && isInside(d.path, path)) {
        d.path = to + d.path.slice(path.length);
        const st = await this.backend.stat(d.path).catch(() => null);
        if (st) d.mtime = st.mtime;
        if (d.kind === 'text') this.loadLanguage(d, null);
      }
    }
    this.emit('tree-changed', dirname(path));
    this.emit('tree-changed', dirname(to));
    this.emit('docs');
    this.updateTitle();
    this.saveSession();
    return true;
  }

  async trashPath(path) {
    const where = this.inNotes(path) ? `${this.backend.notesName}/${TRASH_DIR}` : 'the system trash';
    if (this.settings.get('confirmDelete') && !(await confirm('Move to trash?', `${basename(path)} goes to ${where}. You can restore it from there.`, { ok: 'Move to trash', danger: true }))) return;
    try {
      await this.backend.trash(path);
    } catch (e) { toast(e.message, { error: true }); return; }
    for (const d of [...this.docs]) if (d.path && isInside(d.path, path)) await this.closeDoc(d, { force: true });
    this.emit('tree-changed', dirname(path));
    toast(`Moved ${basename(path)} to trash`);
  }

  async emptyTrash() {
    const days = this.settings.get('trashDays');
    if (!(await confirm('Empty trash?', `Permanently delete items that have been in ${this.backend.notesName}/${TRASH_DIR} for more than ${days} days?`, { ok: 'Delete', danger: true }))) return;
    try {
      const r = await this.backend.trashPurge(days);
      toast(`Removed ${r.removed} item${r.removed === 1 ? '' : 's'} from trash`);
    } catch (e) { toast(e.message, { error: true }); }
  }

  // Syncthing leaves "name.sync-conflict-…" next to a note when two devices edit it.
  async checkConflictCopies(doc) {
    if (!doc.path) return;
    try {
      const items = await this.backend.list(dirname(doc.path));
      const stem = basename(doc.path).replace(/\.[^.]+$/, '');
      const copies = items.filter((i) => !i.dir && i.name.startsWith(stem + '.sync-conflict-')).map((i) => join(dirname(doc.path), i.name));
      doc.conflictCopies = copies;
      if (copies.length) this.emit('active', this.active);
    } catch { /* listing is best effort */ }
  }

  // ── disk changes ─────────────────────────────────────────
  async checkDisk() {
    for (const d of [...this.docs]) {
      if (!d.path || d.saving) continue;
      const st = await this.backend.stat(d.path).catch(() => undefined);
      if (st === undefined) continue; // offline or error: try later
      if (st === null) {
        if (d.conflict !== 'deleted') { d.conflict = 'deleted'; this.emit('conflict', d); this.emit('docs'); }
        continue;
      }
      if (d.conflict === 'deleted') { d.conflict = null; this.emit('conflict', d); }
      if (d.mtime != null && st.mtime !== d.mtime) {
        if (!this.dirty(d)) await this.reload(d, { force: true });
        else if (d.conflict !== 'changed') { d.conflict = 'changed'; this.emit('conflict', d); this.emit('docs'); }
      }
    }
    await this.settings.refresh();
  }

  // ── preview panel ────────────────────────────────────────
  setPreview(on) {
    this.ui.preview = on;
    this.emit('preview', on);
    this.saveSession();
  }

  // ── hot exit ─────────────────────────────────────────────
  sessionData() {
    if (this.active?.kind === 'text') this.active.state = this.view.state;
    return {
      v: 1,
      tabs: this.docs.map((d) => ({
        id: d.id, path: d.path, untitled: d.untitled || null, kind: d.kind, lang: d.kind === 'text' && d.langName !== PLAIN && d.langNameManual ? d.langName : null,
        encoding: d.encoding || null, eol: d.eol || null, sel: d.kind === 'text' ? { a: d.state.selection.main.anchor, h: d.state.selection.main.head } : null,
      })),
      active: this.active?.id || null,
      roots: this.extraRoots,
      closed: this.closed,
      ui: this.ui,
      untitledCounter: this.untitledCounter,
    };
  }

  async writeSession() {
    try { await this.backend.saveSession(this.sessionData()); } catch (e) { console.warn('session not saved', e); }
  }

  // Writes every pending backup and the session; called before the window closes.
  async flush() {
    this.saveSession.cancel();
    await Promise.all(this.docs.filter((d) => d.kind === 'text').map((d) => this.backupNow(d)));
    await this.writeSession();
  }

  // Step 1 of restoring: layout and folders, before the UI is built.
  async loadSession() {
    const s = await this.backend.loadSession().catch(() => null);
    if (s?.ui && typeof s.ui === 'object') Object.assign(this.ui, s.ui);
    if (Array.isArray(s?.roots)) this.extraRoots = s.roots.filter((p) => typeof p === 'string');
    if (Array.isArray(s?.closed)) this.closed = s.closed.slice(0, CLOSED_KEEP);
    this.untitledCounter = s?.untitledCounter || 0;
    this.session = s;
    return s;
  }

  // Step 2: reopen the tabs, with unsaved edits from their backups.
  async restore() {
    const s = this.session;
    const backups = new Set(await this.backend.backupList().catch(() => []));
    let activeDoc = null;
    for (const t of s?.tabs || []) {
      const backup = backups.has(t.id) ? await this.backend.backupRead(t.id).catch(() => null) : null;
      let d = null;
      if (t.path) {
        const st = await this.backend.stat(t.path).catch(() => null);
        if (st && !st.dir) {
          try {
            const r = await this.backend.read(t.path);
            d = this.docFromBytes(t.path, r, { encoding: t.kind === 'text' && t.encoding !== decode(r.bytes).encoding ? t.encoding : null, lang: t.lang, asText: t.kind === 'text' && backup != null });
          } catch { d = null; }
        } else if (backup != null) {
          d = this.makeTextDoc({ path: t.path, text: '', encoding: t.encoding || 'UTF-8', eol: t.eol, lang: t.lang });
          d.conflict = 'deleted';
        }
      } else if (backup != null) {
        d = this.makeTextDoc({ path: null, untitled: t.untitled || 'new', text: '', encoding: t.encoding || 'UTF-8', eol: t.eol, lang: t.lang });
      }
      if (!d) { backups.delete(t.id); continue; }
      d.id = t.id;
      if (t.lang) d.langNameManual = true;
      if (backup != null && d.kind === 'text') {
        d.state = d.state.update({ changes: { from: 0, to: d.state.doc.length, insert: backup } }).state;
        d.wasDirty = this.dirty(d);
      }
      if (t.sel && d.kind === 'text') {
        const len = d.state.doc.length;
        d.state = d.state.update({ selection: EditorSelection.range(Math.min(t.sel.a, len), Math.min(t.sel.h, len)) }).state;
      }
      backups.delete(t.id);
      this.docs.push(d);
      if (t.id === s.active) activeDoc = d;
    }
    for (const orphan of backups) this.backend.backupDelete(orphan).catch(() => {});
    this.emit('docs');
    this.emit('roots');
    if (this.docs.length) this.activate(activeDoc || this.docs[0], { focus: true });
    else this.emit('active', null);
    for (const d of this.docs) if (d.kind === 'text' && d.path) this.checkConflictCopies(d);
  }

  // ── settings file ────────────────────────────────────────
  async openSettingsFile() {
    const p = this.settingsPath;
    if (!p) return;
    if (!(await this.backend.stat(p).catch(() => null))) await this.settings.save();
    const d = await this.open(p);
    if (d && d.langName !== 'JSON') this.setLanguageName('JSON');
  }

  async chooseNotesRoot(first = false) {
    const be = this.backend;
    if (!be.setNotesRoot) return;
    const input = h('input', { value: be.notesRoot || be.defaultNotesRoot || '', spellcheck: 'false', autocapitalize: 'off' });
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') { e.preventDefault(); input.closest('dialog')?.done(input.value); } });
    const browse = be.pickFolder && be.kind === 'desktop'
      ? h('button', { type: 'button', onclick: async () => { const p = await be.pickFolder('Choose your notes folder'); if (p) input.value = p; } }, 'Browse…')
      : null;
    const body = h('div', {},
      h('p', {}, first
        ? 'mnemostxt keeps one folder always in the sidebar — your notes. Its settings live there too, in logs/mnemostxt/settings.json, so every device shares them.'
        : 'The notes folder is always in the sidebar and holds the shared settings file.'),
      h('label.f', {}, h('span', {}, 'Notes folder'), h('div.row', {}, input, browse)),
      be.kind === 'android' ? h('p.muted', {}, 'Android: allow "All files access" for mnemostxt when asked, so it can read a folder like /storage/emulated/0/notes.') : null);
    const v = await sheet({ title: first ? 'Welcome to mnemostxt' : 'Notes folder', body, actions: first ? [{ label: 'Use this folder', primary: true, run: () => input.value }] : [{ label: 'Cancel' }, { label: 'Use this folder', primary: true, run: () => input.value }] });
    const path = (v || '').trim();
    if (!path) { if (first && !be.notesRoot) return this.chooseNotesRoot(true); return; }
    try {
      await be.setNotesRoot(path);
    } catch (e) {
      await alert('Cannot use that folder', e.message);
      return this.chooseNotesRoot(first);
    }
    await this.settings.load();
    this.applyLook();
    this.emit('roots');
  }
}
