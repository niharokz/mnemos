// Web sign-in card (nss-app). The server limits failed attempts per address.
import { h, logo } from './dom.js';

export function showLogin(backend) {
  const user = h('input', { name: 'user', autocomplete: 'username', autocapitalize: 'off', spellcheck: 'false', required: true });
  const pass = h('input', { name: 'password', type: 'password', autocomplete: 'current-password', required: true });
  const err = h('p.err', { role: 'alert' });
  const btn = h('button.primary.block', { type: 'submit' }, 'Sign in');
  const form = h('form.card', {
    onsubmit: async (e) => {
      e.preventDefault();
      btn.disabled = true;
      err.textContent = '';
      try {
        await backend.login(user.value, pass.value);
        location.reload();
      } catch (ex) {
        err.textContent = ex.message;
        btn.disabled = false;
        pass.select();
      }
    },
  },
  h('div.login-brand', {}, logo(30), h('span.brand', {}, 'mnemostxt')),
  h('label.f', {}, h('span', {}, 'User'), user),
  h('label.f', {}, h('span', {}, 'Password'), pass),
  btn, err);
  document.body.className = 'login';
  document.body.replaceChildren(h('main.center', {}, form));
  user.focus();
}
