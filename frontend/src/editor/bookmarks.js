// Notepad++-style bookmarks: Ctrl+F2 toggles, F2 / Shift+F2 jump, a dot in the
// gutter marks the line. Bookmarks move with edits and live per document.
import { StateEffect, StateField, RangeSet } from '@codemirror/state';
import { EditorView, GutterMarker, gutter } from '@codemirror/view';

const toggle = StateEffect.define();
const clear = StateEffect.define();

class Mark extends GutterMarker {
  toDOM() {
    const e = document.createElement('span');
    e.className = 'cm-bookmark';
    return e;
  }
}
const mark = new Mark();

export const bookmarkField = StateField.define({
  create: () => RangeSet.empty,
  update(set, tr) {
    set = set.map(tr.changes);
    for (const e of tr.effects) {
      if (e.is(clear)) set = RangeSet.empty;
      if (e.is(toggle)) {
        let has = false;
        set.between(e.value, e.value, () => { has = true; });
        set = has ? set.update({ filter: (from) => from !== e.value }) : set.update({ add: [mark.range(e.value)], sort: true });
      }
    }
    return set;
  },
});

export const bookmarkGutter = [
  bookmarkField,
  gutter({
    class: 'cm-bookmark-gutter',
    markers: (v) => v.state.field(bookmarkField),
    initialSpacer: () => mark,
    domEventHandlers: {
      mousedown(view, line) {
        view.dispatch({ effects: toggle.of(line.from) });
        return true;
      },
    },
  }),
  EditorView.baseTheme({
    '.cm-bookmark-gutter .cm-gutterElement': { width: '12px', display: 'flex', alignItems: 'center', justifyContent: 'center', cursor: 'pointer' },
    '.cm-bookmark': { display: 'inline-block', width: '8px', height: '8px', borderRadius: '50%', background: 'var(--nss-accent-2)' },
  }),
];

function lines(state) {
  const out = [];
  const iter = state.field(bookmarkField).iter();
  while (iter.value) { out.push(iter.from); iter.next(); }
  return out;
}

export function toggleBookmark(view) {
  const line = view.state.doc.lineAt(view.state.selection.main.head);
  view.dispatch({ effects: toggle.of(line.from) });
  return true;
}

function jump(view, dir) {
  const all = lines(view.state);
  if (!all.length) return true;
  const here = view.state.doc.lineAt(view.state.selection.main.head).from;
  const next = dir > 0 ? all.find((p) => p > here) ?? all[0] : [...all].reverse().find((p) => p < here) ?? all[all.length - 1];
  view.dispatch({ selection: { anchor: next }, scrollIntoView: true });
  return true;
}

export const nextBookmark = (v) => jump(v, 1);
export const prevBookmark = (v) => jump(v, -1);
export const clearBookmarks = (v) => { v.dispatch({ effects: clear.of(null) }); return true; };
