// The one settings file, shared by every mnemostxt (desktop, Android, web):
//   <notes folder>/logs/mnemostxt/settings.json
// (the app keeps nothing else at the top of the notes folder)
// It travels with the notes (Syncthing), so all devices look and behave alike.
// Unknown keys are kept, so a newer version never loses an older one's values.
import { join } from './paths.js';

export const SETTINGS_DIR = 'logs/mnemostxt';
export const TRASH_DIR = 'logs/mnemostxt/trash';
export const SETTINGS_FILE = 'settings.json';

export const DEFAULTS = Object.freeze({
  theme: 'dark', // dark | light | system
  fontSize: 14, // desktop and web
  mobileFontSize: 15, // phones
  lineHeight: 1.55,
  tabSize: 4,
  indentWithTabs: false,
  wordWrap: false,
  lineNumbers: true,
  minimap: false,
  highlightActiveLine: true,
  showWhitespace: false,
  autoCloseBrackets: true,
  autosave: 'notes', // notes (files in the notes folder) | all | off
  autosaveDelayMs: 1000,
  newFileEol: 'LF', // LF | CRLF
  trimTrailingWhitespaceOnSave: false,
  insertFinalNewlineOnSave: false,
  showHidden: false,
  hidden: ['.git', '.trash', '.obsidian', '.stfolder', '.stversions', 'node_modules'],
  trashDays: 30,
  markdownPreviewOnOpen: false,
  confirmDelete: true,
});

const ENUMS = { theme: ['dark', 'light', 'system'], autosave: ['notes', 'all', 'off'], newFileEol: ['LF', 'CRLF'] };
const RANGES = { fontSize: [8, 40], mobileFontSize: [8, 40], lineHeight: [1, 3], tabSize: [1, 16], autosaveDelayMs: [200, 60000], trashDays: [1, 3650] };

// Merges raw JSON onto the defaults, dropping values of the wrong type.
export function sanitize(raw) {
  const out = { ...DEFAULTS };
  if (!raw || typeof raw !== 'object' || Array.isArray(raw)) return out;
  for (const [k, v] of Object.entries(raw)) {
    if (!(k in DEFAULTS)) { out[k] = v; continue; }
    const d = DEFAULTS[k];
    if (Array.isArray(d)) { if (Array.isArray(v) && v.every((x) => typeof x === 'string')) out[k] = v; continue; }
    if (typeof v !== typeof d) continue;
    if (ENUMS[k] && !ENUMS[k].includes(v)) continue;
    if (RANGES[k]) { const [lo, hi] = RANGES[k]; if (!Number.isFinite(v)) continue; out[k] = Math.min(hi, Math.max(lo, v)); continue; }
    out[k] = v;
  }
  return out;
}

export class Settings {
  constructor(backend) {
    this.backend = backend;
    this.values = { ...DEFAULTS };
    this.listeners = new Set();
    this.mtime = null;
    this.timer = null;
  }

  get path() {
    const root = this.backend.notesRoot;
    return root == null ? null : join(root, SETTINGS_DIR, SETTINGS_FILE);
  }

  get(k) { return this.values[k]; }
  onChange(fn) { this.listeners.add(fn); return () => this.listeners.delete(fn); }
  emit(keys) { for (const fn of this.listeners) fn(keys, this.values); }

  async load() {
    const p = this.path;
    if (!p) return;
    try {
      const st = await this.backend.stat(p);
      if (!st) return; // first run: defaults until something changes
      const { bytes, mtime } = await this.backend.read(p);
      this.mtime = mtime;
      const next = sanitize(JSON.parse(new TextDecoder().decode(bytes)));
      const changed = Object.keys(next).filter((k) => JSON.stringify(next[k]) !== JSON.stringify(this.values[k]));
      this.values = next;
      if (changed.length) this.emit(changed);
    } catch (e) {
      console.warn('settings: using defaults —', e?.message || e);
    }
  }

  // Re-reads the file when another device (or the user, by hand) changed it.
  async refresh() {
    const p = this.path;
    if (!p) return;
    const st = await this.backend.stat(p).catch(() => null);
    if (st && st.mtime !== this.mtime) await this.load();
  }

  set(patch) {
    const next = sanitize({ ...this.values, ...patch });
    const changed = Object.keys(patch).filter((k) => JSON.stringify(next[k]) !== JSON.stringify(this.values[k]));
    if (!changed.length) return;
    this.values = next;
    this.emit(changed);
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.save(), 300);
  }

  async save() {
    const p = this.path;
    if (!p) return;
    clearTimeout(this.timer);
    const body = JSON.stringify(this.values, null, 2) + '\n';
    try {
      await this.backend.mkdirAll(join(this.backend.notesRoot, SETTINGS_DIR));
      const r = await this.backend.write(p, new TextEncoder().encode(body), null);
      this.mtime = r.mtime;
    } catch (e) {
      console.warn('settings: not saved —', e?.message || e);
    }
  }
}
