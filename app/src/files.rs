//! File commands: list, read, write (atomic, with a conflict check), stat,
//! create, rename, and the trash. Desktop builds may touch any file the user
//! picks; the notes folder gets its own trash (logs/mnemostxt/trash/), other files go to the
//! system trash.

use crate::util::{err, input, io_err, is_inside, mtime_ms, show, Res};
use base64::{engine::general_purpose::STANDARD as B64, Engine};
use serde::Serialize;
use std::fs;
use std::io::Write;
use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

/// Files larger than this are refused (the editor would not cope anyway).
pub const MAX_READ: u64 = 50 * 1024 * 1024;
/// The app's own folder inside the notes folder (settings.json lives here too).
pub const APP_DIR: [&str; 2] = ["logs", "mnemostxt"];
pub const LEDGER: &str = ".mnemostxt-trash.tsv";

#[derive(Serialize, Debug)]
pub struct Entry {
    pub name: String,
    pub dir: bool,
    pub size: u64,
    pub mtime: i64,
}

#[derive(Serialize, Debug)]
pub struct Stat {
    pub dir: bool,
    pub size: u64,
    pub mtime: i64,
}

#[derive(Serialize, Debug)]
pub struct ReadOut {
    pub data: String,
    pub mtime: i64,
    pub size: u64,
}

#[derive(Serialize, Debug)]
pub struct WriteOut {
    pub mtime: i64,
}

#[derive(Serialize, Debug)]
pub struct PurgeOut {
    pub removed: usize,
    pub bytes: u64,
}

pub fn list(path: &Path) -> Res<Vec<Entry>> {
    let rd = fs::read_dir(path).map_err(|e| io_err(e, path))?;
    let mut out = Vec::new();
    for ent in rd.flatten() {
        let name = ent.file_name().to_string_lossy().to_string();
        // follow symlinks for the kind; a broken link shows as a file
        let meta = fs::metadata(ent.path()).or_else(|_| ent.metadata());
        let Ok(meta) = meta else { continue };
        out.push(Entry { name, dir: meta.is_dir(), size: if meta.is_dir() { 0 } else { meta.len() }, mtime: mtime_ms(&meta) });
    }
    Ok(out)
}

pub fn stat(path: &Path) -> Res<Option<Stat>> {
    match fs::metadata(path) {
        Ok(m) => Ok(Some(Stat { dir: m.is_dir(), size: m.len(), mtime: mtime_ms(&m) })),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(None),
        Err(e) => Err(io_err(e, path)),
    }
}

pub fn read(path: &Path) -> Res<ReadOut> {
    let meta = fs::metadata(path).map_err(|e| io_err(e, path))?;
    if meta.is_dir() {
        return Err(err("INVALID", format!("{} is a folder", show(path))));
    }
    if meta.len() > MAX_READ {
        return Err(err("TOO_LARGE", format!("{} is larger than 50 MB", show(path))));
    }
    let bytes = fs::read(path).map_err(|e| io_err(e, path))?;
    Ok(ReadOut { data: B64.encode(&bytes), mtime: mtime_ms(&meta), size: bytes.len() as u64 })
}

/// Writes the whole file. With `expected` set, refuses (CONFLICT) when the file
/// on disk has another modification time — someone else changed it.
/// The write is atomic (temp file + rename next to the real file), follows
/// symlinks so a linked file stays a link, and keeps the file's permissions.
pub fn write(path: &Path, data: &str, expected: Option<i64>) -> Res<WriteOut> {
    let bytes = B64.decode(data).map_err(|e| err("INVALID", e))?;
    let target = fs::canonicalize(path).unwrap_or_else(|_| path.to_path_buf());
    let existing = fs::metadata(&target).ok();
    if let (Some(exp), Some(meta)) = (expected, existing.as_ref()) {
        if mtime_ms(meta) != exp {
            return Err(err("CONFLICT", format!("{} changed on disk", show(path))));
        }
    }
    if existing.as_ref().is_some_and(|m| m.is_dir()) {
        return Err(err("INVALID", format!("{} is a folder", show(path))));
    }
    let dir = target.parent().ok_or_else(|| err("INVALID", "no parent folder"))?;
    if !dir.exists() {
        return Err(err("NOT_FOUND", format!("folder {} does not exist", show(dir))));
    }
    let name = target.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
    let tmp = dir.join(format!(".~{}.{}.mnemostxt-tmp", name, std::process::id()));
    let atomic = (|| -> std::io::Result<()> {
        let mut f = fs::File::create(&tmp)?;
        f.write_all(&bytes)?;
        f.sync_all()?;
        drop(f);
        if let Some(m) = existing.as_ref() {
            let _ = fs::set_permissions(&tmp, m.permissions());
        }
        fs::rename(&tmp, &target)
    })();
    if atomic.is_err() {
        // e.g. a read-only folder with a writable file, or a file locked by
        // another program on Windows: fall back to writing in place
        let _ = fs::remove_file(&tmp);
        fs::write(&target, &bytes).map_err(|e| io_err(e, path))?;
    }
    let meta = fs::metadata(&target).map_err(|e| io_err(e, path))?;
    Ok(WriteOut { mtime: mtime_ms(&meta) })
}

pub fn create(path: &Path) -> Res<WriteOut> {
    fs::OpenOptions::new().write(true).create_new(true).open(path).map_err(|e| io_err(e, path))?;
    let meta = fs::metadata(path).map_err(|e| io_err(e, path))?;
    Ok(WriteOut { mtime: mtime_ms(&meta) })
}

pub fn rename(from: &Path, to: &Path) -> Res<()> {
    // case-only renames on case-insensitive systems are the same file
    let same = fs::canonicalize(from).ok() == fs::canonicalize(to).ok();
    if to.exists() && !same {
        return Err(err("EXISTS", format!("{} already exists", show(to))));
    }
    if let Some(p) = to.parent() {
        fs::create_dir_all(p).map_err(|e| io_err(e, p))?;
    }
    fs::rename(from, to).map_err(|e| io_err(e, from))
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

/// <notes>/logs/mnemostxt/trash, built from components so it is right on Windows too.
pub fn trash_dir(notes: &Path) -> PathBuf {
    notes.join(APP_DIR[0]).join(APP_DIR[1]).join("trash")
}

/// Moves `path` into <notes>/logs/mnemostxt/trash/ and records it in the ledger
/// (trash name, original path relative to the notes folder, unix time).
pub fn trash_into_notes(path: &Path, notes: &Path) -> Res<()> {
    let notes = dunce::canonicalize(notes).map_err(|e| io_err(e, notes))?;
    // canonical parent + own name: a symlink is trashed as a link, not its target
    let parent = path.parent().ok_or_else(|| err("INVALID", "no parent folder"))?;
    let fname = path.file_name().ok_or_else(|| err("INVALID", "no file name"))?;
    let src = dunce::canonicalize(parent).map_err(|e| io_err(e, parent))?.join(fname);
    fs::symlink_metadata(&src).map_err(|e| io_err(e, path))?;
    let trash = trash_dir(&notes);
    if src == notes || src.starts_with(&trash) {
        return Err(err("INVALID", "cannot trash the notes folder or the trash itself"));
    }
    fs::create_dir_all(&trash).map_err(|e| io_err(e, &trash))?;
    let name = src.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_else(|| "item".into());
    let mut dest = trash.join(&name);
    let mut n = 1;
    while dest.exists() {
        let (stem, ext) = match name.rfind('.') {
            Some(i) if i > 0 => (&name[..i], &name[i..]),
            _ => (name.as_str(), ""),
        };
        dest = trash.join(format!("{stem} ({n}){ext}"));
        n += 1;
    }
    fs::rename(&src, &dest).map_err(|e| io_err(e, path))?;
    let rel = src.strip_prefix(&notes).map(show).unwrap_or_else(|_| show(&src));
    let line = format!("{}\t{}\t{}\n", dest.file_name().unwrap().to_string_lossy(), rel, now_secs());
    let ledger = trash.join(LEDGER);
    let _ = fs::OpenOptions::new().create(true).append(true).open(&ledger).and_then(|mut f| f.write_all(line.as_bytes()));
    Ok(())
}

pub fn trash(path: &Path, notes: Option<&Path>) -> Res<()> {
    if let Some(n) = notes {
        if is_inside(path, n) {
            return trash_into_notes(path, n);
        }
    }
    system_trash(path)
}

#[cfg(not(any(target_os = "android", target_os = "ios")))]
fn system_trash(path: &Path) -> Res<()> {
    trash::delete(path).map_err(|e| err("IO", format!("could not move {} to the trash: {e}", show(path))))
}

#[cfg(any(target_os = "android", target_os = "ios"))]
fn system_trash(path: &Path) -> Res<()> {
    Err(err("DENIED", format!("{} is outside the notes folder", show(path))))
}

fn size_of(p: &Path) -> u64 {
    match fs::symlink_metadata(p) {
        Ok(m) if m.is_dir() => fs::read_dir(p).map(|rd| rd.flatten().map(|e| size_of(&e.path())).sum()).unwrap_or(0),
        Ok(m) => m.len(),
        Err(_) => 0,
    }
}

/// Permanently deletes trash items older than `days` (ledger time, else mtime).
/// The only place mnemostxt deletes anything.
pub fn purge(notes: &Path, days: u64) -> Res<PurgeOut> {
    let trash = trash_dir(notes);
    let ledger_path = trash.join(LEDGER);
    let ledger = fs::read_to_string(&ledger_path).unwrap_or_default();
    let mut when = std::collections::HashMap::new();
    for line in ledger.lines() {
        let cols: Vec<&str> = line.split('\t').collect();
        if cols.len() >= 3 {
            if let Ok(t) = cols[2].parse::<u64>() {
                when.insert(cols[0].to_string(), t);
            }
        }
    }
    let cutoff = now_secs().saturating_sub(days * 86_400);
    let mut out = PurgeOut { removed: 0, bytes: 0 };
    let Ok(rd) = fs::read_dir(&trash) else { return Ok(out) };
    for ent in rd.flatten() {
        let name = ent.file_name().to_string_lossy().to_string();
        if name.starts_with('.') {
            continue; // the ledger and other dotfiles stay
        }
        let t = when.get(&name).copied().unwrap_or_else(|| ent.metadata().map(|m| (mtime_ms(&m) / 1000) as u64).unwrap_or(u64::MAX));
        if t > cutoff {
            continue;
        }
        let p = ent.path();
        let bytes = size_of(&p);
        let gone = if fs::symlink_metadata(&p).map(|m| m.is_dir()).unwrap_or(false) { fs::remove_dir_all(&p) } else { fs::remove_file(&p) };
        if gone.is_ok() {
            out.removed += 1;
            out.bytes += bytes;
            when.remove(&name);
        }
    }
    let kept: String = ledger.lines().filter(|l| when.contains_key(l.split('\t').next().unwrap_or(""))).map(|l| format!("{l}\n")).collect();
    let _ = fs::write(&ledger_path, kept);
    Ok(out)
}

pub fn mkdir_all(path: &Path) -> Res<()> {
    fs::create_dir_all(path).map_err(|e| io_err(e, path))
}

/// Resolves a frontend path argument.
pub fn arg(p: &str, home: Option<&Path>) -> Res<PathBuf> {
    input(p, home)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn write_read_and_conflict() {
        let dir = tempfile::tempdir().unwrap();
        let f = dir.path().join("a.txt");
        let w = write(&f, &B64.encode(b"one"), None).unwrap();
        let r = read(&f).unwrap();
        assert_eq!(B64.decode(r.data).unwrap(), b"one");
        assert_eq!(r.mtime, w.mtime);
        assert!(write(&f, &B64.encode(b"two"), Some(w.mtime - 5000)).unwrap_err().starts_with("CONFLICT"));
        write(&f, &B64.encode(b"two"), Some(w.mtime)).unwrap();
        assert_eq!(fs::read(&f).unwrap(), b"two");
        // no temp files left behind
        assert_eq!(fs::read_dir(dir.path()).unwrap().count(), 1);
    }

    #[cfg(unix)]
    #[test]
    fn write_keeps_symlinks_and_permissions() {
        use std::os::unix::fs::PermissionsExt;
        let dir = tempfile::tempdir().unwrap();
        let real = dir.path().join("real.env");
        fs::write(&real, "x").unwrap();
        fs::set_permissions(&real, fs::Permissions::from_mode(0o600)).unwrap();
        let link = dir.path().join("link.env");
        std::os::unix::fs::symlink(&real, &link).unwrap();
        write(&link, &B64.encode(b"y"), None).unwrap();
        assert!(fs::symlink_metadata(&link).unwrap().file_type().is_symlink());
        assert_eq!(fs::read(&real).unwrap(), b"y");
        assert_eq!(fs::metadata(&real).unwrap().permissions().mode() & 0o777, 0o600);
    }

    #[test]
    fn create_rename_trash_purge() {
        let dir = tempfile::tempdir().unwrap();
        let notes = dir.path().join("notes");
        fs::create_dir_all(notes.join("sub")).unwrap();
        let a = notes.join("sub/a.md");
        create(&a).unwrap();
        assert!(create(&a).unwrap_err().starts_with("EXISTS"));
        let b = notes.join("b.md");
        rename(&a, &b).unwrap();
        fs::write(notes.join("c.md"), "").unwrap();
        assert!(rename(&b, &notes.join("c.md")).unwrap_err().starts_with("EXISTS"));
        trash(&b, Some(&notes)).unwrap();
        fs::write(notes.join("b.md"), "again").unwrap();
        trash(&notes.join("b.md"), Some(&notes)).unwrap();
        assert!(trash_dir(&notes).join("b.md").exists());
        assert!(trash_dir(&notes).join("b (1).md").exists());
        let ledger = fs::read_to_string(trash_dir(&notes).join(LEDGER)).unwrap();
        assert_eq!(ledger.lines().count(), 2);
        assert!(ledger.contains("\tb.md\t"));
        assert_eq!(purge(&notes, 30).unwrap().removed, 0); // too new
        assert_eq!(purge(&notes, 0).unwrap().removed, 2);
        assert!(fs::read_to_string(trash_dir(&notes).join(LEDGER)).unwrap().is_empty());
        assert!(trash(&notes, Some(&notes)).is_err());
        // nothing at the top of the notes folder but the user's own files
        let top: Vec<_> = fs::read_dir(&notes).unwrap().map(|e| e.unwrap().file_name()).collect();
        assert_eq!(top.len(), 3, "{top:?}"); // sub, c.md, logs
    }

    #[test]
    fn list_and_stat() {
        let dir = tempfile::tempdir().unwrap();
        fs::create_dir(dir.path().join("d")).unwrap();
        fs::write(dir.path().join("f"), "12345").unwrap();
        let mut l = list(dir.path()).unwrap();
        l.sort_by(|a, b| a.name.cmp(&b.name));
        assert!(l[0].dir && !l[1].dir && l[1].size == 5);
        assert!(stat(&dir.path().join("nope")).unwrap().is_none());
        assert!(read(dir.path()).unwrap_err().starts_with("INVALID"));
    }
}
