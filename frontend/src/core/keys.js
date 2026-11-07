// Turns keyboard events into strings like "Ctrl+Shift+S" so commands can be
// matched by the same text shown in menus. Uses physical keys (event.code) for
// letters and digits so Shift and keyboard layouts do not change the name.

const CODE_NAMES = {
  Equal: '=', Minus: '-', Slash: '/', Backslash: '\\', Comma: ',', Period: '.', Semicolon: ';', Quote: "'",
  BracketLeft: '[', BracketRight: ']', Backquote: '`', NumpadAdd: 'Num+', NumpadSubtract: 'Num-', NumpadDivide: 'Num/',
  NumpadMultiply: 'Num*', ArrowUp: 'Up', ArrowDown: 'Down', ArrowLeft: 'Left', ArrowRight: 'Right', Space: 'Space',
};

export function keyName(e) {
  const code = e.code || '';
  if (/^Key[A-Z]$/.test(code)) return code.slice(3);
  if (/^Digit[0-9]$/.test(code)) return code.slice(5);
  if (/^Numpad[0-9]$/.test(code)) return code.slice(6);
  if (CODE_NAMES[code]) return CODE_NAMES[code];
  if (/^F([1-9]|1[0-9]|2[0-4])$/.test(e.key)) return e.key;
  if (['Tab', 'Enter', 'Escape', 'PageUp', 'PageDown', 'Home', 'End', 'Delete', 'Backspace', 'Insert'].includes(e.key)) return e.key;
  if (e.key && e.key.length === 1) return e.key.toUpperCase();
  return e.key || '';
}

export function comboOf(e) {
  const k = keyName(e);
  if (!k || ['Control', 'Shift', 'Alt', 'Meta'].includes(k)) return '';
  const mods = [];
  if (e.ctrlKey || e.metaKey) mods.push('Ctrl');
  if (e.altKey) mods.push('Alt');
  if (e.shiftKey) mods.push('Shift');
  return [...mods, k].join('+');
}

// "ctrl+shift+s" -> "Ctrl+Shift+S" (the order used by comboOf).
export function normalizeCombo(s) {
  const m = /^((?:(?:ctrl|alt|shift)\+)*)(.+)$/i.exec(s.trim());
  const mods = new Set(m[1].split('+').filter(Boolean).map((p) => p[0].toUpperCase() + p.slice(1).toLowerCase()));
  let key = m[2]; // may itself contain "+", e.g. "Num+"
  if (key.length === 1) key = key.toUpperCase();
  return [...['Ctrl', 'Alt', 'Shift'].filter((x) => mods.has(x)), key].join('+');
}
