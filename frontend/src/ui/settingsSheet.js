// Preferences: a form over the one settings.json. Every change saves at once
// (it is the same file on every device). "Open settings.json" edits it raw.
import { sheet } from './dialogs.js';
import { h } from './dom.js';

export function showSettings(app) {
  const s = app.settings;
  const v = s.values;

  const seg = (key, options) => {
    const box = h('div.seg', { role: 'group' });
    const draw = () => box.replaceChildren(...options.map(([val, label]) => h('button', { type: 'button', 'aria-pressed': String(s.get(key) === val), onclick: () => { s.set({ [key]: val }); draw(); } }, label)));
    draw();
    return box;
  };
  const num = (key, min, max, step = 1) => h('input', { type: 'number', min, max, step, value: v[key], oninput: (e) => { const n = Number(e.target.value); if (Number.isFinite(n) && n >= min && n <= max) s.set({ [key]: n }); } });
  const sw = (key, label, hint) => h('label.sw', {},
    h('input', { type: 'checkbox', checked: !!v[key], onchange: (e) => s.set({ [key]: e.target.checked }) }),
    h('span', {}, label, hint ? h('small', {}, hint) : null));
  const row = (label, control, hint) => h('div.set-row', {}, h('div', {}, h('div.set-l', {}, label), hint ? h('small', {}, hint) : null), control);

  const hiddenArea = h('textarea', { rows: 3, spellcheck: 'false', onchange: (e) => s.set({ hidden: e.target.value.split(/[\n,]/).map((x) => x.trim()).filter(Boolean) }) }, v.hidden.join('\n'));

  const notesRow = app.backend.setNotesRoot
    ? row('Notes folder', h('button', { type: 'button', onclick: async () => { body.closest('dialog').done(); await app.chooseNotesRoot(); } }, 'Change…'), app.backend.notesRoot)
    : row('Notes folder', h('span.muted', {}, app.backend.notesName), 'Set on the server (MNEMOSTXT_NOTES)');

  const body = h('div.settings',
    {},
    h('h3', {}, 'Appearance'),
    row('Theme', seg('theme', [['dark', 'Dark'], ['light', 'Light'], ['system', 'System']])),
    row('Font size', num(app.mobile ? 'mobileFontSize' : 'fontSize', 8, 40), app.mobile ? 'On phones' : 'On desktop and web'),
    row('Line height', num('lineHeight', 1, 3, 0.05)),
    sw('lineNumbers', 'Line numbers'),
    sw('highlightActiveLine', 'Highlight current line'),
    sw('showWhitespace', 'Show whitespace'),
    sw('wordWrap', 'Word wrap'),
    app.mobile ? null : sw('minimap', 'Minimap'),
    h('h3', {}, 'Editing'),
    row('Tab width', num('tabSize', 1, 16)),
    sw('indentWithTabs', 'Indent with tabs'),
    sw('autoCloseBrackets', 'Auto-close brackets and quotes'),
    row('New file line endings', seg('newFileEol', [['LF', 'LF'], ['CRLF', 'CRLF']])),
    sw('trimTrailingWhitespaceOnSave', 'Trim trailing whitespace on save'),
    sw('insertFinalNewlineOnSave', 'End files with a newline on save'),
    h('h3', {}, 'Saving'),
    row('Autosave', seg('autosave', [['notes', 'Notes folder'], ['all', 'All files'], ['off', 'Off']]), 'Unsaved edits are always kept and restored on restart'),
    row('Autosave delay (ms)', num('autosaveDelayMs', 200, 60000, 100)),
    h('h3', {}, 'Files'),
    notesRow,
    sw('showHidden', 'Show hidden items'),
    h('label.f', {}, h('span', {}, 'Hidden names (one per line)'), hiddenArea),
    sw('markdownPreviewOnOpen', 'Open markdown preview with .md files'),
    sw('confirmDelete', 'Confirm before moving to trash'),
    row('Keep trash for (days)', num('trashDays', 1, 3650), 'Settings → Empty trash removes older items'),
    h('p.muted.set-foot', {}, `Saved in ${app.backend.notesName}/logs/mnemostxt/settings.json · mnemostxt ${app.backend.version}`),
  );

  return sheet({
    title: 'Preferences',
    body,
    wide: true,
    actions: [
      { label: 'Open settings.json', run: () => { app.openSettingsFile(); } },
      { label: 'Done', primary: true },
    ],
  });
}
