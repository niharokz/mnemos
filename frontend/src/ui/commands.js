// Every action with its Notepad++ default shortcut. Menus, the shortcut list
// (F1) and the global key handler all read this one table.
//   scope "app": works anywhere (even in a text field, e.g. Ctrl+S)
//   scope "editor": needs a text document and is skipped while typing in a field
import { undo, redo, selectAll, copyLineUp, deleteLine, moveLineUp, moveLineDown, toggleComment, lineComment, lineUncomment,
  toggleBlockComment, addCursorAbove, addCursorBelow, cursorMatchingBracket, selectMatchingBracket, indentMore, indentLess,
  insertNewlineAndIndent } from '@codemirror/commands';
import { openSearchPanel, findNext, findPrevious, gotoLine, selectSelectionMatches, selectNextOccurrence } from '@codemirror/search';
import { foldAll, unfoldAll, foldCode, unfoldCode } from '@codemirror/language';
import { startCompletion } from '@codemirror/autocomplete';
import { toggleBookmark, nextBookmark, prevBookmark, clearBookmarks } from '../editor/bookmarks.js';
import { applyLines, applyText, changeCase, caseOps, transforms, joinLines, cutLine, tabsToSpaces, leadingSpacesToTabs } from '../editor/lineops.js';
import { comboOf, normalizeCombo } from '../core/keys.js';
import { dialogOpen } from './dialogs.js';

export function buildCommands(app, ui) {
  const v = () => app.view;
  const ed = (fn) => () => { if (app.active?.kind === 'text') { fn(v()); } };
  const desktop = app.backend.kind === 'desktop';
  const list = [
    // File
    { id: 'file.new', label: 'New', keys: ['Ctrl+N'], scope: 'app', run: () => app.newDoc() },
    { id: 'file.open', label: 'Open file…', keys: ['Ctrl+O'], scope: 'app', run: () => ui.openFiles(), when: () => !!app.backend.pickFiles && app.backend.canOpenAnywhere },
    { id: 'file.openFolder', label: 'Open folder…', keys: [], scope: 'app', run: () => ui.openFolder(), when: () => !!app.backend.pickFolder && app.backend.canOpenAnywhere },
    { id: 'file.reopen', label: 'Reopen closed file', keys: ['Ctrl+Shift+T'], scope: 'app', run: () => app.reopenClosed() },
    { id: 'file.save', label: 'Save', keys: ['Ctrl+S'], scope: 'app', run: () => app.save() },
    { id: 'file.saveAs', label: 'Save as…', keys: ['Ctrl+Alt+S'], scope: 'app', run: () => app.saveAs() },
    { id: 'file.saveAll', label: 'Save all', keys: ['Ctrl+Shift+S'], scope: 'app', run: () => app.saveAll() },
    { id: 'file.rename', label: 'Rename…', keys: [], scope: 'app', run: () => app.active?.path && app.renamePath(app.active.path) },
    { id: 'file.reload', label: 'Reload from disk', keys: [], scope: 'app', run: () => app.reload() },
    { id: 'file.trash', label: 'Move to trash', keys: [], scope: 'app', run: () => app.active?.path && app.trashPath(app.active.path) },
    { id: 'file.close', label: 'Close', keys: ['Ctrl+W', 'Ctrl+F4'], scope: 'app', run: () => app.closeDoc() },
    { id: 'file.closeAll', label: 'Close all', keys: ['Ctrl+Shift+W'], scope: 'app', run: () => app.closeAll() },
    { id: 'file.closeOthers', label: 'Close all but this', keys: [], scope: 'app', run: () => app.closeOthers() },
    { id: 'file.print', label: 'Print…', keys: ['Ctrl+P'], scope: 'app', run: () => ui.print(), when: () => app.backend.kind !== 'android' },
    { id: 'file.exit', label: 'Exit', keys: ['Alt+F4'], scope: 'app', run: () => app.backend.quit?.(), when: () => desktop, passive: true },
    { id: 'tab.next', label: 'Next tab', keys: ['Ctrl+Tab', 'Ctrl+PageDown'], scope: 'app', run: () => app.nextTab(1) },
    { id: 'tab.prev', label: 'Previous tab', keys: ['Ctrl+Shift+Tab', 'Ctrl+PageUp'], scope: 'app', run: () => app.nextTab(-1) },

    // Edit
    { id: 'edit.undo', label: 'Undo', keys: ['Ctrl+Z'], run: ed(undo), passive: true },
    { id: 'edit.redo', label: 'Redo', keys: ['Ctrl+Y', 'Ctrl+Shift+Z'], run: ed(redo), passive: true },
    { id: 'edit.cut', label: 'Cut', keys: ['Ctrl+X'], run: () => document.execCommand('cut'), passive: true },
    { id: 'edit.copy', label: 'Copy', keys: ['Ctrl+C'], run: () => document.execCommand('copy'), passive: true },
    { id: 'edit.paste', label: 'Paste', keys: ['Ctrl+V'], run: () => ui.paste(), passive: true },
    { id: 'edit.selectAll', label: 'Select all', keys: ['Ctrl+A'], run: ed(selectAll), passive: true },
    { id: 'edit.duplicate', label: 'Duplicate current line', keys: ['Ctrl+D'], run: ed(copyLineUp) },
    { id: 'edit.deleteLine', label: 'Delete current line', keys: ['Ctrl+Shift+L'], run: ed(deleteLine) },
    { id: 'edit.cutLine', label: 'Cut current line', keys: ['Ctrl+L'], run: ed(cutLine) },
    { id: 'edit.moveUp', label: 'Move line up', keys: ['Ctrl+Shift+Up'], run: ed(moveLineUp) },
    { id: 'edit.moveDown', label: 'Move line down', keys: ['Ctrl+Shift+Down'], run: ed(moveLineDown) },
    { id: 'edit.transpose', label: 'Swap line with previous', keys: ['Ctrl+T'], run: ed(moveLineUp) },
    { id: 'edit.join', label: 'Join lines', keys: ['Ctrl+J'], run: ed(joinLines) },
    { id: 'edit.blankLine', label: 'Insert blank line below', keys: [], run: ed((view) => { const l = view.state.doc.lineAt(view.state.selection.main.head); view.dispatch({ selection: { anchor: l.to } }); insertNewlineAndIndent(view); }) },
    { id: 'edit.upper', label: 'UPPERCASE', keys: ['Ctrl+Shift+U'], run: ed(changeCase(caseOps.upper)) },
    { id: 'edit.lower', label: 'lowercase', keys: ['Ctrl+U'], run: ed(changeCase(caseOps.lower)) },
    { id: 'edit.title', label: 'Proper Case', keys: [], run: ed(changeCase(caseOps.title)) },
    { id: 'edit.invert', label: 'iNVERT cASE', keys: [], run: ed(changeCase(caseOps.invert)) },
    { id: 'edit.toggleComment', label: 'Toggle single-line comment', keys: ['Ctrl+Q'], run: ed(toggleComment) },
    { id: 'edit.comment', label: 'Single-line comment', keys: ['Ctrl+K'], run: ed(lineComment) },
    { id: 'edit.uncomment', label: 'Single-line uncomment', keys: ['Ctrl+Shift+K'], run: ed(lineUncomment) },
    { id: 'edit.blockComment', label: 'Block comment', keys: ['Ctrl+Shift+Q'], run: ed(toggleBlockComment) },
    { id: 'edit.indent', label: 'Increase indent', keys: [], run: ed(indentMore) },
    { id: 'edit.outdent', label: 'Decrease indent', keys: [], run: ed(indentLess) },
    { id: 'edit.complete', label: 'Word completion', keys: ['Ctrl+Enter', 'Ctrl+Space'], run: ed(startCompletion) },
    { id: 'edit.sortAsc', label: 'Sort lines ascending', keys: [], run: ed(applyLines(transforms.sortAsc)) },
    { id: 'edit.sortAscCi', label: 'Sort lines ascending (ignore case)', keys: [], run: ed(applyLines(transforms.sortAscCi)) },
    { id: 'edit.sortDesc', label: 'Sort lines descending', keys: [], run: ed(applyLines(transforms.sortDesc)) },
    { id: 'edit.reverse', label: 'Reverse line order', keys: [], run: ed(applyLines(transforms.reverse)) },
    { id: 'edit.unique', label: 'Remove duplicate lines', keys: [], run: ed(applyLines(transforms.unique)) },
    { id: 'edit.removeEmpty', label: 'Remove empty lines', keys: [], run: ed(applyLines(transforms.removeEmpty)) },
    { id: 'edit.trimTrailing', label: 'Trim trailing space', keys: [], run: ed(applyLines(transforms.trimTrailing)) },
    { id: 'edit.trimLeading', label: 'Trim leading space', keys: [], run: ed(applyLines(transforms.trimLeading)) },
    { id: 'edit.tabsToSpaces', label: 'TAB to space', keys: [], run: ed((view) => applyText((t) => tabsToSpaces(t, app.settings.get('tabSize')))(view)) },
    { id: 'edit.spacesToTabs', label: 'Leading space to TAB', keys: [], run: ed((view) => applyText((t) => leadingSpacesToTabs(t, app.settings.get('tabSize')))(view)) },
    { id: 'edit.cursorUp', label: 'Add cursor above', keys: ['Ctrl+Alt+Up', 'Alt+Shift+Up'], run: ed(addCursorAbove) },
    { id: 'edit.cursorDown', label: 'Add cursor below', keys: ['Ctrl+Alt+Down', 'Alt+Shift+Down'], run: ed(addCursorBelow) },
    { id: 'edit.nextOccurrence', label: 'Add next occurrence', keys: ['Ctrl+Alt+D'], run: ed(selectNextOccurrence) },
    { id: 'edit.allOccurrences', label: 'Select all occurrences', keys: ['Alt+F3'], run: ed(selectSelectionMatches) },
    { id: 'edit.dateTime', label: 'Insert date and time', keys: [], run: ed((view) => view.dispatch(view.state.replaceSelection(new Date().toLocaleString()))) },
    { id: 'edit.readOnly', label: 'Read-only', keys: [], run: () => ui.toggleReadOnly(), checked: () => !!app.active?.readOnly },

    // Search
    { id: 'search.find', label: 'Find…', keys: ['Ctrl+F'], run: ed(openSearchPanel) },
    { id: 'search.replace', label: 'Replace…', keys: ['Ctrl+H'], run: ed((view) => { openSearchPanel(view); setTimeout(() => view.dom.querySelector('.cm-search input[name=replace]')?.focus(), 0); }) },
    { id: 'search.next', label: 'Find next', keys: ['F3'], run: ed(findNext) },
    { id: 'search.prev', label: 'Find previous', keys: ['Shift+F3'], run: ed(findPrevious) },
    { id: 'search.inFiles', label: 'Find in files…', keys: ['Ctrl+Shift+F'], scope: 'app', run: () => ui.showSearch() },
    { id: 'search.goto', label: 'Go to line…', keys: ['Ctrl+G'], run: ed(gotoLine) },
    { id: 'search.brace', label: 'Go to matching brace', keys: ['Ctrl+B'], run: ed(cursorMatchingBracket) },
    { id: 'search.selectBrace', label: 'Select to matching brace', keys: ['Ctrl+Alt+B'], run: ed(selectMatchingBracket) },
    { id: 'bookmark.toggle', label: 'Toggle bookmark', keys: ['Ctrl+F2'], run: ed(toggleBookmark) },
    { id: 'bookmark.next', label: 'Next bookmark', keys: ['F2'], run: ed(nextBookmark) },
    { id: 'bookmark.prev', label: 'Previous bookmark', keys: ['Shift+F2'], run: ed(prevBookmark) },
    { id: 'bookmark.clear', label: 'Clear all bookmarks', keys: [], run: ed(clearBookmarks) },

    // View
    { id: 'view.menu', label: 'Collapse menu bar', keys: [], scope: 'app', run: () => ui.toggleMenu(), checked: () => app.ui.menuCollapsed, when: () => !app.mobile },
    { id: 'view.sidebar', label: 'Sidebar', keys: ['Ctrl+Shift+E'], scope: 'app', run: () => ui.toggleSidebar(), checked: () => app.ui.sidebar },
    { id: 'view.preview', label: 'Markdown preview', keys: ['Ctrl+Shift+M'], scope: 'app', run: () => app.setPreview(!app.ui.preview), checked: () => app.ui.preview },
    { id: 'view.wrap', label: 'Word wrap', keys: ['Alt+Z'], scope: 'app', run: () => app.settings.set({ wordWrap: !app.settings.get('wordWrap') }), checked: () => app.settings.get('wordWrap') },
    { id: 'view.whitespace', label: 'Show whitespace', keys: [], scope: 'app', run: () => app.settings.set({ showWhitespace: !app.settings.get('showWhitespace') }), checked: () => app.settings.get('showWhitespace') },
    { id: 'view.lineNumbers', label: 'Line numbers', keys: [], scope: 'app', run: () => app.settings.set({ lineNumbers: !app.settings.get('lineNumbers') }), checked: () => app.settings.get('lineNumbers') },
    { id: 'view.minimap', label: 'Minimap', keys: [], scope: 'app', run: () => app.settings.set({ minimap: !app.settings.get('minimap') }), checked: () => app.settings.get('minimap'), when: () => !app.mobile },
    { id: 'view.zoomIn', label: 'Zoom in', keys: ['Ctrl+=', 'Ctrl+Num+'], scope: 'app', run: () => ui.zoom(1) },
    { id: 'view.zoomOut', label: 'Zoom out', keys: ['Ctrl+-', 'Ctrl+Num-'], scope: 'app', run: () => ui.zoom(-1) },
    { id: 'view.zoomReset', label: 'Restore default zoom', keys: ['Ctrl+0', 'Ctrl+Num/'], scope: 'app', run: () => ui.zoom(0) },
    { id: 'view.foldAll', label: 'Fold all', keys: ['Alt+0'], run: ed(foldAll) },
    { id: 'view.unfoldAll', label: 'Unfold all', keys: ['Alt+Shift+0'], run: ed(unfoldAll) },
    { id: 'view.fold', label: 'Fold current level', keys: ['Ctrl+Alt+F'], run: ed(foldCode) },
    { id: 'view.unfold', label: 'Unfold current level', keys: ['Ctrl+Alt+Shift+F'], run: ed(unfoldCode) },
    { id: 'view.fullscreen', label: 'Full screen', keys: ['F11'], scope: 'app', run: () => app.backend.toggleFullscreen?.(), when: () => app.backend.kind !== 'android' },
    { id: 'view.themeDark', label: 'nss dark', keys: [], scope: 'app', run: () => app.settings.set({ theme: 'dark' }), checked: () => app.settings.get('theme') === 'dark' },
    { id: 'view.themeLight', label: 'nss light', keys: [], scope: 'app', run: () => app.settings.set({ theme: 'light' }), checked: () => app.settings.get('theme') === 'light' },
    { id: 'view.themeSystem', label: 'Follow system', keys: [], scope: 'app', run: () => app.settings.set({ theme: 'system' }), checked: () => app.settings.get('theme') === 'system' },

    // Settings and help
    { id: 'settings.open', label: 'Preferences…', keys: ['Ctrl+,'], scope: 'app', run: () => ui.showSettings() },
    { id: 'settings.file', label: 'Open settings.json', keys: [], scope: 'app', run: () => app.openSettingsFile() },
    { id: 'settings.notes', label: 'Notes folder…', keys: [], scope: 'app', run: () => app.chooseNotesRoot(), when: () => !!app.backend.setNotesRoot },
    { id: 'settings.trash', label: 'Empty trash…', keys: [], scope: 'app', run: () => app.emptyTrash() },
    { id: 'help.keys', label: 'Keyboard shortcuts', keys: ['F1'], scope: 'app', run: () => ui.showShortcuts() },
    { id: 'help.about', label: 'About mnemostxt', keys: [], scope: 'app', run: () => ui.showAbout() },
    { id: 'web.logout', label: 'Sign out', keys: [], scope: 'app', run: () => ui.logout(), when: () => app.backend.kind === 'web' },
  ];
  for (const c of list) { c.scope ||= 'editor'; c.keys = c.keys.map(normalizeCombo); }
  return list;
}

// Installs the capture-phase key handler. Returns the lookup used by menus.
export function installKeys(commands, app) {
  const byKey = new Map();
  for (const c of commands) for (const k of c.keys) if (!c.passive) byKey.set(k, c);
  const byId = new Map(commands.map((c) => [c.id, c]));

  addEventListener('keydown', (e) => {
    const combo = comboOf(e);
    if (!combo) return;
    const c = byKey.get(combo);
    if (!c || (c.when && !c.when())) return;
    if (dialogOpen() && c.scope === 'app' && !['file.save'].includes(c.id)) return;
    const t = e.target;
    const inField = t instanceof HTMLElement && (t.matches('input, textarea, select') || (t.isContentEditable && !t.closest('.cm-content')));
    if (c.scope === 'editor') {
      if (app.active?.kind !== 'text') return;
      if (inField && !t.closest('.cm-panel')) return; // let fields keep their own keys
      if (t.closest?.('.cm-panel') && !['search.next', 'search.prev', 'search.find', 'search.replace'].includes(c.id)) return;
    }
    e.preventDefault();
    e.stopPropagation();
    c.run();
  }, true);

  return byId;
}
