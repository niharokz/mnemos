// Language detection and the language list for the Language menu. CodeMirror's
// language-data gives ~140 languages (loaded lazily, one chunk each); a few that
// matter here (Caddyfile, .env, Windows batch) are added on top.
import { LanguageDescription, LanguageSupport, StreamLanguage } from '@codemirror/language';
import { languages as builtin } from '@codemirror/language-data';
import { basename, extname } from './paths.js';

const caddyfile = StreamLanguage.define({
  name: 'caddyfile',
  startState: () => ({ depth: 0 }),
  token(stream, state) {
    if (stream.eatSpace()) return null;
    if (stream.match('#')) { stream.skipToEnd(); return 'comment'; }
    if (stream.match(/^"(?:[^"\\]|\\.)*"?/) || stream.match(/^`[^`]*`?/)) return 'string';
    if (stream.match(/^\{[a-zA-Z_.$>\-:/0-9]+\}/)) return 'variableName';
    if (stream.match('{')) { state.depth++; return 'bracket'; }
    if (stream.match('}')) { state.depth = Math.max(0, state.depth - 1); return 'bracket'; }
    if (stream.match(/^@[\w-]+/)) return 'atom';
    if (stream.match(/^\d+(\.\d+)?(ms|s|m|h|d|[kmg]b)?\b/i)) return 'number';
    const first = stream.string.slice(0, stream.start).trim() === '';
    if (stream.match(/^[^\s{}"`#]+/)) return first ? (state.depth === 0 ? 'heading' : 'keyword') : null;
    stream.next();
    return null;
  },
  languageData: { commentTokens: { line: '#' } },
});

const batch = StreamLanguage.define({
  name: 'batch',
  token(stream) {
    if (stream.sol() && stream.match(/^\s*(rem\b|::)/i)) { stream.skipToEnd(); return 'comment'; }
    if (stream.eatSpace()) return null;
    if (stream.match(/^"[^"]*"?/)) return 'string';
    if (stream.match(/^%[\w~:]+%?|^!\w+!/)) return 'variableName';
    if (stream.match(/^:\w+/) && stream.start === 0) return 'labelName';
    if (stream.match(/^(echo|set|if|else|for|in|do|goto|call|exit|setlocal|endlocal|not|exist|defined|errorlevel|shift|pushd|popd|cd|del|copy|move|mkdir|rmdir|start|pause|title)\b/i)) return 'keyword';
    if (stream.match(/^\d+/)) return 'number';
    stream.next();
    return null;
  },
  languageData: { commentTokens: { line: 'REM' } },
});

const extra = [
  LanguageDescription.of({ name: 'Caddyfile', filename: /^caddyfile(\..+)?$/i, extensions: ['caddy', 'caddyfile'], support: new LanguageSupport(caddyfile) }),
  LanguageDescription.of({ name: 'Batch', extensions: ['bat', 'cmd'], support: new LanguageSupport(batch) }),
  LanguageDescription.of({
    name: 'Dotenv', filename: /^\.env(\..+)?$/i, extensions: ['env'],
    load: () => import('@codemirror/legacy-modes/mode/properties').then((m) => new LanguageSupport(StreamLanguage.define(m.properties))),
  }),
];

// Markdown with highlighted ``` code blocks in any known language.
const markdownDesc = LanguageDescription.of({
  name: 'Markdown', alias: ['md'], extensions: ['md', 'markdown', 'mkd', 'mdown'],
  load: () => import('@codemirror/lang-markdown').then((m) => m.markdown({ codeLanguages: (info) => LanguageDescription.matchLanguageName(languages, info, true) })),
});

export const PLAIN = 'Plain text';
export const languages = [...extra, markdownDesc, ...builtin.filter((l) => l.name !== 'Markdown')].sort((a, b) => a.name.localeCompare(b.name, 'en', { sensitivity: 'base' }));

const SHEBANG = [
  [/\b(ba|z|k|da)?sh\b/, 'Shell'], [/\bpython[0-9.]*\b/, 'Python'], [/\bnode\b|\bdeno\b|\bbun\b/, 'JavaScript'],
  [/\bperl\b/, 'Perl'], [/\bruby\b/, 'Ruby'], [/\blua\b/, 'Lua'], [/\bpwsh\b/, 'PowerShell'],
];

// Plain-text extensions that language-data would otherwise not know (keeps them fast).
const PLAIN_EXT = new Set(['.txt', '.log', '.text', '.csv', '.tsv', '.nfo']);

// Returns a LanguageDescription for a file, or null for plain text.
export function detect(path, firstLine = '') {
  const name = basename(path || '');
  const ext = extname(name);
  if (PLAIN_EXT.has(ext)) return null;
  const byName = LanguageDescription.matchFilename(languages, name);
  if (byName) return byName;
  if (firstLine.startsWith('#!')) {
    for (const [re, lang] of SHEBANG) if (re.test(firstLine)) return byNameOf(lang);
  }
  return null;
}

export function byNameOf(name) {
  if (!name || name === PLAIN) return null;
  return languages.find((l) => l.name === name) || LanguageDescription.matchLanguageName(languages, name, false) || null;
}

// The Language menu, grouped A–Z like Notepad++.
export function grouped() {
  const groups = new Map();
  for (const l of languages) {
    const k = /[a-z]/i.test(l.name[0]) ? l.name[0].toUpperCase() : '#';
    if (!groups.has(k)) groups.set(k, []);
    groups.get(k).push(l.name);
  }
  return [...groups.entries()];
}

// Markdown files get the preview button.
export const isMarkdown = (path) => ['.md', '.markdown', '.mdown', '.mkd'].includes(extname(path || ''));
