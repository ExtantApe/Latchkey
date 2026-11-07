#!/bin/bash
# Blocks publishing private details. Terms live outside the repo: one extended regex per line,
# case-insensitive, in the file named by LATCHKEY_PRIVATE_TERMS. Exit 1 means something was found.
set -u
cd "$(dirname "$0")/.."
TERMS="${LATCHKEY_PRIVATE_TERMS:-}"
if git rev-parse --git-dir >/dev/null 2>&1; then
  FILES=$(git ls-files --cached --others --exclude-standard)
else
  FILES=$(find . -type f -not -path './build/*' -not -path './.gradle/*' -not -path './run/*')
fi
FILES=$(echo "$FILES" | grep -v -E '(^|/)(gradle-wrapper\.jar|package-lock\.json)$')
fail=0
if [[ -n "$TERMS" && -f "$TERMS" ]]; then
  terms=$(grep -v -E '^\s*(#|$)' "$TERMS")
  hits=$(echo "$FILES" | tr '\n' '\0' | xargs -0 grep -I -n -i -E -f <(echo "$terms") 2>/dev/null)
  names=$(echo "$FILES" | grep -i -E -f <(echo "$terms"))
  if [[ -n "$hits$names" ]]; then echo "Private terms found:"; echo "$names"; echo "$hits" | cut -c1-160 | head -40; fail=1; fi
else
  echo "No private terms file, so only checking for email addresses and binaries."
fi
emails=$(echo "$FILES" | tr '\n' '\0' | xargs -0 grep -I -n -o -E '[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+\.[A-Za-z]{2,}' 2>/dev/null | grep -v -E '@example\.(com|org|net)')
[[ -n "$emails" ]] && { echo "Email addresses found:"; echo "$emails" | head; fail=1; }
jars=$(echo "$FILES" | grep -E '\.(jar|class|zip)$' | grep -v gradle-wrapper)
[[ -n "$jars" ]] && { echo "Build output found:"; echo "$jars"; fail=1; }
[[ $fail == 0 ]] && echo "Leak check passed ($(echo "$FILES" | wc -l | tr -d ' ') files)"
exit $fail
