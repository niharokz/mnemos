//! Small helpers shared by the commands: error codes, path normalising and
//! modification times. Errors cross to JavaScript as "CODE: message"
//! (frontend/src/backend/errors.js turns them back into codes).

use std::io;
use std::path::{Path, PathBuf};
use std::time::UNIX_EPOCH;

pub type Res<T> = Result<T, String>;

pub fn err(code: &str, msg: impl std::fmt::Display) -> String {
    format!("{code}: {msg}")
}

/// Maps an io::Error to a coded message, naming the path it concerns.
pub fn io_err(e: io::Error, path: &Path) -> String {
    let code = match e.kind() {
        io::ErrorKind::NotFound => "NOT_FOUND",
        io::ErrorKind::PermissionDenied => "DENIED",
        io::ErrorKind::AlreadyExists => "EXISTS",
        io::ErrorKind::InvalidInput | io::ErrorKind::InvalidData => "INVALID",
        _ => "IO",
    };
    err(code, format!("{} ({})", e, show(path)))
}

/// Forward slashes everywhere: the frontend speaks one path style on every OS
/// (Windows accepts "D:/notes/a.md" as well as "D:\notes\a.md").
pub fn show(p: &Path) -> String {
    let s = p.to_string_lossy().replace('\\', "/");
    s.strip_prefix("//?/").map(str::to_string).unwrap_or(s)
}

/// A path from the frontend: "~" expands to home, separators are tidied.
pub fn input(p: &str, home: Option<&Path>) -> Res<PathBuf> {
    let p = p.trim();
    if p.is_empty() {
        return Err(err("INVALID", "empty path"));
    }
    if p.contains('\0') {
        return Err(err("INVALID", "path contains NUL"));
    }
    if let (Some(rest), Some(h)) = (p.strip_prefix('~'), home) {
        if rest.is_empty() || rest.starts_with('/') || rest.starts_with('\\') {
            return Ok(h.join(rest.trim_start_matches(['/', '\\'])));
        }
    }
    Ok(PathBuf::from(p))
}

/// Modification time in milliseconds since 1970 (the frontend's conflict check).
pub fn mtime_ms(meta: &std::fs::Metadata) -> i64 {
    meta.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_millis() as i64).unwrap_or(0)
}

/// True when `child` is `root` or inside it (both made absolute and canonical
/// where they exist; case-insensitive on Windows).
pub fn is_inside(child: &Path, root: &Path) -> bool {
    let c = canonical_or_self(child);
    let r = canonical_or_self(root);
    if cfg!(windows) {
        let c = show(&c).to_lowercase();
        let r = show(&r).to_lowercase().trim_end_matches('/').to_string();
        c == r || c.starts_with(&(r + "/"))
    } else {
        c.starts_with(&r)
    }
}

/// Canonical form of the longest existing prefix, plus the rest as given.
pub fn canonical_or_self(p: &Path) -> PathBuf {
    if let Ok(c) = dunce::canonicalize(p) {
        return c;
    }
    match (p.parent(), p.file_name()) {
        (Some(parent), Some(name)) if parent != p => canonical_or_self(parent).join(name),
        _ => p.to_path_buf(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn expands_home_and_normalises() {
        let home = Path::new("/home/me");
        assert_eq!(input("~/notes", Some(home)).unwrap(), PathBuf::from("/home/me/notes"));
        assert_eq!(input("~", Some(home)).unwrap(), PathBuf::from("/home/me/"));
        assert_eq!(input("~x", Some(home)).unwrap(), PathBuf::from("~x"));
        assert!(input("  ", Some(home)).is_err());
        assert_eq!(show(Path::new("a\\b/c")), "a/b/c");
        assert_eq!(show(Path::new("//?/D:/x")), "D:/x");
    }

    #[test]
    fn inside_checks_real_paths() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().join("notes");
        std::fs::create_dir_all(root.join("sub")).unwrap();
        assert!(is_inside(&root.join("sub/new.md"), &root));
        assert!(is_inside(&root, &root));
        assert!(!is_inside(&dir.path().join("notes2/a"), &root));
        assert!(!is_inside(&root.join("../escape.md"), &root));
    }
}
