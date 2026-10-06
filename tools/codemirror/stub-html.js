// Stand-in for @codemirror/lang-html (aliased in package.json's build script).
// lang-markdown imports it only to highlight raw HTML inside notes and to complete
// HTML tags; samanote needs neither, and the real package (with its CSS and
// JavaScript parsers) would add ~160 KB to the bundle. With no HTML parser,
// HTML in a note is shown as plain markdown text.
export function html() {
  return { support: [], language: { parser: undefined } };
}
export function htmlCompletionSource() {
  return null;
}
