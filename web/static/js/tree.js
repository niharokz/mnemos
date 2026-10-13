// tree.js — the sidebar file tree: folders (remembered open/closed per device),
// the active note highlighted, a filter box that turns it into a flat match list,
// and a "⋯" action button on every row.

import { h, icon, prefs } from "./ui.js";

export class Tree {
  /**
   * @param {HTMLElement} el      the <nav> to render into
   * @param {object} hooks        { open(path), action(node) }
   */
  constructor(el, hooks) {
    this.el = el;
    this.hooks = hooks;
    this.root = null;
    this.active = "";
    this.filter = "";
    this.open = new Set(prefs.get("open", []));
  }

  set(root) { this.root = root; this.render(); }
  setActive(path) {
    this.active = path || "";
    // reveal the active note: open its parent folders
    const parts = this.active.split("/");
    for (let i = 1; i < parts.length; i++) this.open.add(parts.slice(0, i).join("/"));
    this.render();
  }
  setFilter(text) { this.filter = text.trim().toLowerCase(); this.render(); }

  /** All file paths, for the welcome screen and name checks. */
  files() {
    const out = [];
    const walk = (n) => { for (const c of n.children || []) c.dir ? walk(c) : out.push(c); };
    if (this.root) walk(this.root);
    return out;
  }

  toggle(path) {
    this.open.has(path) ? this.open.delete(path) : this.open.add(path);
    prefs.set("open", [...this.open]);
    this.render();
  }

  render() {
    const scroll = this.el.scrollTop;
    if (!this.root) { this.el.replaceChildren(); return; }
    let list;
    if (this.filter) {
      const words = this.filter.split(/\s+/);
      const hits = this.files().filter((f) => words.every((w) => f.path.toLowerCase().includes(w)));
      list = hits.length
        ? h("ul", {}, hits.slice(0, 300).map((f) => this.fileRow(f, true)))
        : h("p.empty", {}, "No notes match.");
    } else if (!(this.root.children || []).length) {
      list = h("p.empty", {}, "The vault is empty. Create a note with the + button.");
    } else {
      list = this.branch(this.root);
    }
    this.el.replaceChildren(list);
    this.el.scrollTop = scroll;
  }

  branch(node) {
    return h("ul", { role: node.path ? "group" : "tree" }, (node.children || []).map((c) => (c.dir ? this.dirRow(c) : this.fileRow(c, false))));
  }

  dirRow(n) {
    const isOpen = this.open.has(n.path);
    const row = h("div.row.dir", { "aria-expanded": String(isOpen) },
      h("button.open", { onclick: () => this.toggle(n.path), title: n.path }, h("span.caret", {}, icon("caret")), h("span", {}, n.name)),
      this.moreBtn(n));
    return h("li", { role: "treeitem", "aria-expanded": String(isOpen) }, row, isOpen && n.children && n.children.length ? this.branch(n) : null);
  }

  fileRow(f, flat) {
    const dot = f.name.lastIndexOf(".");
    const stem = dot > 0 ? f.name.slice(0, dot) : f.name;
    const ext = dot > 0 ? f.name.slice(dot + 1).toLowerCase() : "";
    const where = flat && f.path.includes("/") ? f.path.slice(0, f.path.lastIndexOf("/")) : "";
    const cf = /\.sync-conflict-\d{8}-\d{6}-/.test(f.name);
    const row = h("div.row" + (f.path === this.active ? ".on" : "") + (cf ? ".cf" : ""),
      {},
      h("button.open", { onclick: () => this.hooks.open(f.path), title: f.path, "aria-current": f.path === this.active ? "page" : null },
        h("span.file-ic", {}, icon(cf ? "alert" : "file")),
        h("span", {}, stem),
        ext && ext !== "md" ? h("span.ext", {}, ext) : null,
        where ? h("span.where", {}, where) : null),
      this.moreBtn(f));
    return h("li", { role: "treeitem" }, row);
  }

  moreBtn(node) {
    return h("button.more", { "aria-label": "Actions for " + node.name, title: "Actions", onclick: (e) => { e.stopPropagation(); this.hooks.action(node); } }, icon("more"));
  }
}
