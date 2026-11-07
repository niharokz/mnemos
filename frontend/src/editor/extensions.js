// Builds the CodeMirror setup for one document. Each open tab keeps its own
// EditorState (undo history, folds, bookmarks); the one EditorView swaps between
// them. Settings live in compartments so a change reconfigures in place.
import { Compartment, EditorState } from '@codemirror/state';
import {
  EditorView, keymap, lineNumbers, highlightActiveLine, highlightActiveLineGutter, drawSelection, dropCursor,
  rectangularSelection, crosshairCursor, highlightSpecialChars, highlightWhitespace, highlightTrailingWhitespace,
} from '@codemirror/view';
import { history, historyKeymap, standardKeymap, indentWithTab } from '@codemirror/commands';
import { indentOnInput, indentUnit, bracketMatching, foldGutter, foldKeymap, codeFolding } from '@codemirror/language';
import { search, highlightSelectionMatches } from '@codemirror/search';
import { autocompletion, completionKeymap, closeBrackets, closeBracketsKeymap, completeAnyWord } from '@codemirror/autocomplete';
import { showMinimap } from '@replit/codemirror-minimap';
import { editorTheme } from './theme.js';
import { bookmarkGutter } from './bookmarks.js';

const C = {
  language: new Compartment(),
  wrap: new Compartment(),
  numbers: new Compartment(),
  minimap: new Compartment(),
  indent: new Compartment(),
  readOnly: new Compartment(),
  whitespace: new Compartment(),
  activeLine: new Compartment(),
  brackets: new Compartment(),
};

// The view's update hook; set once by the app (all states share it).
let onUpdate = () => {};
export const setUpdateHandler = (fn) => { onUpdate = fn; };

const minimapExt = showMinimap.compute(['doc'], () => ({
  create: () => ({ dom: document.createElement('div') }),
  displayText: 'blocks',
  showOverlay: 'always',
}));

function parts(s, doc) {
  return {
    wrap: s.wordWrap ? EditorView.lineWrapping : [],
    numbers: s.lineNumbers ? [lineNumbers(), highlightActiveLineGutter()] : [],
    minimap: s.minimap && !doc.mobile ? minimapExt : [],
    indent: [EditorState.tabSize.of(s.tabSize), indentUnit.of(s.indentWithTabs ? '\t' : ' '.repeat(s.tabSize))],
    readOnly: [EditorState.readOnly.of(!!doc.readOnly), EditorView.editable.of(!doc.readOnly)],
    whitespace: s.showWhitespace ? [highlightWhitespace(), highlightTrailingWhitespace()] : [],
    activeLine: s.highlightActiveLine ? highlightActiveLine() : [],
    brackets: s.autoCloseBrackets ? [closeBrackets(), keymap.of(closeBracketsKeymap)] : [],
  };
}

export function createState(text, s, doc, language) {
  const p = parts(s, doc);
  return EditorState.create({
    doc: text,
    extensions: [
      C.language.of(language || []),
      C.wrap.of(p.wrap),
      C.numbers.of(p.numbers),
      C.minimap.of(p.minimap),
      C.indent.of(p.indent),
      C.readOnly.of(p.readOnly),
      C.whitespace.of(p.whitespace),
      C.activeLine.of(p.activeLine),
      C.brackets.of(p.brackets),
      bookmarkGutter,
      foldGutter({ openText: '▾', closedText: '▸' }),
      codeFolding(),
      highlightSpecialChars(),
      history(),
      drawSelection(),
      dropCursor(),
      EditorState.allowMultipleSelections.of(true),
      indentOnInput(),
      bracketMatching(),
      rectangularSelection(), // Alt+drag: column selection, like Notepad++
      crosshairCursor(),
      highlightSelectionMatches(),
      search({ top: true }),
      autocompletion({ activateOnTyping: false, override: null }),
      EditorState.languageData.of(() => [{ autocomplete: completeAnyWord }]),
      // Only plain editing keys here; every Notepad++ shortcut is in editor/keymap.js.
      keymap.of([indentWithTab, ...completionKeymap, ...foldKeymap.filter((k) => !/Mod-Alt|Ctrl-Shift/.test(k.key || '')), ...historyKeymap, ...standardKeymap]),
      editorTheme,
      EditorView.contentAttributes.of({ spellcheck: 'false', autocapitalize: 'off', autocorrect: 'off' }),
      EditorView.updateListener.of((u) => onUpdate(u)),
    ],
  });
}

// Effects that bring a state in line with the current settings.
export function reconfigure(s, doc) {
  const p = parts(s, doc);
  return Object.keys(p).map((k) => C[k].reconfigure(p[k]));
}

export const setLanguage = (support) => C.language.reconfigure(support || []);
