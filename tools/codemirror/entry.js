// Entry point for the vendored CodeMirror 6 bundle (web/static/vendor/codemirror.js).
// Only what samanote's editor uses is re-exported, so esbuild can drop the rest.
// Rebuild after changing versions in package.json:  npm ci && npm run build
export { EditorState, Compartment } from "@codemirror/state";
export {
  EditorView, keymap, drawSelection, highlightActiveLine, highlightActiveLineGutter,
  lineNumbers, placeholder, rectangularSelection, dropCursor,
} from "@codemirror/view";
export { defaultKeymap, history, historyKeymap, indentWithTab } from "@codemirror/commands";
export { syntaxHighlighting, HighlightStyle, indentOnInput, bracketMatching, indentUnit } from "@codemirror/language";
export { markdown, markdownLanguage, markdownKeymap } from "@codemirror/lang-markdown";
export { search, searchKeymap, highlightSelectionMatches } from "@codemirror/search";
export { closeBrackets, closeBracketsKeymap } from "@codemirror/autocomplete";
export { tags } from "@lezer/highlight";
