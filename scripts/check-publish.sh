#!/usr/bin/env bash
#
# check-publish.sh — verify every registry is ready before anything is uploaded.
#
# `make publish` uploads to three registries in sequence: PyPI, npm and Maven
# Central. None of them lets a version be replaced, so a run that succeeds on
# the first and fails on the third is the worst outcome available — the version
# is burned where it landed and unusable where it did not, and the only way out
# is to renumber and republish everything.
#
# So the credentials are checked together, before the first upload. This refuses
# rather than warns, for the same reason fm-release's live-session gate does:
# a check that cannot reach its evidence must not wave the deploy through.
#
# It also checks the version is not already public, which is the other way a
# publish gets halfway.
#
# Usage: scripts/check-publish.sh
# Exit:  0 when all three could publish the current version, 1 otherwise.

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(cat "$ROOT/VERSION")"
SPI_VERSION="$(grep -o '<version>[^<]*</version><!-- spi-version -->' \
                 "$ROOT/sdks/java/fm-spi/pom.xml" | sed 's|<version>\(.*\)</version>.*|\1|')"

problems=0

fail() { printf '  \033[31mFAIL\033[0m  %-22s %s\n' "$1" "$2"; problems=$((problems + 1)); }
pass() { printf '  \033[32m ok \033[0m  %-22s %s\n' "$1" "$2"; }

echo "Publishing fm-sdk $VERSION (fm-spi $SPI_VERSION)"
echo ""
echo "Credentials:"

# --- npm --------------------------------------------------------------------
if npm_user=$(npm whoami 2>/dev/null); then
    pass "npm" "authenticated as $npm_user"
else
    fail "npm" "not logged in — run: npm login"
fi

# --- PyPI -------------------------------------------------------------------
if [[ -n "${TWINE_USERNAME:-}" && -n "${TWINE_PASSWORD:-}" ]]; then
    pass "pypi" "TWINE_USERNAME/TWINE_PASSWORD set"
elif [[ -f "$HOME/.pypirc" ]]; then
    pass "pypi" "~/.pypirc present"
else
    fail "pypi" "no ~/.pypirc, and TWINE_* unset in THIS shell"
fi

# --- Maven Central ----------------------------------------------------------
# The release profile publishes through central-publishing-maven-plugin with
# publishingServerId 'central', so settings.xml needs a matching server entry.
if grep -q '<id>central</id>' "$HOME/.m2/settings.xml" 2>/dev/null; then
    pass "maven central" "server id 'central' in ~/.m2/settings.xml"
else
    if [[ -f "$HOME/.m2/settings.xml" ]]; then
        fail "maven central" "~/.m2/settings.xml has no <server><id>central</id>"
    else
        fail "maven central" "no ~/.m2/settings.xml at all"
    fi
fi

# Central refuses unsigned artifacts, and the release profile runs maven-gpg.
if [[ $(gpg --list-secret-keys 2>/dev/null | grep -c '^sec') -gt 0 ]]; then
    pass "gpg" "$(gpg --list-secret-keys 2>/dev/null | grep -c '^sec') secret key(s) for signing"
else
    fail "gpg" "no secret key — Central refuses unsigned artifacts"
fi

# --- Is this version already public? ----------------------------------------
echo ""
echo "Version availability:"

npm_name=$(python3 -c "import json;print(json.load(open('$ROOT/sdks/typescript/package.json'))['name'])" 2>/dev/null)
if curl -fsS --max-time 20 "https://registry.npmjs.org/$npm_name" 2>/dev/null \
     | python3 -c "import json,sys;sys.exit(0 if '$VERSION' in json.load(sys.stdin)['versions'] else 1)" 2>/dev/null; then
    fail "npm $VERSION" "already published — a version cannot be replaced"
else
    pass "npm $VERSION" "not yet published"
fi

if curl -fsS --max-time 20 "https://pypi.org/pypi/fm-sdk/json" 2>/dev/null \
     | python3 -c "import json,sys;sys.exit(0 if '$VERSION' in json.load(sys.stdin)['releases'] else 1)" 2>/dev/null; then
    fail "pypi $VERSION" "already published — a version cannot be replaced"
else
    pass "pypi $VERSION" "not yet published"
fi

for artifact in "fm-sdk:$VERSION" "fm-spi:$SPI_VERSION"; do
    name="${artifact%%:*}"; want="${artifact##*:}"
    if curl -fsS --max-time 20 \
         "https://repo1.maven.org/maven2/com/flexemarkets/$name/maven-metadata.xml" 2>/dev/null \
         | grep -q "<version>$want</version>"; then
        fail "central $name $want" "already published — Central is immutable"
    else
        pass "central $name $want" "not yet published"
    fi
done

echo ""
if (( problems )); then
    echo "REFUSING: $problems check(s) failed."
    echo ""
    echo "Note: a browser session on pypi.org or central.sonatype.com is not a"
    echo "publishing credential. twine needs an API token in ~/.pypirc or in"
    echo "TWINE_USERNAME/TWINE_PASSWORD; Maven needs a <server><id>central</id>"
    echo "entry holding a portal token; and Central refuses unsigned artifacts,"
    echo "so a GPG secret key is required regardless of either."
    echo ""
    echo "TWINE_* are read from the environment of the shell that runs this, so"
    echo "run it in the shell you exported them in, not another one."
    echo ""
    echo "Nothing has been uploaded. Publishing part-way is worse than not"
    echo "starting: a version that lands on one registry and fails on another"
    echo "cannot be replaced on either, and the only way forward is to renumber"
    echo "every artifact and publish again."
    exit 1
fi

echo "All three registries are ready for $VERSION."
