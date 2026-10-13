// cards.js — the box view of one folder: sub-folders first, then a card per file
// with the first lines of its text (front matter skipped by the server), or the
// picture itself for images.

import { api, rawURL } from "./api.js";
import { isImagePath } from "./images.js";
import { h, icon, ago, size } from "./ui.js";

/**
 * Draws folder `path` ("" = vault root) into el.
 * @param {object} nav { folder(path), note(path) }
 */
export async function renderFolder(el, path, nav) {
  el.replaceChildren(h("p.loading", {}, "Loading…"));
  let cards;
  try {
    cards = await api.folder(path);
  } catch (e) {
    el.replaceChildren(h("p.empty", {}, e.message));
    return false;
  }
  const parts = path ? path.split("/") : [];
  const crumbs = [h("a", { href: "#/f/", onclick: go(nav, "") }, "vault")];
  parts.forEach((p, i) => {
    const sub = parts.slice(0, i + 1).join("/");
    crumbs.push(h("span.sep", {}, "/"), i === parts.length - 1 ? h("strong", {}, p) : h("a", { href: "#/f/" + enc(sub), onclick: go(nav, sub) }, p));
  });
  const dirs = cards.filter((c) => c.dir);
  const files = cards.filter((c) => !c.dir);
  el.replaceChildren(
    h("div.f-head", {}, h("nav.f-crumbs", { "aria-label": "Folder" }, crumbs), h("small.mono", {}, `${dirs.length} folders · ${files.length} files`)),
    cards.length ? h("div.cards", {},
      dirs.map((c) => h("button.cardx.dir", { onclick: () => nav.folder(c.path) },
        h("span.c-ic", {}, icon("folder")), h("strong.ellipsis", {}, c.name), h("small", {}, `${c.items || 0} ${c.items === 1 ? "item" : "items"}`))),
      files.map((c) => {
        const dot = c.name.lastIndexOf(".");
        const ext = dot > 0 ? c.name.slice(dot + 1).toLowerCase() : "";
        return h("button.cardx", { onclick: () => nav.note(c.path), title: c.path },
          h("div.c-top", {}, h("strong.ellipsis", {}, ext === "md" ? c.name.slice(0, dot) : c.name), ext && ext !== "md" ? h("span.badge", {}, ext) : null),
          isImagePath(c.name) ? h("img.c-img", { src: rawURL(c.path), alt: "", loading: "lazy", decoding: "async" })
            : c.preview ? h("p.c-prev", {}, c.preview) : h("p.c-prev.muted", {}, ext === "md" ? "Empty note" : "No preview"),
          h("small.mono", {}, `${ago(c.mtime)} · ${size(c.size || 0)}`));
      })) : h("p.empty", {}, "This folder is empty."));
  return true;
}

const enc = (p) => p.split("/").map(encodeURIComponent).join("/");
const go = (nav, p) => (e) => { e.preventDefault(); nav.folder(p); };
