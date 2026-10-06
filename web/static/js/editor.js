// editor.js — the CodeMirror 6 markdown editor: syntax highlighting in nss colours,
// soft wrap, undo history, find/replace (Ctrl+F), list continuation on Enter,
// bracket closing. Windows line endings are kept as they were in the file.

import {
  EditorState, EditorView, keymap, drawSelection, dropCursor, highlightActiveLine, placeholder,
  rectangularSelection, defaultKeymap, history, historyKeymap, indentWithTab, syntaxHighlighting,
  HighlightStyle, indentOnInput, bracketMatching, indentUnit, markdown, markdownLanguage, search,
  searchKeymap, highlightSelectionMatches, closeBrackets, closeBracketsKeymap, tags as t,
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

export class Editor {
  /**
   * @param {HTMLElement} parent
   * @param {object} hooks { change(), save(), toggleView() }
   */
  constructor(parent, hooks) {
    this.hooks = hooks;
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
          ...closeBracketsKeymap, ...defaultKeymap, ...searchKeymap, ...historyKeymap, indentWithTab,
        ]),
        EditorView.updateListener.of((u) => { if (u.docChanged) hooks.change(); }),
      ],
    });
  }

  /** Loads a note (new undo history). */
  load(doc, { readOnly = false } = {}) {
    const sep = doc.includes("\r\n") ? "\r\n" : "\n";
    this.view.setState(this.state(doc, readOnly, sep));
    this.view.scrollDOM.scrollTop = 0;
  }

  /** Replaces the text after another app changed the file, keeping the cursor and
      scroll position as close as possible. */
  reload(doc, { readOnly = false } = {}) {
    const top = this.view.scrollDOM.scrollTop;
    const head = this.view.state.selection.main.head;
    this.load(doc, { readOnly });
    const pos = Math.min(head, this.view.state.doc.length);
    this.view.dispatch({ selection: { anchor: pos } });
    this.view.scrollDOM.scrollTop = top;
  }

  text() { return this.view.state.sliceDoc(); }
  focus() { this.view.focus(); }
  get scroller() { return this.view.scrollDOM; }
}
