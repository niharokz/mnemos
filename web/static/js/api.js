// api.js — the only place that talks to the server. Every call sends the
// X-Mnemos header (the server refuses changes without it) and turns error
// responses into an Error carrying .status and .data.

let onSignedOut = () => {};

/** Called whenever the server answers 401 (session expired or signed out). */
export function setSignedOutHandler(fn) { onSignedOut = fn; }

async function request(method, url, body, opts = {}) {
  const init = { method, headers: { "X-Mnemos": "1" }, credentials: "same-origin", signal: opts.signal, keepalive: !!opts.keepalive };
  if (opts.raw) {
    init.headers["Content-Type"] = opts.raw.type || "application/octet-stream";
    init.body = opts.raw;
  } else if (body !== undefined) {
    init.headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(url, init);
  } catch (e) {
    if (e.name === "AbortError") throw e;
    const err = new Error("Can't reach mnemos — check your connection.");
    err.status = 0;
    throw err;
  }
  let data = null;
  try { data = await res.json(); } catch { /* empty or not JSON */ }
  if (!res.ok) {
    if (res.status === 401 && !url.endsWith("/api/login")) onSignedOut();
    const err = new Error((data && data.error) || `${res.status} ${res.statusText}`);
    err.status = res.status;
    err.data = data || {};
    throw err;
  }
  return data;
}

const q = (p) => "?path=" + encodeURIComponent(p);

/** Sends a file as the raw request body (images: no JSON, no base64). */
function upload(url, file) {
  return request("POST", url, undefined, { raw: file });
}

export const api = {
  me: () => request("GET", "/api/me"),
  login: (user, password) => request("POST", "/api/login", { user, password }),
  logout: () => request("POST", "/api/logout"),
  config: () => request("GET", "/api/config"),
  tree: () => request("GET", "/api/tree"),
  read: (path) => request("GET", "/api/file" + q(path)),
  version: (path) => request("GET", "/api/version" + q(path)),
  write: (path, content, version, force = false, keepalive = false) =>
    request("PUT", "/api/file" + q(path), { content, version, force }, { keepalive: keepalive && content.length < 60000 }),
  create: (path, folder = false, template = "") => request("POST", "/api/new", { path, folder, template }),
  move: (from, to) => request("POST", "/api/move", { from, to }),
  trash: (path) => request("POST", "/api/trash", { path }),
  folder: (path) => request("GET", "/api/folder" + q(path)),
  search: (text, signal) => request("GET", "/api/search?q=" + encodeURIComponent(text), undefined, { signal }),
  templates: () => request("GET", "/api/templates"),
  trashList: () => request("GET", "/api/trash"),
  restore: (name, to) => request("POST", "/api/restore", { name, to }),
  capture: (text) => request("POST", "/api/capture", { text }),
  conflicts: () => request("GET", "/api/conflicts"),
  resolve: (copy, keep, content = "") => request("POST", "/api/conflicts/resolve", { copy, keep, content }),
  attach: (note, file) => upload("/api/attach?note=" + encodeURIComponent(note) + "&name=" + encodeURIComponent(file.name || "image"), file),
  render: (path, content, signal) => request("POST", "/api/render", { path, content }, { signal }),
};

/** URL of a vault file served as-is (download = save instead of show). */
export function rawURL(path, download = false) {
  return "/raw/" + path.split("/").map(encodeURIComponent).join("/") + (download ? "?download=1" : "");
}
