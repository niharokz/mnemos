// Status bar: save state, conflicts, cursor position, indentation, encoding,
// line endings and language — each clickable, like Notepad++/VS Code.
import { ENCODINGS } from '../core/encoding.js';
import { contextMenu } from './dialogs.js';
import { h } from './dom.js';

export class StatusBar {
  constructor(app, ui) {
    this.app = app;
    this.ui = ui;
    this.el = h('footer.status');
    this.left = h('div.st-left');
    this.right = h('div.st-right');
    this.el.append(this.left, this.right);
    for (const ev of ['cursor', 'active', 'docs', 'saved', 'saving', 'save-failed', 'conflict', 'settings']) app.on(ev, () => this.render());
    this.render();
  }

  item(text, { title, onclick, cls } = {}) {
    return h(onclick ? 'button.st' : 'span.st', { title, onclick, class: cls }, text);
  }

  render() {
    const app = this.app;
    const d = app.active;
    const left = [];
    const right = [];
    if (d) {
      if (d.saving) left.push(this.item('Saving…', { cls: 'muted' }));
      else if (d.conflict === 'changed') left.push(this.item('⚠ Changed on disk', { cls: 'warn', onclick: () => app.resolveConflict(d) }));
      else if (d.conflict === 'deleted') left.push(this.item('⚠ Deleted on disk', { cls: 'warn' }));
      else if (app.dirty(d)) left.push(this.item(d.path ? 'Unsaved' : 'Not saved yet', { cls: 'muted' }));
      else if (d.path) left.push(this.item('Saved', { cls: 'ok' }));
      if (d.readOnly) left.push(this.item('Read-only', { cls: 'muted', onclick: () => this.ui.toggleReadOnly() }));
      if (d.conflictCopies?.length) left.push(this.item(app.mobile ? `⚠ ${d.conflictCopies.length}` : `⚠ ${d.conflictCopies.length} sync conflict${d.conflictCopies.length > 1 ? 's' : ''}`, { cls: 'warn', title: 'Syncthing kept another version of this file', onclick: (e) => this.conflictMenu(e, d) }));
    }
    if (d?.kind === 'text') {
      const st = app.view.state;
      const main = st.selection.main;
      const line = st.doc.lineAt(main.head);
      const sel = st.selection.ranges.reduce((n, r) => n + (r.to - r.from), 0);
      const cursors = st.selection.ranges.length;
      right.push(this.item(`Ln ${line.number}, Col ${main.head - line.from + 1}`, { title: 'Go to line (Ctrl+G)', onclick: () => this.ui.run('search.goto') }));
      if (sel || cursors > 1) right.push(this.item(cursors > 1 ? `${cursors} cursors · Sel ${sel}` : `Sel ${sel}`));
      if (!app.mobile) right.push(this.item(`${st.doc.lines} lines`, { cls: 'muted' }));
      const s = app.settings.values;
      if (!app.mobile) right.push(this.item(s.indentWithTabs ? `Tab: ${s.tabSize}` : `Spaces: ${s.tabSize}`, { title: 'Indentation', onclick: (e) => this.indentMenu(e) }));
      right.push(this.item(d.encoding, { title: 'Encoding', onclick: (e) => this.encodingMenu(e) }));
      right.push(this.item(d.eol, { title: 'Line endings', onclick: (e) => this.eolMenu(e) }));
      right.push(this.item(d.langName || 'Plain text', { title: 'Language', onclick: () => this.ui.pickLanguage() }));
    } else if (d) {
      right.push(this.item(`${(d.size / 1024).toFixed(d.size < 10240 ? 1 : 0)} KB`, { cls: 'muted' }));
    }
    if (!app.mobile) right.push(this.item(app.settings.get('theme') === 'light' ? 'nss light' : app.settings.get('theme') === 'system' ? 'nss auto' : 'nss dark', { title: 'Theme', onclick: () => this.themeCycle() }));
    this.left.replaceChildren(...left);
    this.right.replaceChildren(...right);
  }

  at(e) { const r = e.currentTarget.getBoundingClientRect(); return [r.left, r.top - 8]; }
  popup(e, items) {
    const [x, y] = this.at(e);
    contextMenu(x, y, items);
    const m = document.querySelector('.ctx');
    if (m) m.style.top = Math.max(4, y - m.getBoundingClientRect().height) + 'px';
  }

  indentMenu(e) {
    const s = this.app.settings;
    this.popup(e, [
      { label: (s.get('indentWithTabs') ? '' : '✓ ') + 'Indent with spaces', run: () => s.set({ indentWithTabs: false }) },
      { label: (s.get('indentWithTabs') ? '✓ ' : '') + 'Indent with tabs', run: () => s.set({ indentWithTabs: true }) },
      '-',
      ...[2, 4, 8].map((n) => ({ label: (s.get('tabSize') === n ? '✓ ' : '') + `Width ${n}`, run: () => s.set({ tabSize: n }) })),
    ]);
  }

  encodingMenu(e) {
    const d = this.app.active;
    this.popup(e, [
      ...ENCODINGS.map((enc) => ({ label: (d.encoding === enc ? '✓ ' : '') + `Convert to ${enc}`, run: () => this.app.convertEncoding(enc) })),
      '-',
      ...ENCODINGS.map((enc) => ({ label: `Reopen with ${enc}`, disabled: !d.path, run: () => this.app.reload(d, { encoding: enc }) })),
    ]);
  }

  eolMenu(e) {
    const d = this.app.active;
    this.popup(e, [['CRLF', 'Windows (CR LF)'], ['LF', 'Unix (LF)'], ['CR', 'Macintosh (CR)']].map(([k, l]) => ({ label: (d.eol === k ? '✓ ' : '') + l, run: () => this.app.setEol(k) })));
  }

  conflictMenu(e, d) {
    this.popup(e, d.conflictCopies.map((p) => ({ label: `Open ${p.split('/').pop()}`, run: () => this.app.open(p) })));
  }

  themeCycle() {
    const order = ['dark', 'light', 'system'];
    const s = this.app.settings;
    s.set({ theme: order[(order.indexOf(s.get('theme')) + 1) % order.length] });
  }
}
