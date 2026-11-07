// mnemostxt — start-up: pick the backend, sign in (web), choose the notes folder
// (first run), load the shared settings, restore the last session, build the UI.
import '@fontsource/jetbrains-mono/latin-400.css';
import '@fontsource/jetbrains-mono/latin-700.css';
import './styles/nss-app.css';
import './styles/app.css';
import { createBackend } from './backend/index.js';
import { Settings } from './core/settings.js';
import { App } from './ui/app.js';
import { Shell } from './ui/shell.js';
import { showLogin } from './ui/login.js';
import { h } from './ui/dom.js';

// Anything that breaks start-up is shown, never a blank window.
function fatal(e) {
  console.error(e);
  const msg = e ? `${e.message || e}\n\n${e.stack || ''}` : 'unknown error';
  document.body.className = 'app boot';
  document.body.replaceChildren(h('main.center', {}, h('div.card', { style: { maxWidth: '640px' } },
    h('h2', {}, 'mnemostxt could not start'),
    h('pre', { style: { whiteSpace: 'pre-wrap', fontSize: '12px', color: 'var(--nss-bad)' } }, msg),
    h('button', { onclick: () => location.reload() }, 'Try again'))));
}

async function start() {
  let backend;
  try {
    backend = await createBackend();
  } catch (e) {
    document.body.replaceChildren(h('main.center', {}, h('div.card', {}, h('h2', {}, 'mnemostxt cannot start'), h('p.muted', {}, e?.message || String(e)))));
    return;
  }
  if (backend.kind === 'web' && !backend.signedIn) { showLogin(backend); return; }

  const settings = new Settings(backend);
  const app = new App(backend, settings);
  window.mnemostxt = app; // handy in the devtools console

  await app.loadSession();
  if (backend.notesRoot == null && backend.setNotesRoot) {
    document.body.className = 'app boot';
    await app.chooseNotesRoot(true);
  }
  await settings.load();
  app.applyLook();

  const shell = new Shell(app);
  await shell.tree.renderAll();
  await app.restore();
  if (backend.startupFiles?.length) await shell.openPaths(backend.startupFiles);
  if (!app.docs.length) app.emit('active', null);
  document.body.classList.add('ready');
}

start().catch(fatal);
