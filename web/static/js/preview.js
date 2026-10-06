// preview.js — the live preview. Markdown is rendered by the server (the same
// goldmark everywhere, no second markdown library in the browser); requests are
// debounced and stale ones cancelled. In split view the preview follows the
// editor's scroll position.

import { api, rawURL } from "./api.js";
import { h } from "./ui.js";

const IMAGE = /\.(png|jpe?g|gif|webp|avif|svg|bmp|ico)$/i;

export class Preview {
  constructor(article, pane) {
    this.el = article;
    this.pane = pane;
    this.timer = 0;
    this.ctrl = null;
    this.lastKey = "";
    // external links open in a new tab; note links (#/n/…) use the router
    this.el.addEventListener("click", (e) => {
      const a = e.target.closest("a[href]");
      if (!a) return;
      const href = a.getAttribute("href");
      if (/^(https?:|mailto:)/i.test(href) || href.startsWith("/raw/")) {
        e.preventDefault();
        window.open(a.href, "_blank", "noopener,noreferrer");
      }
    });
  }

  /** Renders soon (after typing pauses). */
  schedule(path, text, delay = 250) {
    clearTimeout(this.timer);
    this.timer = setTimeout(() => this.render(path, text), delay);
  }

  async render(path, text) {
    clearTimeout(this.timer);
    const key = path + "\0" + text;
    if (key === this.lastKey) return;
    if (this.ctrl) this.ctrl.abort();
    const ctrl = (this.ctrl = new AbortController());
    try {
      const { html } = await api.render(path, text, ctrl.signal);
      if (ctrl.signal.aborted) return;
      this.lastKey = key;
      this.el.innerHTML = html || '<p class="note">Empty note.</p>'; // server output: raw HTML is already stripped
    } catch (e) {
      if (e.name !== "AbortError") this.el.replaceChildren(h("p.note", {}, "Preview failed: " + e.message));
    }
  }

  /** Shows a file that is not text: the image itself, or a link to the raw file. */
  showFile(file) {
    this.lastKey = "";
    const url = rawURL(file.path);
    const kb = Math.max(1, Math.round(file.size / 1024)).toLocaleString() + " KB";
    const msg = file.too_large ? "This file is too large to open here." : "This is not a text file.";
    this.el.replaceChildren(h("div.note", {},
      IMAGE.test(file.path) ? h("img", { src: url, alt: file.path }) : null,
      h("p", {}, IMAGE.test(file.path) ? `${file.path} · ${kb}` : `${msg} (${kb})`),
      h("a", { href: url, target: "_blank", rel: "noopener" }, "Open the raw file ↗")));
  }

  clear() { this.lastKey = ""; this.el.replaceChildren(); }

  /** Follows the editor: same relative scroll position. */
  follow(scroller) {
    const max = scroller.scrollHeight - scroller.clientHeight;
    const ratio = max > 0 ? scroller.scrollTop / max : 0;
    this.pane.scrollTop = ratio * (this.pane.scrollHeight - this.pane.clientHeight);
  }
}
