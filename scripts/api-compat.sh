#!/usr/bin/env bash
# Fails when the public API at <commit> breaks the public API at <baseline-commit>, the previous release: the highest
# stable version below it that Maven Central holds, or before that the last release before Maven Central
# (scripts/release-plan.sh). .github/workflows/publish.yml runs it for every stable release that keeps its major
# version, in the release job and again in the publish job.
#
#   scripts/api-compat.sh <baseline-commit> <commit>
#
# It compares the committed binary-compatibility dumps, sallapush/api/sallapush.api, which the build's apiCheck keeps
# equal to the compiled library: every class and member of the baseline must still exist, unchanged. Additions are
# allowed; a removed or changed declaration is a break, and a break needs a new major version. (apiCheck itself cannot
# serve here: it compares with the committed dump, so it would also fail on every addition.)
set -euo pipefail

top="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
readonly API=sallapush/api/sallapush.api

die() {
  printf 'api-compat.sh: %s\n' "$*" >&2
  exit 1
}

[ "$#" -eq 2 ] || {
  echo 'usage: scripts/api-compat.sh <baseline-commit> <commit>' >&2
  exit 2
}
baseline="$(git -C "$top" rev-parse --verify --quiet "$1^{commit}")" || die "$1 is not a commit"
current="$(git -C "$top" rev-parse --verify --quiet "$2^{commit}")" || die "$2 is not a commit"

work="$(mktemp -d "${TMPDIR:-/tmp}/sallapush-api-compat.XXXXXX")"
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT TERM

# Flattens a .api dump into "class<TAB>declaration" lines: class headers keyed by class name, members keyed by their
# enclosing class, so that equal member lines of different classes stay distinct.
flatten_api() {
  awk '
    /^[^[:space:]].*\{[[:space:]]*$/ {
      header = $0
      sub(/[[:space:]]*\{[[:space:]]*$/, "", header)
      name = header
      sub(/ : .*$/, "", name)
      count = split(name, words, " ")
      owner = words[count]
      print owner "\t" header
      next
    }
    /^\}/ { owner = ""; next }
    owner != "" && NF > 0 {
      member = $0
      sub(/^[[:space:]]+/, "", member)
      print owner "\t  " member
    }
  ' "$1" | LC_ALL=C sort -u
}

git -C "$top" show "$baseline:$API" >"$work/baseline.api" 2>/dev/null || die "$API does not exist at $baseline"
git -C "$top" show "$current:$API" >"$work/current.api" 2>/dev/null || die "$API does not exist at $current"
flatten_api "$work/baseline.api" >"$work/baseline.flat"
flatten_api "$work/current.api" >"$work/current.flat"
[ -s "$work/baseline.flat" ] || die "the dump at $baseline has no declarations"
LC_ALL=C comm -23 "$work/baseline.flat" "$work/current.flat" >"$work/removed"
if [ -s "$work/removed" ]; then
  echo "declarations of $baseline that are missing or changed in $current:"
  tr '\t' ' ' <"$work/removed" | sed 's/^/  /'
  die "$(grep -c '' "$work/removed") declaration(s) break the API of $baseline; a break needs a new major version"
fi
echo "$API: every declaration of $baseline is unchanged at $current ($(grep -c '' "$work/current.flat") now, $(grep -c '' "$work/baseline.flat") before)"
