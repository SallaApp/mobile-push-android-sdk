#!/usr/bin/env bash
# Prints the CHANGELOG.md entry of a version without its heading: the notes of the version's GitHub Release
# (.github/workflows/publish.yml).
#
#   release-notes.sh <version> [<changelog>]    the changelog defaults to CHANGELOG.md in the current folder
#
# It fails unless the changelog has exactly one "## [<version>]" heading, alone or followed by a space as in
# "## [1.2.0] - 2026-10-20", and some text under it before the next "## " heading.
set -euo pipefail

if [ "$#" -lt 1 ] || [ "$#" -gt 2 ]; then
  echo 'usage: release-notes.sh <version> [<changelog>]' >&2
  exit 2
fi
version="$1"
changelog="${2:-CHANGELOG.md}"
[ -f "$changelog" ] || { echo "::error::$changelog does not exist" >&2; exit 1; }

entry="$(tr -d '\r' <"$changelog" | awk -v heading="## [$version]" '
  index($0, heading) == 1 && (length($0) == length(heading) || substr($0, length(heading) + 1, 1) == " ") {
    found++
    inside = 1
    next
  }
  /^## / { inside = 0 }
  inside { print }
  END { if (found != 1) exit 3 }
')" || { echo "::error::$changelog must have one '## [$version]' heading" >&2; exit 1; }
# Without the blank lines around it; $(...) already drops the trailing ones.
entry="$(printf '%s\n' "$entry" | sed '/./,$!d')"
[ -n "$entry" ] || { echo "::error::the CHANGELOG.md entry of $version is empty" >&2; exit 1; }
printf '%s\n' "$entry"
