# Keyboard shortcuts

Notepad++ defaults. They work the same on Linux, Windows, the web and (with a
hardware keyboard) Android. In the app: **? → Keyboard shortcuts** (F1).
Generated from `frontend/src/ui/commands.js`.

Mouse: **Alt+drag** selects a column · **Ctrl+click** adds a cursor ·
**middle-click** a tab closes it · a lone **Alt** opens the collapsed menu.

## File

| Action | Keys |
|---|---|
| New | `Ctrl+N` |
| Open file | `Ctrl+O` |
| Reopen closed file | `Ctrl+Shift+T` |
| Save | `Ctrl+S` |
| Save as | `Ctrl+Alt+S` |
| Save all | `Ctrl+Shift+S` |
| Close | `Ctrl+W` · `Ctrl+F4` |
| Close all | `Ctrl+Shift+W` |
| Print | `Ctrl+P` |
| Exit | `Alt+F4` |
| Next tab | `Ctrl+Tab` · `Ctrl+PageDown` |
| Previous tab | `Ctrl+Shift+Tab` · `Ctrl+PageUp` |

## Edit

| Action | Keys |
|---|---|
| Undo | `Ctrl+Z` |
| Redo | `Ctrl+Y` · `Ctrl+Shift+Z` |
| Cut | `Ctrl+X` |
| Copy | `Ctrl+C` |
| Paste | `Ctrl+V` |
| Select all | `Ctrl+A` |
| Duplicate current line | `Ctrl+D` |
| Delete current line | `Ctrl+Shift+L` |
| Cut current line | `Ctrl+L` |
| Move line up | `Ctrl+Shift+Up` |
| Move line down | `Ctrl+Shift+Down` |
| Swap line with previous | `Ctrl+T` |
| Join lines | `Ctrl+J` |
| UPPERCASE | `Ctrl+Shift+U` |
| lowercase | `Ctrl+U` |
| Toggle single-line comment | `Ctrl+Q` |
| Single-line comment | `Ctrl+K` |
| Single-line uncomment | `Ctrl+Shift+K` |
| Block comment | `Ctrl+Shift+Q` |
| Word completion | `Ctrl+Enter` · `Ctrl+Space` |
| Add cursor above | `Ctrl+Alt+Up` · `Alt+Shift+Up` |
| Add cursor below | `Ctrl+Alt+Down` · `Alt+Shift+Down` |
| Add next occurrence | `Ctrl+Alt+D` |
| Select all occurrences | `Alt+F3` |

## Search

| Action | Keys |
|---|---|
| Find | `Ctrl+F` |
| Replace | `Ctrl+H` |
| Find next | `F3` |
| Find previous | `Shift+F3` |
| Find in files | `Ctrl+Shift+F` |
| Go to line | `Ctrl+G` |
| Go to matching brace | `Ctrl+B` |
| Select to matching brace | `Ctrl+Alt+B` |
| Toggle bookmark | `Ctrl+F2` |
| Next bookmark | `F2` |
| Previous bookmark | `Shift+F2` |

## View

| Action | Keys |
|---|---|
| Sidebar | `Ctrl+Shift+E` |
| Markdown preview | `Ctrl+Shift+M` |
| Word wrap | `Alt+Z` |
| Zoom in | `Ctrl+=` · `Ctrl+Num+` |
| Zoom out | `Ctrl+-` · `Ctrl+Num-` |
| Restore default zoom | `Ctrl+0` · `Ctrl+Num/` |
| Fold all | `Alt+0` |
| Unfold all | `Alt+Shift+0` |
| Fold current level | `Ctrl+Alt+F` |
| Unfold current level | `Ctrl+Alt+Shift+F` |
| Full screen | `F11` |

## Settings and help

| Action | Keys |
|---|---|
| Preferences | `Ctrl+,` |
| Keyboard shortcuts | `F1` |

## Differences from Notepad++

- Multi-cursor keys come from VS Code (Notepad++ has none by default):
  Ctrl+Alt+↑/↓ add a cursor, Ctrl+Alt+D adds the next match, Alt+F3 selects all matches.
- Ctrl+Shift+M (Markdown preview), Ctrl+Shift+E (sidebar), Alt+Z (word wrap)
  and Ctrl+, (preferences) are mnemostxt additions that clash with nothing in Notepad++.
- No macros, plugins or Run menu — mnemostxt is just a notepad.
