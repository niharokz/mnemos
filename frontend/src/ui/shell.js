// Lays out the window and wires the pieces together:
//   desktop: menu bar · sidebar (open editors, folders, find in files) · tabs ·
//            editor/viewer · markdown preview · status bar
//   phone:   app bar · drawer (same sidebar) · tabs · editor · symbol bar
import { EditorSelection } from '@codemirror/state';
import { undo, redo, indentMore, cursorCharLeft, cursorCharRight } from '@codemirror/commands';
import { basename } from '../core/paths.js';
import { isMarkdown, languages, PLAIN } from '../core/languages.js';
import { buildCommands, installKeys } from './commands.js';
import { MenuBar } from './menu.js';
import { Tree } from './tree.js';
import { Tabs } from './tabs.js';
import { StatusBar } from './statusbar.js';
import { SearchPanel } from './search.js';
import { Preview } from './preview.js';
import { Viewer } from './viewer.js';
import { showSettings } from './settingsSheet.js';
import { sheet, toast } from './dialogs.js';
import { h, icon, logo, debounce } from './dom.js';

export class Shell {
  constructor(app) {
    this.app = app;
    const be = app.backend;
    this.commands = buildCommands(app, this);
    this.cmd = installKeys(this.commands, app);
    this.menu = new MenuBar(app, this, this.cmd);
    this.tree = new Tree(app);
    this.tabs = new Tabs(app);
    this.status = new StatusBar(app, this);
    this.search = new SearchPanel(app);
    this.preview = new Preview(app, (url) => this.openExternal(url));
    this.viewer = new Viewer(app);
    this.build();
    this.wire();
    if (be.onOpenFiles) be.onOpenFiles((paths) => this.openPaths(paths));
    if (be.onDropPaths) be.onDropPaths((paths) => this.openPaths(paths));
  }

  run(id) { this.cmd.get(id)?.run(); }

  // ── layout ───────────────────────────────────────────────
  build() {
    const app = this.app;
    document.body.className = 'app' + (app.mobile ? ' mobile' : '') + ` be-${app.backend.kind}`;

    this.titleEl = h('div.ab-title');
    this.sidebarBtn = h('button.ghost.icon', { title: 'Sidebar (Ctrl+Shift+E)', 'aria-label': 'Sidebar', onclick: () => this.toggleSidebar() }, icon(app.mobile ? 'menu' : 'sidebar'));
    if (app.mobile) {
      this.previewBtn = h('button.ghost.icon', { title: 'Preview', 'aria-label': 'Markdown preview', onclick: () => app.setPreview(!app.ui.preview) }, icon('eye'));
      this.saveBtn = h('button.ghost.icon', { 'aria-label': 'Save', onclick: () => app.save() }, icon('save'));
      this.moreBtn = h('button.ghost.icon', { 'aria-label': 'Menu', onclick: () => (this.menu.drop ? this.menu.close() : this.menu.openMobile(this.moreBtn)) }, icon('more'));
      this.appbar = h('header.appbar', {}, this.sidebarBtn, this.titleEl,
        h('button.ghost.icon', { 'aria-label': 'Undo', onclick: () => undo(app.view) }, icon('undo')),
        h('button.ghost.icon', { 'aria-label': 'Redo', onclick: () => redo(app.view) }, icon('redo')),
        this.previewBtn, this.saveBtn, this.moreBtn);
    } else {
      this.appbar = h('header.appbar', {}, h('div.ab-logo', { title: `mnemostxt ${app.backend.version}` }, logo(18)), this.menu.el, this.titleEl,
        h('div.ab-actions', {},
          h('button.ghost.icon', { title: app.ui.menuCollapsed ? 'Expand menu bar' : 'Collapse menu bar', 'aria-label': 'Collapse menu bar', onclick: () => this.toggleMenu() }, icon('collapse')),
          this.sidebarBtn));
    }

    // sidebar
    this.filter = h('input.tree-filter', { type: 'search', placeholder: 'Filter', spellcheck: 'false', autocapitalize: 'off', oninput: (e) => this.tree.setFilter(e.target.value) });
    this.openList = h('div.open-list');
    this.openHead = h('button.sec-h', { onclick: () => { app.ui.openEditors = !(app.ui.openEditors ?? true); this.renderOpenList(); app.saveSession(); } });
    const notesRoot = () => app.backend.notesRoot;
    this.filesView = h('div.side-view.files', {},
      h('section.sec.open-sec', {}, this.openHead, this.openList),
      h('section.sec.folders', {},
        h('div.sec-h.static', {}, h('span', {}, 'Folders'), h('span.grow'),
          h('button.ghost.icon.sm', { title: 'New file', onclick: () => app.createIn(notesRoot(), 'file') }, icon('plus')),
          app.backend.canOpenAnywhere && app.backend.pickFolder ? h('button.ghost.icon.sm', { title: 'Open folder…', onclick: () => this.openFolder() }, icon('folder')) : null,
          h('button.ghost.icon.sm', { title: 'Refresh', onclick: () => this.tree.refreshAll().then(() => this.tree.renderAll()) }, icon('refresh'))),
        this.filter, this.tree.el));
    this.searchView = h('div.side-view.search', { hidden: true }, this.search.el);
    this.viewBtns = {
      files: h('button.ghost.icon', { title: 'Folders', 'aria-label': 'Folders', onclick: () => this.setSideView('files') }, icon('files')),
      search: h('button.ghost.icon', { title: 'Find in files (Ctrl+Shift+F)', 'aria-label': 'Find in files', onclick: () => this.setSideView('search') }, icon('search')),
    };
    this.side = h('aside.side', { 'aria-label': 'Sidebar' },
      h('div.side-top', {}, app.mobile ? h('div.side-brand', {}, logo(20), h('span.brand', {}, 'mnemostxt')) : null, h('span.grow'), this.viewBtns.files, this.viewBtns.search,
        h('button.ghost.icon', { title: 'Preferences', 'aria-label': 'Preferences', onclick: () => this.showSettings() }, icon('gear'))),
      this.filesView, this.searchView);
    this.scrim = h('div.scrim', { hidden: true, onclick: () => this.toggleSidebar(false) });
    this.sideResizer = h('div.resizer.v', { 'aria-hidden': 'true' });

    // centre
    this.banner = h('div.banner', { hidden: true });
    this.editorHost = app.view.dom.parentElement;
    this.editorHost.append(app.view.dom);
    this.empty = h('div.empty-state');
    this.prevResizer = h('div.resizer.v.prev', { 'aria-hidden': 'true' });
    this.work = h('div.work', {}, this.editorHost, this.viewer.el, this.empty, this.prevResizer, this.preview.el);
    this.center = h('main.center-col', {}, this.tabs.el, this.banner, this.work);
    this.main = h('div.main', {}, this.side, this.sideResizer, this.center);

    this.symbar = app.mobile ? this.buildSymbolBar() : null;
    this.printArea = h('pre#print-area', { 'aria-hidden': 'true' });
    document.body.replaceChildren(this.appbar, this.main, this.status.el, this.scrim, ...(this.symbar ? [this.symbar] : []), this.printArea);

    this.applySidebar();
    this.applyWidths();
    this.setSideView(app.ui.sidebarView || 'files');
    this.renderOpenList();
  }

  buildSymbolBar() {
    const app = this.app;
    const ins = (text) => () => {
      const v = app.view;
      v.dispatch(v.state.changeByRange((r) => ({ changes: { from: r.from, to: r.to, insert: text }, range: EditorSelection.cursor(r.from + text.length) })));
      v.focus();
    };
    const keys = [
      ['Tab', () => { indentMore(app.view); app.view.focus(); }],
      ['←', () => { cursorCharLeft(app.view); app.view.focus(); }],
      ['→', () => { cursorCharRight(app.view); app.view.focus(); }],
      ...['{', '}', '[', ']', '(', ')', '#', '-', '*', '/', ':', '_', '"', "'", '`', '=', '<', '>', '|', '\\', '~'].map((c) => [c, ins(c)]),
    ];
    // pointerdown + preventDefault keeps the keyboard open
    return h('div.symbar', { hidden: true }, keys.map(([label, fn]) => h('button', { onpointerdown: (e) => { e.preventDefault(); fn(); } }, label)));
  }

  // ── wiring ───────────────────────────────────────────────
  wire() {
    const app = this.app;
    app.on('active', (d) => this.showActive(d));
    app.on('docs', () => { this.renderOpenList(); this.renderTitle(); this.renderBanner(); });
    app.on('saved', () => { this.renderOpenList(); this.renderTitle(); });
    app.on('conflict', () => this.renderBanner());
    app.on('cursor', () => this.renderTitle());
    app.on('preview', () => this.showActive(app.active));
    app.on('drawer', (open) => this.toggleSidebar(open));
    app.on('settings', () => this.status.render());

    const recheck = debounce(() => { app.checkDisk(); this.tree.refreshAll(); }, 250);
    addEventListener('focus', recheck);
    document.addEventListener('visibilitychange', () => {
      if (document.visibilityState === 'visible') recheck();
      else app.flush();
    });
    setInterval(() => { if (document.visibilityState === 'visible') app.checkDisk(); }, app.backend.kind === 'web' ? 30000 : 15000);

    if (app.backend.onCloseRequested) app.backend.onCloseRequested(async () => { await app.flush(); });
    addEventListener('beforeunload', () => { app.flush(); });
    addEventListener('beforeprint', () => { this.printArea.textContent = app.active?.kind === 'text' ? app.textOf(app.active) : ''; });

    this.dragResize(this.sideResizer, (x) => { app.ui.sidebarWidth = Math.max(180, Math.min(560, x - this.side.getBoundingClientRect().left)); this.applyWidths(); });
    this.dragResize(this.prevResizer, (x) => {
      const r = this.work.getBoundingClientRect();
      app.ui.previewWidth = Math.max(20, Math.min(75, ((r.right - x) / r.width) * 100));
      this.applyWidths();
    });

    if (app.mobile) {
      // symbol bar above the keyboard while the editor has focus
      app.view.contentDOM.addEventListener('focus', () => { this.symbar.hidden = false; document.body.classList.add('typing'); });
      app.view.contentDOM.addEventListener('blur', () => setTimeout(() => { if (!app.view.hasFocus) { this.symbar.hidden = true; document.body.classList.remove('typing'); } }, 120));
      const vv = window.visualViewport;
      if (vv) vv.addEventListener('resize', () => { document.body.style.setProperty('--kb', Math.max(0, innerHeight - vv.height - vv.offsetTop) + 'px'); });
      // Android back: close the drawer, menu or preview before leaving the app
      addEventListener('popstate', () => {
        if (this.menu.drop) this.menu.close();
        else if (this.side.classList.contains('open')) this.toggleSidebar(false, true);
        else if (app.ui.preview) app.setPreview(false);
      });
    }
  }

  dragResize(handle, onMove) {
    handle.addEventListener('pointerdown', (e) => {
      e.preventDefault();
      handle.setPointerCapture(e.pointerId);
      document.body.classList.add('resizing');
      const move = (ev) => onMove(ev.clientX);
      const up = () => { handle.removeEventListener('pointermove', move); document.body.classList.remove('resizing'); this.app.saveSession(); this.app.view.requestMeasure(); };
      handle.addEventListener('pointermove', move);
      handle.addEventListener('pointerup', up, { once: true });
    });
  }

  applyWidths() {
    const ui = this.app.ui;
    document.body.style.setProperty('--side-w', ui.sidebarWidth + 'px');
    document.body.style.setProperty('--prev-w', ui.previewWidth + '%');
  }

  applySidebar() {
    const open = this.app.mobile ? this.side.classList.contains('open') : this.app.ui.sidebar;
    document.body.classList.toggle('no-side', !this.app.mobile && !this.app.ui.sidebar);
    this.scrim.hidden = !(this.app.mobile && open);
  }

  // ── active document ──────────────────────────────────────
  showActive(d) {
    const app = this.app;
    const text = d?.kind === 'text';
    this.editorHost.hidden = !text;
    this.empty.hidden = !!d;
    this.viewer.el.hidden = !d || text;
    if (d && !text) this.viewer.show(d); else this.viewer.clear();
    if (!d) this.renderEmpty();
    const md = text && isMarkdown(d.path || d.untitled || '');
    document.body.classList.toggle('is-md', !!md);
    this.prevResizer.hidden = !(md && app.ui.preview) || app.mobile;
    if (app.mobile) {
      document.body.classList.toggle('preview-only', !!(md && app.ui.preview));
      if (this.previewBtn) this.previewBtn.hidden = !md;
      if (md && app.ui.preview) history.pushState({ m: 'preview' }, '');
    }
    this.preview.render();
    this.renderTitle();
    this.renderBanner();
    this.renderOpenList();
    if (text) requestAnimationFrame(() => app.view.requestMeasure());
  }

  renderTitle() {
    const app = this.app;
    const d = app.active;
    if (!d) { this.titleEl.replaceChildren(h('span.muted', {}, app.mobile ? 'mnemostxt' : '')); return; }
    const dirty = app.dirty(d);
    this.titleEl.replaceChildren(...[
      h('span.ab-name', {}, (dirty ? '● ' : '') + app.displayName(d)),
      d.path ? h('span.ab-dir', {}, app.mobile ? app.displayDir(d) : '— ' + app.displayDir(d)) : null].filter(Boolean));
    this.titleEl.title = d.path || d.untitled;
    if (this.saveBtn) this.saveBtn.classList.toggle('attn', dirty);
    app.updateTitle();
  }

  renderOpenList() {
    const app = this.app;
    const open = app.ui.openEditors ?? true;
    this.openHead.replaceChildren(icon(open ? 'chevronDown' : 'chevronRight', 'i tw'), h('span', {}, 'Open editors'), h('span.badge', {}, app.docs.length));
    this.openList.hidden = !open;
    this.openList.replaceChildren(...app.docs.map((d) => h('div.oe', {
      class: [d === app.active && 'active', app.dirty(d) && 'dirty', d.conflict && 'warn'].filter(Boolean).join(' '),
      onclick: () => { app.activate(d); if (app.mobile) this.toggleSidebar(false); },
      title: d.path || d.untitled,
    }, icon(d.kind === 'image' ? 'image' : 'fileText', 'i ti'), h('span.tl', {}, app.displayName(d)), h('span.oe-dir', {}, d.path ? basename(app.displayDir(d)) : ''),
    h('button.tab-x', { 'aria-label': 'Close', onclick: (e) => { e.stopPropagation(); app.closeDoc(d); } }, h('span.dot'), icon('x')))));
  }

  renderBanner() {
    const app = this.app;
    const d = app.active;
    const b = this.banner;
    let content = null;
    if (d?.conflict === 'changed') {
      content = [icon('warn'), h('span.grow', {}, 'This file changed on disk while you had unsaved edits.'),
        h('button', { onclick: () => app.reload(d, { force: true }) }, 'Reload theirs'),
        h('button.primary', { onclick: () => app.save(d, { overwrite: true }) }, 'Keep mine')];
    } else if (d?.conflict === 'deleted') {
      content = [icon('warn'), h('span.grow', {}, 'This file was deleted or moved on disk.'),
        h('button', { onclick: () => app.closeDoc(d, { force: true }) }, 'Close'),
        h('button.primary', { onclick: () => { d.mtime = null; app.save(d, { overwrite: true }); } }, 'Save again')];
    }
    b.hidden = !content;
    b.replaceChildren(...(content || []));
  }

  renderEmpty() {
    const app = this.app;
    const recent = app.closed.slice(0, 6);
    this.empty.replaceChildren(h('div.welcome', {},
      logo(64),
      h('h1', {}, 'mnemostxt'),
      h('p.muted', {}, 'Just a notepad. Your notes folder is always on the left.'),
      h('div.welcome-actions', {},
        h('button.primary', { onclick: () => app.newDoc() }, 'New file', h('kbd', {}, 'Ctrl+N')),
        app.backend.canOpenAnywhere && app.backend.pickFiles ? h('button', { onclick: () => this.openFiles() }, 'Open file', h('kbd', {}, 'Ctrl+O')) : null,
        h('button', { onclick: () => this.showSearch() }, 'Find in files', h('kbd', {}, 'Ctrl+Shift+F'))),
      recent.length ? h('div.recent', {}, h('h3', {}, 'Recently closed'), recent.map((p) => h('button.ghost', { onclick: () => app.open(p), title: p }, icon('fileText'), basename(p)))) : null));
  }

  // ── commands used by the menu ────────────────────────────
  async openFiles() {
    const paths = await this.app.backend.pickFiles();
    this.openPaths(paths);
  }

  async openFolder() {
    const p = await this.app.backend.pickFolder('Open folder');
    if (p) { this.app.addRoot(p); this.toggleSidebar(true); }
  }

  async openPaths(paths) {
    for (const p of paths) {
      const st = await this.app.backend.stat(p).catch(() => null);
      if (st?.dir) { this.app.addRoot(p); this.toggleSidebar(true); } else if (st) await this.app.open(p);
      else toast(`Not found: ${p}`, { error: true });
    }
  }

  print() {
    if (this.app.active?.kind !== 'text') return;
    window.print();
  }

  async paste() {
    try {
      const t = await navigator.clipboard.readText();
      this.app.view.dispatch(this.app.view.state.replaceSelection(t));
    } catch { toast('Use Ctrl+V to paste'); }
  }

  toggleReadOnly() {
    const d = this.app.active;
    if (d?.kind !== 'text') return;
    d.readOnly = !d.readOnly;
    this.app.reconfigureActive();
    this.status.render();
  }

  showSearch() {
    if (this.app.mobile) this.toggleSidebar(true); else if (!this.app.ui.sidebar) this.toggleSidebar(true);
    this.setSideView('search');
    const v = this.app.view;
    const sel = this.app.active?.kind === 'text' ? v.state.sliceDoc(v.state.selection.main.from, v.state.selection.main.to) : '';
    this.search.focus(sel && !sel.includes('\n') ? sel : '');
  }

  setSideView(which) {
    this.app.ui.sidebarView = which;
    this.filesView.hidden = which !== 'files';
    this.searchView.hidden = which !== 'search';
    for (const [k, b] of Object.entries(this.viewBtns)) b.classList.toggle('on', k === which);
  }

  toggleMenu() {
    this.app.ui.menuCollapsed = !this.app.ui.menuCollapsed;
    this.menu.close();
    this.menu.render();
    this.app.saveSession();
  }

  toggleSidebar(force, fromHistory = false) {
    const app = this.app;
    if (app.mobile) {
      const open = force ?? !this.side.classList.contains('open');
      const was = this.side.classList.contains('open');
      this.side.classList.toggle('open', open);
      if (open && !was) history.pushState({ m: 'drawer' }, '');
      if (!open && was && !fromHistory && history.state?.m === 'drawer') history.back();
      if (open) app.view.contentDOM.blur();
    } else {
      app.ui.sidebar = force ?? !app.ui.sidebar;
      app.saveSession();
    }
    this.applySidebar();
    requestAnimationFrame(() => app.view.requestMeasure());
  }

  zoom(dir) {
    const app = this.app;
    app.ui.zoom = dir === 0 ? 0 : Math.max(-6, Math.min(24, app.ui.zoom + dir));
    app.applyLook();
    app.saveSession();
    toast(`Zoom ${app.fontSize()}px`, { ms: 900 });
  }

  showSettings() { return showSettings(this.app); }

  async logout() {
    await this.app.flush();
    await this.app.backend.logout().catch(() => {});
    location.reload();
  }

  openExternal(url) {
    if (this.app.backend.kind === 'web') { window.open(url, '_blank', 'noopener'); return; }
    import('@tauri-apps/plugin-opener').then((m) => m.openUrl(url)).catch(() => toast(url));
  }

  pickLanguage() {
    const app = this.app;
    const d = app.active;
    if (d?.kind !== 'text') return;
    const names = [PLAIN, ...languages.map((l) => l.name)];
    const input = h('input', { type: 'search', placeholder: 'Language', autofocus: true, spellcheck: 'false' });
    const list = h('div.pick-list');
    const draw = () => {
      const q = input.value.trim().toLowerCase();
      list.replaceChildren(...names.filter((n) => !q || n.toLowerCase().includes(q) || languages.find((l) => l.name === n)?.extensions.some((e) => e === q.replace(/^\./, '')))
        .slice(0, 200).map((n) => h('button.pick', { class: n === d.langName ? 'on' : '', onclick: () => { app.setLanguageName(n); list.closest('dialog').done(); } }, n)));
    };
    input.addEventListener('input', draw);
    input.addEventListener('keydown', (e) => { if (e.key === 'Enter') list.querySelector('button')?.click(); });
    draw();
    sheet({ title: 'Language', body: h('div', {}, input, list) });
  }

  showShortcuts() {
    const groups = [['File', 'file.', 'tab.'], ['Edit', 'edit.'], ['Search', 'search.', 'bookmark.'], ['View', 'view.'], ['Settings and help', 'settings.', 'help.']];
    const rows = (prefixes) => this.commands.filter((c) => c.keys.length && prefixes.some((p) => c.id.startsWith(p)) && (!c.when || c.when()))
      .map((c) => h('tr', {}, h('td', {}, c.label), h('td', {}, c.keys.map((k) => h('kbd', {}, k)))));
    const body = h('div.shortcuts', {},
      h('p.muted', {}, 'Notepad++ key bindings. Alt+drag selects a column; Ctrl+click adds a cursor.'),
      groups.map(([title, ...p]) => [h('h3', {}, title), h('table', {}, h('tbody', {}, rows(p)))]));
    sheet({ title: 'Keyboard shortcuts', body, wide: true });
  }

  showAbout() {
    const be = this.app.backend;
    sheet({
      title: 'About',
      body: h('div.about', {}, logo(56), h('h2', {}, 'mnemostxt'), h('p', {}, `Version ${be.version} · ${be.kind === 'web' ? 'web' : be.platform}`),
        h('p.muted', {}, 'A plain notepad for your notes folder — desktop, Android and web.'),
        h('p', {}, h('a', { href: '#', onclick: (e) => { e.preventDefault(); this.openExternal('https://gitlab.com/niharokz/mnemostxt'); } }, 'gitlab.com/niharokz/mnemostxt'))),
      actions: [{ label: 'OK', primary: true }],
    });
  }
}
