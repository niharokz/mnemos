// Unit tests for the pure modules (run: npm test).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { join, basename, dirname, extname, isInside, relative, pretty, validName } from '../src/core/paths.js';
import { decode, encode, detectEol, looksBinary, ansiLossy, toBase64, fromBase64 } from '../src/core/encoding.js';
import { comboOf, normalizeCombo } from '../src/core/keys.js';
import { sanitize, DEFAULTS } from '../src/core/settings.js';
import { transforms, caseOps, tabsToSpaces, leadingSpacesToTabs } from '../src/editor/lineops.js';

test('paths', () => {
  assert.equal(join('/home/me', 'notes', 'a.md'), '/home/me/notes/a.md');
  assert.equal(join('', 'a.md'), 'a.md');
  assert.equal(join('D:/notes/', '/x'), 'D:/notes/x');
  assert.equal(basename('D:/notes/a.md'), 'a.md');
  assert.equal(dirname('D:/notes/a.md'), 'D:/notes');
  assert.equal(dirname('D:/a.md'), 'D:/');
  assert.equal(dirname('/a.md'), '/');
  assert.equal(dirname('a.md'), '');
  assert.equal(extname('x/Readme.MD'), '.md');
  assert.equal(extname('.env'), '');
  assert.ok(isInside('/home/me/notes/a.md', '/home/me/notes'));
  assert.ok(!isInside('/home/me/notes2/a.md', '/home/me/notes'));
  assert.ok(isInside('d:/Notes/a.md', 'D:/notes'));
  assert.ok(isInside('sub/a.md', ''));
  assert.equal(relative('/n/sub/a.md', '/n'), 'sub/a.md');
  assert.equal(relative('/x/a.md', '/n'), null);
  assert.equal(pretty('/home/me/notes', '/home/me'), '~/notes');
  assert.ok(validName('a b.md'));
  for (const bad of ['', '..', 'a/b', 'a:b', 'x\u0001']) assert.ok(!validName(bad), bad);
});

test('encoding round trips', () => {
  const text = 'héllo — ünïcode ✓\nline 2';
  for (const enc of ['UTF-8', 'UTF-8 BOM', 'UTF-16 LE', 'UTF-16 BE']) {
    const bytes = encode(text, enc, 'CRLF');
    const d = decode(bytes);
    assert.equal(d.encoding, enc);
    assert.equal(d.eol, 'CRLF');
    assert.equal(d.text.replace(/\r\n/g, '\n'), text);
  }
  const ansi = encode('café €5', 'ANSI');
  assert.deepEqual([...ansi], [0x63, 0x61, 0x66, 0xe9, 0x20, 0x80, 0x35]);
  assert.equal(decode(ansi).encoding, 'ANSI');
  assert.equal(decode(ansi).text, 'café €5');
  assert.ok(ansiLossy('✓'));
  assert.ok(!ansiLossy('café €'));
});

test('line endings and binary detection', () => {
  assert.equal(detectEol('a\nb\n'), 'LF');
  assert.equal(detectEol('a\r\nb\r\nc\n'), 'CRLF');
  assert.equal(detectEol('a\rb\r'), 'CR');
  assert.equal(detectEol('single'), null);
  assert.ok(looksBinary(new Uint8Array([1, 0, 2])));
  assert.ok(!looksBinary(new Uint8Array([0xff, 0xfe, 0x41, 0x00])));
  const b = new Uint8Array([0, 1, 2, 250, 255]);
  assert.deepEqual(fromBase64(toBase64(b)), b);
});

test('key combos', () => {
  assert.equal(comboOf({ code: 'KeyS', key: 's', ctrlKey: true, shiftKey: true }), 'Ctrl+Shift+S');
  assert.equal(comboOf({ code: 'Digit0', key: ')', altKey: true, shiftKey: true }), 'Alt+Shift+0');
  assert.equal(comboOf({ code: 'F3', key: 'F3', shiftKey: true }), 'Shift+F3');
  assert.equal(comboOf({ code: 'ArrowUp', key: 'ArrowUp', ctrlKey: true, altKey: true }), 'Ctrl+Alt+Up');
  assert.equal(comboOf({ code: 'ShiftLeft', key: 'Shift', shiftKey: true }), '');
  assert.equal(normalizeCombo('shift+ctrl+s'), 'Ctrl+Shift+S');
  assert.equal(normalizeCombo('Ctrl+Num+'), 'Ctrl+Num+');
  assert.equal(normalizeCombo('Ctrl+='), 'Ctrl+=');
});

test('settings sanitize', () => {
  const s = sanitize({ theme: 'neon', fontSize: 99, tabSize: '4', hidden: ['.git', 3], wordWrap: true, extra: 1 });
  assert.equal(s.theme, DEFAULTS.theme);
  assert.equal(s.fontSize, 40);
  assert.equal(s.tabSize, DEFAULTS.tabSize);
  assert.deepEqual(s.hidden, DEFAULTS.hidden);
  assert.equal(s.wordWrap, true);
  assert.equal(s.extra, 1); // unknown keys survive
  assert.deepEqual(sanitize(null), { ...DEFAULTS });
});

test('line operations', () => {
  assert.deepEqual(transforms.sortAsc(['b', 'a', 'C']), ['a', 'b', 'C']);
  assert.deepEqual(transforms.unique(['a', 'b', 'a']), ['a', 'b']);
  assert.deepEqual(transforms.removeEmpty(['a', ' ', '', 'b']), ['a', 'b']);
  assert.deepEqual(transforms.trimTrailing(['a  ', 'b\t']), ['a', 'b']);
  assert.equal(caseOps.title("hello wORLD it's"), "Hello World It's");
  assert.equal(caseOps.invert('aBc'), 'AbC');
  assert.equal(tabsToSpaces('\tx\ty', 4), '    x   y');
  assert.equal(leadingSpacesToTabs('      x', 4), '\t  x');
});
