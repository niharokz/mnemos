//! mnemostxt — the Tauri side of the desktop (Linux, Windows) and Android apps.
//! The editor UI is frontend/; these commands give it the file system.
//! Every command is async, so it runs off the main (UI) thread.

mod files;
mod search;
mod session;
mod util;

use serde::Serialize;
use std::path::PathBuf;
use std::sync::Mutex;
use tauri::{AppHandle, Manager, State};
use util::{err, show, Res};

#[cfg(not(any(target_os = "android", target_os = "ios")))]
use tauri::Emitter;

struct AppState {
    notes: Mutex<Option<PathBuf>>,
    startup: Vec<String>,
}

#[derive(Serialize)]
struct Info {
    platform: &'static str,
    version: String,
    home: Option<String>,
    notes_dir: Option<String>,
    default_notes_dir: String,
    args: Vec<String>,
}

fn home(app: &AppHandle) -> Option<PathBuf> {
    app.path().home_dir().ok()
}
fn data_dir(app: &AppHandle) -> Res<PathBuf> {
    app.path().app_local_data_dir().map_err(|e| err("IO", e))
}
fn config_dir(app: &AppHandle) -> Res<PathBuf> {
    app.path().app_config_dir().map_err(|e| err("IO", e))
}
fn p(app: &AppHandle, s: &str) -> Res<PathBuf> {
    files::arg(s, home(app).as_deref())
}

/// Files and folders named on the command line ("mnemostxt notes.md ~/notes").
fn cli_paths(args: &[String], cwd: &std::path::Path) -> Vec<String> {
    args.iter()
        .skip(1)
        .filter(|a| !a.starts_with('-'))
        .map(|a| {
            let pb = PathBuf::from(a);
            let abs = if pb.is_absolute() { pb } else { cwd.join(pb) };
            show(&util::canonical_or_self(&abs))
        })
        .collect()
}

// ── commands ────────────────────────────────────────────────────────────────

#[tauri::command]
async fn app_info(app: AppHandle, state: State<'_, AppState>) -> Res<Info> {
    let h = home(&app);
    let default = if cfg!(target_os = "android") {
        "/storage/emulated/0/notes".to_string()
    } else {
        h.as_ref().map(|h| show(&h.join("notes"))).unwrap_or_else(|| "notes".into())
    };
    Ok(Info {
        platform: std::env::consts::OS,
        version: app.package_info().version.to_string(),
        home: h.as_deref().map(show),
        notes_dir: state.notes.lock().unwrap().as_deref().map(show),
        default_notes_dir: default,
        args: state.startup.clone(),
    })
}

#[tauri::command]
async fn set_notes_dir(app: AppHandle, state: State<'_, AppState>, path: String) -> Res<String> {
    let dir = p(&app, &path)?;
    std::fs::create_dir_all(&dir).map_err(|e| util::io_err(e, &dir))?;
    let dir = dunce::canonicalize(&dir).map_err(|e| util::io_err(e, &dir))?;
    std::fs::read_dir(&dir).map_err(|e| util::io_err(e, &dir))?; // readable?
    session::save_bootstrap(&config_dir(&app)?, &session::Bootstrap { notes_dir: Some(show(&dir)) })?;
    *state.notes.lock().unwrap() = Some(dir.clone());
    Ok(show(&dir))
}

#[tauri::command]
async fn list_dir(app: AppHandle, path: String) -> Res<Vec<files::Entry>> {
    files::list(&p(&app, &path)?)
}

#[tauri::command]
async fn read_file(app: AppHandle, path: String) -> Res<files::ReadOut> {
    files::read(&p(&app, &path)?)
}

#[tauri::command]
async fn write_file(app: AppHandle, path: String, data: String, expected_mtime: Option<i64>) -> Res<files::WriteOut> {
    files::write(&p(&app, &path)?, &data, expected_mtime)
}

#[tauri::command]
async fn stat_path(app: AppHandle, path: String) -> Res<Option<files::Stat>> {
    files::stat(&p(&app, &path)?)
}

#[tauri::command]
async fn mkdir_all(app: AppHandle, path: String) -> Res<()> {
    files::mkdir_all(&p(&app, &path)?)
}

#[tauri::command]
async fn create_file(app: AppHandle, path: String) -> Res<files::WriteOut> {
    files::create(&p(&app, &path)?)
}

#[tauri::command]
async fn rename_path(app: AppHandle, from: String, to: String) -> Res<()> {
    files::rename(&p(&app, &from)?, &p(&app, &to)?)
}

#[tauri::command]
async fn trash_path(app: AppHandle, path: String, notes_root: Option<String>) -> Res<()> {
    let notes = match notes_root {
        Some(n) => Some(p(&app, &n)?),
        None => None,
    };
    files::trash(&p(&app, &path)?, notes.as_deref())
}

#[tauri::command]
async fn trash_purge(app: AppHandle, notes_root: String, days: u64) -> Res<files::PurgeOut> {
    files::purge(&p(&app, &notes_root)?, days)
}

#[tauri::command]
async fn search(
    app: AppHandle,
    roots: Vec<String>,
    query: String,
    regex: bool,
    case_sensitive: bool,
    hidden: Vec<String>,
    max: usize,
) -> Res<Vec<search::Hit>> {
    let roots = roots.iter().map(|r| p(&app, r)).collect::<Res<Vec<_>>>()?;
    search::search(&roots, &query, regex, case_sensitive, &hidden, max.clamp(1, 10_000))
}

#[tauri::command]
async fn session_load(app: AppHandle) -> Res<Option<String>> {
    session::session_load(&data_dir(&app)?)
}

#[tauri::command]
async fn session_save(app: AppHandle, json: String) -> Res<()> {
    session::session_save(&data_dir(&app)?, &json)
}

#[tauri::command]
async fn backup_write(app: AppHandle, id: String, text: String) -> Res<()> {
    session::backup_write(&data_dir(&app)?, &id, &text)
}

#[tauri::command]
async fn backup_read(app: AppHandle, id: String) -> Res<Option<String>> {
    session::backup_read(&data_dir(&app)?, &id)
}

#[tauri::command]
async fn backup_delete(app: AppHandle, id: String) -> Res<()> {
    session::backup_delete(&data_dir(&app)?, &id)
}

#[tauri::command]
async fn backup_list(app: AppHandle) -> Res<Vec<String>> {
    Ok(session::backup_list(&data_dir(&app)?))
}

// ── app ─────────────────────────────────────────────────────────────────────

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let args: Vec<String> = std::env::args().collect();
    let cwd = std::env::current_dir().unwrap_or_default();
    let startup = if cfg!(any(target_os = "android", target_os = "ios")) { vec![] } else { cli_paths(&args, &cwd) };

    let mut builder = tauri::Builder::default();

    #[cfg(not(any(target_os = "android", target_os = "ios")))]
    {
        // a second launch ("open with mnemostxt") hands its files to the running window
        builder = builder
            .plugin(tauri_plugin_single_instance::init(|app, argv, cwd| {
                let paths = cli_paths(&argv, std::path::Path::new(&cwd));
                if let Some(w) = app.get_webview_window("main") {
                    let _ = w.unminimize();
                    let _ = w.set_focus();
                }
                if !paths.is_empty() {
                    let _ = app.emit_to("main", "open-files", paths);
                }
            }))
            .plugin(tauri_plugin_window_state::Builder::default().build());
    }

    builder
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_opener::init())
        .setup(move |app| {
            let notes =
                config_dir(app.handle()).ok().and_then(|c| session::load_bootstrap(&c).notes_dir).map(PathBuf::from).filter(|p| p.is_dir());
            app.manage(AppState { notes: Mutex::new(notes), startup: startup.clone() });
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            app_info,
            set_notes_dir,
            list_dir,
            read_file,
            write_file,
            stat_path,
            mkdir_all,
            create_file,
            rename_path,
            trash_path,
            trash_purge,
            search,
            session_load,
            session_save,
            backup_write,
            backup_read,
            backup_delete,
            backup_list
        ])
        .run(tauri::generate_context!())
        .expect("error while running mnemostxt");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cli_paths_resolve_relative_and_skip_flags() {
        let d = tempfile::tempdir().unwrap();
        std::fs::write(d.path().join("a.md"), "").unwrap();
        let got = cli_paths(&["mnemostxt".into(), "--flag".into(), "a.md".into()], d.path());
        assert_eq!(got, vec![show(&dunce::canonicalize(d.path().join("a.md")).unwrap())]);
    }
}
