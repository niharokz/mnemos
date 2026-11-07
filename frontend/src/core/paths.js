// Path helpers. Every backend speaks forward slashes ("D:/notes/a.md",
// "/home/me/notes/a.md", or "a.md" relative to the web notes root).

export const norm = (p) => (p || '').replace(/\\/g, '/').replace(/\/{2,}/g, '/');

export function join(...parts) {
  const out = parts.filter((p) => p !== '' && p != null).map(norm).join('/');
  return out.replace(/\/{2,}/g, '/');
}

export function basename(p) {
  const s = norm(p).replace(/\/+$/, '');
  const i = s.lastIndexOf('/');
  return i < 0 ? s : s.slice(i + 1);
}

export function dirname(p) {
  const s = norm(p).replace(/\/+$/, '');
  const i = s.lastIndexOf('/');
  if (i < 0) return '';
  if (i === 0) return '/';
  if (/^[A-Za-z]:$/.test(s.slice(0, i))) return s.slice(0, i + 1); // "D:/"
  return s.slice(0, i);
}

// ".md" (lower case), "" when there is none; dotfiles like ".env" have none.
export function extname(p) {
  const b = basename(p);
  const i = b.lastIndexOf('.');
  return i > 0 ? b.slice(i).toLowerCase() : '';
}

export function stem(p) {
  const b = basename(p);
  const e = extname(b);
  return e ? b.slice(0, -e.length) : b;
}

// True when child is root itself or inside it (case-insensitive on drive paths).
export function isInside(child, root) {
  if (root == null) return false;
  let c = norm(child).replace(/\/+$/, '');
  let r = norm(root).replace(/\/+$/, '');
  if (/^[A-Za-z]:/.test(r)) { c = c.toLowerCase(); r = r.toLowerCase(); }
  if (r === '') return !c.startsWith('/') && !/^[A-Za-z]:/.test(c);
  return c === r || c.startsWith(r + '/');
}

// Path of child relative to root ("" for root itself), or null when outside.
export function relative(child, root) {
  if (!isInside(child, root)) return null;
  const r = norm(root).replace(/\/+$/, '');
  const c = norm(child).replace(/\/+$/, '');
  return r === '' ? c : c.slice(r.length + 1);
}

// Shortens the home folder to "~" for display.
export function pretty(p, home) {
  const s = norm(p);
  if (home && isInside(s, home)) return '~' + s.slice(norm(home).replace(/\/+$/, '').length);
  return s;
}

// A file name the user may type: no separators, not "." or "..", no control chars.
export function validName(name) {
  const n = (name || '').trim();
  if (!n || n === '.' || n === '..') return false;
  // eslint-disable-next-line no-control-regex
  return !/[\\/:*?"<>|\x00-\x1f]/.test(n);
}
