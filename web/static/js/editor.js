// editor.js — the CodeMirror 6 markdown editor: syntax highlighting in nss colours,
// soft wrap, undo history, find/replace (Ctrl+F), list continuation on Enter,
// bracket closing and a small formatting toolbar. Windows line endings are kept
// as they were in the file.

import {
  EditorState, EditorView, keymap, drawSelection, dropCursor, highlightActiveLine, placeholder,
  rectangularSelection, defaultKeymap, history, historyKeymap, indentWithTab, syntaxHighlighting,
  HighlightStyle, indentOnInput, bracketMatching, indentUnit, markdown, markdownLanguage, search,
  searchKeymap, highlightSelectionMatches, closeBrackets, closeBracketsKeymap, tags as t, EditorSelection,
} from "../vendor/codemirror.js";

// Markdown colours, all from nss tokens so light/dark just work.
const mdStyle = HighlightStyle.define([
  { tag: t.heading1, fontWeight: "700", fontSize: "1.32em", color: "var(--nss-fg)" },
  { tag: t.heading2, fontWeight: "700", fontSize: "1.18em", color: "var(--nss-fg)" },
  { tag: t.heading3, fontWeight: "700", fontSize: "1.06em", color: "var(--nss-fg)" },
  { tag: [t.heading4, t.heading5, t.heading6], fontWeight: "700", color: "var(--nss-muted)" },
  { tag: t.strong, fontWeight: "700" },
  { tag: t.emphasis, fontStyle: "italic" },
  { tag: t.strikethrough, textDecoration: "line-through", color: "var(--nss-muted)" },
  { tag: [t.link, t.url], color: "var(--nss-ink)" },
  { tag: t.monospace, color: "var(--nss-accent-2)" },
  { tag: t.quote, color: "var(--nss-muted)", fontStyle: "italic" },
  { tag: [t.processingInstruction, t.contentSeparator], color: "var(--nss-accent)" },
  { tag: t.meta, color: "var(--nss-muted)" },
  { tag: t.list, color: "var(--nss-fg)" },
  { tag: [t.labelName, t.string], color: "var(--nss-muted)" },
  { tag: t.comment, color: "var(--nss-muted)" },
]);

// YAML properties at the top of a note ("---" … "---") are not markdown: without
// this, the closing "---" would turn the last property into a big heading.
const Frontmatter = {
  defineNodes: [{ name: "Frontmatter", block: true, style: t.meta }],
  parseBlock: [{
    name: "Frontmatter",
    before: "HorizontalRule",
    parse(cx, line) {
      if (cx.lineStart !== 0 || line.text.trimEnd() !== "---") return false;
      const from = cx.lineStart;
      while (cx.nextLine()) {
        if (line.text.trimEnd() === "---") {
          cx.nextLine();
          break;
        }
      }
      cx.addElement(cx.elt("Frontmatter", from, cx.prevLineEnd()));
      return true;
    },
  }],
};

const theme = EditorView.theme({
  "&": { color: "var(--nss-fg)", backgroundColor: "transparent" },
  ".cm-gutters": { display: "none" },
});

// toolbar: [label, title, action]
const TOOLS = [
  ["B", "Bold (Ctrl+B)", (e) => e.wrap("**")],
  ["I", "Italic (Ctrl+I)", (e) => e.wrap("*")],
  ["H", "Heading", (e) => e.prefix("## ")],
  ["•", "List", (e) => e.prefix("- ")],
  ["☐", "Task", (e) => e.prefix("- [ ] ")],
  ["❝", "Quote", (e) => e.prefix("> ")],
  ["</>", "Code", (e) => e.wrap("`")],
  ["🔗", "Link (Ctrl+K)", (e) => e.link()],
  ["🖼", "Image (or paste / drop one)", (e) => e.hooks.images && e.hooks.images(null)],
];

export class Editor {
  /**
   * @param {HTMLElement} parent
   * @param {object} hooks { change(), save(), toggleView(), images(files, pos) }
   *   images(null) asks for a picker; files come from a paste or a drop at pos
   */
  constructor(parent, hooks) {
    this.hooks = hooks;
    this.markdown = true;
    this.tracked = new Set(); // positions waiting for an upload, see track()
    this.bar = document.createElement("div");
    this.bar.className = "fmt-bar";
    this.bar.setAttribute("role", "toolbar");
    this.bar.setAttribute("aria-label", "Formatting");
    for (const [label, title, run] of TOOLS) {
      const b = document.createElement("button");
      b.type = "button";
      b.className = "ghost";
      b.textContent = label;
      b.title = title;
      b.setAttribute("aria-label", title);
      b.addEventListener("mousedown", (ev) => ev.preventDefault()); // keep the editor's selection
      b.addEventListener("click", () => { run(this); this.focus(); });
      this.bar.append(b);
    }
    parent.append(this.bar);
    this.view = new EditorView({ parent, state: this.state("", false, "\n") });
  }

  state(doc, readOnly, sep) {
    const hooks = this.hooks;
    return EditorState.create({
      doc,
      extensions: [
        history(),
        drawSelection(),
        dropCursor(),
        rectangularSelection(),
        highlightActiveLine(),
        indentOnInput(),
        bracketMatching(),
        closeBrackets(),
        highlightSelectionMatches(),
        search({ top: true }),
        indentUnit.of("  "),
        EditorState.tabSize.of(4),
        sep === "\r\n" ? EditorState.lineSeparator.of(sep) : [], // LF files: CodeMirror's default
        EditorState.readOnly.of(readOnly),
        EditorView.editable.of(!readOnly),
        EditorView.lineWrapping,
        EditorView.contentAttributes.of({ spellcheck: "true", autocorrect: "on", autocapitalize: "sentences", "aria-label": "Note text" }),
        EditorView.editorAttributes.of({ class: readOnly ? "ro" : "" }),
        markdown({ base: markdownLanguage, completeHTMLTags: false, extensions: [Frontmatter] }),
        syntaxHighlighting(mdStyle),
        theme,
        placeholder("Start writing…"),
        keymap.of([
          { key: "Mod-s", preventDefault: true, run: () => { hooks.save(); return true; } },
          { key: "Mod-e", preventDefault: true, run: () => { hooks.toggleView(); return true; } },
          { key: "Mod-b", preventDefault: true, run: () => this.markdown && this.wrap("**") },
          { key: "Mod-i", preventDefault: true, run: () => this.markdown && this.wrap("*") },
          { key: "Mod-k", preventDefault: true, run: () => this.markdown && this.link() },
          ...closeBracketsKeymap, ...defaultKeymap, ...searchKeymap, ...historyKeymap, indentWithTab,
        ]),
        EditorView.updateListener.of((u) => {
          if (!u.docChanged) return;
          for (const t of this.tracked) t.pos = u.changes.mapPos(t.pos);
          hooks.change();
        }),
        EditorView.domEventHandlers({
          paste: (e, view) => this.takeImages(e, e.clipboardData, view.state.selection.main.head),
          drop: (e, view) => this.takeImages(e, e.dataTransfer, view.posAtCoords({ x: e.clientX, y: e.clientY }) ?? view.state.selection.main.head),
        }),
      ],
    });
  }

  /** Loads a note (new undo history). markdown=false hides the toolbar. */
  load(doc, { readOnly = false, markdown = true } = {}) {
    const sep = doc.includes("\r\n") ? "\r\n" : "\n";
    this.readOnly = readOnly;
    this.markdown = markdown;
    this.bar.hidden = readOnly || !markdown;
    for (const t of this.tracked) t.lost = true; // they pointed into the previous file
    this.tracked.clear();
    this.view.setState(this.state(doc, readOnly, sep));
    this.view.scrollDOM.scrollTop = 0;
  }

  /** Replaces all text as one undoable edit (used by the table view). */
  setText(text) {
    if (text === this.text()) return;
    this.view.dispatch({ changes: { from: 0, to: this.view.state.doc.length, insert: text } });
  }

  /** Puts the cursor on a 1-based line and scrolls it to the middle. */
  gotoLine(n) {
    const doc = this.view.state.doc;
    const line = doc.line(Math.min(Math.max(1, n), doc.lines));
    this.view.dispatch({ selection: { anchor: line.from }, effects: EditorView.scrollIntoView(line.from, { y: "center" }) });
  }

  /** Scrolls to the end (follow a log). */
  toEnd() {
    const end = this.view.state.doc.length;
    this.view.dispatch({ effects: EditorView.scrollIntoView(end, { y: "end" }) });
  }

  /** Wraps each selection in mark (or puts the cursor between two marks). */
  wrap(mark) {
    if (this.readOnly) return false;
    const st = this.view.state;
    this.view.dispatch(st.changeByRange((r) => {
      const text = st.sliceDoc(r.from, r.to);
      const n = mark.length;
      if (text.startsWith(mark) && text.endsWith(mark) && text.length >= 2 * n) { // unwrap
        return { changes: { from: r.from, to: r.to, insert: text.slice(n, -n) }, range: EditorSelection.range(r.from, r.to - 2 * n) };
      }
      return { changes: [{ from: r.from, insert: mark }, { from: r.to, insert: mark }], range: EditorSelection.range(r.from + n, r.to + n) };
    }));
    return true;
  }

  /** Adds (or removes, when every line has it) a prefix on the selected lines. */
  prefix(p) {
    if (this.readOnly) return false;
    const st = this.view.state;
    const lines = new Set();
    for (const r of st.selection.ranges) {
      for (let l = st.doc.lineAt(r.from).number; l <= st.doc.lineAt(r.to).number; l++) lines.add(l);
    }
    const all = [...lines].map((l) => st.doc.line(l));
    const strip = /^(#{1,6} |- \[[ xX]\] |[-*+] |> )/;
    const has = all.every((l) => l.text.startsWith(p));
    const changes = all.map((l) => {
      const m = l.text.match(strip);
      if (has) return { from: l.from, to: l.from + p.length, insert: "" };
      return { from: l.from, to: l.from + (m ? m[0].length : 0), insert: p };
    });
    this.view.dispatch({ changes });
    return true;
  }

  /** Hands pasted or dropped image files to hooks.images; other data is left to CodeMirror. */
  takeImages(e, data, pos) {
    const files = [...(data?.files || [])].filter((f) => f.type.startsWith("image/"));
    if (!files.length || !this.markdown || this.readOnly || !this.hooks.images) return false;
    e.preventDefault();
    this.hooks.images(files, pos);
    return true;
  }

  /** Remembers a position that follows later edits (an upload's insertion point).
      Loading another file drops it: its .lost becomes true. */
  track(pos = this.view.state.selection.main.head) {
    const t = { pos: Math.min(pos, this.view.state.doc.length), lost: false };
    this.tracked.add(t);
    return t;
  }

  /** Inserts markdown blocks at a tracked position, each on a line of its own. */
  insertAt(t, text) {
    this.tracked.delete(t);
    if (t.lost || !text) return false;
    const doc = this.view.state.doc;
    const line = doc.lineAt(t.pos);
    const prevBlank = line.number === 1 || doc.line(line.number - 1).text.trim() === "";
    // a blank line around the images, so they never merge into a paragraph
    const before = t.pos > line.from ? "\n\n" : prevBlank ? "" : "\n";
    const after = t.pos < line.to ? "\n\n" : "\n";
    const insert = before + text + after;
    this.view.dispatch({ changes: { from: t.pos, insert }, selection: { anchor: t.pos + insert.length } });
    return true;
  }

  /** [selection](url) with the cursor on "url". */
  link() {
    if (this.readOnly) return false;
    const st = this.view.state;
    const r = st.selection.main;
    const text = st.sliceDoc(r.from, r.to) || "text";
    const insert = `[${text}](url)`;
    const at = r.from + text.length + 3;
    this.view.dispatch({ changes: { from: r.from, to: r.to, insert }, selection: EditorSelection.range(at, at + 3) });
    return true;
  }

  /** Flips the n-th task checkbox (0-based, in preview order: front matter and code skipped). */
  toggleTask(n) {
    const doc = this.view.state.doc;
    let fence = null, inFront = false, k = 0;
    for (let i = 1; i <= doc.lines; i++) {
      const line = doc.line(i);
      const t = line.text;
      if (i === 1 && t.trimEnd() === "---") { inFront = true; continue; }
      if (inFront) { if (t.trimEnd() === "---") inFront = false; continue; }
      const f = t.match(/^\s*(```|~~~)/);
      if (f) { fence = fence ? (f[1] === fence ? null : fence) : f[1]; continue; }
      if (fence) continue;
      const m = t.match(/^(\s*(?:>\s*)*(?:[-*+]|\d+[.)])\s+\[)([ xX])\]/);
      if (!m) continue;
      if (k++ === n) {
        const at = line.from + m[1].length;
        this.view.dispatch({ changes: { from: at, to: at + 1, insert: m[2] === " " ? "x" : " " } });
        return true;
      }
    }
    return false;
  }

  /** Replaces the text after another app changed the file, keeping the cursor and
      scroll position as close as possible. */
  reload(doc, { readOnly = false } = {}) {
    const top = this.view.scrollDOM.scrollTop;
    const head = this.view.state.selection.main.head;
    this.load(doc, { readOnly, markdown: this.markdown });
    const pos = Math.min(head, this.view.state.doc.length);
    this.view.dispatch({ selection: { anchor: pos } });
    this.view.scrollDOM.scrollTop = top;
  }

  text() { return this.view.state.sliceDoc(); }
  focus() { this.view.focus(); }
  get scroller() { return this.view.scrollDOM; }
}
