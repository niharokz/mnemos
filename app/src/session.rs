//! Hot exit, like VS Code: the open tabs (session.json) and a backup of every
//! unsaved edit (backups/<id>.txt) live in the app's own data folder, so
//! closing the window never loses work and the next start restores it.
//! Also the bootstrap file holding the notes folder path.

use crate::util::{err, io_err, Res};
use serde::{Deserialize, Serialize};
use std::fs;
use std::path::{Path, PathBuf};

#[derive(Serialize, Deserialize, Default)]
pub struct Bootstrap {
    pub notes_dir: Option<String>,
}

pub fn load_bootstrap(config_dir: &Path) -> Bootstrap {
    fs::read_to_string(config_dir.join("bootstrap.json")).ok().and_then(|s| serde_json::from_str(&s).ok()).unwrap_or_default()
}

pub fn save_bootstrap(config_dir: &Path, b: &Bootstrap) -> Res<()> {
    fs::create_dir_all(config_dir).map_err(|e| io_err(e, config_dir))?;
    let p = config_dir.join("bootstrap.json");
    fs::write(&p, serde_json::to_string_pretty(b).unwrap()).map_err(|e| io_err(e, &p))
}

/// Backup ids come from the frontend; only [a-z0-9] is accepted so an id can
/// never name a path.
fn backup_path(data_dir: &Path, id: &str) -> Res<PathBuf> {
    if id.is_empty() || id.len() > 64 || !id.chars().all(|c| c.is_ascii_alphanumeric()) {
        return Err(err("INVALID", "bad backup id"));
    }
    Ok(data_dir.join("backups").join(format!("{id}.txt")))
}

pub fn session_load(data_dir: &Path) -> Res<Option<String>> {
    match fs::read_to_string(data_dir.join("session.json")) {
        Ok(s) => Ok(Some(s)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(io_err(e, data_dir)),
    }
}

pub fn session_save(data_dir: &Path, json: &str) -> Res<()> {
    serde_json::from_str::<serde_json::Value>(json).map_err(|e| err("INVALID", e))?;
    fs::create_dir_all(data_dir).map_err(|e| io_err(e, data_dir))?;
    let tmp = data_dir.join("session.json.tmp");
    fs::write(&tmp, json).map_err(|e| io_err(e, &tmp))?;
    fs::rename(&tmp, data_dir.join("session.json")).map_err(|e| io_err(e, data_dir))
}

pub fn backup_write(data_dir: &Path, id: &str, text: &str) -> Res<()> {
    let p = backup_path(data_dir, id)?;
    fs::create_dir_all(p.parent().unwrap()).map_err(|e| io_err(e, data_dir))?;
    let tmp = p.with_extension("tmp");
    fs::write(&tmp, text).map_err(|e| io_err(e, &tmp))?;
    fs::rename(&tmp, &p).map_err(|e| io_err(e, &p))
}

pub fn backup_read(data_dir: &Path, id: &str) -> Res<Option<String>> {
    match fs::read_to_string(backup_path(data_dir, id)?) {
        Ok(s) => Ok(Some(s)),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(io_err(e, data_dir)),
    }
}

pub fn backup_delete(data_dir: &Path, id: &str) -> Res<()> {
    match fs::remove_file(backup_path(data_dir, id)?) {
        Err(e) if e.kind() != std::io::ErrorKind::NotFound => Err(io_err(e, data_dir)),
        _ => Ok(()),
    }
}

pub fn backup_list(data_dir: &Path) -> Vec<String> {
    let Ok(rd) = fs::read_dir(data_dir.join("backups")) else { return vec![] };
    rd.flatten().filter_map(|e| e.file_name().to_string_lossy().strip_suffix(".txt").map(str::to_string)).collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn session_and_backups() {
        let d = tempfile::tempdir().unwrap();
        assert!(session_load(d.path()).unwrap().is_none());
        session_save(d.path(), r#"{"v":1}"#).unwrap();
        assert_eq!(session_load(d.path()).unwrap().unwrap(), r#"{"v":1}"#);
        assert!(session_save(d.path(), "not json").is_err());
        backup_write(d.path(), "abc123", "unsaved").unwrap();
        assert_eq!(backup_list(d.path()), vec!["abc123"]);
        assert_eq!(backup_read(d.path(), "abc123").unwrap().unwrap(), "unsaved");
        backup_delete(d.path(), "abc123").unwrap();
        backup_delete(d.path(), "abc123").unwrap();
        assert!(backup_read(d.path(), "abc123").unwrap().is_none());
        assert!(backup_write(d.path(), "../x", "").is_err());
        let b = Bootstrap { notes_dir: Some("/n".into()) };
        save_bootstrap(d.path(), &b).unwrap();
        assert_eq!(load_bootstrap(d.path()).notes_dir.as_deref(), Some("/n"));
    }
}
