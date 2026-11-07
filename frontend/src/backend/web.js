// Backend for the web build: the Go server (server/) behind a sign-in. Paths are
// relative to the server's notes folder ("" is the root); nothing outside it is
// reachable. Session state and unsaved-edit backups stay in this browser.
import { BackendError } from './errors.js';

const H = { 'X-Mnemostxt': '1' };
const LS_SESSION = 'mnemostxt:session';
const LS_BACKUP = 'mnemostxt:backup:';

async function req(method, url, { body, json, raw } = {}) {
  let res;
  try {
    res = await fetch(url, {
      method,
      credentials: 'same-origin',
      headers: { ...H, ...(json !== undefined ? { 'Content-Type': 'application/json' } : {}) },
      body: json !== undefined ? JSON.stringify(json) : body,
    });
  } catch {
    throw new BackendError('OFFLINE', 'The server cannot be reached');
  }
  if (raw && res.ok) return res;
  const data = await res.json().catch(() => ({}));
  if (!res.ok) {
    const code = data.code || { 401: 'AUTH', 404: 'NOT_FOUND', 409: 'CONFLICT', 413: 'TOO_LARGE', 403: 'DENIED' }[res.status] || 'IO';
    throw new BackendError(code, data.error || res.statusText);
  }
  return data;
}

const q = (p) => encodeURIComponent(p);

export async function createWebBackend() {
  const api = new URL('../api/', location.href).pathname; // works under /app/
  const me = await req('GET', api + 'me');
  const info = me.signed_in ? await req('GET', api + 'info') : { version: '' };

  const be = {
    kind: 'web',
    platform: 'web',
    version: info.version,
    signedIn: !!me.signed_in,
    user: me.user,
    home: null,
    notesRoot: '',
    notesName: info.notes_name || 'notes',
    startupFiles: [],
    canOpenAnywhere: false,

    login: (user, password) => req('POST', api + 'login', { json: { user, password } }),
    logout: () => req('POST', api + 'logout', { json: {} }),

    list: (path) => req('GET', `${api}list?path=${q(path)}`),
    async read(path) {
      const res = await req('GET', `${api}file?path=${q(path)}`, { raw: true });
      return { bytes: new Uint8Array(await res.arrayBuffer()), mtime: Number(res.headers.get('X-Mtime')), size: Number(res.headers.get('Content-Length') || 0) };
    },
    write: (path, bytes, expectedMtime) =>
      req('PUT', `${api}file?path=${q(path)}${expectedMtime != null ? `&expect=${expectedMtime}` : ''}`, { body: bytes }),
    stat: async (path) => (await req('GET', `${api}stat?path=${q(path)}`)).stat,
    mkdirAll: (path) => req('POST', `${api}mkdir?path=${q(path)}`),
    createFile: (path) => req('POST', `${api}create?path=${q(path)}`),
    rename: (from, to) => req('POST', api + 'rename', { json: { from, to } }),
    trash: (path) => req('POST', `${api}trash?path=${q(path)}`),
    trashPurge: (days) => req('POST', `${api}trash/purge?days=${days}`),
    search: (_roots, query, o) =>
      req('GET', `${api}search?q=${q(query)}&regex=${o.regex ? 1 : 0}&case=${o.caseSensitive ? 1 : 0}&max=${o.max || 2000}`),

    loadSession: async () => { try { return JSON.parse(localStorage.getItem(LS_SESSION)); } catch { return null; } },
    saveSession: async (obj) => { try { localStorage.setItem(LS_SESSION, JSON.stringify(obj)); } catch { /* storage full or blocked */ } },
    backupWrite: async (id, text) => { try { localStorage.setItem(LS_BACKUP + id, text); } catch { /* storage full */ } },
    backupRead: async (id) => { try { return localStorage.getItem(LS_BACKUP + id); } catch { return null; } },
    backupDelete: async (id) => { try { localStorage.removeItem(LS_BACKUP + id); } catch { /* ignore */ } },
    backupList: async () => {
      try { return Object.keys(localStorage).filter((k) => k.startsWith(LS_BACKUP)).map((k) => k.slice(LS_BACKUP.length)); } catch { return []; }
    },

    setTitle: (t) => { document.title = t; },
    toggleFullscreen: () => (document.fullscreenElement ? document.exitFullscreen() : document.documentElement.requestFullscreen()).catch(() => {}),
  };
  return be;
}
