#!/usr/bin/env bash
#
# check-release-workflow.sh -- hold the release workflow's npm step to skipping
# a version that is already published, and the repo to the word "view" where a
# find-and-replace once wrote "desk".
#
# The View -> Desk rename of the SDK type (0.4) was done by a replacement that
# also caught the English word: `npm view` became `npm desk`, which is not an
# npm command, so the "already published?" test always answered no and a
# re-run of a release that had already reached npm failed there instead of
# skipping it. The same pass left "point of desk", "connections desk" and
# "registry's desk" in comments and docs.
#
# Runs the workflow's own "Publish to npm" script, cut out of release.yml,
# against a stub npm -- so what is checked is the text that will run.

set -uo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
fail=0
ok()  { printf '  ok    %s\n' "$1"; }
bad() { printf '  FAIL  %s\n' "$1"; fail=1; }

# 1. The English word is back where the rename took it.
hits=$(git grep -n -E "npm desk|point of desk|connections desk|registry's desk|maintained desk of" -- . ':!scripts/check-release-workflow.sh' || true)
if [[ -z "$hits" ]]; then ok "no view->desk mis-renames"; else bad "view->desk mis-renames remain:"; printf '%s\n' "$hits" | sed 's/^/        /'; fi

# 2. The npm step skips a published version and publishes a new one.
step=$(awk '/- name: Publish to npm/{f=1} f&&/run: \|/{r=1; next} r&&/^      - name:|^  [a-z-]+:$/{exit} r{sub(/^          /,""); print}' .github/workflows/release.yml)
if [[ -z "$step" ]]; then bad "found the Publish to npm step in release.yml"; exit 1; fi

TMP=$(mktemp -d); trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/pkg"
printf '{"name":"@flexemarkets/fm-sdk","version":"9.9.9"}\n' > "$TMP/pkg/package.json"
cat > "$TMP/bin/npm" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$NPM_LOG"
case "$1" in
  view)    [[ "$PUBLISHED" == yes ]] && { echo 9.9.9; exit 0; } || exit 1 ;;
  publish) exit 0 ;;
  *)       echo "npm: unknown command $1" >&2; exit 1 ;;
esac
STUB
chmod +x "$TMP/bin/npm"

run() { (cd "$TMP/pkg" && PATH="$TMP/bin:$PATH" NPM_LOG="$TMP/log" PUBLISHED="$1" bash -c "$step") 2>&1; }

: > "$TMP/log"; out=$(run yes)
if grep -q "already published" <<<"$out" && ! grep -q "^publish" "$TMP/log"; then ok "a re-run skips a version already on npm"; else bad "a re-run skips a version already on npm (npm saw: $(tr '\n' ' ' < "$TMP/log"))"; fi
: > "$TMP/log"; out=$(run no)
if grep -q "^publish" "$TMP/log"; then ok "a new version is published"; else bad "a new version is published"; fi

exit $fail
