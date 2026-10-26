# shellcheck shell=bash disable=SC2034 # the constants are read by the scripts that source this file
# Definitions shared by verify.sh, release-plan.sh and self-test.sh. Sourced, never run. The functions never exit:
# they print what is wrong to stderr and return 1, so they also work inside $(...), where errexit does not apply.

# Release versions: X.Y.Z or X.Y.Z-(alpha|beta|rc).N, without build metadata, and at most 32 characters, the longest
# sdk_version the SallaPush server accepts. A release tag is the version itself, without a "v" (docs/RELEASING.md).
readonly SDK_VERSION_RE='^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(-(alpha|beta|rc)\.(0|[1-9][0-9]*))?$'
readonly SDK_VERSION_MAX_LENGTH=32

# The last release published before Maven Central, which never held it or the releases before it. Until Maven Central
# holds a stable release, a new release keeps the public API of the highest release up to this one that is not below
# SDK_API_BASELINE_FLOOR (scripts/release-plan.sh).
readonly SDK_LAST_RELEASE_BEFORE_MAVEN_CENTRAL=1.0.4

# The first public release. The releases below it were never public, so no release keeps their API: none of them is
# an API baseline (scripts/release-plan.sh).
readonly SDK_API_BASELINE_FLOOR=1.1.0

# The size gate: SallaPush may add at most 5 MB (5,242,880 bytes) to an app. verify.sh passes it to
# scripts/size-report.sh, whose own default budget must be the same number, the one CONTRIBUTING.md states.
readonly SDK_SIZE_GATE_BYTES=5242880

# The largest file a commit may hold.
readonly SDK_MAX_FILE_BYTES=5242880

# The golden payloads the unit tests read: copies of the SallaPush server's wire contract, without the store's
# credential fields; the tests read only their data. The server sends these payloads, so a golden changes only together
# with that contract.
readonly SDK_GOLDEN_DIR=sallapush/src/test/resources/golden
readonly SDK_GOLDEN_NAMES="c3-android compat-android compat-android-typed-data compat-push-ref"

sdk_golden_sha256() {
  case "$1" in
    c3-android) echo 3507b03d5a695263c74aa715f197aa5c4fd574ef9a7aa3546d49db220c389e7a ;;
    compat-android) echo 0b50393714cbe55215d29cf793eba45157d1333986904a2b27f94d40c297d514 ;;
    compat-android-typed-data) echo f5ceff49c59b0cd8d4848a1b59e083a7aa17f4d4830c5e5907cc5aa51a6abeef ;;
    compat-push-ref) echo e60562f5e5c1d788351a9c88717410d41bc103817132526a558c2022b42610cc ;;
    *) return 1 ;;
  esac
}

# The sha256 of gradle/wrapper/gradle-wrapper.jar for a Gradle version, exactly as Gradle publishes it at
# https://services.gradle.org/distributions/gradle-<version>-wrapper.jar.sha256. Fails for a version that is not pinned
# here: the wrapper jar is the first code a build runs, and the only code that checks distributionSha256Sum.
sdk_gradle_wrapper_jar_sha256() {
  case "$1" in
    8.12) echo 2db75c40782f5e8ba1fc278a5574bab070adccb2d21ca5a6e5ed840888448046 ;;
    *) return 1 ;;
  esac
}

sdk_sha256_of() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum "$1" | cut -d ' ' -f 1
  elif command -v shasum >/dev/null 2>&1; then
    shasum -a 256 "$1" | cut -d ' ' -f 1
  else
    openssl dgst -sha256 -r "$1" | cut -d ' ' -f 1
  fi
}

# Prints the VERSION_NAME that gradle.properties declares: in the working tree of <top>, or at <commit> of the
# repository at <top>. It is the version a release tag must equal. Fails unless exactly one declaration exists and its
# value is a plain version string.
sdk_declared_version() {
  local top="$1" commit="${2:-}" content matches count version
  local pattern='^[[:space:]]*VERSION_NAME[[:space:]]*=[[:space:]]*(.*[^[:space:]])[[:space:]]*$'
  if [ -n "$commit" ]; then
    content="$(git -C "$top" show "$commit:gradle.properties" 2>/dev/null)" ||
      { echo "gradle.properties does not exist at $commit" >&2; return 1; }
  else
    [ -f "$top/gradle.properties" ] || { echo "gradle.properties does not exist" >&2; return 1; }
    content="$(cat "$top/gradle.properties")" || return 1
  fi
  matches="$(printf '%s\n' "$content" | tr -d '\r' | grep -E "$pattern" || true)"
  count="$(printf '%s' "$matches" | grep -c '' || true)"
  [ "$count" -eq 1 ] ||
    { echo "gradle.properties must declare VERSION_NAME exactly once (found $count matching lines)" >&2; return 1; }
  version="$(printf '%s\n' "$matches" | sed -E "s/$pattern/\\1/")"
  case "$version" in
    *[!0-9A-Za-z.+-]* | '')
      echo "gradle.properties declares VERSION_NAME '$version', which is not a plain version string" >&2
      return 1
      ;;
  esac
  printf '%s\n' "$version"
}

# Fails unless <version> is a release version (SDK_VERSION_RE) of at most SDK_VERSION_MAX_LENGTH characters.
sdk_check_version() {
  local version="$1"
  [[ "$version" =~ $SDK_VERSION_RE ]] ||
    { echo "$version is not a release version: X.Y.Z or X.Y.Z-(alpha|beta|rc).N" >&2; return 1; }
  [ "${#version}" -le "$SDK_VERSION_MAX_LENGTH" ] ||
    { echo "$version is longer than $SDK_VERSION_MAX_LENGTH characters, the longest sdk_version the SallaPush server accepts" >&2; return 1; }
}

# Prints the "## [<version>]" headings of a CHANGELOG read from stdin: the heading alone, or followed by a space, as in
# "## [1.2.0] - 2026-10-04". It reads to the end, so a writer never sees SIGPIPE under pipefail.
sdk_changelog_headings() {
  tr -d '\r' | awk -v heading="## [$1]" '
    index($0, heading) == 1 && (length($0) == length(heading) || substr($0, length(heading) + 1, 1) == " ")
  '
}

# Fails unless the CHANGELOG read from stdin has exactly one "## [<version>]" heading; prints that heading.
sdk_changelog_heading() {
  local headings count
  headings="$(sdk_changelog_headings "$1")" || return 1
  count="$(printf '%s' "$headings" | grep -c '' || true)"
  [ "$count" -eq 1 ] || {
    echo "CHANGELOG.md must have one '## [$1]' heading for the version, found $count" >&2
    return 1
  }
  printf '%s\n' "$headings"
}

# Prints the default budget of a size-report.sh: the N of its one line budget="${SIZE_REPORT_BUDGET_BYTES:-N}". Fails
# unless that line exists exactly once and N is SDK_SIZE_GATE_BYTES.
sdk_size_budget() {
  local script="$1" budgets
  [ -f "$script" ] || { echo "$script does not exist" >&2; return 1; }
  budgets="$(sed -nE 's/^budget="\$\{SIZE_REPORT_BUDGET_BYTES:-([0-9]+)\}"$/\1/p' "$script")"
  [ "$(printf '%s' "$budgets" | grep -c '')" -eq 1 ] || {
    echo "$script must set its default budget once, as budget=\"\${SIZE_REPORT_BUDGET_BYTES:-<bytes>}\"" >&2
    return 1
  }
  [ "$budgets" = "$SDK_SIZE_GATE_BYTES" ] || {
    echo "$script sets a default budget of $budgets bytes; the size gate is $SDK_SIZE_GATE_BYTES bytes (5 MB)" >&2
    return 1
  }
  printf '%s\n' "$budgets"
}

# The environment of an Android build: exports ANDROID_HOME when it is unset (from ANDROID_SDK_ROOT, else the Android
# Studio default on macOS), and requires JDK 17 or newer on PATH.
sdk_android_environment() {
  local java_version
  if [ -z "${ANDROID_HOME:-}" ]; then
    if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
      export ANDROID_HOME="$ANDROID_SDK_ROOT"
    elif [ -d "$HOME/Library/Android/sdk" ]; then
      export ANDROID_HOME="$HOME/Library/Android/sdk"
    else
      echo "set ANDROID_HOME to the Android SDK" >&2
      return 1
    fi
  fi
  command -v java >/dev/null 2>&1 || { echo "java is not on PATH; JDK 17 is required" >&2; return 1; }
  java_version="$(java -version 2>&1 | awk -F '"' '/version/ && !found { print $2; found = 1 }')"
  java_version="${java_version#1.}"
  java_version="${java_version%%[.+-]*}"
  case "$java_version" in
    '' | *[!0-9]*)
      echo "cannot read the Java version" >&2
      return 1
      ;;
  esac
  [ "$java_version" -ge 17 ] || { echo "Java $java_version found; the Android build needs JDK 17 or newer" >&2; return 1; }
}
