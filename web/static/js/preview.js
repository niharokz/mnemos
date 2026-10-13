// preview.js — the live preview. Markdown is rendered by the server (the same
// goldmark everywhere, no second markdown library in the browser); requests are
// debounced and stale ones cancelled. In split view the preview follows the
// editor's scroll position. Task checkboxes can be ticked right in the preview.

import { api, rawURL } from "./api.js";
import { h } from "./ui.js";
import { lightbox, isImagePath } from "./images.js";


export class Preview {
  /** onTask(n) flips the n-th task in the source; return false to refuse. */
  constructor(article, pane, onTask = () => false) {
    this.el = article;
    this.pane = pane;
    this.onTask = onTask;
    this.tasks = false;
    this.timer = 0;
    this.ctrl = null;
    this.lastKey = "";
    // external links open in a new tab; note links (#/n/…) use the router
    this.el.addEventListener("click", (e) => {
      if (e.target.matches('input[type="checkbox"]')) {
        const all = [...this.el.querySelectorAll('input[type="checkbox"]')];
        if (!this.tasks || !this.onTask(all.indexOf(e.target))) e.preventDefault();
        return;
      }
      if (e.target.tagName === "IMG" && !e.target.closest("a")) { lightbox(e.target.src, e.target.alt); return; }
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

  /** Whether ticking tasks is allowed (text notes that can be edited). */
  allowTasks(on) { this.tasks = on; }

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
      for (const c of this.el.querySelectorAll('input[type="checkbox"]')) {
        c.disabled = !this.tasks;
        c.title = this.tasks ? "Tick it" : "";
      }
    } catch (e) {
      if (e.name !== "AbortError") this.el.replaceChildren(h("p.note", {}, "Preview failed: " + e.message));
    }
  }

  /** Shows a file that is not text: an image itself (tap for full screen), or links to the raw file. */
  showFile(file) {
    this.lastKey = "";
    const url = rawURL(file.path);
    const name = file.path.slice(file.path.lastIndexOf("/") + 1);
    const kb = Math.max(1, Math.round(file.size / 1024)).toLocaleString() + " KB";
    const image = isImagePath(file.path);
    const info = h("p", {}, image ? `${name} · ${kb}` : `${file.too_large ? "This file is too large to open here." : "This is not a text file."} (${kb})`);
    const img = image ? h("img.img-view", { src: url, alt: name, title: "Tap for full screen" }) : null;
    if (img) img.addEventListener("load", () => { info.textContent = `${name} · ${img.naturalWidth}×${img.naturalHeight} · ${kb}`; });
    this.el.replaceChildren(h("div.note", {},
      img,
      info,
      h("p.file-acts", {},
        h("a", { href: url, target: "_blank", rel: "noopener" }, "Open the raw file ↗"),
        h("a", { href: rawURL(file.path, true), download: name }, "Download ↓"))));
  }

  clear() { this.lastKey = ""; this.el.replaceChildren(); }

  /** Follows the editor: same relative scroll position. */
  follow(scroller) {
    const max = scroller.scrollHeight - scroller.clientHeight;
    const ratio = max > 0 ? scroller.scrollTop / max : 0;
    this.pane.scrollTop = ratio * (this.pane.scrollHeight - this.pane.clientHeight);
  }
}
