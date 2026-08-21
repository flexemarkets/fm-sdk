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
# The checks ask whether publishing can proceed UNATTENDED, not merely whether
# a credential exists. 0.0.9 taught that distinction: `npm whoami` succeeded,
# the gate passed, and `npm publish` then stopped for a two-factor one-time
# password that make could not answer -- by which point PyPI had already
# uploaded, and PyPI is append-only. A credential that will pause for a human
# is not a credential this can publish with.
#
# `make publish` now runs npm first for the same reason: it is the only
# registry that can demand interaction, so a refusal there costs nothing.
#
# fm-sdk and fm-spi are on separate version lines but ship through one reactor
# deploy, so whenever one moves without the other the unchanged one is already
# published and blocks the changed one. `java` covers the pair; `spi` covers
# fm-spi alone, for the release where only the contract changed.
#
# A release must also be recorded in git before it is uploaded. The registries
# are append-only but git is not append-only in the same way: a tag can be
# added later, so the omission is quiet and stays quiet. That is not
# hypothetical: 0.0.7 through 0.0.12 shipped untagged and the commit each was
# built from had to be recovered afterwards by reading `chore:` messages and
# diffing poms. Those tags have since been back-filled -- every published
# version has one, so the history is whole and this paragraph is a record of
# why the check exists rather than a description of the present. The Release
# workflow cannot make the mistake, being triggered by the tag, so this check
# exists for the local `make publish` path, which is how those six went
# out.
#
# Usage: scripts/check-publish.sh [all|npm|pypi|java|spi]
# Exit:  0 when the named registry could publish the current version, 1 otherwise.

set -uo pipefail

TARGET="${1:-all}"

case "$TARGET" in
    all|npm|pypi|java|spi) ;;
    -h|--help)
        # The whole header, however long it grows. A fixed line range silently
        # stopped showing the paragraphs added after it was written.
        awk 'NR<3 {next} /^#/ {sub(/^# ?/, ""); print; next} {exit}' "${BASH_SOURCE[0]}"
        exit 0
        ;;
    *)
        echo "check-publish: unknown registry '$TARGET' (want: all, npm, pypi, java, spi)" >&2
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
    if ! npm_user=$(npm whoami 2>/dev/null); then
        fail "npm" "not logged in — run: npm login"
        hints+=("npm: a browser session on npmjs.com is not a publishing credential; npm login writes the token this needs.")
        return
    fi

    # Being logged in is not the same as being able to publish, and the gap
    # between them is what half-published 0.0.9. `npm whoami` succeeds under
    # two-factor auth; `npm publish` then stops and demands a one-time
    # password, which a script cannot answer. PyPI had already uploaded by
    # then, and PyPI is append-only.
    #
    # An automation token is exempt -- it publishes without an OTP by design --
    # but nothing in the CLI distinguishes one from a login token, so that case
    # is declared rather than detected. See FM_NPM_AUTOMATION_TOKEN below.
    local tfa_mode
    tfa_mode=$(npm profile get --json 2>/dev/null \
                 | tr -d ' \n' | grep -o '"mode":"[^"]*"' | cut -d'"' -f4)

    if [[ -n "${FM_NPM_AUTOMATION_TOKEN:-}" ]]; then
        pass "npm" "authenticated as $npm_user (automation token declared)"
        return
    fi

    case "$tfa_mode" in
        auth-and-writes)
            if [[ -n "${OTP:-}" ]]; then
                pass "npm" "authenticated as $npm_user (2FA on writes, OTP supplied)"
            else
                fail "npm" "2FA required for publishing, no OTP supplied"
                hints+=("npm: this account has two-factor auth set to auth-and-writes, so npm publish will stop and ask for a code that make cannot answer. Re-run as: make publish OTP=<code from your authenticator>. If the credential is an automation token, which is exempt, set FM_NPM_AUTOMATION_TOKEN=1 to say so.")
            fi
            ;;
        auth-only|disabled)
            pass "npm" "authenticated as $npm_user (2FA: $tfa_mode, no OTP needed)"
            ;;
        *)
            # Same rule as everywhere else here: a check that cannot reach its
            # evidence refuses rather than assumes. Publishing is append-only,
            # so guessing costs a version number.
            fail "npm" "could not read the account's 2FA setting"
            hints+=("npm: 'npm profile get' did not report a tfa mode, so whether publishing needs an OTP is unknown. Check 'npm profile get' by hand, or set FM_NPM_AUTOMATION_TOKEN=1 if this is an automation token.")
            ;;
    esac
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
# Is this release recorded in git?
# ---------------------------------------------------------------------------

# Each artifact's tag has to exist, sit on the commit being published, and be on
# every remote -- in that order, because each answer makes the next question
# meaningful. A tag pointing somewhere other than HEAD is worse than no tag: it
# says the artifact came from a commit it did not.
#
# fm-sdk and fm-spi are tagged separately because they are versioned separately:
# `v$VERSION` is the SDK, `fm-spi-v$SPI_VERSION` the contract. Neither prefix
# reaches the other's, and only the SDK's matches release.yml's `v*.*.*`
# trigger, so tagging the contract cannot fire an SDK publish.

# The tree itself, asked once however many artifacts ship from it.
check_git_worktree() {
    if ! git -C "$ROOT" rev-parse --git-dir >/dev/null 2>&1; then
        fail "git" "not a git checkout — cannot confirm the release is recorded"
        hints+=("git: publishing from an unpacked archive leaves nothing pointing at the source of the artifact. Publish from a clone.")
        return 1
    fi

    if [[ -n "$(git -C "$ROOT" status --porcelain --untracked-files=no)" ]]; then
        fail "git worktree" "tracked files modified since the last commit"
        hints+=("git: the tree has changes no tag covers, so the build would not be any tagged commit. Commit them or stash them.")
        return 1
    fi

    pass "git worktree" "clean at $(git -C "$ROOT" rev-parse --short HEAD)"
}

# $1 tag, $2 what it names (for the hint), $3 version
check_release_tag() {
    local tag="$1" what="$2" version="$3"

    if ! git -C "$ROOT" rev-parse -q --verify "refs/tags/$tag" >/dev/null 2>&1; then
        fail "$tag" "no such tag"
        hints+=("git: tag the release commit first — git tag -a $tag -m \"$what $version\" && git push origin $tag. fm-sdk 0.0.7 through 0.0.12 once shipped without one and had to be reconstructed afterwards from release messages and pom diffs; they are tagged now, and this check is what keeps that true.")
        return
    fi

    local tagged head
    tagged=$(git -C "$ROOT" rev-list -n1 "$tag")
    head=$(git -C "$ROOT" rev-parse HEAD)

    if [[ "$tagged" != "$head" ]]; then
        fail "$tag" "points at ${tagged:0:8}, HEAD is ${head:0:8}"
        hints+=("git: $tag names a different commit than the one about to be published, so the artifact would not match what the tag claims. Move the tag, or publish from the tagged commit.")
        return
    fi

    pass "$tag" "at HEAD"

    # A tag only on this machine records nothing anyone else can reach, which
    # is most of the point. Same rule as the registry checks: unreachable
    # evidence refuses rather than assumes.
    local remote remote_tag
    for remote in $(git -C "$ROOT" remote); do
        remote_tag=$(git -C "$ROOT" ls-remote --tags "$remote" "refs/tags/$tag" 2>/dev/null)
        if [[ -z "$remote_tag" ]]; then
            fail "$tag on $remote" "not pushed"
            hints+=("git: push the tag before publishing — git push $remote $tag. A tag that exists only locally disappears with the machine, and the published artifact is then unattributable.")
        else
            pass "$tag on $remote" "present"
        fi
    done
}

# fm-spi ships inside the same reactor bundle as the SDK, so it needs its own
# tag whenever it is actually in that bundle -- and does not when the pom holds
# it back for not having moved.
check_spi_release_tag() {
    if grep -qx "fm-spi" <<< "$(excluded_artifacts)"; then
        pass "fm-spi-v$SPI_VERSION" "excluded from this release by the pom"
        return
    fi

    check_release_tag "fm-spi-v$SPI_VERSION" "fm-spi" "$SPI_VERSION"
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

# Artifacts the release profile keeps off Central, read from the pom rather
# than repeated here. A gate with its own idea of what ships is a gate that
# eventually disagrees with the build, and the disagreement is silent in
# whichever direction hurts: refusing a release that would work, or waving one
# through that will not.
excluded_artifacts() {
    grep -o '<excludeArtifact>[^<]*</excludeArtifact>' "$ROOT/sdks/java/pom.xml" \
        | sed 's|</\?excludeArtifact>||g'
}

# fm-spi is checked against its own version, not the SDK's: it is on a separate
# line and the two are not required to agree.
check_spi_version() {
    if curl -fsS --max-time 20 \
         "https://repo1.maven.org/maven2/com/flexemarkets/fm-spi/maven-metadata.xml" 2>/dev/null \
         | grep -q "<version>$SPI_VERSION</version>"; then
        fail "central fm-spi $SPI_VERSION" "already published — Central is immutable"
    else
        pass "central fm-spi $SPI_VERSION" "not yet published"
    fi
}

check_java_version() {
    local artifact name want excluded
    excluded="$(excluded_artifacts)"

    for artifact in "fm-sdk:$VERSION" "fm-spi:$SPI_VERSION"; do
        name="${artifact%%:*}"; want="${artifact##*:}"

        if grep -qx "$name" <<< "$excluded"; then
            pass "central $name" "excluded from this release by the pom"
            continue
        fi

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
    spi) echo "Publishing fm-spi $SPI_VERSION to Maven Central (fm-sdk $VERSION unchanged)" ;;
    java)
        if grep -qx "fm-spi" <<< "$(excluded_artifacts)"; then
            echo "Publishing fm-sdk $VERSION to Maven Central (fm-spi $SPI_VERSION excluded)"
        else
            echo "Publishing fm-sdk $VERSION and fm-spi $SPI_VERSION to Maven Central"
        fi
        ;;
esac

echo ""
# Checked first, and for every registry: it is the one failure here that costs
# nothing to fix and cannot be fixed afterwards without the answer already
# being lost.
echo "Release record:"
if check_git_worktree; then
    [[ "$TARGET" != "spi" ]] && check_release_tag "v$VERSION" "fm-sdk" "$VERSION"
    { wants java || [[ "$TARGET" == "spi" ]]; } && check_spi_release_tag
fi
echo ""

echo "Credentials:"
wants npm  && check_npm_credentials
wants pypi && check_pypi_credentials
wants java && check_java_credentials
wants spi  && check_java_credentials

echo ""
echo "Version availability:"
wants npm  && check_npm_version
wants pypi && check_pypi_version
wants java && check_java_version
wants spi  && check_spi_version

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
    spi) echo "central is ready for fm-spi $SPI_VERSION." ;;
    *)   echo "$TARGET is ready for $VERSION." ;;
esac
