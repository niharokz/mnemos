// flow.js — the Flow view: the notes of a folder (and its sub-folders), newest
// first, one per screen like Shorts. Scroll or swipe (snap), or use ↑ ↓ / j k.
// Each note is rendered by the server when it comes near the screen, and the next
// one is fetched ahead so moving on feels instant.

import { api } from "./api.js";
import { h, ago } from "./ui.js";
import { lightbox } from "./images.js";

const NOTE = /\.(md|markdown)$/i;
let keyHandler = null;

/**
 * Draws the Flow of `folder` ("" = whole vault) into el.
 * @param {object} nav { files() → tree files, note(path), folder(path) }
 */
export function renderFlow(el, folder, nav) {
  stopFlow();
  const files = nav.files()
    .filter((f) => NOTE.test(f.name) && !f.name.includes(".sync-conflict-") && (!folder || f.path.startsWith(folder + "/")))
    .sort((a, b) => b.mtime - a.mtime);
  if (!files.length) {
    el.replaceChildren(h("p.empty", {}, "No notes here yet."));
    return;
  }
  const slides = files.map((f, i) => h("article.flow-s", { "data-i": i, "data-path": f.path },
    h("header.flow-h", {},
      h("strong.ellipsis", {}, f.name.replace(NOTE, "")),
      h("small.mono.ellipsis", {}, (f.path.includes("/") ? f.path.slice(0, f.path.lastIndexOf("/")) + " · " : "") + ago(f.mtime)),
      h("button.flow-open", { onclick: () => nav.note(f.path) }, "Open")),
    h("div.prose.flow-b", {}, h("p.loading", {}, "…")),
    h("small.flow-n.mono", {}, `${i + 1} / ${files.length}`)));
  const box = h("div.flow", { tabindex: "0", "aria-label": "Flow: one note at a time" }, slides);
  box.addEventListener("click", (e) => {
    if (e.target.tagName === "IMG") lightbox(e.target.src, e.target.alt);
    const a = e.target.closest("a[href]");
    if (a && /^(https?:|mailto:)/i.test(a.getAttribute("href"))) { e.preventDefault(); window.open(a.href, "_blank", "noopener,noreferrer"); }
  });
  el.replaceChildren(box);

  // render a slide and its neighbours as they come into view
  const loaded = new Set();
  const load = async (i) => {
    if (i < 0 || i >= slides.length || loaded.has(i)) return;
    loaded.add(i);
    const body = slides[i].querySelector(".flow-b");
    try {
      const f = await api.read(files[i].path);
      const { html } = await api.render(f.path, f.content || "");
      body.innerHTML = html || '<p class="note">Empty note.</p>'; // server output: raw HTML already stripped
      for (const c of body.querySelectorAll('input[type="checkbox"]')) c.disabled = true;
    } catch (e) {
      body.replaceChildren(h("p.empty", {}, e.message));
    }
  };
  const io = new IntersectionObserver((entries) => {
    for (const en of entries) {
      if (!en.isIntersecting) continue;
      const i = +en.target.dataset.i;
      load(i); load(i + 1); load(i - 1);
    }
  }, { root: box, threshold: 0.35 });
  slides.forEach((s) => io.observe(s));
  box._io = io;

  // keyboard: ↑ ↓ / j k move one note, Enter opens it
  const step = (d) => box.scrollBy({ top: d * box.clientHeight, behavior: "smooth" });
  const current = () => slides[Math.round(box.scrollTop / Math.max(1, box.clientHeight))];
  keyHandler = (e) => {
    if (el.hidden || e.target.closest("input, textarea, dialog")) return;
    if (e.key === "ArrowDown" || e.key === "j" || e.key === "PageDown") { e.preventDefault(); step(1); }
    else if (e.key === "ArrowUp" || e.key === "k" || e.key === "PageUp") { e.preventDefault(); step(-1); }
    else if (e.key === "Enter" && current()) { e.preventDefault(); nav.note(current().dataset.path); }
  };
  document.addEventListener("keydown", keyHandler);
  requestAnimationFrame(() => box.focus({ preventScroll: true }));
}

/** Stops the keyboard handler and observers of the last Flow. */
export function stopFlow() {
  if (keyHandler) document.removeEventListener("keydown", keyHandler);
  keyHandler = null;
  const old = document.querySelector(".flow");
  if (old && old._io) old._io.disconnect();
}
