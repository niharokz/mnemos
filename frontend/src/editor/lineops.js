// Notepad++ line operations (Edit menu). Each works on the selected lines, or
// on the whole document when nothing is selected — like Notepad++. The text
// transforms are pure functions so they can be tested without an editor.
import { EditorSelection } from '@codemirror/state';

export const transforms = {
  sortAsc: (ls) => [...ls].sort((a, b) => a.localeCompare(b)),
  sortDesc: (ls) => [...ls].sort((a, b) => b.localeCompare(a)),
  sortAscCi: (ls) => [...ls].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' })),
  reverse: (ls) => [...ls].reverse(),
  unique: (ls) => [...new Set(ls)],
  removeEmpty: (ls) => ls.filter((l) => l.trim() !== ''),
  trimTrailing: (ls) => ls.map((l) => l.replace(/[ \t]+$/, '')),
  trimLeading: (ls) => ls.map((l) => l.replace(/^[ \t]+/, '')),
};

export const caseOps = {
  upper: (s) => s.toUpperCase(),
  lower: (s) => s.toLowerCase(),
  title: (s) => s.toLowerCase().replace(/(^|[^\p{L}\p{N}'])(\p{L})/gu, (_, a, b) => a + b.toUpperCase()),
  invert: (s) => [...s].map((c) => (c === c.toUpperCase() ? c.toLowerCase() : c.toUpperCase())).join(''),
};

export function tabsToSpaces(text, size) {
  return text.split('\n').map((line) => {
    let col = 0, out = '';
    for (const ch of line) {
      if (ch === '\t') { const n = size - (col % size); out += ' '.repeat(n); col += n; } else { out += ch; col++; }
    }
    return out;
  }).join('\n');
}

export function leadingSpacesToTabs(text, size) {
  return text.split('\n').map((line) => {
    const m = /^[ \t]+/.exec(line);
    if (!m) return line;
    let col = 0;
    for (const ch of m[0]) col = ch === '\t' ? col + size - (col % size) : col + 1;
    return '\t'.repeat(Math.floor(col / size)) + ' '.repeat(col % size) + line.slice(m[0].length);
  }).join('\n');
}

// Line range covered by the main selection (whole document when it is empty).
function block(state) {
  const sel = state.selection.main;
  if (sel.empty) return { from: 0, to: state.doc.length };
  const a = state.doc.lineAt(sel.from);
  let b = state.doc.lineAt(sel.to);
  if (sel.to === b.from && b.number > a.number) b = state.doc.line(b.number - 1); // selection ending at column 0
  return { from: a.from, to: b.to };
}

export function applyLines(fn) {
  return (view) => {
    const { from, to } = block(view.state);
    const text = view.state.sliceDoc(from, to);
    const next = fn(text.split('\n')).join('\n');
    if (next !== text) view.dispatch({ changes: { from, to, insert: next }, selection: EditorSelection.range(from, from + next.length), userEvent: 'input' });
    return true;
  };
}

export function applyText(fn) {
  return (view) => {
    const { from, to } = block(view.state);
    const text = view.state.sliceDoc(from, to);
    const next = fn(text);
    if (next !== text) view.dispatch({ changes: { from, to, insert: next }, userEvent: 'input' });
    return true;
  };
}

// Case changes act on each selection; with nothing selected, on the word at the cursor.
export function changeCase(fn) {
  return (view) => {
    const tr = view.state.changeByRange((r) => {
      const range = r.empty ? view.state.wordAt(r.head) || r : r;
      const insert = fn(view.state.sliceDoc(range.from, range.to));
      return { changes: { from: range.from, to: range.to, insert }, range: EditorSelection.range(range.from, range.from + insert.length) };
    });
    view.dispatch(view.state.update(tr, { userEvent: 'input' }));
    return true;
  };
}

// Ctrl+J: join the selected lines (or the current line with the next) with a space.
export function joinLines(view) {
  const { state } = view;
  const sel = state.selection.main;
  const first = state.doc.lineAt(sel.from);
  let last = state.doc.lineAt(sel.to);
  if (last.number === first.number) {
    if (first.number === state.doc.lines) return true;
    last = state.doc.line(first.number + 1);
  }
  const changes = [];
  for (let n = first.number; n < last.number; n++) {
    const a = state.doc.line(n), b = state.doc.line(n + 1);
    const lead = /^[ \t]*/.exec(b.text)[0].length;
    const trail = /[ \t]*$/.exec(a.text)[0].length;
    changes.push({ from: a.to - trail, to: b.from + lead, insert: a.text.trim() && b.text.trim() ? ' ' : '' });
  }
  view.dispatch({ changes, userEvent: 'input' });
  return true;
}

// Ctrl+L (Notepad++ "line cut"): copies the current lines and removes them.
export function cutLine(view) {
  const { state } = view;
  const first = state.doc.lineAt(state.selection.main.from);
  const last = state.doc.lineAt(state.selection.main.to);
  const from = first.from;
  const to = last.number < state.doc.lines ? state.doc.line(last.number + 1).from : last.to;
  const text = state.sliceDoc(first.from, last.to) + '\n';
  navigator.clipboard?.writeText(text).catch(() => {});
  view.dispatch({ changes: { from: last.number < state.doc.lines ? from : Math.max(0, from - 1), to }, userEvent: 'delete.cut' });
  return true;
}

// Removes trailing whitespace in the whole document (used on save).
export function trimTrailingChanges(state) {
  const changes = [];
  for (let n = 1; n <= state.doc.lines; n++) {
    const l = state.doc.line(n);
    const m = /[ \t]+$/.exec(l.text);
    if (m) changes.push({ from: l.to - m[0].length, to: l.to });
  }
  return changes;
}
