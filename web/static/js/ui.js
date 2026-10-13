// ui.js — small UI helpers: element builder, per-device settings, toast, and the
// sheet used for every prompt, confirm and menu (no window.prompt/confirm).

/** h("button.primary", {onclick}, "Save") → element. Tag may carry .classes and #id. */
export function h(spec, attrs = {}, ...kids) {
  const [, tag = "div", rest = ""] = spec.match(/^([a-z0-9-]*)(.*)$/i);
  const el = document.createElement(tag || "div");
  for (const m of rest.matchAll(/([.#])([\w-]+)/g)) {
    if (m[1] === ".") el.classList.add(m[2]); else el.id = m[2];
  }
  for (const [k, v] of Object.entries(attrs || {})) {
    if (v == null || v === false) continue;
    if (k.startsWith("on")) el.addEventListener(k.slice(2), v);
    else if (k === "html") el.innerHTML = v;
    else if (k === "value") el.value = v; // a textarea has no value attribute
    else if (k in el && typeof v !== "string") el[k] = v;
    else el.setAttribute(k, v === true ? "" : v);
  }
  for (const k of kids.flat()) if (k != null && k !== false) el.append(k);
  return el;
}

/** An icon from the sprite in index.html. */
export function icon(name) {
  const ns = "http://www.w3.org/2000/svg";
  const svg = document.createElementNS(ns, "svg");
  svg.setAttribute("class", "i");
  svg.setAttribute("aria-hidden", "true");
  const use = document.createElementNS(ns, "use");
  use.setAttribute("href", "#i-" + name);
  svg.append(use);
  return svg;
}

/** Per-device settings in localStorage; quietly does nothing where storage is blocked. */
export const prefs = {
  get(key, def) {
    try {
      const v = localStorage.getItem("mnemos." + key);
      return v == null ? def : JSON.parse(v);
    } catch { return def; }
  },
  set(key, value) {
    try { localStorage.setItem("mnemos." + key, JSON.stringify(value)); } catch { /* private mode */ }
  },
};

let toastTimer;
/** Brief message at the bottom of the screen. */
export function toast(msg, err = false, ms = 3200) {
  const el = document.getElementById("toast");
  el.textContent = msg;
  el.classList.toggle("err", err);
  el.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.hidden = true; }, err ? Math.max(ms, 5000) : ms);
}

const sheet = () => document.getElementById("sheet");

/** Opens the sheet with a title, body nodes and footer buttons; resolves with the
    value passed to close(), or null when dismissed (Esc / backdrop). opts.wide makes
    a bigger panel (search, trash …); the sheet's close function is sheetClose(). */
export function openSheet(title, body, buttons = [], opts = {}) {
  const d = sheet();
  d.classList.toggle("wide", !!opts.wide);
  return new Promise((resolve) => {
    let done = false;
    const close = (v) => {
      if (done) return;
      done = true;
      d.close();
      resolve(v);
    };
    d.replaceChildren(...[
      h("div.sheet-h", {}, h("h2", {}, title), h("button.ghost.icon", { "aria-label": "Close", onclick: () => close(null) }, icon("x"))),
      h("div.sheet-b", {}, body),
      buttons.length ? h("div.sheet-f", {}, buttons.map((b) => h("button" + (b.cls || ""), { type: b.submit ? "submit" : "button", disabled: !!b.disabled, onclick: b.onclick || (b.submit ? null : () => close(b.value)) }, b.label))) : null,
    ].filter(Boolean));
    // a "close" event from the previous sheet can arrive after this one opened: ignore it while open
    d.onclose = () => { if (!d.open) close(null); };
    d.onclick = (e) => { if (e.target === d) close(null); };
    d.showModal();
    d._close = close;
    if (opts.onOpen) opts.onOpen(close);
  });
}

/** Asks for a line of text. Resolves with the trimmed text, or null. */
export function promptText(title, { label = "", value = "", placeholder = "", ok = "OK", hint = "", select } = {}) {
  const input = h("input", { value, placeholder, autocapitalize: "off", spellcheck: "false", autocomplete: "off" });
  const form = h("form", {}, label ? h("label.f", {}, h("span", {}, label), input) : input, hint ? h("p", {}, hint) : null);
  const p = openSheet(title, form, [{ label: "Cancel", value: null, cls: ".ghost" }, { label: ok, cls: ".primary", submit: true }]);
  const d = sheet();
  const submitBtn = d.querySelector('button[type="submit"]');
  submitBtn.addEventListener("click", (e) => { e.preventDefault(); form.requestSubmit(); });
  form.addEventListener("submit", (e) => {
    e.preventDefault();
    const v = input.value.trim();
    if (v) d._close(v);
  });
  requestAnimationFrame(() => {
    input.focus();
    if (select) input.setSelectionRange(select[0], select[1]);
  });
  return p;
}

/** Yes/no question. Resolves true or false. */
export async function confirmSheet(title, text, ok = "OK", danger = false) {
  const v = await openSheet(title, h("p", {}, text), [
    { label: "Cancel", value: false, cls: ".ghost" },
    { label: ok, value: true, cls: danger ? ".danger.primary" : ".primary" },
  ]);
  return v === true;
}

/** A list of actions. items: [{label, icon, value, danger}] → resolves value or null. */
export function menuSheet(title, items) {
  let closeFn;
  const list = h("ul.menu", {}, items.map((it) => h("li", {}, h("button" + (it.danger ? ".danger" : ""), { onclick: () => closeFn(it.value) }, icon(it.icon), it.label))));
  const p = openSheet(title, list, []);
  closeFn = sheet()._close;
  return p;
}

/** Closes the open sheet with a value. */
export function sheetClose(v = null) { const d = sheet(); if (d.open && d._close) d._close(v); }

/** "5m", "3h", "2d" or a date, for lists. */
export function ago(ms) {
  const s = (Date.now() - ms) / 1000;
  if (s < 60) return "now";
  if (s < 3600) return Math.round(s / 60) + "m";
  if (s < 86400) return Math.round(s / 3600) + "h";
  if (s < 86400 * 30) return Math.round(s / 86400) + "d";
  return new Date(ms).toISOString().slice(0, 10);
}

/** Human file size. */
export function size(n) {
  if (n < 1024) return n + " B";
  if (n < 1048576) return Math.round(n / 1024) + " KB";
  return (n / 1048576).toFixed(1) + " MB";
}

/** Calls fn after the input pauses for ms. */
export function debounce(fn, ms) {
  let t;
  return (...a) => { clearTimeout(t); t = setTimeout(() => fn(...a), ms); };
}
