//! Find in files: walks the sidebar folders and returns matching lines.
//! Skips hidden names (settings → hidden), symlinked folders (no loops),
//! binary files and files over 4 MB.

use crate::util::{err, show, Res};
use regex::RegexBuilder;
use serde::Serialize;
use std::collections::HashSet;
use std::fs;
use std::io::Read;
use std::path::PathBuf;

const MAX_FILE: u64 = 4 * 1024 * 1024;
const MAX_TEXT: usize = 300;

#[derive(Serialize, Debug)]
pub struct Hit {
    pub path: String,
    pub line: usize,
    pub col: usize,
    pub text: String,
}

pub fn search(roots: &[PathBuf], query: &str, regex: bool, case_sensitive: bool, hidden: &[String], max: usize) -> Res<Vec<Hit>> {
    if query.is_empty() {
        return Ok(vec![]);
    }
    let pattern = if regex { query.to_string() } else { regex::escape(query) };
    let re = RegexBuilder::new(&pattern)
        .case_insensitive(!case_sensitive)
        .size_limit(1 << 20)
        .build()
        .map_err(|e| err("INVALID", format!("bad pattern: {e}")))?;
    let hidden: HashSet<&str> = hidden.iter().map(String::as_str).collect();
    let mut out = Vec::new();
    let mut seen = HashSet::new();
    let mut stack: Vec<PathBuf> = roots.iter().rev().cloned().collect();
    while let Some(dir) = stack.pop() {
        if out.len() >= max {
            break;
        }
        let Ok(canon) = dunce::canonicalize(&dir) else { continue };
        if !seen.insert(canon) {
            continue; // a folder that is also inside another root
        }
        let Ok(rd) = fs::read_dir(&dir) else { continue };
        let mut entries: Vec<_> = rd.flatten().collect();
        entries.sort_by_key(|e| e.file_name());
        let mut subdirs = Vec::new();
        for ent in entries {
            let name = ent.file_name().to_string_lossy().to_string();
            if hidden.contains(name.as_str()) {
                continue;
            }
            let Ok(ft) = ent.file_type() else { continue };
            let path = ent.path();
            if ft.is_dir() {
                subdirs.push(path);
                continue;
            }
            if ft.is_symlink() && fs::metadata(&path).map(|m| m.is_dir()).unwrap_or(true) {
                continue;
            }
            scan_file(&path, &re, max, &mut out);
            if out.len() >= max {
                break;
            }
        }
        stack.extend(subdirs.into_iter().rev());
    }
    Ok(out)
}

fn scan_file(path: &PathBuf, re: &regex::Regex, max: usize, out: &mut Vec<Hit>) {
    let Ok(meta) = fs::metadata(path) else { return };
    if meta.len() > MAX_FILE {
        return;
    }
    let Ok(mut f) = fs::File::open(path) else { return };
    let mut bytes = Vec::with_capacity(meta.len() as usize);
    if f.read_to_end(&mut bytes).is_err() || bytes[..bytes.len().min(8192)].contains(&0) {
        return;
    }
    let text = String::from_utf8_lossy(&bytes);
    let shown = show(path);
    for (i, line) in text.lines().enumerate() {
        if let Some(m) = re.find(line) {
            let col = line[..m.start()].chars().count() + 1;
            let t: String = line.chars().take(MAX_TEXT).collect();
            out.push(Hit { path: shown.clone(), line: i + 1, col, text: t });
            if out.len() >= max {
                return;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn finds_and_skips() {
        let dir = tempfile::tempdir().unwrap();
        let root = dir.path().to_path_buf();
        fs::create_dir_all(root.join("sub")).unwrap();
        fs::create_dir_all(root.join(".git")).unwrap();
        fs::write(root.join("a.md"), "hello world\nnothing\nHello again").unwrap();
        fs::write(root.join("sub/b.txt"), "say héllo hello").unwrap();
        fs::write(root.join(".git/c"), "hello").unwrap();
        fs::write(root.join("bin"), b"hello\0").unwrap();
        let hits = search(std::slice::from_ref(&root), "hello", false, false, &[".git".into()], 100).unwrap();
        assert_eq!(hits.len(), 3, "{hits:?}");
        assert_eq!((hits[1].line, hits[1].col), (3, 1));
        assert_eq!(hits[2].col, 11); // characters, not bytes
        let cs = search(std::slice::from_ref(&root), "Hello", false, true, &[".git".into()], 100).unwrap();
        assert_eq!(cs.len(), 1);
        let re = search(std::slice::from_ref(&root), "h.llo w", true, false, &[], 100).unwrap();
        assert_eq!(re.len(), 1);
        assert!(search(std::slice::from_ref(&root), "(", true, false, &[], 100).is_err());
        assert_eq!(search(&[root], "hello", false, false, &[".git".into()], 1).unwrap().len(), 1);
    }
}
