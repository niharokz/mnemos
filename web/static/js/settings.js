// settings.js — the Settings sheet: what the vault holds (notes, size, trash) and
// Cleanup, the built-in cleanupSync.sh: move Syncthing conflict copies into the
// trash, then delete trash items older than N days. Nothing runs on its own; the
// preview lists exactly what will happen before you press the button.

import { api } from "./api.js";
import { h, size, openSheet, toast, confirmSheet } from "./ui.js";

const n = (x) => Number(x || 0).toLocaleString();

/** "1,284 notes · 312.4 MB" for the sidebar. */
export async function statsLine() {
  try {
    const s = await api.stats();
    return `${n(s.notes)} notes · ${size(s.bytes)}`;
  } catch { return ""; }
}

export async function settingsSheet(ctx) {
  const stats = h("dl.kv", {}, h("dt", {}, "Loading…"));
  const days = h("input", { type: "number", min: "1", max: "3650", value: "30", inputmode: "numeric", disabled: ctx.readOnly });
  const preview = h("div.cl-prev", {});
  const runBtn = h("button.danger.primary", { disabled: true }, "Clean up now");
  const body = [
    h("h3", {}, "This vault"), stats,
    h("h3", {}, "Cleanup"),
    h("p.muted", {}, "Moves Syncthing conflict copies (*sync-conflict*) into .trash, then deletes trash items older than the days below — for good. Hidden folders are left alone."),
    h("label.f", {}, h("span", {}, "Keep trash for (days)"), days),
    h("div.cl-acts", {}, h("button", { onclick: () => check() }, "Preview"), runBtn),
    preview,
    ctx.readOnly ? h("p.muted", {}, "The demo is read-only: you can preview, not clean up.") : null,
  ];

  const showStats = async () => {
    try {
      const s = await api.stats();
      stats.replaceChildren(
        h("dt", {}, "Notes"), h("dd", {}, n(s.notes)),
        h("dt", {}, "Files"), h("dd", {}, `${n(s.files)} in ${n(s.folders)} folders`),
        h("dt", {}, "Size"), h("dd", {}, size(s.bytes)),
        h("dt", {}, "Trash"), h("dd", {}, `${n(s.trash_items)} items · ${size(s.trash_bytes)}`),
        h("dt", {}, "Conflicts"), h("dd", {}, n(s.conflicts)));
    } catch (e) { stats.replaceChildren(h("dt", {}, e.message)); }
  };
  const check = async () => {
    const d = parseInt(days.value, 10);
    if (!(d >= 1 && d <= 3650)) { toast("Days must be 1–3650", true); return; }
    preview.replaceChildren(h("p.loading", {}, "Checking…"));
    try {
      const r = await api.cleanupPreview(d);
      preview.replaceChildren(
        h("p", {}, `${r.moved.length} conflict copies to move · ${r.deleted.length} trash items to delete (${size(r.freed_size)})`),
        r.moved.length || r.deleted.length ? h("ul.cl-list.mono", {},
          r.moved.map((p) => h("li", {}, "→ .trash  " + p)),
          r.deleted.map((p) => h("li.bad", {}, "✕  " + p))) : null);
      runBtn.disabled = ctx.readOnly || (!r.moved.length && !r.deleted.length);
    } catch (e) { preview.replaceChildren(h("p.err", {}, e.message)); }
  };
  runBtn.onclick = async () => {
    const d = parseInt(days.value, 10);
    if (!(await confirmSheet("Clean up?", `Conflict copies go to the trash and trash items older than ${d} days are deleted for good.`, "Clean up", true))) { settingsSheet(ctx); return; }
    try {
      await api.settingsSave({ cleanup_days: d });
      const r = await api.cleanup(d);
      toast(`Moved ${r.moved.length} · deleted ${r.deleted.length} (${size(r.freed_size)})`);
      ctx.loadTree();
      ctx.refreshStats();
    } catch (e) { toast(e.message, true); }
  };
  days.addEventListener("change", async () => {
    const d = parseInt(days.value, 10);
    if (!ctx.readOnly && d >= 1 && d <= 3650) { try { await api.settingsSave({ cleanup_days: d }); } catch (e) { toast(e.message, true); } }
    runBtn.disabled = true;
    preview.replaceChildren();
  });

  openSheet("Settings", body, [], { wide: true });
  try { days.value = (await api.settings()).cleanup_days; } catch { /* default */ }
  showStats();
}
