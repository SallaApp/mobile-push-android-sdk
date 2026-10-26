#!/usr/bin/env bash
# Tells .github/workflows/publish.yml whether Maven Central already serves a version of the library, from the five
# files a complete publication holds: the POM, the AAR, the Gradle module metadata, the sources jar and the javadoc jar.
# The Central Portal publishes a deployment's files together, so Maven Central serves all five or none, except for the
# minutes in which it is still syncing a new release.
#
#   publication-state.sh <repository-url> <group-path> <artifact> <version>
#
# Environment: GITHUB_OUTPUT (the step's output file). Maven Central is read anonymously.
# It writes published=true to GITHUB_OUTPUT when all five files exist, and published=false when none does or when
# Maven Central answers anything but 200 or 404 for one of them: the publish job then uploads, and the Central Portal
# refuses a deployment of a version it already holds, so a run never publishes a version twice. The GitHub Release job
# creates the release only on published=true. It fails when only some of the files exist (re-run the workflow once
# Maven Central serves them all) and when a request fails.
set -euo pipefail

if [ "$#" -ne 4 ]; then
  echo 'usage: publication-state.sh <repository-url> <group-path> <artifact> <version>' >&2
  exit 2
fi
repository="${1%/}"
group_path="$2"
artifact="$3"
version="$4"
[ -n "${GITHUB_OUTPUT:-}" ] || { echo "::error::publication-state.sh needs GITHUB_OUTPUT"; exit 1; }

# The HTTP status of one file, read anonymously; curl reads nothing from standard input.
status_of() {
  curl --silent --show-error --location --output /dev/null --write-out '%{http_code}' "$1" </dev/null
}

base="$repository/$group_path/$artifact/$version/$artifact-$version"
present=""
absent=""
unknown=""
for suffix in .pom .aar .module -sources.jar -javadoc.jar; do
  file="$artifact-$version$suffix"
  code="$(status_of "$base$suffix")" || { echo "::error::could not reach Maven Central for $base$suffix"; exit 1; }
  case "$code" in
    200) present="$present${present:+, }$file" ;;
    404) absent="$absent${absent:+, }$file" ;;
    *) unknown="$unknown${unknown:+, }$file (HTTP $code)" ;;
  esac
done

if [ -n "$unknown" ]; then
  echo "::warning::Maven Central gave unexpected answers for $unknown; uploading, and the Central Portal refuses a deployment of $version if it already holds that version."
  echo "published=false" >>"$GITHUB_OUTPUT"
elif [ -z "$absent" ]; then
  echo "$version is already on Maven Central ($present); nothing to do."
  echo "published=true" >>"$GITHUB_OUTPUT"
elif [ -z "$present" ]; then
  echo "$version is not on Maven Central yet."
  echo "published=false" >>"$GITHUB_OUTPUT"
else
  echo "::error::Maven Central serves only some files of $version (present: $present; missing: $absent). The Central Portal publishes a deployment's files together, so Maven Central is still syncing it: re-run this workflow once it serves every file."
  exit 1
fi
