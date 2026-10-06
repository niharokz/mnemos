// api.js — the only place that talks to the server. Every call sends the
// X-Samanote header (the server refuses changes without it) and turns error
// responses into an Error carrying .status and .data.

let onSignedOut = () => {};

/** Called whenever the server answers 401 (session expired or signed out). */
export function setSignedOutHandler(fn) { onSignedOut = fn; }

async function request(method, url, body, opts = {}) {
  const init = { method, headers: { "X-Samanote": "1" }, credentials: "same-origin", signal: opts.signal, keepalive: !!opts.keepalive };
  if (body !== undefined) {
    init.headers["Content-Type"] = "application/json";
    init.body = JSON.stringify(body);
  }
  let res;
  try {
    res = await fetch(url, init);
  } catch (e) {
    if (e.name === "AbortError") throw e;
    const err = new Error("Can't reach samanote — check your connection.");
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
  create: (path, folder = false) => request("POST", "/api/new", { path, folder }),
  move: (from, to) => request("POST", "/api/move", { from, to }),
  trash: (path) => request("POST", "/api/trash", { path }),
  render: (path, content, signal) => request("POST", "/api/render", { path, content }, { signal }),
};

/** URL of a vault file served as-is. */
export function rawURL(path) {
  return "/raw/" + path.split("/").map(encodeURIComponent).join("/");
}
