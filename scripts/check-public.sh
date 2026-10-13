#!/bin/sh
# Blocks a push that would publish secrets or personal data.
#
# Install once as a git pre-push hook:
#   ln -s ../../scripts/check-public.sh .git/hooks/pre-push
# Or run by hand:  sh scripts/check-public.sh
#
# Put words that must never appear in the public repo in .private-words
# (one per line, gitignored): your domain, server user name, host paths...
set -eu
cd "$(git rev-parse --show-toplevel)"
fail=0

for f in .env .private-words; do
  if git ls-files --error-unmatch "$f" >/dev/null 2>&1; then
    echo "✗ $f is tracked by git - remove it: git rm --cached $f"; fail=1
  fi
done

pattern='^(MNEMOS|SAMANOTE)_(PASSWORD|SECRET|STORE_PASSWORD|KEY_PASSWORD)=.{8,}'
if git grep -nI -E "$pattern" -- . ':!.env.example' >/dev/null 2>&1; then
  echo "✗ something that looks like a real password or secret is committed:"
  git grep -nI -E "$pattern" -- . ':!.env.example'; fail=1
fi
# the Android signing key and its passwords live in ~/.mnemos-keys, never in the repo
if git ls-files | grep -E '\.(jks|keystore|p12)$|(^|/)keystore\.env$' >/dev/null; then
  echo "✗ a signing key or keystore.env is tracked:"
  git ls-files | grep -E '\.(jks|keystore|p12)$|(^|/)keystore\.env$' | sed 's/^/    /'; fail=1
fi
if git grep -nI -E '\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}' -- . ':!vendor' >/dev/null 2>&1; then
  echo "✗ a bcrypt hash is committed:"
  git grep -nI -l -E '\$2[aby]\$[0-9]{2}\$[./A-Za-z0-9]{53}' -- . ':!vendor' | sed 's/^/    /'; fail=1
fi

if [ -f .private-words ]; then
  while IFS= read -r word; do
    case "$word" in ''|'#'*) continue ;; esac
    if git grep -nIiF -e "$word" -- . ':!vendor' ':!web/static/vendor' >/dev/null 2>&1; then
      echo "✗ private word \"$word\" found in:"
      git grep -nIiF -l -e "$word" -- . ':!vendor' ':!web/static/vendor' | sed 's/^/    /'; fail=1
    fi
  done < .private-words
fi

if [ "$fail" -ne 0 ]; then
  echo "Push blocked. Fix the files above (or remove the word from .private-words)."
  exit 1
fi
echo "✓ no secrets or private words found"
