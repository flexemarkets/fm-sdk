#!/usr/bin/env bash
#
# check-publish.sh — verify a registry is ready before anything is uploaded.
#
# None of the three registries lets a version be replaced. npm, PyPI and Maven
# Central are all append-only, so an upload that fails part-way is the worst
# outcome available: the version is burned where it landed and unusable where
# it did not, and the only way out is to renumber and republish everything.
#
# So publishing is gated, and the gate refuses rather than warns — for the same
# reason fm-release's live-session gate does: a check that cannot reach its
# evidence must not wave the deploy through.
#
# The checks are per-registry because the publish targets are. `make
# publish-java` must not be blocked by a missing npm login — and, the failure
# that prompted this, must not be *unblocked* merely because the only gate hung
# off the aggregate `publish` target. 0.0.7 went live on npm and PyPI through
# their individual targets with nothing checked at all, for exactly that reason.
#
# `all` additionally serves the aggregate `publish` target, which has to know
# every registry will accept before the first byte is sent to any of them.
#
# Usage: scripts/check-publish.sh [all|npm|pypi|java]
# Exit:  0 when the named registry could publish the current version, 1 otherwise.

set -uo pipefail

TARGET="${1:-all}"

case "$TARGET" in
    all|npm|pypi|java) ;;
    -h|--help)
        sed -n '3,25p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
        exit 0
        ;;
    *)
        echo "check-publish: unknown registry '$TARGET' (want: all, npm, pypi, java)" >&2
        exit 1
        ;;
esac

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(cat "$ROOT/VERSION")"
SPI_VERSION="$(grep -o '<version>[^<]*</version><!-- spi-version -->' \
                 "$ROOT/sdks/java/fm-spi/pom.xml" | sed 's|<version>\(.*\)</version>.*|\1|')"

problems=0
hints=()

fail() { printf '  \033[31mFAIL\033[0m  %-22s %s\n' "$1" "$2"; problems=$((problems + 1)); }
pass() { printf '  \033[32m ok \033[0m  %-22s %s\n' "$1" "$2"; }

wants() { [[ "$TARGET" == "all" || "$TARGET" == "$1" ]]; }

# ---------------------------------------------------------------------------
# Credentials
# ---------------------------------------------------------------------------

check_npm_credentials() {
    if npm_user=$(npm whoami 2>/dev/null); then
        pass "npm" "authenticated as $npm_user"
    else
        fail "npm" "not logged in — run: npm login"
        hints+=("npm: a browser session on npmjs.com is not a publishing credential; npm login writes the token this needs.")
    fi
}

check_pypi_credentials() {
    if [[ -n "${TWINE_USERNAME:-}" && -n "${TWINE_PASSWORD:-}" ]]; then
        pass "pypi" "TWINE_USERNAME/TWINE_PASSWORD set"
    elif [[ -f "$HOME/.pypirc" ]]; then
        pass "pypi" "~/.pypirc present"
    else
        fail "pypi" "no ~/.pypirc, and TWINE_* unset in THIS shell"
        hints+=("pypi: twine needs an API token in ~/.pypirc or in TWINE_USERNAME/TWINE_PASSWORD. TWINE_* are read from the environment of the shell that runs this, so run it in the shell you exported them in, not another one.")
    fi
}

check_java_credentials() {
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
        hints+=("maven central: needs a <server><id>central</id> entry holding a portal user token — not a browser session on central.sonatype.com.")
    fi

    # Central refuses unsigned artifacts, and the release profile runs maven-gpg.
    local keys
    keys=$(gpg --list-secret-keys 2>/dev/null | grep -c '^sec')
    if (( keys > 0 )); then
        pass "gpg" "$keys secret key(s) for signing"
    else
        fail "gpg" "no secret key — Central refuses unsigned artifacts"
        hints+=("gpg: Central validates every signature against a public keyserver, so the key must also be published and its email verified — a local secret key alone still fails validation.")
    fi
}

# ---------------------------------------------------------------------------
# Is this version already public?
# ---------------------------------------------------------------------------

check_npm_version() {
    local name
    name=$(python3 -c "import json;print(json.load(open('$ROOT/sdks/typescript/package.json'))['name'])" 2>/dev/null)
    if curl -fsS --max-time 20 "https://registry.npmjs.org/$name" 2>/dev/null \
         | python3 -c "import json,sys;sys.exit(0 if '$VERSION' in json.load(sys.stdin)['versions'] else 1)" 2>/dev/null; then
        fail "npm $VERSION" "already published — a version cannot be replaced"
    else
        pass "npm $VERSION" "not yet published"
    fi
}

check_pypi_version() {
    if curl -fsS --max-time 20 "https://pypi.org/pypi/fm-sdk/json" 2>/dev/null \
         | python3 -c "import json,sys;sys.exit(0 if '$VERSION' in json.load(sys.stdin)['releases'] else 1)" 2>/dev/null; then
        fail "pypi $VERSION" "already published — a version cannot be replaced"
    else
        pass "pypi $VERSION" "not yet published"
    fi
}

# fm-spi is on its own version line, so it is checked against its own version
# rather than the SDK's — they are equal today and are not required to stay so.
check_java_version() {
    local artifact name want
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
}

# ---------------------------------------------------------------------------

case "$TARGET" in
    all)  echo "Publishing fm-sdk $VERSION to npm, PyPI and Maven Central (fm-spi $SPI_VERSION)" ;;
    npm)  echo "Publishing fm-sdk $VERSION to npm" ;;
    pypi) echo "Publishing fm-sdk $VERSION to PyPI" ;;
    java) echo "Publishing fm-sdk $VERSION and fm-spi $SPI_VERSION to Maven Central" ;;
esac

echo ""
echo "Credentials:"
wants npm  && check_npm_credentials
wants pypi && check_pypi_credentials
wants java && check_java_credentials

echo ""
echo "Version availability:"
wants npm  && check_npm_version
wants pypi && check_pypi_version
wants java && check_java_version

echo ""
if (( problems )); then
    echo "REFUSING: $problems check(s) failed."
    echo ""
    # Guarded: an "already published" failure adds no hint, so the array can be
    # empty here, and "${hints[@]}" on an empty array is an unbound-variable
    # error under set -u on bash before 4.4.
    if (( ${#hints[@]} )); then
        for hint in "${hints[@]}"; do
            echo "  - $hint"
        done
        echo ""
    fi
    echo "Nothing has been uploaded. Publishing part-way is worse than not"
    echo "starting: a version that lands on one registry and fails on another"
    echo "cannot be replaced on either, and the only way forward is to renumber"
    echo "every artifact and publish again."
    exit 1
fi

case "$TARGET" in
    all) echo "All three registries are ready for $VERSION." ;;
    *)   echo "$TARGET is ready for $VERSION." ;;
esac
