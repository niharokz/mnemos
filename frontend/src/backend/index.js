// Picks the backend: Tauri (desktop and Android), the web server, or the
// in-memory demo (`npm run dev`, or any build opened with ?demo).
export const isTauri = () => typeof window !== 'undefined' && !!window.__TAURI_INTERNALS__;

export async function createBackend() {
  if (isTauri()) return (await import('./tauri.js')).createTauriBackend();
  if (new URLSearchParams(location.search).has('demo') || location.protocol === 'file:') {
    return (await import('./memory.js')).createMemoryBackend();
  }
  return (await import('./web.js')).createWebBackend();
}
