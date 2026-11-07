// Markdown preview: a side panel on desktop, a full view on phones. Rendered with
// marked and sanitised with DOMPurify; images next to the note load through the
// backend, links to other notes open them, web links open in the browser.
import { marked } from 'marked';
import DOMPurify from 'dompurify';
import { dirname, join, extname } from '../core/paths.js';
import { isMarkdown } from '../core/languages.js';
import { h, debounce } from './dom.js';

marked.setOptions({ gfm: true, breaks: false });

const MIME = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.avif': 'image/avif', '.bmp': 'image/bmp' };

export class Preview {
  constructor(app, openExternal) {
    this.app = app;
    this.openExternal = openExternal;
    this.urls = new Map(); // path -> blob URL
    this.body = h('article.md');
    this.el = h('aside.preview', { 'aria-label': 'Markdown preview' }, this.body);
    this.update = debounce(() => this.render(), 180);
    app.on('content', () => this.app.ui.preview && this.update());
    app.on('active', () => this.app.ui.preview && this.render());
    app.on('preview', () => this.render());
    this.body.addEventListener('click', (e) => this.onClick(e));
  }

  get visible() {
    const d = this.app.active;
    return this.app.ui.preview && d?.kind === 'text' && isMarkdown(d.path || d.untitled || '');
  }

  stripFrontMatter(text) {
    const m = /^---\r?\n([\s\S]*?)\r?\n---\r?\n?/.exec(text);
    if (!m) return { meta: null, body: text };
    return { meta: m[1], body: text.slice(m[0].length) };
  }

  render() {
    this.el.hidden = !this.visible;
    document.body.classList.toggle('has-preview', !this.el.hidden);
    if (this.el.hidden) return;
    const d = this.app.active;
    const { meta, body } = this.stripFrontMatter(this.app.textOf(d));
    const html = DOMPurify.sanitize(marked.parse(body), { ADD_ATTR: ['target'] });
    this.body.innerHTML = (meta ? `<pre class="fm">${escapeHtml(meta)}</pre>` : '') + html;
    for (const cb of this.body.querySelectorAll('input[type=checkbox]')) cb.disabled = true;
    this.loadImages(d);
  }

  async loadImages(d) {
    const base = d.path ? dirname(d.path) : this.app.backend.notesRoot;
    for (const img of this.body.querySelectorAll('img')) {
      const src = img.getAttribute('src') || '';
      if (/^(https?:|data:|blob:)/i.test(src) || base == null) continue;
      const path = src.startsWith('/') && this.app.backend.notesRoot != null ? join(this.app.backend.notesRoot, src) : join(base, decodeURIComponent(src));
      img.removeAttribute('src');
      try {
        if (!this.urls.has(path)) {
          const r = await this.app.backend.read(path);
          this.urls.set(path, URL.createObjectURL(new Blob([r.bytes], { type: MIME[extname(path)] || 'application/octet-stream' })));
        }
        img.src = this.urls.get(path);
      } catch {
        img.alt = `[missing image: ${src}]`;
        img.classList.add('missing');
      }
    }
  }

  onClick(e) {
    const a = e.target.closest('a');
    if (!a) return;
    const href = a.getAttribute('href') || '';
    e.preventDefault();
    if (/^(https?:|mailto:)/i.test(href)) { this.openExternal(href); return; }
    if (href.startsWith('#')) { this.body.querySelector(`[id="${CSS.escape(href.slice(1))}"]`)?.scrollIntoView(); return; }
    const d = this.app.active;
    const target = decodeURIComponent(href.split('#')[0]);
    if (!target) return;
    const base = d?.path ? dirname(d.path) : this.app.backend.notesRoot;
    this.app.open(target.startsWith('/') ? join(this.app.backend.notesRoot, target) : join(base, target));
  }
}

const escapeHtml = (s) => s.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c]);
