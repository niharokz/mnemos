// The tab strip: one tab per open file. ● marks unsaved edits, middle-click
// closes, drag reorders, right-click has the Notepad++ tab menu.
import { contextMenu, toast } from './dialogs.js';
import { h, icon } from './dom.js';

export class Tabs {
  constructor(app) {
    this.app = app;
    this.el = h('div.tabs', { role: 'tablist' });
    this.el.addEventListener('wheel', (e) => { if (Math.abs(e.deltaY) > Math.abs(e.deltaX)) { this.el.scrollLeft += e.deltaY; e.preventDefault(); } }, { passive: false });
    this.el.addEventListener('dblclick', (e) => { if (e.target === this.el) app.newDoc(); });
    for (const ev of ['docs', 'active', 'saved', 'conflict']) app.on(ev, () => this.render());
    this.render();
  }

  render() {
    const app = this.app;
    this.el.replaceChildren(...app.docs.map((d, i) => {
      const dirty = app.dirty(d);
      const tab = h('div.tab', {
        role: 'tab', 'aria-selected': String(d === app.active), draggable: 'true', title: d.path || d.untitled,
        class: [d === app.active && 'active', dirty && 'dirty', d.conflict && 'warn', !d.path && 'untitled'].filter(Boolean).join(' '),
        onclick: () => app.activate(d),
        onauxclick: (e) => { if (e.button === 1) { e.preventDefault(); app.closeDoc(d); } },
        oncontextmenu: (e) => { e.preventDefault(); this.menu(e.clientX, e.clientY, d); },
        ondragstart: (e) => { e.dataTransfer.setData('text/x-mnemostxt-tab', String(i)); e.dataTransfer.effectAllowed = 'move'; },
        ondragover: (e) => { if (e.dataTransfer.types.includes('text/x-mnemostxt-tab')) { e.preventDefault(); tab.classList.add('drop'); } },
        ondragleave: () => tab.classList.remove('drop'),
        ondrop: (e) => {
          e.preventDefault();
          tab.classList.remove('drop');
          const from = Number(e.dataTransfer.getData('text/x-mnemostxt-tab'));
          if (Number.isInteger(from) && from !== i) app.moveTab(from, i);
        },
      },
      h('span.tab-name', {}, app.displayName(d)),
      h('button.tab-x', { 'aria-label': 'Close', tabindex: '-1', onclick: (e) => { e.stopPropagation(); app.closeDoc(d); } }, h('span.dot'), icon('x')));
      return tab;
    }), h('button.tab-new.ghost.icon', { title: 'New (Ctrl+N)', 'aria-label': 'New file', onclick: () => app.newDoc() }, icon('plus')));
    this.el.querySelector('.tab.active')?.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }

  menu(x, y, d) {
    const app = this.app;
    contextMenu(x, y, [
      { label: 'Close', keys: 'Ctrl+W', run: () => app.closeDoc(d) },
      { label: 'Close all but this', run: () => app.closeOthers(d) },
      { label: 'Close all to the right', run: () => app.closeRight(d) },
      { label: 'Close all', keys: 'Ctrl+Shift+W', run: () => app.closeAll() },
      '-',
      { label: 'Save', run: () => app.save(d) },
      { label: 'Rename…', disabled: !d.path, run: () => app.renamePath(d.path) },
      { label: 'Reload from disk', disabled: !d.path, run: () => app.reload(d) },
      '-',
      { label: 'Copy path', disabled: !d.path, run: () => navigator.clipboard?.writeText(d.path).then(() => toast('Path copied'), () => {}) },
      { label: 'Reveal in sidebar', disabled: !d.path, run: () => app.emit('reveal', d.path) },
    ]);
  }
}
