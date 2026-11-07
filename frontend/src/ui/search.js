// Find in files (Ctrl+Shift+F): searches every folder in the sidebar and lists
// matches grouped by file; clicking one opens the file at that line.
import { relative } from '../core/paths.js';
import { h, icon } from './dom.js';

export class SearchPanel {
  constructor(app) {
    this.app = app;
    this.input = h('input', { type: 'search', placeholder: 'Find in files', spellcheck: 'false', autocapitalize: 'off', enterkeyhint: 'search' });
    this.regex = h('input', { type: 'checkbox' });
    this.cs = h('input', { type: 'checkbox' });
    this.info = h('div.sr-info.muted');
    this.results = h('div.sr-list');
    this.el = h('div.search-panel', {},
      h('form.sr-form', { onsubmit: (e) => { e.preventDefault(); this.run(); } },
        this.input,
        h('div.sr-opts', {}, h('label', {}, this.cs, 'Match case'), h('label', {}, this.regex, 'Regex'))),
      this.info, this.results);
    this.seq = 0;
  }

  focus(text) {
    if (text) this.input.value = text;
    this.input.focus();
    this.input.select();
  }

  async run() {
    const q = this.input.value;
    if (!q) return;
    if (this.regex.checked) { try { new RegExp(q); } catch (e) { this.info.textContent = 'Invalid regex: ' + e.message; return; } }
    const seq = ++this.seq;
    this.info.textContent = 'Searching…';
    this.results.replaceChildren();
    const roots = this.app.roots.map((r) => r.path);
    let hits;
    try {
      hits = await this.app.backend.search(roots, q, { regex: this.regex.checked, caseSensitive: this.cs.checked, hidden: this.app.settings.get('hidden'), max: 2000 });
    } catch (e) {
      if (seq === this.seq) this.info.textContent = e.message;
      return;
    }
    if (seq !== this.seq) return;
    const byFile = new Map();
    for (const m of hits) { if (!byFile.has(m.path)) byFile.set(m.path, []); byFile.get(m.path).push(m); }
    this.info.textContent = hits.length ? `${hits.length}${hits.length >= 2000 ? '+' : ''} matches in ${byFile.size} files` : 'No matches';
    const rootOf = (p) => this.app.roots.find((r) => relative(p, r.path) != null);
    const frag = document.createDocumentFragment();
    for (const [path, list] of byFile) {
      const r = rootOf(path);
      const rel = r ? `${r.name}/${relative(path, r.path)}` : path;
      const group = h('details.sr-file', { open: true }, h('summary', { title: path }, icon('fileText'), h('span.ellipsis', {}, rel), h('span.badge', {}, list.length)));
      for (const m of list) {
        group.append(h('button.sr-hit', { onclick: () => { this.app.open(path, { line: m.line, col: m.col }); if (this.app.mobile) this.app.emit('drawer', false); } },
          h('span.sr-ln', {}, m.line), h('span.sr-text', {}, m.text.trim())));
      }
      frag.append(group);
    }
    this.results.replaceChildren(frag);
  }
}
