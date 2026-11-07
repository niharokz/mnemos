// Viewer for files that are not text: images, PDFs (pdf.js, loaded only when
// needed) and binary files (with "Open as text anyway").
import { extname } from '../core/paths.js';
import { h } from './dom.js';

const MIME = { '.png': 'image/png', '.jpg': 'image/jpeg', '.jpeg': 'image/jpeg', '.gif': 'image/gif', '.webp': 'image/webp', '.svg': 'image/svg+xml', '.avif': 'image/avif', '.bmp': 'image/bmp', '.ico': 'image/x-icon' };

let pdfjs = null;
async function loadPdfjs() {
  if (pdfjs) return pdfjs;
  pdfjs = await import('pdfjs-dist');
  const worker = (await import('pdfjs-dist/build/pdf.worker.min.mjs?url')).default;
  pdfjs.GlobalWorkerOptions.workerSrc = worker;
  return pdfjs;
}

const kb = (n) => (n < 1048576 ? `${(n / 1024).toFixed(1)} KB` : `${(n / 1048576).toFixed(1)} MB`);

export class Viewer {
  constructor(app) {
    this.app = app;
    this.el = h('section.viewer');
    this.url = null;
    this.token = 0;
  }

  async show(doc) {
    const token = ++this.token;
    if (this.url) { URL.revokeObjectURL(this.url); this.url = null; }
    this.el.replaceChildren();
    if (doc.kind === 'image') {
      this.url = URL.createObjectURL(new Blob([doc.bytes], { type: MIME[extname(doc.path)] || 'image/*' }));
      const img = h('img', { src: this.url, alt: doc.path, draggable: 'false' });
      const info = h('div.v-info.muted', {}, kb(doc.size));
      img.onload = () => { info.textContent = `${img.naturalWidth} × ${img.naturalHeight} · ${kb(doc.size)}`; };
      const stage = h('div.v-stage.fit', { onclick: () => stage.classList.toggle('fit') }, img);
      const bar = h('div.v-bar', {}, info, extname(doc.path) === '.svg' ? h('button', { onclick: () => this.app.openAsText(doc) }, 'Edit source') : null);
      this.el.append(bar, stage);
    } else if (doc.kind === 'pdf') {
      const pages = h('div.v-pdf');
      this.el.append(h('div.v-bar', {}, h('div.v-info.muted', {}, `PDF · ${kb(doc.size)}`)), pages);
      try {
        const lib = await loadPdfjs();
        const pdf = await lib.getDocument({ data: doc.bytes.slice() }).promise;
        if (token !== this.token) return;
        this.el.querySelector('.v-info').textContent = `PDF · ${pdf.numPages} page${pdf.numPages > 1 ? 's' : ''} · ${kb(doc.size)}`;
        const width = Math.min(this.el.clientWidth - 32, 980) || 800;
        for (let n = 1; n <= pdf.numPages; n++) {
          if (token !== this.token) return;
          const page = await pdf.getPage(n);
          const base = page.getViewport({ scale: 1 });
          const scale = (width / base.width) * (window.devicePixelRatio || 1);
          const vp = page.getViewport({ scale });
          const canvas = h('canvas', { width: Math.floor(vp.width), height: Math.floor(vp.height), style: { width: width + 'px' } });
          pages.append(canvas);
          await page.render({ canvas, canvasContext: canvas.getContext('2d'), viewport: vp }).promise;
        }
      } catch (e) {
        pages.append(h('p.muted', {}, 'Cannot show this PDF: ' + (e?.message || e)));
      }
    } else {
      this.el.append(h('div.v-binary', {},
        h('h3', {}, 'This looks like a binary file'),
        h('p.muted', {}, `${kb(doc.size)} — editing it as text may damage it.`),
        h('button', { onclick: () => this.app.openAsText(doc) }, 'Open as text anyway')));
    }
  }

  clear() {
    this.token++;
    if (this.url) { URL.revokeObjectURL(this.url); this.url = null; }
    this.el.replaceChildren();
  }
}
