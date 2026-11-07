// The folder tree: the notes folder (always first, pinned) plus any folders the
// user opened. Folders load when expanded and collapse/expand like Sublime's
// sidebar; the expanded set survives restarts. Drag a file onto a folder to move it.
import { basename, dirname, isInside, join } from '../core/paths.js';
import { contextMenu, toast } from './dialogs.js';
import { h, icon } from './dom.js';

const collator = new Intl.Collator(undefined, { numeric: true, sensitivity: 'base' });
const isConflictCopy = (name) => /\.sync-conflict-\d{8}-\d{6}-[A-Z0-9]+/.test(name);

export class Tree {
  constructor(app) {
    this.app = app;
    this.cache = new Map(); // dir -> entries
    this.expanded = new Set(app.ui.expanded || []);
    this.filter = '';
    this.el = h('div.tree', { role: 'tree', tabindex: '0' });
    this.el.addEventListener('contextmenu', (e) => { if (e.target === this.el) e.preventDefault(); });
    app.on('roots', () => this.renderAll());
    app.on('active', () => this.markActive());
    app.on('docs', () => this.markActive());
    app.on('tree-changed', (dir) => this.refreshDir(dir));
    app.on('settings', (keys) => { if (keys.some((k) => k === 'hidden' || k === 'showHidden')) this.renderAll(); });
    app.on('reveal', (p) => this.reveal(p));
  }

  hidden(name) {
    const s = this.app.settings.values;
    return !s.showHidden && s.hidden.includes(name);
  }

  async load(dir, force = false) {
    if (!force && this.cache.has(dir)) return this.cache.get(dir);
    let items;
    try {
      items = await this.app.backend.list(dir);
    } catch (e) {
      items = { error: e.code === 'DENIED' ? 'No permission to read this folder' : e.message };
    }
    if (Array.isArray(items)) items.sort((a, b) => (a.dir !== b.dir ? (a.dir ? -1 : 1) : collator.compare(a.name, b.name)));
    this.cache.set(dir, items);
    return items;
  }

  persist() {
    this.app.ui.expanded = [...this.expanded].slice(-400);
    this.app.saveSession();
  }

  async renderAll() {
    const roots = this.app.roots;
    let added = false;
    for (const r of roots) if (!this.expanded.has(r.path) && !this.app.ui.expandedInit?.includes(r.path)) {
      this.expanded.add(r.path); // a root starts open the first time it is shown
      (this.app.ui.expandedInit ||= []).push(r.path);
      added = true;
    }
    if (added) this.persist();
    const frag = document.createDocumentFragment();
    for (const r of roots) frag.append(await this.node({ name: r.name, dir: true }, r.path, 0, r));
    this.el.replaceChildren(frag);
    this.markActive();
  }

  async node(entry, path, depth, root = null) {
    const open = this.expanded.has(path);
    const conflict = !entry.dir && isConflictCopy(entry.name);
    const row = h('div.tn', {
      role: 'treeitem', 'aria-expanded': entry.dir ? String(open) : null, tabindex: '-1',
      class: [root ? 'root' : '', entry.dir ? 'dir' : 'file', conflict ? 'conflict' : '', entry.name.startsWith('.') ? 'dot' : ''].filter(Boolean).join(' '),
      style: { paddingLeft: 8 + depth * 14 + 'px' },
      dataset: { path },
      title: conflict ? 'Syncthing conflict copy — compare it with the original and delete one' : path,
      draggable: root ? null : 'true',
    },
    entry.dir ? icon(open ? 'chevronDown' : 'chevronRight', 'i tw') : h('span.tw'),
    entry.dir ? icon(open ? 'folderOpen' : 'folder', 'i ti') : icon(conflict ? 'warn' : /\.(png|jpe?g|gif|webp|svg|bmp|ico|avif)$/i.test(entry.name) ? 'image' : 'fileText', 'i ti'),
    h('span.tl', {}, entry.name),
    root?.pinned ? icon('pin', 'i tpin') : null);

    row.addEventListener('click', (e) => { e.stopPropagation(); entry.dir ? this.toggle(path, row) : this.app.open(path); if (this.app.mobile && !entry.dir) this.app.emit('drawer', false); });
    row.addEventListener('auxclick', (e) => { if (e.button === 1 && !entry.dir) this.app.open(path, { focus: false }); });
    row.addEventListener('contextmenu', (e) => { e.preventDefault(); e.stopPropagation(); this.menu(e.clientX, e.clientY, path, entry, root); });
    let pressTimer; // long-press on touch opens the context menu
    row.addEventListener('touchstart', (e) => { const t = e.touches[0]; pressTimer = setTimeout(() => this.menu(t.clientX, t.clientY, path, entry, root), 520); }, { passive: true });
    for (const ev of ['touchend', 'touchmove', 'touchcancel']) row.addEventListener(ev, () => clearTimeout(pressTimer), { passive: true });

    if (!root) row.addEventListener('dragstart', (e) => { e.dataTransfer.setData('text/x-mnemostxt-path', path); e.dataTransfer.effectAllowed = 'move'; });
    if (entry.dir) {
      row.addEventListener('dragover', (e) => { if (e.dataTransfer.types.includes('text/x-mnemostxt-path')) { e.preventDefault(); row.classList.add('drop'); } });
      row.addEventListener('dragleave', () => row.classList.remove('drop'));
      row.addEventListener('drop', async (e) => {
        e.preventDefault();
        row.classList.remove('drop');
        const from = e.dataTransfer.getData('text/x-mnemostxt-path');
        if (!from || from === path || dirname(from) === path || isInside(path, from)) return;
        await this.app.movePath(from, join(path, basename(from)));
      });
    }

    const wrap = h('div.tg', { dataset: { path } }, row);
    if (entry.dir && open) wrap.append(await this.children(path, depth + 1));
    return wrap;
  }

  async children(dir, depth) {
    const items = await this.load(dir);
    const box = h('div.tc', { role: 'group' });
    if (!Array.isArray(items)) { box.append(h('div.tn.err', { style: { paddingLeft: 8 + depth * 14 + 'px' } }, items.error)); return box; }
    const shown = items.filter((i) => !this.hidden(i.name));
    if (!shown.length) box.append(h('div.tn.empty', { style: { paddingLeft: 22 + depth * 14 + 'px' } }, 'empty'));
    for (const it of shown) box.append(await this.node(it, join(dir, it.name), depth));
    return box;
  }

  async toggle(path, row) {
    const wrap = row.parentElement;
    const depth = (parseInt(row.style.paddingLeft, 10) - 8) / 14;
    if (this.expanded.has(path)) {
      this.expanded.delete(path);
      wrap.querySelector(':scope > .tc')?.remove();
      row.setAttribute('aria-expanded', 'false');
      row.querySelector('.tw').replaceWith(icon('chevronRight', 'i tw'));
      row.querySelector('.ti').replaceWith(icon('folder', 'i ti'));
    } else {
      this.expanded.add(path);
      row.setAttribute('aria-expanded', 'true');
      row.querySelector('.tw').replaceWith(icon('chevronDown', 'i tw'));
      row.querySelector('.ti').replaceWith(icon('folderOpen', 'i ti'));
      wrap.append(await this.children(path, depth + 1));
      this.applyFilter();
      this.markActive();
    }
    this.persist();
  }

  async refreshDir(dir) {
    if (dir == null) return;
    this.cache.delete(dir);
    const wrap = this.el.querySelector(`.tg[data-path="${CSS.escape(dir)}"]`);
    if (!wrap) return;
    const row = wrap.querySelector(':scope > .tn');
    const depth = (parseInt(row.style.paddingLeft, 10) - 8) / 14;
    if (this.expanded.has(dir)) {
      const fresh = await this.children(dir, depth + 1);
      const old = wrap.querySelector(':scope > .tc');
      old ? old.replaceWith(fresh) : wrap.append(fresh);
    }
    this.applyFilter();
    this.markActive();
  }

  // Re-lists every expanded folder (on focus: picks up Syncthing changes).
  async refreshAll() {
    for (const dir of [...this.cache.keys()]) {
      const before = JSON.stringify(this.cache.get(dir));
      if (!this.expanded.has(dir)) { this.cache.delete(dir); continue; }
      await this.load(dir, true);
      if (JSON.stringify(this.cache.get(dir)) !== before) await this.refreshDir(dir).catch(() => {});
    }
  }

  async reveal(path) {
    const root = this.app.roots.find((r) => isInside(path, r.path));
    if (!root) return;
    let dir = dirname(path);
    const chain = [];
    while (dir && isInside(dir, root.path)) { chain.unshift(dir); if (dir === root.path) break; dir = dirname(dir); }
    let changed = false;
    for (const d of chain) if (!this.expanded.has(d)) { this.expanded.add(d); changed = true; }
    if (changed) { await this.renderAll(); this.persist(); }
    const row = this.el.querySelector(`.tn[data-path="${CSS.escape(path)}"]`);
    row?.scrollIntoView({ block: 'nearest' });
    row?.classList.add('flash');
    setTimeout(() => row?.classList.remove('flash'), 900);
  }

  markActive() {
    const p = this.app.active?.path;
    const open = new Set(this.app.docs.map((d) => d.path).filter(Boolean));
    for (const row of this.el.querySelectorAll('.tn.file')) {
      row.classList.toggle('active', row.dataset.path === p);
      row.classList.toggle('opened', open.has(row.dataset.path));
    }
  }

  setFilter(q) {
    this.filter = q.trim().toLowerCase();
    this.applyFilter();
  }

  applyFilter() {
    const q = this.filter;
    const groups = [...this.el.querySelectorAll('.tg')].reverse(); // children before parents
    for (const g of groups) {
      const row = g.querySelector(':scope > .tn');
      if (!q || row.classList.contains('root')) { g.hidden = false; continue; }
      const self = row.querySelector('.tl')?.textContent.toLowerCase().includes(q);
      const kid = [...g.querySelectorAll(':scope > .tc > .tg')].some((c) => !c.hidden);
      g.hidden = !(self || kid);
    }
  }

  menu(x, y, path, entry, root) {
    const app = this.app;
    const dir = entry.dir ? path : dirname(path);
    const items = [
      { label: 'New file…', run: () => app.createIn(dir, 'file') },
      { label: 'New folder…', run: () => app.createIn(dir, 'dir') },
      '-',
    ];
    if (!entry.dir) items.push({ label: 'Open', run: () => app.open(path) }, { label: 'Open in background', run: () => app.open(path, { focus: false }) }, '-');
    if (!root) {
      items.push({ label: 'Rename…', run: () => app.renamePath(path) });
      items.push({ label: 'Move to trash', danger: true, run: () => app.trashPath(path) });
      items.push('-');
    }
    items.push({ label: 'Copy path', run: () => navigator.clipboard?.writeText(path).then(() => toast('Path copied'), () => toast(path)) });
    items.push({ label: 'Refresh', run: () => this.refreshDir(dir) });
    if (root && !root.pinned) items.push('-', { label: 'Remove folder from sidebar', run: () => app.removeRoot(root.path) });
    contextMenu(x, y, items);
  }
}
