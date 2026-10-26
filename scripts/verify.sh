#!/usr/bin/env bash
# Runs the whole verification of the SallaPush Android SDK. Every pull request and every push to master runs it in CI
# (.github/workflows/ci.yml, one step per step below), and a release tag runs it again before anything is published
# (publish.yml). Run it before you push: the same script gives the same result on your computer.
#
#   scripts/verify.sh [step...]
#
# Without steps it runs every step, in this order, and stops at the first failure:
#   version    gradle.properties declares a release version, and CHANGELOG.md has its entry
#   goldens    the golden payloads the tests read are the SallaPush server's contract, unchanged
#   catalogue  the log codes are the shared log catalogue's (scripts/check-log-catalogue.sh)
#   links      every relative Markdown link resolves inside this repository
#   tree       what a commit takes: no secret, build output, symbolic link, nested repository or file over 5 MB; no
#              build file or script that reaches outside the repository; no properties file that sets a path on one
#              computer (org.gradle.java.home, sdk.dir); no text that refers to a design document outside this
#              repository; the files a build and a release need; every workflow action pinned to a commit
#   wrapper    the Gradle wrapper downloads an official, checksummed distribution, and its jar is Gradle's own
#   build      ./gradlew check :sallapush:apiCheck :sallapush:publishToMavenLocal :sample:assembleRelease: the unit tests
#              (Robolectric), Android lint, the public API check and the log catalogue coverage, the AAR in your Maven
#              repository, and the sample minified with R8
#   consumer   ./gradlew -p consumer-check assembleDebug: the published AAR resolved and used as README.md tells an app
#   pom        the published POM as Maven Central takes it: its coordinates, name, description, URL, licence,
#              developer and source repository, no dependency but the designed ones, and the sources and javadoc jars
#   gates      the crash-safety gates: no banned construct in the library's code, no default argument in its public
#              API, no Kotlin null-check intrinsic in its release classes
#   snippets   README.md's code equals the code that builds (scripts/check-readme-snippets.sh, then its self-test)
#   size       what SallaPush adds to an app, gated at 5 MB (scripts/size-report.sh)
#
# Environment:
#   VERIFY_LOG_DIR  when set, receives <step>.log per step and summary.tsv
#   ANDROID_HOME    the Android SDK (default on macOS: ~/Library/Android/sdk when it exists); JDK 17 or newer on PATH
set -euo pipefail

readonly STEPS="version goldens catalogue links tree wrapper build consumer pom gates snippets size"

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
top="$(cd "$here/.." && pwd -P)"
# shellcheck source-path=SCRIPTDIR
# shellcheck source=common.sh
. "$here/common.sh"

work="$(mktemp -d "${TMPDIR:-/tmp}/sallapush-verify.XXXXXX")"
trap 'rm -rf "$work"' EXIT
trap 'exit 130' INT TERM

fail() {
  printf 'verify.sh: %s\n' "$*" >&2
  exit 1
}

usage() {
  echo "usage: scripts/verify.sh [step...]    steps: $STEPS" >&2
  exit 2
}

problems=0

report() {
  problems=$((problems + 1))
  printf '%s\n' "$*"
}

# The files a commit takes (tracked, and untracked but not ignored), NUL-separated, into <listing>.
list_files() {
  git -C "$top" ls-files -z --cached --others --exclude-standard >"$1" ||
    fail "cannot list the repository's files (git ls-files failed); run the verification in a clone of the repository"
}

# ---------------------------------------------------------------------------------------------------
# Static checks

step_version() {
  local version heading
  version="$(sdk_declared_version "$top")" || exit 1
  sdk_check_version "$version" || exit 1
  [ -f "$top/CHANGELOG.md" ] || fail "CHANGELOG.md does not exist"
  heading="$(sdk_changelog_heading "$version" <"$top/CHANGELOG.md")" || exit 1
  echo "gradle.properties declares $version; CHANGELOG.md has its entry: $heading"
}

step_goldens() {
  local name file expected actual
  for name in $SDK_GOLDEN_NAMES; do
    file="$SDK_GOLDEN_DIR/$name.json"
    expected="$(sdk_golden_sha256 "$name")"
    if [ -L "$top/$file" ]; then
      report "$file is a symbolic link; it must be a regular file, a copy of the contract's payload"
    elif [ ! -f "$top/$file" ]; then
      report "$file is missing"
    else
      actual="$(sdk_sha256_of "$top/$file")"
      [ "$actual" = "$expected" ] ||
        report "$file has sha256 $actual, not the contract's $expected: the golden payloads are the SallaPush server's wire contract; change one only together with that contract, then its sha256 in scripts/common.sh"
    fi
  done
  [ "$problems" -eq 0 ] || fail "$problems golden payload problem(s)"
  echo "the golden payloads ($SDK_GOLDEN_NAMES) are the contract's"
}

step_catalogue() { "$here/check-log-catalogue.sh"; }

# Every relative Markdown link must resolve, and stay inside the repository.
step_links() {
  list_files "$work/files"
  # grep exits 1 when there is no Markdown file, which is no failure; a failed git stopped above.
  tr '\0' '\n' <"$work/files" | grep -E '\.md$' >"$work/markdown" || true
  [ -s "$work/markdown" ] || fail "no Markdown file in the repository; README.md, CHANGELOG.md and CONTRIBUTING.md are required"
  python3 - "$top" "$work/markdown" <<'PY'
import os, re, sys
from urllib.parse import unquote

top, listing = os.path.realpath(sys.argv[1]), sys.argv[2]
inline = re.compile(r'!?\[[^\]]*\]\(\s*<?([^)\s>]+)>?(?:\s+(?:"[^"]*"|\'[^\']*\'))?\s*\)')
reference = re.compile(r'^\s{0,3}\[[^\]]+\]:\s*<?(\S+?)>?(?:\s+.*)?$')
fence = re.compile(r'^\s{0,3}(```|~~~)')
problems = checked = 0
with open(listing, encoding="utf-8") as names:
    files = [line.strip() for line in names if line.strip()]
for name in files:
    path = os.path.join(top, name)
    if not os.path.isfile(path):
        continue
    in_fence = False
    with open(path, encoding="utf-8") as handle:
        for number, line in enumerate(handle, 1):
            if fence.match(line):
                in_fence = not in_fence
                continue
            if in_fence:
                continue
            text = re.sub(r'`[^`]*`', '', line)
            targets = inline.findall(text)
            match = reference.match(text)
            if match:
                targets.append(match.group(1))
            for target in targets:
                if re.match(r'^[A-Za-z][A-Za-z0-9+.-]*:', target) or target.startswith('#'):
                    continue
                checked += 1
                if target.startswith('/'):
                    print(f"{name}:{number}: {target} is an absolute link; use a path relative to the file")
                    problems += 1
                    continue
                relative = unquote(target.split('#', 1)[0].split('?', 1)[0])
                if not relative:
                    continue
                resolved = os.path.realpath(os.path.join(os.path.dirname(path), relative))
                if os.path.commonpath([resolved, top]) != top:
                    print(f"{name}:{number}: {target} points outside the repository")
                    problems += 1
                elif not os.path.exists(resolved):
                    print(f"{name}:{number}: {target} does not exist")
                    problems += 1
print(f"{len(files)} Markdown file(s), {checked} relative link(s), {problems} problem(s)")
sys.exit(1 if problems else 0)
PY
}

# File names that hold credentials or signing material (matched case-insensitively), and directories of build output.
check_path() {
  local path="$1" dir component
  local -a components
  shopt -s nocasematch
  case "${path##*/}" in
    local.properties | keystore.properties | secrets.properties | google-services.json | googleservice-info.plist | \
      id_rsa | id_dsa | id_ecdsa | id_ed25519 | id_ecdsa_sk | id_ed25519_sk | .netrc | _netrc | .git-credentials | \
      *.jks | *.keystore | *.p8 | *.p12 | *.pfx | *.pem | *.key | *.ppk | *.mobileprovision | .env*)
      report "$path: a file of credentials or signing material; never commit it"
      ;;
  esac
  shopt -u nocasematch
  dir="${path%/*}"
  [ "$dir" != "$path" ] || return 0
  IFS=/ read -r -a components <<<"$dir"
  for component in ${components[@]+"${components[@]}"}; do
    case "$component" in
      build | .gradle | .kotlin | .cxx | .externalNativeBuild | captures)
        report "$path: build output ($component/); never commit it"
        return 0
        ;;
    esac
  done
}

# Properties keys that hold a path on one computer: the JDK that runs Gradle (org.gradle.java.home), which breaks the
# build wherever that path does not exist, CI included, and the Android SDK (sdk.dir, the ignored local.properties'
# key). The builds take them from JAVA_HOME or PATH, and ANDROID_HOME. As Java reads a properties file, a key is
# case-sensitive and ends at its first blank, = or :; a file name matches case-insensitively, as in check_path. The
# report never shows the value.
check_properties() {
  local path="$1" properties=false status=0 hit key
  shopt -s nocasematch
  case "${path##*/}" in
    *.properties) properties=true ;;
  esac
  shopt -u nocasematch
  $properties || return 0
  grep -anE '^[[:space:]]*(org\.gradle\.java\.home|sdk\.dir)([[:space:]=:]|$)' -- "$top/$path" >"$work/machine-paths" ||
    status=$?
  # grep exits 1 when no line matches, which is no failure.
  [ "$status" -le 1 ] || fail "cannot read $path"
  while IFS= read -r hit; do
    key="${hit#*:}"
    key="${key#"${key%%[![:space:]]*}"}"
    report "$path:${hit%%:*}: sets ${key%%[[:space:]=:]*}, a path on one computer; never commit it (the builds take the JDK from JAVA_HOME or PATH, the Android SDK from ANDROID_HOME)"
  done <"$work/machine-paths"
}

# Reads "<base depth><TAB><path>" lines, and prints path:line: token for every relative reference in those files that
# climbs above the repository's root, from a file whose directory is <base depth> folders deep. A path segment holding an
# expansion ($VAR, $(cmd), ${var}, ~) stands for the base directory, which is how $(dirname "$0")/.. is meant.
escaping_references() {
  awk -F '\t' -v top="$top" '
    function check(token, file, number, base_depth,    n, i, seg, depth, lowest) {
      if (index(token, "..") == 0 || substr(token, 1, 1) == "/") return
      n = split(token, seg, "/")
      depth = 0; lowest = 0
      for (i = 1; i <= n; i++) {
        if (seg[i] == "" || seg[i] == ".") continue
        if (seg[i] == "..") { depth--; if (depth < lowest) lowest = depth }
        else if (seg[i] ~ /[$(){}~]/) depth = 0
        else depth++
      }
      if (-lowest > base_depth) printf "%s:%d: %s\n", file, number, token
    }
    {
      base_depth = $1 + 0
      file = $2
      source = top "/" file
      number = 0
      while ((status = (getline line < source)) > 0) {
        number++
        while (match(line, "[A-Za-z0-9_.@+~/${}()-]+")) {
          check(substr(line, RSTART, RLENGTH), file, number, base_depth)
          line = substr(line, RSTART + RLENGTH)
        }
      }
      if (status < 0) { printf "cannot read %s\n", file > "/dev/stderr"; failed = 1 }
      close(source)
    }
    END { exit failed }
  ' "$1"
}

readonly REQUIRED_FILES="settings.gradle.kts build.gradle.kts gradle.properties gradlew gradle/wrapper/gradle-wrapper.properties
gradle/wrapper/gradle-wrapper.jar sallapush/build.gradle.kts sallapush/api/sallapush.api README.md CHANGELOG.md CONTRIBUTING.md
LICENSE .github/workflows/ci.yml .github/workflows/publish.yml .github/scripts/publication-state.sh
.github/scripts/release-notes.sh scripts/common.sh scripts/log-catalogue.txt"
readonly EXECUTABLE_FILES="gradlew scripts/verify.sh scripts/check-log-catalogue.sh scripts/release-plan.sh scripts/api-compat.sh
scripts/self-test.sh scripts/check-readme-snippets.sh scripts/size-report.sh"

step_tree() {
  local entry mode path base depth slashes hit file
  list_files "$work/files"
  git -C "$top" ls-files -z --stage >"$work/staged" ||
    fail "cannot list the staged files (git ls-files failed)"

  # Symbolic links and submodules: staged entries by their mode (a checkout without symbolic links turns a staged
  # link into a plain file), and every file a commit takes by what the working tree holds.
  while IFS= read -r -d '' entry; do
    mode="${entry%% *}"
    path="${entry#*$'\t'}"
    case "$mode" in
      120000) report "$path is a symbolic link (to $(git -C "$top" cat-file blob ":$path")); commit regular files only" ;;
      160000) report "$path is a submodule; the SDK repository holds no other repository" ;;
    esac
  done <"$work/staged"

  : >"$work/regular"
  : >"$work/configs"
  while IFS= read -r -d '' path; do
    case "$path" in
      */)
        report "$path is a nested git repository; a commit would record it as a submodule"
        continue
        ;;
    esac
    check_path "$path"
    if [ -L "$top/$path" ]; then
      [ "$(git -C "$top" --literal-pathspecs ls-files --stage -- "$path" | cut -d ' ' -f 1)" = 120000 ] ||
        report "$path is a symbolic link (to $(readlink "$top/$path")); commit regular files only"
      continue
    fi
    [ -f "$top/$path" ] || continue
    printf '%s\0' "$path" >>"$work/regular"
    check_properties "$path"

    # Build files, scripts and tool configs must not refer to a path outside the repository. A reference resolves
    # against the file's directory, and a workflow's against the workspace's root, where its steps run.
    case "${path##*/}" in
      Makefile | *.mk | *.gradle | *.gradle.kts | *.properties | *.pro | *.toml | *.sh | *.yml | *.yaml) ;;
      *) continue ;;
    esac
    case "$path" in
      .github/workflows/* | .github/actions/*) base="" ;;
      */*) base="${path%/*}" ;;
      *) base="" ;;
    esac
    if [ -z "$base" ]; then
      depth=0
    else
      slashes="${base//[!\/]/}"
      depth=$((${#slashes} + 1))
    fi
    printf '%s\t%s\n' "$depth" "$path" >>"$work/configs"
  done <"$work/files"
  escaping_references "$work/configs" >"$work/escapes" || fail "cannot read the build files and scripts"
  while IFS= read -r hit; do
    report "$hit resolves outside the repository"
  done <"$work/escapes"

  # A file over 5 MB, and a private key or a GitHub token in any file a commit takes. The report never shows the secret.
  # Text files must also stand on their own: no line refers to a design document readers of this repository can't
  # open, by its name, its section signs, its route labels or its markers.
  python3 - "$top" "$work/regular" "$SDK_MAX_FILE_BYTES" >"$work/secrets" <<'PY' || fail "cannot scan the files for secrets"
import os, re, sys

# Each pattern is written so that this file's own text never matches it.
findings = (
    (re.compile(rb"-----BEGIN [A-Z0-9 ]{0,40}PRIVATE KEY(?: BLOCK)?-----"), "a private key (PEM, OpenSSH, PKCS#8 or PGP)"),
    (re.compile(rb"---- BEGIN SSH2 ENCRYPTED " + rb"PRIVATE KEY ----"), "a private key (SSH2)"),
    (re.compile(rb"PuTTY-User-Key-File-[0-9]+: "), "a private key (PuTTY)"),
    (re.compile(rb"(?<![A-Za-z0-9_])gh[pousr]_[A-Za-z0-9]{36}(?![A-Za-z0-9_])"), "a GitHub token"),
    (re.compile(rb"(?<![A-Za-z0-9_])github_pat_[A-Za-z0-9]{22}_[A-Za-z0-9]{59}(?![A-Za-z0-9_])"), "a GitHub token"),
)
references = re.compile(rb"\bDES" rb"IGN\b|\xc2\xa7|\bC1\.[1-9]\b|\bFRO" rb"ZEN\b")
top, limit = sys.argv[1], int(sys.argv[3])
with open(sys.argv[2], "rb") as listing:
    paths = [path for path in listing.read().split(b"\0") if path]
for path in paths:
    name = path.decode("utf-8", "replace")
    with open(os.path.join(top.encode(), path), "rb") as handle:
        data = handle.read()
    if len(data) > limit:
        print(f"{name}: {len(data)} bytes, over the {limit}-byte (5 MB) limit")
    for pattern, what in findings:
        if pattern.search(data):
            print(f"{name}: holds {what}; remove it, and revoke it if it was ever pushed")
            break
    if b"\0" in data[:8192]:
        continue
    for number, line in enumerate(data.splitlines(), 1):
        found = references.search(line)
        if found:
            shown = found.group().decode("utf-8", "replace")
            print(f"{name}:{number}: refers to a design document outside this repository ({shown}); say it in plain words")
PY
  while IFS= read -r hit; do
    report "$hit"
  done <"$work/secrets"

  # grep reads a file here, never a pipe: grep -q stops reading early, and under pipefail the writer's SIGPIPE would fail
  # the pipeline although the file was found.
  tr '\0' '\n' <"$work/files" >"$work/file-list"
  for file in $REQUIRED_FILES $EXECUTABLE_FILES; do
    if ! grep -qxF -- "$file" "$work/file-list"; then
      report "$file is missing from what a commit takes (absent, or ignored by .gitignore)"
    elif [ -L "$top/$file" ] || [ ! -f "$top/$file" ]; then
      report "$file must be a regular file"
    fi
  done
  for file in $EXECUTABLE_FILES; do
    [ -f "$top/$file" ] || continue
    [ -x "$top/$file" ] || report "$file is not executable (chmod +x $file)"
    mode="$(git -C "$top" ls-files --stage -- "$file" | cut -d ' ' -f 1)"
    case "$mode" in
      '' | 100755) ;;
      *) report "$file is staged with mode $mode; stage it as executable (git update-index --chmod=+x $file)" ;;
    esac
  done

  # Workflows: every action pinned to a full commit id, except this repository's reusable workflows; and no
  # pull_request_target workflow that checks out code, since it runs with this repository's secrets and write token for
  # anyone's pull request.
  tr '\0' '\n' <"$work/files" | grep -E '^\.github/workflows/[^/]+\.ya?ml$' >"$work/workflows" || true
  python3 - "$top" "$work/workflows" >"$work/workflow-problems" <<'PY' || fail "cannot read the workflows"
import os, re, sys

top, listing = sys.argv[1], sys.argv[2]
uses = re.compile(r"^\s*(?:-\s+)?uses:\s*(['\"]?)([^\s'\"#]+)\1\s*(?:#.*)?$")
pinned = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+(?:/[^@\s]+)?@[0-9a-f]{40}$")
local = re.compile(r"^\./\.github/workflows/[A-Za-z0-9_.-]+\.ya?ml$")
with open(listing, encoding="utf-8") as names:
    files = [line.strip() for line in names if line.strip()]
for name in files:
    with open(os.path.join(top, name), encoding="utf-8") as handle:
        lines = handle.read().splitlines()
    checkout = target = False
    for number, line in enumerate(lines, 1):
        match = uses.match(line)
        if match:
            reference = match.group(2)
            if not (pinned.match(reference) or local.match(reference)):
                print(f"{name}:{number}: uses {reference}; pin the action to a full commit id (owner/repo@<40 hex> # vX.Y.Z)")
            if reference.startswith("actions/checkout@"):
                checkout = True
        if re.match(r"^\s*pull_request_target\s*:", line) or re.match(r"^on:\s*\[?.*\bpull_request_target\b", line):
            target = True
    if target and checkout:
        print(f"{name}: a pull_request_target workflow checks out code; it runs with this repository's secrets for "
              "any pull request, so it must never run the pull request's code")
PY
  while IFS= read -r hit; do
    report "$hit"
  done <"$work/workflow-problems"

  [ "$problems" -eq 0 ] || fail "$problems problem(s) in what a commit takes"
  echo "$(tr -cd '\0' <"$work/files" | wc -c | tr -d ' ') files: no secret, build output, symbolic link, nested repository or file over 5 MB; nothing reaches outside the repository; no properties file sets a path on one computer; no reference to an outside design document; every required file present; every workflow action pinned"
}

# The one value of a key in a Java properties file, with the \: and \= escapes undone; fails unless the key is set
# exactly once.
wrapper_property() {
  local file="$1" key="$2" values
  values="$(tr -d '\r' <"$file" | sed -nE "s/^[[:space:]]*${key}[[:space:]]*[=:][[:space:]]*(.*[^[:space:]])[[:space:]]*\$/\\1/p")"
  [ "$(printf '%s' "$values" | grep -c '')" -eq 1 ] || fail "${file#"$top"/} must set $key exactly once"
  printf '%s\n' "$values" | sed -e 's/\\:/:/g' -e 's/\\=/=/g'
}

# The Gradle wrapper is the first code every build runs, the publish job's included, which holds a packages: write
# token. CI's setup-gradle validates the jar too; this step makes every local run do the same, and keeps the
# distribution pinned.
step_wrapper() {
  local properties="$top/gradle/wrapper/gradle-wrapper.properties" jar="$top/gradle/wrapper/gradle-wrapper.jar"
  local url sum validate version distribution expected actual
  local url_re='^https://services\.gradle\.org/distributions/gradle-([0-9]+(\.[0-9]+)+)-(bin|all)\.zip$'
  [ -f "$properties" ] || fail "gradle/wrapper/gradle-wrapper.properties does not exist"
  [ -f "$jar" ] || fail "gradle/wrapper/gradle-wrapper.jar does not exist"
  url="$(wrapper_property "$properties" distributionUrl)"
  sum="$(wrapper_property "$properties" distributionSha256Sum)"
  validate="$(wrapper_property "$properties" validateDistributionUrl)"
  [[ "$url" =~ $url_re ]] ||
    fail "distributionUrl is $url; the wrapper must download an official distribution from https://services.gradle.org/distributions/"
  version="${BASH_REMATCH[1]}"
  distribution="gradle-$version-${BASH_REMATCH[3]}.zip"
  [[ "$sum" =~ ^[0-9a-f]{64}$ ]] ||
    fail "distributionSha256Sum must be the sha256 of $distribution (64 lowercase hex), from https://services.gradle.org/distributions/$distribution.sha256"
  [ "$validate" = true ] || fail "validateDistributionUrl must be true"
  expected="$(sdk_gradle_wrapper_jar_sha256 "$version")" ||
    fail "the wrapper jar of Gradle $version is not pinned: compare gradle-wrapper.jar with https://services.gradle.org/distributions/gradle-$version-wrapper.jar.sha256, then add it to sdk_gradle_wrapper_jar_sha256 in scripts/common.sh"
  actual="$(sdk_sha256_of "$jar")"
  [ "$actual" = "$expected" ] ||
    fail "gradle-wrapper.jar (sha256 $actual) is not Gradle $version's wrapper ($expected); run ./gradlew wrapper once more, so that the jar matches the distribution, or restore Gradle's jar"
  echo "Gradle $version from services.gradle.org, distributionSha256Sum $sum, gradle-wrapper.jar is Gradle's own ($actual)"
}

# ---------------------------------------------------------------------------------------------------
# Builds

android_environment() { sdk_android_environment || exit 1; }

run_gradle() {
  android_environment
  (cd "$top" && ./gradlew --no-daemon --stacktrace "$@")
}

step_build() {
  local aar="$top/sallapush/build/outputs/aar/sallapush-release.aar"
  run_gradle check :sallapush:apiCheck :sallapush:publishToMavenLocal :sample:assembleRelease
  [ -f "$aar" ] || fail "the build made no $aar"
  # Without this consumer rule, AGP 9's R8 removes the constructor of WorkManager's Room database, and minified apps
  # crash at launch.
  unzip -p "$aar" proguard.txt | tr -d '\r' | tr '\n' ' ' |
    grep -qE 'keep class \* extends androidx\.room\.RoomDatabase \{ *<init>\(\); *\}' ||
    fail "$aar ships no consumer rule that keeps the constructor of WorkManager's Room database"
}
step_consumer() { run_gradle -p consumer-check assembleDebug; }

# The publication in the local Maven repository, as Maven Central takes it: the POM's coordinates, the metadata Maven
# Central requires (name, description, URL, licence, developer, source repository), no dependency but the designed ones,
# and the sources jar and a javadoc jar that holds HTML pages beside it.
step_pom() {
  local version group pom
  version="$(sdk_declared_version "$top")" || exit 1
  group="$(sed -nE 's/^[[:space:]]*GROUP[[:space:]]*=[[:space:]]*([^[:space:]]+)[[:space:]]*$/\1/p' "$top/gradle.properties" | tr -d '\r')"
  [ -n "$group" ] || fail "gradle.properties has no GROUP"
  pom="$HOME/.m2/repository/$(printf '%s' "$group" | tr . /)/mobile-push/$version/mobile-push-$version.pom"
  [ -f "$pom" ] || fail "$pom does not exist; run the build step (publishToMavenLocal) first"
  python3 - "$pom" "$group" mobile-push "$version" <<'PY'
import os
import sys
import xml.etree.ElementTree as ElementTree
import zipfile

pom, group, artifact, version = sys.argv[1:5]
ns = {"m": "http://maven.apache.org/POM/4.0.0"}
root = ElementTree.parse(pom).getroot()

def text(element, tag):
    found = element.find(f"m:{tag}", ns)
    return found.text.strip() if found is not None and found.text else None

problems = []
for tag, expected in (("groupId", group), ("artifactId", artifact), ("version", version)):
    if text(root, tag) != expected:
        problems.append(f"{tag} is {text(root, tag)}, expected {expected}")
for tag in ("name", "description", "url"):
    if not text(root, tag):
        problems.append(f"no <{tag}>; Maven Central requires the project's name, description and URL")
licenses = [(text(entry, "name"), text(entry, "url")) for entry in root.findall("m:licenses/m:license", ns)]
if licenses != [("MIT License", "https://opensource.org/license/mit")]:
    problems.append(f"the licenses are {licenses}; the POM declares the MIT License (https://opensource.org/license/mit) of LICENSE, and only it")
if not any(text(entry, "name") for entry in root.findall("m:developers/m:developer", ns)):
    problems.append("no <developer> with a name; Maven Central requires one")
scm = root.find("m:scm", ns)
if scm is None or not text(scm, "url") or not text(scm, "connection"):
    problems.append("no <scm> with a url and a connection; Maven Central requires the source repository")
base = pom[:-len(".pom")]
for classifier in ("sources", "javadoc"):
    jar = f"{base}-{classifier}.jar"
    if not os.path.isfile(jar):
        problems.append(f"no {os.path.basename(jar)} beside the POM; Maven Central requires the {classifier} jar")
        continue
    try:
        with zipfile.ZipFile(jar) as archive:
            names = archive.namelist()
    except zipfile.BadZipFile:
        problems.append(f"{os.path.basename(jar)} is not a jar")
        continue
    if classifier == "javadoc" and not any(name.endswith(".html") for name in names):
        problems.append(f"{os.path.basename(jar)} holds no HTML page: the API documentation is missing")
allowed = {("com.google.firebase", "firebase-messaging"), ("androidx.core", "core"), ("androidx.work", "work-runtime")}
messaging_scope = None
for dependency in root.findall("m:dependencies/m:dependency", ns):
    coordinate = (text(dependency, "groupId"), text(dependency, "artifactId"))
    scope = text(dependency, "scope") or "compile"
    if coordinate == ("com.google.firebase", "firebase-messaging"):
        messaging_scope = scope
    elif coordinate not in allowed and not (coordinate[0] == "org.jetbrains.kotlin" and (coordinate[1] or "").startswith("kotlin-stdlib")):
        problems.append(f"unexpected dependency {coordinate[0]}:{coordinate[1]} ({scope}); the SDK ships no third-party libraries")
if messaging_scope != "compile":
    problems.append(f"firebase-messaging must be a compile-scope (api) dependency, found {messaging_scope}")
for problem in problems:
    print(f"{pom}: {problem}")
if problems:
    sys.exit(1)
print(f"{group}:{artifact}:{version}: coordinates, Maven Central metadata, dependencies, sources and javadoc jars OK")
PY
}

# The crash-safety gates: the constructs that can throw into an app, banned from the library's code; default arguments
# in the public API, whose generated stubs run before the null guard of a Java caller; and the Kotlin null-check
# intrinsics, which must not exist in the release classes, so that a Java null reaches the guarded body of every entry.
step_gates() {
  local sources="$top/sallapush/src/main" api="$top/sallapush/api/sallapush.api"
  local classes="$top/sallapush/build/tmp/kotlin-classes/release" status=0
  android_environment
  [ -d "$sources" ] || fail "sallapush/src/main does not exist"
  find "$sources" -type f \( -name '*.kt' -o -name '*.java' \) -print0 >"$work/sources"
  : >"$work/banned"
  if [ -s "$work/sources" ]; then
    xargs -0 grep -nHE \
      -e '!!' \
      -e '(^|[^A-Za-z0-9_])lateinit([^A-Za-z0-9_]|$)' \
      -e '(^|[^A-Za-z0-9_.])(error|require|check|requireNotNull|checkNotNull|TODO)\(' \
      -e '(^|[^A-Za-z0-9_])(runBlocking|GlobalScope)([^A-Za-z0-9_]|$)' \
      -e '@JvmOverloads' \
      <"$work/sources" | grep -vE '^[^:]+:[0-9]+:[[:space:]]*(//|\*|/\*)' >"$work/banned" || true
  fi
  if [ -s "$work/banned" ]; then
    cat "$work/banned"
    echo "banned constructs in sallapush/src/main: !!, lateinit, error(), require(), check(), requireNotNull(), checkNotNull(), TODO(), runBlocking, GlobalScope, @JvmOverloads"
    status=1
  fi

  [ -f "$api" ] || fail "sallapush/api/sallapush.api does not exist"
  # shellcheck disable=SC2016 # a literal $default: the suffix of Kotlin default-argument stubs
  if grep -nE '\$default ' "$api" | grep -v 'copy\$default'; then
    echo "public functions with default arguments (their stubs run before the null guard)"
    status=1
  fi

  [ -d "$classes" ] || fail "no release classes at ${classes#"$top"/}; run the build step first"
  find "$classes" -type f -name '*.class' -print0 >"$work/classes"
  [ -s "$work/classes" ] || fail "no .class files under ${classes#"$top"/}"
  xargs -0 javap -c -p <"$work/classes" >"$work/javap.txt"
  awk '
    /^(Compiled from|[a-z ]*(class|interface|enum) )/ { owner = $0 }
    /checkNotNullParameter|checkParameterIsNotNull|checkNotNullExpressionValue/ { print owner ": " $0; found = 1 }
    END { exit found ? 1 : 0 }
  ' "$work/javap.txt" || {
    echo "Kotlin null-check intrinsics in the release classes; the library's -Xno-param-assertions, -Xno-call-assertions and -Xno-receiver-assertions compiler flags are missing"
    status=1
  }
  [ "$status" -eq 0 ] || exit 1
  echo "grep gate, default-argument gate and bytecode gate: clean ($(tr -cd '\0' <"$work/classes" | wc -c | tr -d ' ') classes)"
}

# The README check, then its self-test: proof, on scratch copies, that the checker still catches every kind of drift
# (python3 only, seconds).
step_snippets() {
  "$here/check-readme-snippets.sh"
  "$here/check-readme-snippets.sh" --self-test
}

# The gate is 5 MB, passed to the size script, so a SIZE_REPORT_BUDGET_BYTES left in the caller's environment can
# never change it; the script's own default must be the same number, the one CONTRIBUTING.md states.
step_size() {
  android_environment
  sdk_size_budget "$here/size-report.sh" >/dev/null || exit 1
  if [ -n "${SIZE_REPORT_BUDGET_BYTES+set}" ] && [ "$SIZE_REPORT_BUDGET_BYTES" != "$SDK_SIZE_GATE_BYTES" ]; then
    echo "SIZE_REPORT_BUDGET_BYTES=$SIZE_REPORT_BUDGET_BYTES is ignored: the size gate is $SDK_SIZE_GATE_BYTES bytes"
  fi
  echo "size budget: $SDK_SIZE_GATE_BYTES bytes (5 MB; also the default of scripts/size-report.sh)"
  SIZE_REPORT_BUDGET_BYTES="$SDK_SIZE_GATE_BYTES" "$here/size-report.sh"
}

# ---------------------------------------------------------------------------------------------------
# Runner

case "${1:-}" in
  -h | --help) usage ;;
esac
steps="$*"
[ -n "$steps" ] || steps="$STEPS"
for step in $steps; do
  case " $STEPS " in
    *" $step "*) ;;
    *) fail "unknown step '$step' (steps: $STEPS)" ;;
  esac
done

logs="${VERIFY_LOG_DIR:-$work/logs}"
mkdir -p "$logs"
summary="$logs/summary.tsv"
[ -f "$summary" ] || printf 'step\tresult\tseconds\n' >"$summary"
grouped=false
[ "${GITHUB_ACTIONS:-}" != true ] || [ "$steps" = "${steps%% *}" ] || grouped=true

for step in $steps; do
  log="$logs/$step.log"
  ! $grouped || echo "::group::verify.sh $step"
  echo "==> verify.sh $step"
  started="$(date +%s)"
  # A subshell with errexit, outside any if/!/&& context, so that a failing command inside a step can never be
  # ignored.
  set +e
  (
    set -e
    "step_$step"
  ) 2>&1 | tee "$log"
  status="${PIPESTATUS[0]}"
  set -e
  seconds=$(($(date +%s) - started))
  ! $grouped || echo "::endgroup::"
  if [ "$status" -ne 0 ]; then
    printf '%s\tfailed\t%s\n' "$step" "$seconds" >>"$summary"
    fail "$step failed (exit $status) after ${seconds}s; log: $log"
  fi
  printf '%s\tpassed\t%s\n' "$step" "$seconds" >>"$summary"
  echo "==> verify.sh $step: passed in ${seconds}s"
done
echo "verify.sh: all passed ($steps)"
