#!/usr/bin/env bash
# Generates a synthetic vault for performance tests: 5 000 files, ~50 MB of text, nested folders,
# front matter, tags, a few big logs and CSVs. Usage: scripts/make-test-vault.sh [dir] [files]
set -euo pipefail
dir="${1:-./test-vault}"
count="${2:-5000}"
mkdir -p "$dir/.obsidian" "$dir/.stfolder"
words=(vault note sync teal grid offline file markdown preview editor plain text folder quiet fast safe garden run budget idea)
for ((i = 1; i <= count; i++)); do
  folder="$dir/area-$((i % 20))/topic-$((i % 7))"
  mkdir -p "$folder"
  if ((i % 250 == 0)); then
    # A big log every 250 files.
    for ((l = 0; l < 20000; l++)); do echo "2026-10-12 12:00:$((l % 60)) INFO sync[$l] scanned $((l * 7 % 5000)) files"; done > "$folder/run-$i.log"
  elif ((i % 100 == 0)); then
    { echo "date,item,amount"; for ((r = 0; r < 500; r++)); do echo "2026-10-$((r % 28 + 1)),item $r,$((r * 13 % 900)).$((r % 100))"; done; } > "$folder/table-$i.csv"
  else
    {
      echo "---"
      echo "status: $([ $((i % 3)) -eq 0 ] && echo open || echo done)"
      echo "tags: [${words[$((i % ${#words[@]}))]}, demo]"
      echo "---"
      echo "# Note $i"
      echo
      for ((p = 0; p < 12; p++)); do
        line=""
        for ((w = 0; w < 14; w++)); do line+="${words[$(((i * 31 + p * 7 + w) % ${#words[@]}))]} "; done
        echo "$line #${words[$((p % ${#words[@]}))]}"
      done
    } > "$folder/note-$i.md"
  fi
done
echo "Wrote $count files to $dir ($(du -sh "$dir" | cut -f1))."
echo "Copy it to the phone (adb push $dir /sdcard/test-vault) and pick it as the vault."
