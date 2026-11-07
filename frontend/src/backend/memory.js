// In-memory backend for `npm run dev` and UI tests (open index.html?demo).
// Nothing is written anywhere; a reload starts over.
import { BackendError } from './errors.js';

const SAMPLE = {
  'notes/todo.md': '# Todo\n\n- [ ] renew domain\n- [x] backup the server\n\nSee [inbox](inbox.md).\n',
  'notes/inbox.md': '# Inbox\n\nQuick thoughts land here.\n',
  'notes/projects/mnemostxt.md': '---\ntitle: mnemostxt\ntags: [app]\n---\n\n## Plan\n\n1. editor\n2. **ship**\n\n```js\nconsole.log("hi")\n```\n',
  'notes/todo.sync-conflict-20261107-101500-ABCDEFG.md': '# Todo\n\n- [ ] renew domain (phone)\n',
  'server/docker/caddy/Caddyfile': '# reverse proxy\n{\n\temail admin@example.com\n}\n\nnotes.example.com {\n\treverse_proxy mnemostxt:8080\n\tencode zstd gzip\n}\n',
  'server/docker/compose.yml': 'services:\n  mnemostxt:\n    image: mnemostxt:local\n    restart: unless-stopped\n',
  'server/taskmaster/event_update.py': 'def main():\n    for e in load():\n        print(e)  # TODO\n\nif __name__ == "__main__":\n    main()\n',
};

export function createMemoryBackend() {
  const files = new Map(); // path -> {bytes, mtime}
  const dirs = new Set(['/', '/home', '/home/demo', '/home/demo/notes']);
  let t = Date.now() - 3600e3;
  const addDirs = (p) => { const parts = p.split('/'); for (let i = 2; i < parts.length; i++) dirs.add(parts.slice(0, i).join('/')); };
  for (const [k, v] of Object.entries(SAMPLE)) {
    const p = '/home/demo/' + k;
    files.set(p, { bytes: new TextEncoder().encode(v), mtime: (t += 60e3) });
    addDirs(p);
  }
  const session = { value: null };
  const backups = new Map();
  const now = () => Date.now();

  const be = {
    kind: 'desktop',
    platform: 'demo',
    version: 'dev',
    home: '/home/demo',
    notesRoot: '/home/demo/notes',
    defaultNotesRoot: '/home/demo/notes',
    notesName: 'notes',
    startupFiles: [],
    canOpenAnywhere: true,
    demo: true,

    async setNotesRoot(p) { dirs.add(p); be.notesRoot = p; return p; },
    async list(path) {
      const base = path.replace(/\/$/, '');
      if (!dirs.has(base)) throw new BackendError('NOT_FOUND', path);
      const out = new Map();
      for (const d of dirs) if (d.startsWith(base + '/') && !d.slice(base.length + 1).includes('/')) out.set(d, { name: d.slice(base.length + 1), dir: true, size: 0, mtime: 0 });
      for (const [f, v] of files) if (f.startsWith(base + '/') && !f.slice(base.length + 1).includes('/')) out.set(f, { name: f.slice(base.length + 1), dir: false, size: v.bytes.length, mtime: v.mtime });
      return [...out.values()];
    },
    async read(path) {
      const f = files.get(path);
      if (!f) throw new BackendError('NOT_FOUND', path);
      return { bytes: f.bytes, mtime: f.mtime, size: f.bytes.length };
    },
    async write(path, bytes, expected) {
      const f = files.get(path);
      if (expected != null && f && f.mtime !== expected) throw new BackendError('CONFLICT', 'changed on disk');
      const m = Math.max(now(), (f?.mtime || 0) + 1);
      files.set(path, { bytes, mtime: m });
      addDirs(path);
      return { mtime: m };
    },
    async stat(path) {
      if (dirs.has(path)) return { dir: true, size: 0, mtime: 0 };
      const f = files.get(path);
      return f ? { dir: false, size: f.bytes.length, mtime: f.mtime } : null;
    },
    async mkdirAll(path) { addDirs(path + '/x'); },
    async createFile(path) {
      if (files.has(path) || dirs.has(path)) throw new BackendError('EXISTS', 'already exists');
      return be.write(path, new Uint8Array(), null);
    },
    async rename(from, to) {
      if (files.has(to) || dirs.has(to)) throw new BackendError('EXISTS', 'already exists');
      for (const [k, v] of [...files]) if (k === from || k.startsWith(from + '/')) { files.delete(k); files.set(to + k.slice(from.length), v); addDirs(to + k.slice(from.length)); }
      for (const d of [...dirs]) if (d === from || d.startsWith(from + '/')) { dirs.delete(d); dirs.add(to + d.slice(from.length)); }
    },
    async trash(path) {
      for (const k of [...files.keys()]) if (k === path || k.startsWith(path + '/')) files.delete(k);
      for (const d of [...dirs]) if (d === path || d.startsWith(path + '/')) dirs.delete(d);
    },
    async trashPurge() { return { removed: 0, bytes: 0 }; },
    async search(roots, query, o) {
      const out = [];
      const re = o.regex ? new RegExp(query, o.caseSensitive ? 'g' : 'gi') : null;
      for (const [p, f] of files) {
        if (!roots.some((r) => p.startsWith(r + '/'))) continue;
        new TextDecoder().decode(f.bytes).split('\n').forEach((line, i) => {
          const col = re ? line.search(re) : (o.caseSensitive ? line.indexOf(query) : line.toLowerCase().indexOf(query.toLowerCase()));
          if (col >= 0) out.push({ path: p, line: i + 1, col: col + 1, text: line.slice(0, 300) });
        });
      }
      return out;
    },
    loadSession: async () => session.value,
    saveSession: async (s) => { session.value = s; },
    backupWrite: async (id, text) => { backups.set(id, text); },
    backupRead: async (id) => backups.get(id) ?? null,
    backupDelete: async (id) => { backups.delete(id); },
    backupList: async () => [...backups.keys()],
    async pickFiles() { return ['/home/demo/server/docker/compose.yml']; },
    async pickFolder() { return '/home/demo/server'; },
    async pickSavePath(p) { return p; },
    setTitle: (t) => { document.title = t; },
    toggleFullscreen: () => {},
  };
  return be;
}
