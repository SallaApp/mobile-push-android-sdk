#!/usr/bin/env bash
# Resolves and checks a release tag (.github/workflows/publish.yml): the release job runs it before anything is built,
# and the publish job runs it again just before it uploads, against Maven Central as it is by then.
#
#   scripts/release-plan.sh <tag>
#
# A release is a tag X.Y.Z or X.Y.Z-(alpha|beta|rc).N on this repository: the version itself, without a "v"
# (docs/RELEASING.md). Release tags are never moved or deleted, so a tag these checks refused stays. What was
# released is what Maven Central holds: a refused tag published nothing. It prints key=value lines for $GITHUB_OUTPUT:
#   version             the version, which is the tag
#   prerelease          true for X.Y.Z-(alpha|beta|rc).N
#   sha                 the commit the tag points at (GITHUB_SHA is never trusted for a tag)
#   api_baseline        the release whose public API this version must keep, among the stable versions below it whose
#                       tag's commit is an ancestor of the tagged one: the highest that Maven Central holds, or, when
#                       Maven Central holds none above the last release before Maven Central (RELEASE_LAST_BEFORE_CENTRAL),
#                       the highest release up to that one; never a release below the first public release
#                       (RELEASE_API_BASELINE_FLOOR). Empty for a pre-release, when there is none, and when Maven
#                       Central already holds this version (it was checked before it was)
#   api_baseline_source where api_baseline comes from: maven-central, or tag for a release before Maven Central; empty
#                       without one
#   major_bump          true when the major version grew past api_baseline's: the release may then break its API
# It fails unless:
#   - the tag is a release version of at most 32 characters, the longest sdk_version the SallaPush server accepts;
#   - the tag points at a commit of a branch of this repository (RELEASE_BRANCH_REFS, default refs/remotes/origin/,
#     where actions/checkout puts every branch with fetch-depth: 0), never at one that only a pull request from a fork
#     holds: the publish job builds the tagged commit with the signing key and the Central Portal token;
#   - a stable version points at a commit on master (RELEASE_MASTER_REF, default refs/remotes/origin/master). A pre-release
#     may be tagged on another branch's commit, to try a change before it is merged: every release runs the pull
#     request checks before anything is published;
#   - gradle.properties declares exactly this VERSION_NAME at the tagged commit;
#   - CHANGELOG.md has one "## [<version>]" entry at the tagged commit;
#   - for a stable version that Maven Central does not hold yet, it holds no later stable version either: stable
#     versions are published in increasing order, so that each one is checked against the release before it;
#   - and Maven Central holds each stable tag that the search for api_baseline passes over, unless CHANGELOG.md marks
#     it "## [<version>] - Not released" at the tagged commit: Maven Central lacks a refused tag, but also one whose
#     deployment still waits in the Central Portal, whose API this version must keep once a maintainer publishes it.
#
# Only a stable version reads Maven Central, anonymously, with curl:
#   RELEASE_MAVEN_URL            the Maven repository; default https://repo1.maven.org/maven2, where Maven Central serves
#                                what the Central Portal publishes
#   RELEASE_LAST_BEFORE_CENTRAL  the last release published before Maven Central, which never held it; default
#                                SDK_LAST_RELEASE_BEFORE_MAVEN_CENTRAL of scripts/common.sh. Set but empty: none
#   RELEASE_API_BASELINE_FLOOR   the first public release: the releases below it were never public, so none of them is
#                                an API baseline; default SDK_API_BASELINE_FLOOR of scripts/common.sh. Set but empty:
#                                none
# Maven Central holds a version when it serves the version's POM, and does not when it answers 404 for it. Any other
# answer about an earlier or a later version fails the plan, because the API check cannot be decided without it.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
top="$(cd "$here/.." && pwd -P)"
# shellcheck source-path=SCRIPTDIR
# shellcheck source=common.sh
. "$here/common.sh"
master_ref="${RELEASE_MASTER_REF:-refs/remotes/origin/master}"
branch_refs="${RELEASE_BRANCH_REFS:-refs/remotes/origin/}"
maven_url="${RELEASE_MAVEN_URL:-https://repo1.maven.org/maven2}"
maven_url="${maven_url%/}"
before_central="${RELEASE_LAST_BEFORE_CENTRAL-$SDK_LAST_RELEASE_BEFORE_MAVEN_CENTRAL}"
api_floor="${RELEASE_API_BASELINE_FLOOR-$SDK_API_BASELINE_FLOOR}"
readonly ARTIFACT=mobile-push

die() {
  printf 'release-plan.sh: %s\n' "$*" >&2
  exit 1
}

note() {
  printf 'release-plan.sh: %s\n' "$*" >&2
}

git_top() {
  git -C "$top" "$@"
}

[ "$#" -eq 1 ] || {
  echo 'usage: scripts/release-plan.sh <X.Y.Z or X.Y.Z-(alpha|beta|rc).N>' >&2
  exit 2
}
tag="$1"
if [ -n "$before_central" ]; then
  { [[ "$before_central" =~ $SDK_VERSION_RE ]] && [ -z "${BASH_REMATCH[4]}" ]; } ||
    die "the last release before Maven Central must be a stable version, not '$before_central'"
fi
if [ -n "$api_floor" ]; then
  { [[ "$api_floor" =~ $SDK_VERSION_RE ]] && [ -z "${BASH_REMATCH[4]}" ]; } ||
    die "the API baseline floor must be a stable version, not '$api_floor'"
fi
sdk_check_version "$tag" || die "the tag $tag is not a release; release tags are the version itself, such as 1.2.0 or 1.2.0-beta.1"
[[ "$tag" =~ $SDK_VERSION_RE ]]
major="${BASH_REMATCH[1]}"
version="$tag"

sha="$(git_top rev-parse --verify --quiet "refs/tags/$tag^{commit}")" || die "the tag $tag does not exist in this clone"
prerelease=false
case "$version" in *-*) prerelease=true ;; esac

branches="$(git_top for-each-ref --count=1 --format='%(refname)' "$branch_refs")" ||
  die "cannot list the branches under $branch_refs"
[ -n "$branches" ] ||
  die "no branch of this repository is under $branch_refs; the release checks read every branch (actions/checkout: fetch-depth: 0)"
branches="$(git_top for-each-ref --count=1 --contains "$sha" --format='%(refname)' "$branch_refs")" ||
  die "cannot list the branches that hold $sha"
[ -n "$branches" ] ||
  die "the tag $tag points at $sha, which is on no branch of this repository: a release is a commit of a branch here, never a pull request's from a fork; land the change on a branch of this repository first"

if [ "$prerelease" = false ]; then
  git_top rev-parse --verify --quiet "$master_ref^{commit}" >/dev/null ||
    die "$master_ref is missing; a stable release is checked against master (actions/checkout: fetch-depth: 0)"
  git_top merge-base --is-ancestor "$sha" "$master_ref" ||
    die "the stable release $tag must point at a commit on master, and $sha is not on $master_ref: merge the release's pull request, then tag its commit on master"
fi

declared="$(sdk_declared_version "$top" "$sha")" || die "cannot read VERSION_NAME at $sha"
[ "$declared" = "$version" ] ||
  die "gradle.properties declares VERSION_NAME=$declared at $sha, but the tag is $version; a release tag is the version the commit declares"

changelog="$(git_top show "$sha:CHANGELOG.md" 2>/dev/null)" || die "CHANGELOG.md does not exist at $sha"
heading="$(printf '%s\n' "$changelog" | sdk_changelog_heading "$version")" ||
  die "CHANGELOG.md at $sha needs one '## [$version]' entry that says what the release changes"
case "$heading" in
  *[Uu]nreleased*)
    echo "::warning::CHANGELOG.md's heading for $version reads '$heading'; date it (## [$version] - YYYY-MM-DD) in the next change" >&2
    ;;
esac

# ---------------------------------------------------------------------------------------------------
# Maven Central: which stable versions are releases. Functions, because bash 3.2 (macOS) cannot parse a case statement
# written inside $(...).

# The HTTP status of one file. Maven Central is read anonymously, and curl reads nothing from standard input.
http_status() {
  curl --silent --show-error --location --retry 2 --max-time 60 --output /dev/null --write-out '%{http_code}' "$1" \
    </dev/null
}

# Prints what Maven Central holds of <version>: "present" when it serves the version's POM, "absent" when it answers
# 404 for it, otherwise "HTTP <code> for <file>". Fails only when curl does.
package_state() {
  local file code
  file="$ARTIFACT-$1.pom"
  code="$(http_status "$maven_url/$group_path/$ARTIFACT/$1/$file")" || {
    printf 'release-plan.sh: could not reach Maven Central for %s\n' "$maven_url/$group_path/$ARTIFACT/$1/$file" >&2
    return 1
  }
  case "$code" in
    200) echo present ;;
    404) echo absent ;;
    *) echo "HTTP $code for $file" ;;
  esac
}

# Succeeds when the stable version $1 is below the stable version $2. For versions without a pre-release part, sort -V
# orders as Semantic Versioning does.
is_below() {
  [ "$1" != "$2" ] && [ "$(printf '%s\n%s\n' "$1" "$2" | sort -V | sed -n 1p)" = "$1" ]
}

# The stable versions of this clone's tags, one per line, whatever their commit.
stable_tags() {
  local candidate
  while IFS= read -r candidate; do
    [[ "$candidate" =~ $SDK_VERSION_RE ]] || continue
    case "$candidate" in *-*) continue ;; esac
    printf '%s\n' "$candidate"
  done <<<"$tags"
}

# The stable versions below this one whose tag is an ancestor of the tagged commit, highest first. A stable tag off master
# was refused, so it is never one of them.
stable_versions_below() {
  local candidate status found=""
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    is_below "$candidate" "$version" || continue
    status=0
    git_top merge-base --is-ancestor "refs/tags/$candidate" "$sha" || status=$?
    case "$status" in
      0) found="$found$candidate"$'\n' ;;
      1) ;;
      *)
        printf 'release-plan.sh: git merge-base failed (exit %s) for the tag %s\n' "$status" "$candidate" >&2
        return 1
        ;;
    esac
  done <<<"$stable"
  printf '%s' "$found" | sort -r -V
}

# The stable versions above this one, whatever their commit, lowest first.
stable_versions_above() {
  local candidate found=""
  while IFS= read -r candidate; do
    [ -n "$candidate" ] || continue
    is_below "$version" "$candidate" || continue
    found="$found$candidate"$'\n'
  done <<<"$stable"
  printf '%s' "$found" | sort -V
}

api_baseline=""
api_baseline_source=""
major_bump=false
if [ "$prerelease" = false ]; then
  command -v curl >/dev/null 2>&1 || die "curl is required to read Maven Central"
  properties="$(git_top show "$sha:gradle.properties" 2>/dev/null)" || die "gradle.properties does not exist at $sha"
  group="$(printf '%s\n' "$properties" | tr -d '\r' |
    sed -nE 's/^[[:space:]]*GROUP[[:space:]]*=[[:space:]]*([^[:space:]]+)[[:space:]]*$/\1/p')"
  group_re='^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)*$'
  [[ "$group" =~ $group_re ]] ||
    die "gradle.properties at $sha must declare GROUP once, as a Maven group such as com.salla"
  group_path="$(printf '%s' "$group" | tr . /)"
  tags="$(git_top tag -l)" || die "cannot list this clone's tags"
  stable="$(stable_tags)"

  state="$(package_state "$version")" || exit 1
  if [ "$state" = present ]; then
    note "Maven Central already holds $version, which these checks passed before it was published: nothing is compared again, and the publish job only confirms the publication"
  else
    [ "$state" = absent ] ||
      echo "::warning::Maven Central answered $state; $version is checked as a version it does not hold yet" >&2
    later="$(stable_versions_above)"
    while IFS= read -r candidate; do
      [ -n "$candidate" ] || continue
      state="$(package_state "$candidate")" || exit 1
      case "$state" in
        absent) ;;
        present)
          die "Maven Central already holds $candidate, a later stable version: stable versions are published in increasing order, so that each one is checked against the release before it; release a version above $candidate"
          ;;
        *) die "cannot tell whether Maven Central holds $candidate ($state); run the release checks again once it answers" ;;
      esac
    done <<<"$later"

    below="$(stable_versions_below)" || exit 1
    while IFS= read -r candidate; do
      [ -n "$candidate" ] || continue
      # The releases below the floor were never public, and the candidates come highest first.
      if [ -n "$api_floor" ] && is_below "$candidate" "$api_floor"; then
        note "$candidate and the releases below it were never public (below $api_floor), so their API is no baseline"
        break
      fi
      # The releases before Maven Central are in no registry this reads: the highest of them is the baseline while
      # Maven Central holds no later release.
      if [ -n "$before_central" ] && ! is_below "$before_central" "$candidate"; then
        api_baseline="$candidate"
        api_baseline_source=tag
        note "the API baseline is $candidate, a release from before Maven Central"
        break
      fi
      state="$(package_state "$candidate")" || exit 1
      case "$state" in
        present)
          api_baseline="$candidate"
          api_baseline_source=maven-central
          break
          ;;
        absent)
          # Refused, or uploaded and still waiting in the Central Portal: only the release after a refused tag says
          # which, in its CHANGELOG.md.
          marks="$(printf '%s\n' "$changelog" | sdk_changelog_headings "$candidate")" || die "cannot read CHANGELOG.md at $sha"
          case "$marks" in
            *[Nn]ot\ released*)
              note "$candidate is tagged but not on Maven Central, and CHANGELOG.md says it was not released, so its API is no baseline"
              ;;
            *)
              die "Maven Central does not hold $candidate, a stable version tagged below $version, and CHANGELOG.md does not mark it '## [$candidate] - Not released': its deployment may still wait in the Central Portal, and $version must then keep its API. Publish it there, and re-run this workflow once Maven Central serves it (docs/RELEASING.md)"
              ;;
          esac
          ;;
        *) die "cannot tell whether Maven Central holds $candidate ($state), so the API baseline is unknown; run the release checks again once it answers" ;;
      esac
    done <<<"$below"
  fi
  if [ -n "$api_baseline" ] && [ "$major" -gt "${api_baseline%%.*}" ]; then
    major_bump=true
  fi
fi

printf 'version=%s\nprerelease=%s\nsha=%s\napi_baseline=%s\napi_baseline_source=%s\nmajor_bump=%s\n' \
  "$version" "$prerelease" "$sha" "$api_baseline" "$api_baseline_source" "$major_bump"
