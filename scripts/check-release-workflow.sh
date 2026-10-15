#!/usr/bin/env bash
#
# check-release-workflow.sh -- hold the release workflow's npm step to skipping
# a version that is already published, the repo to the word "view" where a
# find-and-replace once wrote "desk", and every job that runs the publish gate
# to checking out the tags the gate reads.
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


# 3. Every job that runs a publish check can see the tags it checks.
#
# check-publish.sh reads the release record: v<version>, and fm-spi-v<n> or
# fm-expr-v<n> whenever those ship in the bundle. actions/checkout fetches one
# commit and no other tags unless told to, so a job with a bare checkout
# reports "no such tag" for a tag that is there. 0.6.0 was the first release to
# ship fm-spi since the gate learned to check its tag: the gate job passed, the
# Java job's own check failed on fm-spi-v0.0.14, and npm and PyPI had already
# published.
missing=$(python3 - .github/workflows/release.yml <<'PY'
import re, sys
text = open(sys.argv[1]).read()
jobs = re.split(r'(?m)^  ([a-z][a-z0-9-]*):\s*$', text.split('\njobs:\n', 1)[1])
for name, body in zip(jobs[1::2], jobs[2::2]):
    if 'make check-publish' not in body:
        continue
    if not (re.search(r'fetch-depth:\s*0\b', body) and re.search(r'fetch-tags:\s*true\b', body)):
        print(name)
PY
)
if [[ -z "$missing" ]]; then
  ok "every job running a publish check fetches the tags it reads"
else
  bad "jobs run a publish check without fetch-depth: 0 and fetch-tags: true: $(tr '\n' ' ' <<<"$missing")"
fi

exit $fail
