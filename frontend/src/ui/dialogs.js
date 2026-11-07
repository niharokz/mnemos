// Sheets (nss-app <dialog class="sheet">), prompts and toasts. Every dialog
// returns a promise; Escape or the backdrop resolves it as "cancel".
import { h, icon } from './dom.js';

let open = 0;
export const dialogOpen = () => open > 0;

export function sheet({ title, body, actions = [], wide = false, onClose }) {
  return new Promise((resolve) => {
    const d = h('dialog.sheet', { class: wide ? 'wide' : '' });
    const done = (v) => { if (!d.open) return; d.close(); resolve(v); };
    const footer = actions.length
      ? h('div.sheet-f', {}, actions.map((a) => h('button', {
        class: [a.primary && 'primary', a.danger && 'danger'].filter(Boolean).join(' '),
        type: a.submit ? 'submit' : 'button',
        onclick: async () => { const v = a.run ? await a.run() : a.value; if (v !== false) done(v ?? a.value); },
      }, a.label)))
      : null;
    d.append(...[
      h('div.sheet-h', {}, h('h2', {}, title), h('button.ghost.icon', { 'aria-label': 'Close', onclick: () => done(undefined) }, icon('x'))),
      h('div.sheet-b', {}, body),
      footer,
    ].filter(Boolean));
    d.addEventListener('close', () => { open--; d.remove(); onClose?.(); resolve(undefined); });
    d.addEventListener('click', (e) => { if (e.target === d) done(undefined); });
    document.body.append(d);
    open++;
    d.showModal();
    setTimeout(() => (d.querySelector('[autofocus]') || d.querySelector('input,select,textarea') || d.querySelector('.sheet-f button.primary'))?.focus(), 30);
    d.done = done;
  });
}

export function prompt(title, { label = '', value = '', ok = 'OK', placeholder = '', selectStem = false } = {}) {
  const input = h('input', { value, placeholder, autofocus: true, spellcheck: 'false', autocapitalize: 'off' });
  const form = h('form', { onsubmit: (e) => { e.preventDefault(); input.closest('dialog').done(input.value); } },
    label ? h('label.f', {}, h('span', {}, label), input) : input);
  setTimeout(() => {
    input.focus();
    const dot = value.lastIndexOf('.');
    if (selectStem && dot > 0) input.setSelectionRange(0, dot); else input.select();
  }, 40);
  return sheet({ title, body: form, actions: [{ label: 'Cancel', value: undefined }, { label: ok, primary: true, run: () => input.value }] });
}

export function confirm(title, message, { ok = 'OK', danger = false, cancel = 'Cancel', third } = {}) {
  const actions = [{ label: cancel, value: false }];
  if (third) actions.push({ label: third, value: 'third' });
  actions.push({ label: ok, value: true, primary: true, danger });
  return sheet({ title, body: h('p', {}, message), actions }).then((v) => v ?? false);
}

export function alert(title, message) {
  return sheet({ title, body: h('p', {}, message), actions: [{ label: 'OK', value: true, primary: true }] });
}

let toastEl, toastTimer;
export function toast(msg, { error = false, ms = 2600 } = {}) {
  toastEl?.remove();
  toastEl = h('div.toast', { class: error ? 'err' : '', role: 'status' }, msg);
  document.body.append(toastEl);
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => toastEl?.remove(), ms);
}

// A small context menu at the pointer: items [{label, run, keys?, danger?}] or "-".
export function contextMenu(x, y, items) {
  document.querySelector('.ctx')?.remove();
  const m = h('div.ctx', { role: 'menu' }, items.map((it) => it === '-'
    ? h('div.ctx-sep')
    : h('button.ctx-item', { class: it.danger ? 'danger' : '', disabled: it.disabled, onclick: () => { m.remove(); it.run(); } },
      h('span', {}, it.label), it.keys ? h('kbd', {}, it.keys) : null)));
  document.body.append(m);
  const r = m.getBoundingClientRect();
  m.style.left = Math.min(x, innerWidth - r.width - 6) + 'px';
  m.style.top = Math.min(y, innerHeight - r.height - 6) + 'px';
  const close = (e) => { if (!m.contains(e.target)) { m.remove(); removeEventListener('pointerdown', close, true); } };
  setTimeout(() => addEventListener('pointerdown', close, true));
  addEventListener('keydown', function esc(e) { if (e.key === 'Escape') { m.remove(); removeEventListener('keydown', esc); } });
}
