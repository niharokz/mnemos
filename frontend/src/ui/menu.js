// The menu bar (File, Edit, Search, View, Encoding, Language, Settings, ?).
// It can collapse into one ≡ button (View → Collapse menu bar); a lone Alt
// press opens it, like Notepad++. On phones the same menus open from ⋮ as a
// drill-down list.
import { ENCODINGS } from '../core/encoding.js';
import { grouped, PLAIN } from '../core/languages.js';
import { h, icon } from './dom.js';

export function menuModel(app, ui) {
  const sub = (label, items) => ({ label, items });
  return [
    sub('File', ['file.new', 'file.open', 'file.openFolder', '-', 'file.reopen', '-', 'file.save', 'file.saveAs', 'file.saveAll', 'file.rename', 'file.reload', 'file.trash', '-', 'file.close', 'file.closeAll', 'file.closeOthers', '-', 'file.print', '-', 'file.exit']),
    sub('Edit', ['edit.undo', 'edit.redo', '-', 'edit.cut', 'edit.copy', 'edit.paste', 'edit.selectAll', '-',
      sub('Line operations', ['edit.duplicate', 'edit.deleteLine', 'edit.cutLine', 'edit.moveUp', 'edit.moveDown', 'edit.transpose', 'edit.join', 'edit.blankLine', '-', 'edit.sortAsc', 'edit.sortAscCi', 'edit.sortDesc', 'edit.reverse', 'edit.unique', 'edit.removeEmpty']),
      sub('Convert case', ['edit.upper', 'edit.lower', 'edit.title', 'edit.invert']),
      sub('Comment', ['edit.toggleComment', 'edit.comment', 'edit.uncomment', 'edit.blockComment']),
      sub('Blank operations', ['edit.trimTrailing', 'edit.trimLeading', 'edit.tabsToSpaces', 'edit.spacesToTabs']),
      sub('Multi-cursor', ['edit.cursorUp', 'edit.cursorDown', 'edit.nextOccurrence', 'edit.allOccurrences']),
      '-', 'edit.indent', 'edit.outdent', 'edit.complete', 'edit.dateTime', '-', 'edit.readOnly']),
    sub('Search', ['search.find', 'search.replace', 'search.next', 'search.prev', '-', 'search.inFiles', '-', 'search.goto', 'search.brace', 'search.selectBrace', '-',
      sub('Bookmark', ['bookmark.toggle', 'bookmark.next', 'bookmark.prev', 'bookmark.clear'])]),
    sub('View', ['view.menu', 'view.sidebar', 'view.preview', '-', 'view.wrap', 'view.whitespace', 'view.lineNumbers', 'view.minimap', '-',
      'view.zoomIn', 'view.zoomOut', 'view.zoomReset', '-', 'view.foldAll', 'view.unfoldAll', 'view.fold', 'view.unfold', '-',
      sub('Theme', ['view.themeDark', 'view.themeLight', 'view.themeSystem']), 'view.fullscreen']),
    { label: 'Encoding', items: () => encodingItems(app) },
    { label: 'Language', items: () => languageItems(app, ui) },
    sub('Settings', ['settings.open', 'settings.file', 'settings.notes', 'settings.trash']),
    sub('?', ['help.keys', 'help.about', 'web.logout']),
  ];
}

function encodingItems(app) {
  const d = app.active?.kind === 'text' ? app.active : null;
  const enc = d?.encoding;
  return [
    ...ENCODINGS.map((e) => ({ label: `Convert to ${e}`, checked: () => enc === e, disabled: !d, run: () => app.convertEncoding(e) })),
    '-',
    { label: 'Reopen with', items: ENCODINGS.map((e) => ({ label: e, disabled: !d?.path, run: () => app.reload(d, { encoding: e }) })) },
    '-',
    { label: 'Windows (CR LF)', checked: () => d?.eol === 'CRLF', disabled: !d, run: () => app.setEol('CRLF') },
    { label: 'Unix (LF)', checked: () => d?.eol === 'LF', disabled: !d, run: () => app.setEol('LF') },
    { label: 'Macintosh (CR)', checked: () => d?.eol === 'CR', disabled: !d, run: () => app.setEol('CR') },
  ];
}

function languageItems(app, ui) {
  const d = app.active?.kind === 'text' ? app.active : null;
  const cur = d?.langName;
  return [
    { label: 'Choose language…', run: () => ui.pickLanguage(), disabled: !d },
    { label: 'Auto-detect', run: () => { if (d) { d.langNameManual = false; app.loadLanguage(d, null); } }, disabled: !d },
    { label: PLAIN, checked: () => cur === PLAIN, run: () => app.setLanguageName(PLAIN), disabled: !d },
    '-',
    ...grouped().map(([letter, names]) => ({
      label: letter,
      items: names.map((n) => ({ label: n, checked: () => cur === n, disabled: !d, run: () => app.setLanguageName(n) })),
    })),
  ];
}

// Resolves "id" strings to command entries; drops ones whose `when` is false.
function resolve(items, cmds) {
  const list = typeof items === 'function' ? items() : items;
  const out = [];
  for (const it of list) {
    if (it === '-') { if (out.length && out[out.length - 1] !== '-') out.push('-'); continue; }
    if (typeof it === 'string') {
      const c = cmds.get(it);
      if (!c || (c.when && !c.when())) continue;
      out.push({ label: c.label, keys: c.keys[0], checked: c.checked, run: c.run });
    } else out.push(it);
  }
  while (out[out.length - 1] === '-') out.pop();
  return out;
}

export class MenuBar {
  constructor(app, ui, cmds) {
    this.app = app;
    this.ui = ui;
    this.cmds = cmds;
    this.model = menuModel(app, ui);
    this.openIdx = -1;
    this.drop = null;
    this.el = h('nav.menubar', { role: 'menubar' });
    this.render();
    addEventListener('pointerdown', (e) => { if (this.drop && !this.drop.contains(e.target) && !this.el.contains(e.target)) this.close(); }, true);
    addEventListener('keydown', (e) => { if (e.key === 'Escape' && this.drop) { this.close(); e.stopPropagation(); } }, true);
    addEventListener('blur', () => this.close());
    // a lone Alt press (no other key) toggles the menu, like Notepad++
    let altAlone = false;
    addEventListener('keydown', (e) => { altAlone = e.key === 'Alt' && !e.ctrlKey && !e.shiftKey && !e.metaKey; }, true);
    addEventListener('keyup', (e) => {
      if (e.key === 'Alt' && altAlone && !app.mobile) {
        e.preventDefault();
        if (this.drop) this.close(); else this.openAt(0);
      }
      altAlone = false;
    }, true);
  }

  render() {
    this.el.replaceChildren();
    if (this.app.ui.menuCollapsed) {
      this.el.append(h('button.mb-top.icon', { title: 'Menu (Alt)', 'aria-label': 'Menu', onclick: () => (this.drop ? this.close() : this.openAt(0)) }, icon('menu')));
    } else {
      this.model.forEach((m, i) => this.el.append(h('button.mb-top', {
        'data-i': i,
        onclick: () => (this.openIdx === i ? this.close() : this.openAt(i)),
        onpointerenter: () => { if (this.drop && this.openIdx !== i) this.openAt(i); },
      }, m.label)));
    }
  }

  openAt(i) {
    this.close();
    this.openIdx = i;
    if (this.app.ui.menuCollapsed) {
      const anchor = this.el.firstChild;
      this.drop = this.dropdown(this.model.map((m) => ({ label: m.label, items: m.items })), anchor);
    } else {
      const anchor = this.el.querySelector(`[data-i="${i}"]`);
      anchor.classList.add('open');
      this.drop = this.dropdown(this.model[i].items, anchor);
    }
  }

  close() {
    this.drop?.remove();
    document.querySelectorAll('.dropdown').forEach((d) => d.remove());
    this.el.querySelectorAll('.open').forEach((b) => b.classList.remove('open'));
    this.drop = null;
    this.openIdx = -1;
  }

  dropdown(items, anchor, level = 0) {
    const d = h('div.dropdown', { role: 'menu' });
    for (const it of resolve(items, this.cmds)) {
      if (it === '-') { d.append(h('div.ctx-sep')); continue; }
      const isSub = !!it.items;
      const checked = it.checked?.();
      const b = h('button.mi', { disabled: it.disabled, role: 'menuitem' },
        h('span.mi-check', {}, checked ? '✓' : ''),
        h('span.mi-label', {}, it.label),
        isSub ? icon('chevronRight', 'i mi-sub') : h('kbd', {}, it.keys || ''));
      if (isSub) {
        let flyout = null;
        const openSub = () => {
          d.querySelectorAll(':scope > .mi.sub-open').forEach((x) => x.classList.remove('sub-open'));
          document.querySelectorAll('.dropdown').forEach((x) => { if (Number(x.dataset.level) > level) x.remove(); });
          b.classList.add('sub-open');
          flyout = this.dropdown(it.items, b, level + 1);
        };
        b.addEventListener('pointerenter', openSub);
        b.addEventListener('click', openSub);
      } else {
        b.addEventListener('pointerenter', () => document.querySelectorAll('.dropdown').forEach((x) => { if (Number(x.dataset.level) > level) x.remove(); }));
        b.addEventListener('click', () => { this.close(); it.run?.(); });
      }
      d.append(b);
    }
    d.dataset.level = level;
    document.body.append(d);
    const r = anchor.getBoundingClientRect();
    const dr = d.getBoundingClientRect();
    let x = level === 0 ? r.left : r.right - 2;
    let y = level === 0 ? r.bottom + 2 : r.top - 4;
    if (x + dr.width > innerWidth - 4) x = level === 0 ? innerWidth - dr.width - 4 : r.left - dr.width + 2;
    if (y + dr.height > innerHeight - 4) y = Math.max(4, innerHeight - dr.height - 4);
    d.style.left = Math.max(4, x) + 'px';
    d.style.top = y + 'px';
    return d;
  }

  // Phones: the whole menu as a drill-down list in a full-height panel.
  openMobile(anchor) {
    this.close();
    const stack = [{ label: 'Menu', items: this.model.map((m) => ({ label: m.label, items: m.items })) }];
    const panel = h('div.dropdown.mobile-menu', { role: 'menu' });
    const draw = () => {
      const top = stack[stack.length - 1];
      panel.replaceChildren(
        ...(stack.length > 1 ? [h('button.mi.mi-back', { onclick: () => { stack.pop(); draw(); } }, icon('chevronDown', 'i rot90'), h('span.mi-label', {}, top.label))] : []),
        ...resolve(top.items, this.cmds).map((it) => it === '-' ? h('div.ctx-sep') : h('button.mi', {
          disabled: it.disabled,
          onclick: () => { if (it.items) { stack.push(it); draw(); } else { this.close(); it.run?.(); } },
        }, h('span.mi-check', {}, it.checked?.() ? '✓' : ''), h('span.mi-label', {}, it.label), it.items ? icon('chevronRight', 'i mi-sub') : null)),
      );
    };
    draw();
    document.body.append(panel);
    const r = anchor.getBoundingClientRect();
    panel.style.top = r.bottom + 4 + 'px';
    panel.style.right = '6px';
    this.drop = panel;
  }
}
