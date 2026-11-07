// Backend for the desktop (Linux, Windows) and Android builds: the Rust side of
// the Tauri app (app/src/*.rs) does the file work; see ARCHITECTURE.md.
import { invoke } from '@tauri-apps/api/core';
import { listen } from '@tauri-apps/api/event';
import { getCurrentWindow } from '@tauri-apps/api/window';
import { getCurrentWebview } from '@tauri-apps/api/webview';
import { fromBase64, toBase64 } from '../core/encoding.js';
import { fromRust } from './errors.js';

const call = async (cmd, args) => {
  try {
    return await invoke(cmd, args);
  } catch (e) {
    throw fromRust(e);
  }
};

export async function createTauriBackend() {
  const info = await call('app_info');
  const mobile = info.platform === 'android';
  let dialog = null;
  const dlg = async () => (dialog ??= await import('@tauri-apps/plugin-dialog'));

  const be = {
    kind: mobile ? 'android' : 'desktop',
    platform: info.platform,
    version: info.version,
    home: info.home,
    notesRoot: info.notes_dir, // null until chosen on first run
    defaultNotesRoot: info.default_notes_dir,
    notesName: 'notes',
    startupFiles: info.args || [],
    canOpenAnywhere: !mobile, // desktop opens any file; Android stays in the notes folder

    async setNotesRoot(path) {
      be.notesRoot = await call('set_notes_dir', { path });
      return be.notesRoot;
    },
    list: (path) => call('list_dir', { path }),
    async read(path) {
      const r = await call('read_file', { path });
      return { bytes: fromBase64(r.data), mtime: r.mtime, size: r.size };
    },
    write: (path, bytes, expectedMtime) => call('write_file', { path, data: toBase64(bytes), expectedMtime: expectedMtime ?? null }),
    stat: (path) => call('stat_path', { path }),
    mkdirAll: (path) => call('mkdir_all', { path }),
    createFile: (path) => call('create_file', { path }),
    rename: (from, to) => call('rename_path', { from, to }),
    trash: (path) => call('trash_path', { path, notesRoot: be.notesRoot }),
    trashPurge: (days) => call('trash_purge', { notesRoot: be.notesRoot, days }),
    search: (roots, query, opts) => call('search', { roots, query, regex: !!opts.regex, caseSensitive: !!opts.caseSensitive, hidden: opts.hidden || [], max: opts.max || 2000 }),

    // hot exit: session and unsaved-edit backups live in the app's own data folder
    loadSession: async () => { const s = await call('session_load'); return s ? JSON.parse(s) : null; },
    saveSession: (obj) => call('session_save', { json: JSON.stringify(obj) }),
    backupWrite: (id, text) => call('backup_write', { id, text }),
    backupRead: (id) => call('backup_read', { id }),
    backupDelete: (id) => call('backup_delete', { id }),
    backupList: () => call('backup_list'),

    async pickFiles() {
      const r = await (await dlg()).open({ multiple: true, directory: false });
      return r == null ? [] : (Array.isArray(r) ? r : [r]).map(norm);
    },
    async pickFolder(title) {
      const r = await (await dlg()).open({ directory: true, multiple: false, title });
      return r ? norm(r) : null;
    },
    async pickSavePath(defaultPath) {
      const r = await (await dlg()).save({ defaultPath });
      return r ? norm(r) : null;
    },
    onOpenFiles: (fn) => listen('open-files', (e) => fn((e.payload || []).map(norm))),
    onDropPaths: (fn) => getCurrentWebview().onDragDropEvent((e) => { if (e.payload.type === 'drop') fn(e.payload.paths.map(norm)); }),
    onCloseRequested: (fn) => getCurrentWindow().onCloseRequested(fn),
    setTitle: (t) => getCurrentWindow().setTitle(t).catch(() => {}),
    async toggleFullscreen() {
      const w = getCurrentWindow();
      await w.setFullscreen(!(await w.isFullscreen()));
    },
    quit: () => getCurrentWindow().close(),
  };
  return be;
}

const norm = (p) => String(p).replace(/\\/g, '/');
