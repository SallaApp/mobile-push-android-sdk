#!/usr/bin/env bash
# Proves that this repository's checks still catch what they exist to catch, and still pass what they must accept. It
# runs them on scratch copies of the repository, each changed in one way, and on synthetic repositories:
#   - scripts/verify.sh, the static steps (version goldens catalogue links tree wrapper) and the pom and gates steps;
#   - scripts/check-log-catalogue.sh;
#   - scripts/release-plan.sh and scripts/api-compat.sh, on a synthetic history of releases, with a stand-in for curl
#     that plays Maven Central;
#   - .github/scripts/publication-state.sh, with a stand-in for curl, and .github/scripts/release-notes.sh.
# The Gradle steps (build, consumer, size) and the README checker (which has its own --self-test) are not run here.
# It needs bash, git, python3 and a JDK (javac and javap, for the bytecode gate); no network. Every git repository it
# writes lives under a temporary folder, with an empty git configuration, and is deleted at the end. CI runs it on every
# pull request (ci.yml), before the build.
#
#   scripts/self-test.sh
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
top="$(cd "$here/.." && pwd -P)"

[ "$#" -eq 0 ] || {
  echo 'usage: scripts/self-test.sh' >&2
  exit 2
}
for tool in git python3 javac javap; do
  command -v "$tool" >/dev/null 2>&1 || {
    echo "self-test.sh: $tool is required" >&2
    exit 1
  }
done

scratch="$(mktemp -d "${TMPDIR:-/tmp}/sallapush-self-test.XXXXXX")"
trap 'rm -rf "$scratch"' EXIT
trap 'exit 130' INT TERM

# An empty git configuration for every repository here, and for the checks they run: a user's global excludes or
# signing settings must not decide a case. git before 2.32 ignores GIT_CONFIG_GLOBAL, so HOME moves too. The checks
# read nothing else from HOME but ~/.m2, which the POM cases write here.
mkdir -p "$scratch/home"
export HOME="$scratch/home" XDG_CONFIG_HOME="$scratch/home/.config" GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_GLOBAL=/dev/null
export GIT_AUTHOR_NAME=self-test GIT_AUTHOR_EMAIL=self-test@example.invalid
export GIT_COMMITTER_NAME=self-test GIT_COMMITTER_EMAIL=self-test@example.invalid
export ANDROID_HOME="$scratch/android-sdk"
unset VERIFY_LOG_DIR SIZE_REPORT_BUDGET_BYTES RELEASE_MASTER_REF RELEASE_BRANCH_REFS RELEASE_MAVEN_URL
unset RELEASE_LAST_BEFORE_CENTRAL
unset GITHUB_ACTIONS STUB_PUBLISHED STUB_POM_ONLY STUB_NO_POM STUB_ODD STUB_CODE STUB_DOWN

g() {
  git -c commit.gpgsign=false -c tag.gpgsign=false -c core.hooksPath=/dev/null "$@"
}

new_repository() {
  mkdir -p "$1"
  g -C "$1" init -q
  g -C "$1" symbolic-ref HEAD refs/heads/master
}

passed=0
failed=0
out="$scratch/out"

ok() {
  passed=$((passed + 1))
  printf 'ok   %s\n' "$1"
}

not_ok() {
  failed=$((failed + 1))
  printf 'FAIL %s: %s\n' "$1" "$2"
  sed -n '1,12s/^/       /p' "$out"
}

# expect <name> <expected> <command...>: with an empty <expected> the command must pass; otherwise it must fail, and
# its output must contain <expected>.
expect() {
  local name="$1" expected="$2" status=0
  shift 2
  "$@" >"$out" 2>&1 || status=$?
  if [ -z "$expected" ]; then
    if [ "$status" -eq 0 ]; then ok "$name"; else not_ok "$name" "expected a pass, got exit $status"; fi
  elif [ "$status" -eq 0 ]; then
    not_ok "$name" "expected a failure that says '$expected', but it passed"
  elif grep -qF -- "$expected" "$out"; then
    ok "$name"
  else
    not_ok "$name" "expected '$expected' in its output (exit $status)"
  fi
}

# expect_output <name> <expected>: the output of the last expect holds <expected>.
expect_output() {
  if grep -qF -- "$2" "$out"; then ok "$1"; else not_ok "$1" "expected '$2' in the output"; fi
}

# expect_line <name> <line>: the output of the last expect has the line <line>, exactly.
expect_line() {
  if grep -qxF -- "$2" "$out"; then ok "$1"; else not_ok "$1" "expected the line '$2' in the output"; fi
}

# Replaces the first occurrence of <old> with <new> in a file of the case; fails the run when <old> is absent, so that
# a case can never pass vacuously after the file it edits has changed.
edit() {
  python3 - "$case/$1" "$2" "$3" <<'PY'
import sys
path, old, new = sys.argv[1:4]
with open(path, encoding="utf-8") as handle:
    text = handle.read()
if old not in text:
    sys.exit(f"self-test.sh: {path} does not contain {old!r}; update the case")
with open(path, "w", encoding="utf-8") as handle:
    handle.write(text.replace(old, new, 1))
PY
}

# ---------------------------------------------------------------------------------------------------
# A copy of what a commit takes, as an untracked tree in a repository of its own.

pristine="$scratch/pristine"
case="$scratch/case"
new_repository "$pristine"
git -C "$top" ls-files -z --cached --others --exclude-standard >"$scratch/files"
while IFS= read -r -d '' path; do
  [ -f "$top/$path" ] || continue
  mkdir -p "$(dirname "$pristine/$path")"
  cp -p "$top/$path" "$pristine/$path"
done <"$scratch/files"

fresh() {
  rm -rf "$case"
  cp -Rp "$pristine" "$case"
}

verify() {
  "$case/scripts/verify.sh" "$@"
}

echo "== verify.sh: the unchanged copy"
fresh
expect "the static steps pass on the unchanged copy" "" verify version goldens catalogue links tree wrapper

echo "== verify.sh version"
version="$(sed -nE 's/^VERSION_NAME=(.*)$/\1/p' "$pristine/gradle.properties")"
fresh; edit gradle.properties "VERSION_NAME=$version" "VERSION_NAME=1.0"
expect "a version that is not X.Y.Z fails" "is not a release version" verify version
fresh; edit gradle.properties "VERSION_NAME=$version" "VERSION_NAME=1.0.0-alpha.123456789012345678901234"
expect "a version over 32 characters fails" "longer than 32 characters" verify version
fresh; edit gradle.properties "VERSION_NAME=$version" "VERSION_NAME=$version
VERSION_NAME=$version"
expect "a version declared twice fails" "exactly once" verify version
fresh; edit CHANGELOG.md "## [$version]" "## [$version.9]"
expect "a version without its CHANGELOG entry fails" "must have one '## [$version]' heading" verify version

echo "== verify.sh goldens"
golden="sallapush/src/test/resources/golden/compat-push-ref.json"
fresh; printf ' ' >>"$case/$golden"
expect "a changed golden payload fails" "not the contract's" verify goldens
fresh; rm "$case/$golden"
expect "a missing golden payload fails" "compat-push-ref.json is missing" verify goldens
fresh; rm "$case/$golden"; ln -s "$case/README.md" "$case/$golden"
expect "a golden payload that is a symbolic link fails" "is a symbolic link" verify goldens

echo "== check-log-catalogue.sh"
logcode="sallapush/src/main/java/com/salla/push/internal/log/LogCode.kt"
fresh; edit "$logcode" 'INIT_STARTED("init.started", INFO,' 'INIT_STARTED("init.started", WARN,'
expect "a code at another level fails" "init.started: the SDK logs it at warn" verify catalogue
fresh; edit "$logcode" '("init.started", INFO, LogCategory.LIFECYCLE)' '("init.started", INFO, LogCategory.NETWORK)'
expect "a code with another category fails" "gives it the category network" verify catalogue
fresh; edit "$logcode" '    INTERNAL_ERROR(' '    SELF_TEST_NEW("selftest.added", WARN, LogCategory.LIFECYCLE),
    INTERNAL_ERROR('
expect "a code the catalogue lacks fails" "selftest.added exists in the SDK" verify catalogue
fresh; edit scripts/log-catalogue.txt "net.monitor_unavailable        warn     android  network" "net.monitor_unavailable        warn     ios      network"
expect "a code the catalogue gives the iOS SDK only fails" "lists it for the iOS SDK only" verify catalogue
fresh; printf 'selftest.missing               warn     android  lifecycle\n' >>"$case/scripts/log-catalogue.txt"
expect "a catalogued Android code the SDK lacks fails" "has no such code" verify catalogue
expect_output "  ... and the test catalogue must list it too" "log-catalog-android.txt does not list selftest.missing"
fresh; printf 'selftest.ios_only              warn     ios      lifecycle\n' >>"$case/scripts/log-catalogue.txt"
expect "an iOS-only code is not this SDK's and passes" "" verify catalogue
fresh; edit sallapush/src/test/resources/log-catalog-android.txt "init.started info" "init.started debug"
expect "the test catalogue at another level fails" "log-catalog-android.txt says debug" verify catalogue
fresh; edit "$logcode" '    INTERNAL_ERROR(' '    // SELF_TEST_COMMENT("selftest.comment", WARN, LogCategory.LIFECYCLE),
    /* SELF_TEST_BLOCK("selftest.block", WARN, LogCategory.LIFECYCLE), */
    INTERNAL_ERROR('
expect "codes inside comments are not codes" "" verify catalogue
fresh; edit "$logcode" 'INIT_STARTED("init.started", INFO,' 'INIT_STARTED(START_CODE, INFO,'
expect "a code that is not a string literal cannot be read, and fails" "is not a plain string literal" verify catalogue
fresh; edit scripts/log-catalogue.txt "init.started                   info     both     lifecycle" "init.started                   loud     both     lifecycle"
expect "a catalogue line with an unknown level fails" "has the level loud" verify catalogue

echo "== verify.sh links"
fresh; printf '\nSee [the guide](docs/missing.md).\n' >>"$case/CONTRIBUTING.md"
expect "a link to a missing file fails" "docs/missing.md does not exist" verify links
fresh; printf '\nSee [the guide](/README.md).\n' >>"$case/CONTRIBUTING.md"
expect "an absolute link fails" "is an absolute link" verify links
fresh; printf '\nSee [another project](../other-project/README.md).\n' >>"$case/CONTRIBUTING.md"
expect "a link out of the repository fails" "points outside the repository" verify links
fresh
# shellcheck disable=SC2016 # literal Markdown backticks
printf '\n```text\n[not a link](nowhere.md)\n```\n' >>"$case/CONTRIBUTING.md"
expect "a link inside a code block is not checked" "" verify links

echo "== verify.sh tree"
fresh; : >"$case/AuthKey_ABC123DEFG.p8"
expect "a signing key fails" "AuthKey_ABC123DEFG.p8: a file of credentials" verify tree
fresh; : >"$case/secrets.properties"
expect "secrets.properties fails" "secrets.properties: a file of credentials" verify tree
fresh; : >"$case/release.JKS"; g -C "$case" add -f release.JKS
expect "a keystore added past .gitignore fails, whatever its case" "release.JKS: a file of credentials" verify tree
fresh; printf '{}' >"$case/sample/google-services.json"; g -C "$case" add -f sample/google-services.json
expect "google-services.json added past .gitignore fails" "sample/google-services.json: a file of credentials" verify tree
fresh; printf 'TOKEN=x\n' >"$case/.env.local"; g -C "$case" add -f .env.local
expect "a .env file added past .gitignore fails" ".env.local: a file of credentials" verify tree
fresh; printf 'sdk.dir=/x\n' >"$case/local.properties"; : >"$case/sample/debug.keystore"
expect "ignored local.properties and keystores are not taken by a commit, and pass" "" verify tree
fresh; mkdir -p "$case/sample/build"; : >"$case/sample/build/output.txt"; g -C "$case" add -f sample/build/output.txt
expect "build output added by force fails" "sample/build/output.txt: build output (build/)" verify tree
fresh; head -c 5242881 /dev/zero >"$case/big.bin"
expect "a file over 5 MB fails" "big.bin: 5242881 bytes, over" verify tree
fresh; ln -s README.md "$case/link.md"
expect "a symbolic link fails" "link.md is a symbolic link" verify tree
# A clone inside the tree. git counts a folder as a repository of its own once its HEAD resolves.
fresh; new_repository "$case/nested"; : >"$case/nested/file"; g -C "$case/nested" add file
g -C "$case/nested" commit -q -m "A nested repository"
expect "a nested repository fails" "nested/ is a nested git repository" verify tree
fresh; rm "$case/CONTRIBUTING.md"
expect "a missing required file fails" "CONTRIBUTING.md is missing from what a commit takes" verify tree
fresh; rm "$case/LICENSE"
expect "a missing LICENSE fails" "LICENSE is missing from what a commit takes" verify tree
fresh; printf 'CHANGELOG.md\n' >>"$case/.gitignore"
expect "an ignored required file fails" "CHANGELOG.md is missing from what a commit takes" verify tree
fresh; chmod -x "$case/scripts/release-plan.sh"
expect "a script that lost its executable bit fails" "scripts/release-plan.sh is not executable" verify tree
fresh; g -C "$case" add scripts/api-compat.sh; g -C "$case" update-index --chmod=-x scripts/api-compat.sh
expect "a script staged without the executable mode fails" "scripts/api-compat.sh is staged with mode 100644" verify tree
fresh; printf '\nincludeBuild("../other-build")\n' >>"$case/settings.gradle.kts"
expect "a build file that reaches outside the repository fails" "../other-build resolves outside the repository" verify tree
# Built at run time: the tree check reads this file too, and a literal path two levels up would be one.
up=..
fresh; edit .github/workflows/ci.yml "run: scripts/verify.sh version" "run: $up/$up/tools/verify.sh version"
expect "a workflow step that reaches outside the workspace fails" "$up/$up/tools/verify.sh resolves outside the repository" verify tree
fresh; printf '\nrun = "../x"\n' >>"$case/consumer-check/gradle.properties"
expect "a reference that stays inside the repository passes" "" verify tree
checkout_pin="$(grep -m1 -oE 'actions/checkout@[0-9a-f]{40}' "$pristine/.github/workflows/ci.yml")"
fresh; edit .github/workflows/ci.yml "$checkout_pin" "actions/checkout@v7"
expect "an action pinned to a tag fails" "uses actions/checkout@v7; pin the action" verify tree
fresh; printf 'name: Probe\non:\n  pull_request_target:\npermissions: {}\njobs:\n  probe:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: %s # v7.0.1\n' \
  "$checkout_pin" >"$case/.github/workflows/probe.yml"
expect "a pull_request_target workflow that checks out code fails" "a pull_request_target workflow checks out code" verify tree
# No owner gets an exception, an organization's shared action included.
fresh; printf 'name: Probe\non:\n  pull_request:\npermissions: {}\njobs:\n  probe:\n    runs-on: ubuntu-latest\n    steps:\n      - uses: example-org/shared-actions/check@main\n' \
  >"$case/.github/workflows/probe.yml"
expect "an action pinned to a branch fails, whoever owns it" "pin the action to a full commit id" verify tree
# The secrets are assembled at run time, so that this file never holds one.
fresh; printf '%s%s\nb3BlbnNzaA==\n' '-----BEGIN OPENSSH ' 'PRIVATE KEY-----' >"$case/sample/raw-key.txt"
expect "a private key in any file fails" "sample/raw-key.txt: holds a private key" verify tree
fresh; printf 'token = "%s%s"\n' 'ghp_' 'abcdefghijklmnopqrstuvwxyz0123456789' >"$case/sample/token.txt"
expect "a GitHub token in any file fails" "sample/token.txt: holds a GitHub token" verify tree
# The design document's name and markers are assembled at run time too, so that this file names none of them.
section="$(printf '\302\247')"
label="C1.$((1 + 2))"
catalog_test="sallapush/src/test/java/com/salla/push/LogCatalogTest.kt"
fresh; printf '\n// The rule of %s%s %s4.2.\n' 'DES' 'IGN' "$section" >>"$case/$catalog_test"
expect "a reference to an outside design document fails, with its line" \
  "$catalog_test:$(wc -l <"$case/$catalog_test" | tr -d ' '): refers to a design document outside this repository" verify tree
fresh; printf '\n// Answered as %s says.\n' "$label" >>"$case/sallapush/src/main/java/com/salla/push/internal/api/Routes.kt"
expect "one of its route labels fails" "refers to a design document outside this repository ($label)" verify tree
fresh; printf '# The retry rules (%s%s).\n' 'FRO' 'ZEN' >>"$case/scripts/log-catalogue.txt"
expect "one of its markers fails" \
  "scripts/log-catalogue.txt:$(wc -l <"$case/scripts/log-catalogue.txt" | tr -d ' '): refers to a design document" verify tree
fresh; printf '\n// The design of a frozen process; see the docs, section 2.\n' >>"$case/sallapush/src/main/java/com/salla/push/internal/api/Routes.kt"
expect "the plain words pass" "" verify tree

echo "== verify.sh wrapper"
wrapper="gradle/wrapper/gradle-wrapper.properties"
fresh; edit "$wrapper" 'https\://services.gradle.org/distributions/' 'https\://mirror.example.com/distributions/'
expect "a distribution from another host fails" "must download an official distribution" verify wrapper
fresh; edit "$wrapper" "validateDistributionUrl=true" "validateDistributionUrl=false"
expect "validateDistributionUrl=false fails" "validateDistributionUrl must be true" verify wrapper
fresh; printf 'x' >>"$case/gradle/wrapper/gradle-wrapper.jar"
expect "a wrapper jar that is not Gradle's fails" "is not Gradle 8.12's wrapper" verify wrapper
fresh; edit "$wrapper" "gradle-8.12-bin.zip" "gradle-8.13-bin.zip"
expect "a Gradle version whose wrapper jar is not pinned fails" "the wrapper jar of Gradle 8.13 is not pinned" verify wrapper

echo "== verify.sh pom"
group_path="com/salla"
pom_dir="$HOME/.m2/repository/$group_path/mobile-push/$version"
# write_jar <classifier> <entry>: the jar of that classifier beside the POM, holding one file named <entry>.
write_jar() {
  python3 - "$pom_dir/mobile-push-$version-$1.jar" "$2" <<'PY'
import sys
import zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr(sys.argv[2], "self-test\n")
PY
}
# write_pom [version] [firebase-messaging scope] [extra dependencies]: the designed POM, with its sources jar and a
# javadoc jar of HTML pages, as the build publishes them.
write_pom() {
  mkdir -p "$pom_dir"
  cat >"$pom_dir/mobile-push-$version.pom" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>com.salla</groupId>
  <artifactId>mobile-push</artifactId>
  <version>${1:-$version}</version>
  <packaging>aar</packaging>
  <name>SallaPush</name>
  <description>SallaPush Android SDK</description>
  <url>https://github.com/SallaApp/mobile-push-android-sdk</url>
  <licenses>
    <license>
      <name>MIT License</name>
      <url>https://opensource.org/license/mit</url>
      <distribution>repo</distribution>
    </license>
  </licenses>
  <developers>
    <developer>
      <id>salla</id>
      <name>Salla</name>
    </developer>
  </developers>
  <scm>
    <connection>scm:git:https://github.com/SallaApp/mobile-push-android-sdk.git</connection>
    <url>https://github.com/SallaApp/mobile-push-android-sdk</url>
  </scm>
  <dependencies>
    <dependency><groupId>com.google.firebase</groupId><artifactId>firebase-messaging</artifactId><version>24.1.0</version><scope>${2:-compile}</scope></dependency>
    <dependency><groupId>org.jetbrains.kotlin</groupId><artifactId>kotlin-stdlib</artifactId><version>1.9.24</version><scope>compile</scope></dependency>
    <dependency><groupId>androidx.core</groupId><artifactId>core</artifactId><version>1.13.1</version><scope>runtime</scope></dependency>
    <dependency><groupId>androidx.work</groupId><artifactId>work-runtime</artifactId><version>2.9.1</version><scope>runtime</scope></dependency>${3:-}
  </dependencies>
</project>
EOF
  write_jar sources com/salla/push/SallaPush.kt
  write_jar javadoc index.html
}
# pom_edit <old> <new>: replaces the first <old> in the POM; fails the run when <old> is absent.
pom_edit() {
  python3 - "$pom_dir/mobile-push-$version.pom" "$1" "$2" <<'PY'
import sys
path, old, new = sys.argv[1:4]
with open(path, encoding="utf-8") as handle:
    text = handle.read()
if old not in text:
    sys.exit(f"self-test.sh: the POM does not contain {old!r}; update the case")
with open(path, "w", encoding="utf-8") as handle:
    handle.write(text.replace(old, new, 1))
PY
}
fresh
expect "a missing POM fails" "run the build step (publishToMavenLocal) first" verify pom
write_pom
expect "the designed POM passes" "" verify pom
write_pom; pom_edit "  <name>SallaPush</name>
" ""
expect "a POM without a name fails" "no <name>" verify pom
write_pom; pom_edit "  <description>SallaPush Android SDK</description>
" ""
expect "a POM without a description fails" "no <description>" verify pom
write_pom; pom_edit "
  <url>https://github.com/SallaApp/mobile-push-android-sdk</url>" ""
expect "a POM without a URL fails" "no <url>" verify pom
write_pom; pom_edit "<name>MIT License</name>" "<name>Apache License 2.0</name>"
expect "a POM with another licence fails" "the POM declares the MIT License" verify pom
write_pom; pom_edit "      <name>Salla</name>
" ""
expect "a POM without a developer's name fails" "no <developer> with a name" verify pom
write_pom; pom_edit "    <connection>scm:git:https://github.com/SallaApp/mobile-push-android-sdk.git</connection>
" ""
expect "a POM without the source repository's connection fails" "no <scm> with a url and a connection" verify pom
write_pom; rm "$pom_dir/mobile-push-$version-sources.jar"
expect "a missing sources jar fails" "no mobile-push-$version-sources.jar beside the POM" verify pom
write_pom; rm "$pom_dir/mobile-push-$version-javadoc.jar"
expect "a missing javadoc jar fails" "no mobile-push-$version-javadoc.jar beside the POM" verify pom
write_pom; write_jar javadoc package-list
expect "a javadoc jar without HTML pages fails" "holds no HTML page" verify pom
write_pom "$version" runtime
expect "firebase-messaging in runtime scope fails" "firebase-messaging must be a compile-scope" verify pom
write_pom "$version" compile "
    <dependency><groupId>com.squareup.okhttp3</groupId><artifactId>okhttp</artifactId><version>4.12.0</version></dependency>"
expect "a third-party dependency fails" "unexpected dependency com.squareup.okhttp3:okhttp" verify pom
write_pom 9.9.9
expect "a POM of another version fails" "version is 9.9.9, expected $version" verify pom
write_pom; pom_edit "<groupId>com.salla</groupId>
  <artifactId>mobile-push</artifactId>" "<groupId>com.salla.push</groupId>
  <artifactId>sallapush</artifactId>"
expect "a POM with the old coordinates fails" "groupId is com.salla.push, expected com.salla" verify pom
expect_output "  ... and names the artifact" "artifactId is sallapush, expected mobile-push"
rm -rf "$HOME/.m2"

echo "== verify.sh gates"
classes="sallapush/build/tmp/kotlin-classes/release"
# Java classes stand in for the Kotlin compiler's output: one with no null-check intrinsic, and one that calls
# kotlin.jvm.internal.Intrinsics.checkNotNullParameter, as Kotlin code compiled without the SDK's flags does.
compile_probe() {
  local source="$scratch/probe-src"
  rm -rf "$source"
  mkdir -p "$source/kotlin/jvm/internal" "$source/com/salla/push" "$case/$classes"
  cat >"$source/kotlin/jvm/internal/Intrinsics.java" <<'EOF'
package kotlin.jvm.internal;
public final class Intrinsics {
    public static void checkNotNullParameter(Object value, String name) { }
}
EOF
  printf 'package com.salla.push;\npublic final class Probe {\n    public static void entry(Object value) {\n%s    }\n}\n' "$1" \
    >"$source/com/salla/push/Probe.java"
  javac -nowarn -d "$scratch/probe-classes" "$source/kotlin/jvm/internal/Intrinsics.java" "$source/com/salla/push/Probe.java" >/dev/null
  mkdir -p "$case/$classes/com/salla/push"
  cp "$scratch/probe-classes/com/salla/push/Probe.class" "$case/$classes/com/salla/push/"
  rm -rf "$scratch/probe-classes"
}
fresh
expect "gates without release classes fail" "run the build step first" verify gates
compile_probe '        value.hashCode();
'
expect "the library's sources and clean classes pass" "" verify gates
compile_probe '        kotlin.jvm.internal.Intrinsics.checkNotNullParameter(value, "value");
'
expect "a null-check intrinsic in the release classes fails" "Kotlin null-check intrinsics in the release classes" verify gates
expect_output "  ... and the report names the class" "Probe"
main_source="sallapush/src/main/java/com/salla/push/internal/util/SelfTest.kt"
banned_case() {
  fresh
  compile_probe '        value.hashCode();
'
  printf 'package com.salla.push.internal.util\n\ninternal fun selfTest(value: String?): Int {\n    %s\n}\n' "$1" >"$case/$main_source"
}
banned_case 'return value!!.length'
expect "!! in the library fails" "banned constructs" verify gates
banned_case 'return requireNotNull(value).length'
expect "requireNotNull( in the library fails" "banned constructs" verify gates
banned_case 'return kotlinx.coroutines.runBlocking { 1 }'
expect "runBlocking in the library fails" "banned constructs" verify gates
banned_case '// check(value != null) is banned, so the code says it in a comment
    return value?.length ?: 0'
expect "a banned construct in a comment passes" "" verify gates
banned_case 'return log.error(value)'
expect "a method named like a banned function passes" "" verify gates
fresh; compile_probe '        value.hashCode();
'
# shellcheck disable=SC2016 # a literal $default: the suffix of Kotlin default-argument stubs
printf 'public final class com/salla/push/SelfTest {\n\tpublic static synthetic fun send$default (Lcom/salla/push/SelfTest;ILjava/lang/Object;)V\n}\n' >>"$case/sallapush/api/sallapush.api"
expect "a public default argument fails" "public functions with default arguments" verify gates

echo "== release-plan.sh and api-compat.sh (a synthetic release history; curl is a stand-in for Maven Central)"
rel="$scratch/release"
new_repository "$rel"
mkdir -p "$rel/scripts" "$rel/sallapush/api"
cp -p "$top/scripts/common.sh" "$top/scripts/release-plan.sh" "$top/scripts/api-compat.sh" "$rel/scripts/"
printf '# Changelog\n' >"$rel/CHANGELOG.md"
api_v1='public final class com/salla/push/SallaPush {
	public static final field INSTANCE Lcom/salla/push/SallaPush;
	public static final fun initWithContext (Landroid/content/Context;Ljava/lang/String;)V
}

public final class com/salla/push/SallaPushConfig {
	public final fun getAppId ()Ljava/lang/String;
}
'
# release <version> [<heading suffix>]: commits gradle.properties at <version>, with a CHANGELOG entry.
release() {
  printf 'GROUP=com.salla\nVERSION_NAME=%s\n' "$1" >"$rel/gradle.properties"
  printf '\n## [%s]%s\n\n- A change.\n' "$1" "${2- - 2026-10-04}" >>"$rel/CHANGELOG.md"
  g -C "$rel" add -A
  g -C "$rel" commit -q -m "Release $1"
}
on_master() {
  g -C "$rel" update-ref refs/remotes/origin/master HEAD
}
# api_edit <old> <new>: replaces <old> in the API dump; fails the run when <old> is absent.
api_edit() {
  python3 - "$rel/sallapush/api/sallapush.api" "$1" "$2" <<'PY'
import sys
path, old, new = sys.argv[1:4]
text = open(path, encoding="utf-8").read()
if old not in text:
    sys.exit(f"self-test.sh: the API dump does not contain {old!r}; update the case")
open(path, "w", encoding="utf-8").write(text.replace(old, new, 1))
PY
}

# Maven Central as release-plan.sh sees it through curl: every file of a version in STUB_PUBLISHED answers 200, only the
# POM of one in STUB_POM_ONLY, every file but the POM of one in STUB_NO_POM, the code STUB_CODE every file of one in
# STUB_ODD, and curl itself fails for one in STUB_DOWN. Any other file answers 404, and a URL outside the layout of
# STUB_REPOSITORY answers 400, so a wrong URL can never pass for a published or an absent version.
registry="$scratch/registry-bin"
mkdir -p "$registry"
cat >"$registry/curl" <<'EOF'
#!/usr/bin/env bash
for argument in "$@"; do url="$argument"; done
printf '%s\n' "$*" >>"$STUB_LOG.argv"
cat >>"$STUB_LOG.config"
version="${url%/*}"
version="${version##*/}"
base="$STUB_REPOSITORY/com/salla/mobile-push/$version/mobile-push-$version"
suffix="${url#"$base"}"
case "$suffix" in
  .pom | .aar | .module | -sources.jar | -javadoc.jar) [ "$url" = "$base$suffix" ] || { printf 400; exit 0; } ;;
  *) printf 400; exit 0 ;;
esac
case " ${STUB_DOWN:-} " in *" $version "*) echo "curl: (7) Failed to connect" >&2; exit 7 ;; esac
case " ${STUB_ODD:-} " in *" $version "*) printf '%s' "${STUB_CODE:-500}"; exit 0 ;; esac
case " ${STUB_PUBLISHED:-} " in *" $version "*) printf 200; exit 0 ;; esac
case " ${STUB_POM_ONLY:-} " in *" $version "*) [ "$suffix" != .pom ] || { printf 200; exit 0; } ;; esac
case " ${STUB_NO_POM:-} " in *" $version "*) [ "$suffix" = .pom ] || { printf 200; exit 0; } ;; esac
printf 404
EOF
chmod +x "$registry/curl"
# plan <versions on Maven Central> <tag> [NAME=value...]: release-plan.sh, with the stand-in for Maven Central, no
# release before Maven Central and no API baseline floor, unless a case sets RELEASE_LAST_BEFORE_CENTRAL or
# RELEASE_API_BASELINE_FLOOR.
plan() {
  local published="$1" tag="$2"
  shift 2
  rm -f "$scratch/registry.argv" "$scratch/registry.config"
  env PATH="$registry:$PATH" STUB_LOG="$scratch/registry" RELEASE_LAST_BEFORE_CENTRAL= RELEASE_API_BASELINE_FLOOR= \
    STUB_REPOSITORY=https://repo1.maven.org/maven2 STUB_PUBLISHED="$published" "$@" \
    "$rel/scripts/release-plan.sh" "$tag"
}
api() {
  "$rel/scripts/api-compat.sh" "$@"
}

printf '%s' "$api_v1" >"$rel/sallapush/api/sallapush.api"
release 1.0.0; on_master; g -C "$rel" tag 1.0.0
api_edit "	public final fun getAppId ()Ljava/lang/String;
" "	public final fun getAppId ()Ljava/lang/String;
	public final fun getAppVersion ()Ljava/lang/String;
"
release 1.1.0; on_master; g -C "$rel" tag -a -m "SallaPush 1.1.0" 1.1.0
release 1.2.0-beta.1; on_master; g -C "$rel" tag 1.2.0-beta.1
side="$(g -C "$rel" rev-parse HEAD)"
release 1.2.0; on_master; g -C "$rel" tag 1.2.0
master_1_2_0="$(g -C "$rel" rev-parse HEAD)"

expect "a stable tag on master passes" "" plan "1.0.0 1.1.0 1.2.0-beta.1" 1.2.0
expect_line "  ... its API baseline is the release before it" "api_baseline=1.1.0"
expect_line "  ... on Maven Central" "api_baseline_source=maven-central"
expect_line "  ... and it is no major bump" "major_bump=false"
expect_line "  ... and its sha is the tagged commit" "sha=$master_1_2_0"
if grep -qF "https://repo1.maven.org/maven2/com/salla/mobile-push/1.1.0/mobile-push-1.1.0.pom" "$scratch/registry.argv"; then
  ok "  ... Maven Central is read where it serves releases"
else
  not_ok "  ... Maven Central is read where it serves releases" "no request for mobile-push-1.1.0.pom"
fi
if [ -s "$scratch/registry.config" ] || grep -qiE -- 'user|authorization|--config' "$scratch/registry.argv"; then
  not_ok "  ... anonymously" "curl got credentials"
else
  ok "  ... anonymously"
fi
expect "a pre-release passes" "" plan "" 1.2.0-beta.1
expect_line "  ... as a pre-release" "prerelease=true"
expect_line "  ... without an API baseline" "api_baseline="
if [ -e "$scratch/registry.argv" ]; then
  not_ok "  ... and never reads Maven Central" "curl was called"
else
  ok "  ... and never reads Maven Central"
fi
expect "an annotated tag resolves to its commit" "" plan "1.0.0" 1.1.0
expect_line "  ... the commit, not the tag object" "sha=$(g -C "$rel" rev-parse '1.1.0^{commit}')"
expect_line "  ... and the first stable release is its baseline" "api_baseline=1.0.0"
expect "the first stable release has no baseline" "" plan "" 1.0.0
expect_line "  ... none" "api_baseline="
for bad in v1.2.0 1.2 1.2.0-beta 01.2.0 1.2.0+build.1 1.2.0-preview.1; do
  expect "the tag $bad is refused" "is not a release" plan "" "$bad"
done
expect "a tag over 32 characters is refused" "longer than 32 characters" plan "" 1.0.0-alpha.123456789012345678901234
expect "a tag that does not exist fails" "the tag 1.9.0 does not exist" plan "" 1.9.0

# A branch of this repository other than master, as actions/checkout fetches it with fetch-depth: 0.
on_side() {
  g -C "$rel" update-ref refs/remotes/origin/side HEAD
}
g -C "$rel" checkout -q -b side "$side"
release 1.3.9; on_side; g -C "$rel" tag 1.3.9
release 1.5.0; on_side; g -C "$rel" tag 1.5.0
expect "a stable tag off master fails" "must point at a commit on master" plan "" 1.5.0
release 1.6.0-rc.1; on_side; g -C "$rel" tag 1.6.0-rc.1
expect "a pre-release on another branch passes" "" plan "" 1.6.0-rc.1
# A commit of a pull request from a fork: no branch of this repository holds it, and actions/checkout fetches no
# refs/pull.
g -C "$rel" checkout -q --detach side
release 1.6.0-rc.2; g -C "$rel" tag 1.6.0-rc.2
expect "a pre-release on a commit of no branch fails" "which is on no branch of this repository" plan "" 1.6.0-rc.2
expect "a clone without the branches fails" "no branch of this repository is under refs/remotes/absent/" \
  plan "" 1.6.0-rc.1 RELEASE_BRANCH_REFS=refs/remotes/absent/
g -C "$rel" checkout -q master

release 1.3.0; on_master; g -C "$rel" tag 1.3.1
expect "a tag that is not the declared version fails" "declares VERSION_NAME=1.3.0 at" plan "" 1.3.1
printf 'GROUP=com.salla\nVERSION_NAME=1.4.0\n' >"$rel/gradle.properties"
g -C "$rel" commit -q -am "Version 1.4.0 without an entry"; on_master; g -C "$rel" tag 1.4.0
expect "a release without its CHANGELOG entry fails" "needs one '## [1.4.0]' entry" plan "" 1.4.0
release 1.4.1; printf '\n## [1.4.1]\n\n- Twice.\n' >>"$rel/CHANGELOG.md"
g -C "$rel" commit -q -am "A second entry"; on_master; g -C "$rel" tag 1.4.1
expect "a release with two CHANGELOG entries fails" "needs one '## [1.4.1]' entry" plan "" 1.4.1
# The release after refused tags says in its CHANGELOG.md that they were not released.
printf '\n## [1.3.1] - Not released\n\n## [1.4.0] - Not released\n\n## [1.4.1] - Not released\n' >>"$rel/CHANGELOG.md"
release 1.4.2 " - Unreleased"; on_master; g -C "$rel" tag 1.4.2
# What Maven Central holds once 1.2.0 is published: the tags 1.3.1, 1.4.0 and 1.4.1 above were refused, and stay.
published="1.0.0 1.1.0 1.2.0-beta.1 1.2.0"
expect "an Unreleased heading passes" "" plan "$published" 1.4.2
expect_output "  ... with a warning" "::warning::"
expect "a stable release while master is unknown fails" "is missing; a stable release is checked against master" \
  plan "$published" 1.4.2 RELEASE_MASTER_REF=refs/remotes/origin/absent
expect "refused tags stay, and are no releases" "" plan "$published" 1.4.2
expect_line "  ... the baseline is 1.2.0, the highest release on Maven Central, not the refused 1.4.1" "api_baseline=1.2.0"
expect_output "  ... and the plan names the refused tags it passed over" "1.4.1 is tagged but not on Maven Central"
expect "the highest release on Maven Central on the tagged history is the baseline" "" plan "$published 1.4.1" 1.4.2
expect_line "  ... 1.4.1, once it is published" "api_baseline=1.4.1"
expect "a stable tag off master is never the baseline" "" plan "$published 1.3.9" 1.4.2
expect_line "  ... even when Maven Central holds it" "api_baseline=1.2.0"
expect "a version is on Maven Central when it serves its POM" "" plan "1.0.0 1.1.0" 1.4.2 STUB_POM_ONLY=1.2.0
expect_line "  ... and is the baseline" "api_baseline=1.2.0"
expect "files without the POM are no release, so the next one waits for it" "Maven Central does not hold 1.2.0" \
  plan "1.0.0 1.1.0" 1.4.2 STUB_NO_POM=1.2.0
expect "a stable version below one Maven Central holds is refused" "already holds 1.5.0, a later stable version" \
  plan "$published 1.5.0" 1.4.2
expect "a later pre-release on Maven Central does not count" "" plan "$published 1.6.0-rc.1" 1.4.2
expect "a version Maven Central already holds compares nothing" "" plan "$published 1.4.2 1.5.0" 1.4.2
expect_line "  ... no baseline" "api_baseline="
expect_output "  ... and says why" "already holds 1.4.2"
expect "an unexpected answer about an earlier release fails" "cannot tell whether Maven Central holds 1.2.0 (HTTP 403" \
  plan "$published" 1.4.2 STUB_ODD=1.2.0 STUB_CODE=403
expect "an unexpected answer about a later version fails" "cannot tell whether Maven Central holds 1.5.0 (HTTP 500" \
  plan "$published" 1.4.2 STUB_ODD=1.5.0 STUB_CODE=500
expect "an unexpected answer about the version itself only warns" "" plan "$published" 1.4.2 STUB_ODD=1.4.2 STUB_CODE=502
expect_output "  ... with a warning" "::warning::Maven Central answered HTTP 502 for mobile-push-1.4.2.pom"
expect_line "  ... and the version is checked as unpublished" "api_baseline=1.2.0"
expect "Maven Central out of reach fails" "could not reach Maven Central" plan "$published" 1.4.2 STUB_DOWN=1.2.0
expect "RELEASE_MAVEN_URL names another Maven repository" "" plan "$published" 1.4.2 \
  RELEASE_MAVEN_URL=https://maven.example.invalid/maven2/ STUB_REPOSITORY=https://maven.example.invalid/maven2
expect_line "  ... and is read there" "api_baseline=1.2.0"

# The releases before Maven Central, which never held them: the highest is the baseline until Maven Central holds one.
expect "the first release on Maven Central keeps the API of the last release before it" "" \
  plan "" 1.4.2 RELEASE_LAST_BEFORE_CENTRAL=1.2.0
expect_line "  ... a tag" "api_baseline=1.2.0"
expect_line "  ... and says so" "api_baseline_source=tag"
expect_output "  ... a tag above it that Maven Central lacks was refused" "1.4.1 is tagged but not on Maven Central"
if grep -qF "mobile-push-1.2.0.pom" "$scratch/registry.argv"; then
  not_ok "  ... and Maven Central is never asked about the releases before it" "it was"
else
  ok "  ... and Maven Central is never asked about the releases before it"
fi
expect "a release on Maven Central wins over the releases before it" "" plan "1.4.1" 1.4.2 RELEASE_LAST_BEFORE_CENTRAL=1.2.0
expect_line "  ... as the baseline" "api_baseline=1.4.1"
expect_line "  ... from Maven Central" "api_baseline_source=maven-central"
expect "a last release before Maven Central that is a pre-release fails" "must be a stable version" \
  plan "" 1.4.2 RELEASE_LAST_BEFORE_CENTRAL=1.2.0-beta.1
expect "without RELEASE_LAST_BEFORE_CENTRAL, it is scripts/common.sh's" "" env -u RELEASE_LAST_BEFORE_CENTRAL \
  RELEASE_API_BASELINE_FLOOR= PATH="$registry:$PATH" STUB_LOG="$scratch/registry" \
  STUB_REPOSITORY=https://repo1.maven.org/maven2 STUB_PUBLISHED= "$rel/scripts/release-plan.sh" 1.1.0
expect_line "  ... 1.0.4, so without a floor 1.1.0 keeps the API of the release 1.0.0" "api_baseline=1.0.0"
expect_line "  ... as a tag" "api_baseline_source=tag"

# The API baseline floor: the releases below the first public release were never public, so no release keeps their API.
expect "the first public release keeps no API of the releases before it" "" \
  plan "" 1.1.0 RELEASE_LAST_BEFORE_CENTRAL=1.0.4 RELEASE_API_BASELINE_FLOOR=1.1.0
expect_line "  ... no baseline" "api_baseline="
expect_output "  ... and says why" "1.0.0 and the releases below it were never public (below 1.1.0)"
expect "a release above the floor keeps the API of the floor" "" plan "1.1.0" 1.2.0 RELEASE_API_BASELINE_FLOOR=1.1.0
expect_line "  ... 1.1.0, from Maven Central" "api_baseline=1.1.0"
expect "  ... and waits while the floor's deployment is not on Maven Central" "Maven Central does not hold 1.1.0" \
  plan "1.0.0" 1.2.0 RELEASE_API_BASELINE_FLOOR=1.1.0
expect "an API baseline floor that is a pre-release fails" "must be a stable version" \
  plan "" 1.4.2 RELEASE_API_BASELINE_FLOOR=1.2.0-beta.1
expect "without RELEASE_API_BASELINE_FLOOR, it is scripts/common.sh's" "" env -u RELEASE_LAST_BEFORE_CENTRAL \
  -u RELEASE_API_BASELINE_FLOOR PATH="$registry:$PATH" STUB_LOG="$scratch/registry" \
  STUB_REPOSITORY=https://repo1.maven.org/maven2 STUB_PUBLISHED= "$rel/scripts/release-plan.sh" 1.1.0
expect_line "  ... 1.1.0, so 1.1.0 keeps no API of the releases before it" "api_baseline="

api_edit "	public final fun getAppVersion ()Ljava/lang/String;
" ""
release 2.0.0; on_master; g -C "$rel" tag 2.0.0
expect "a new major version passes the plan" "" plan "$published 1.4.2" 2.0.0
expect_line "  ... as a major bump" "major_bump=true"
expect_line "  ... over the last release" "api_baseline=1.4.2"

expect "an API addition is compatible" "" api 1.0.0 1.1.0
expect "an unchanged API is compatible" "" api 1.1.0 1.2.0
expect "a removed member breaks the API" "1 declaration(s) break the API" api 1.2.0 2.0.0
expect_output "  ... and is named" "getAppVersion"
g -C "$rel" checkout -q -b header 1.2.0
api_edit "public final class com/salla/push/SallaPushConfig {" "public class com/salla/push/SallaPushConfig {"
g -C "$rel" commit -q -am "Open SallaPushConfig"
expect "a changed class header breaks the API" "break the API" api 1.2.0 HEAD
g -C "$rel" checkout -q -b moved 1.2.0
api_edit "	public final fun getAppVersion ()Ljava/lang/String;
" ""
api_edit "	public static final field INSTANCE" "	public final fun getAppVersion ()Ljava/lang/String;
	public static final field INSTANCE"
g -C "$rel" commit -q -am "Move getAppVersion"
expect "a member moved to another class breaks the API" "break the API" api 1.2.0 HEAD
g -C "$rel" rm -q sallapush/api/sallapush.api
g -C "$rel" commit -q -m "No dump"
expect "a commit without the dump fails" "does not exist at" api HEAD 1.2.0
expect "an unknown commit fails" "is not a commit" api 1.2.0 no-such-commit
g -C "$rel" checkout -q master

# A refused release stays tagged: 3.1.0 breaks the API of 3.0.0, so its release checks refuse it, and nothing is
# published. 3.1.1, whose CHANGELOG.md says that 3.1.0 was not released, restores only part of what 3.1.0 removed, and
# must still be checked against 3.0.0.
release 3.0.0; on_master; g -C "$rel" tag 3.0.0
api_edit "	public static final fun initWithContext (Landroid/content/Context;Ljava/lang/String;)V
" ""
api_edit "	public final fun getAppId ()Ljava/lang/String;
" ""
release 3.1.0; on_master; g -C "$rel" tag 3.1.0
published="$published 1.4.2 2.0.0 3.0.0"
expect "a release that breaks the API of the release before it" "" plan "$published" 3.1.0
expect_line "  ... is compared with that release" "api_baseline=3.0.0"
expect "  ... and refused" "2 declaration(s) break the API" api 3.0.0 3.1.0
api_edit "	public static final field INSTANCE Lcom/salla/push/SallaPush;
" "	public static final field INSTANCE Lcom/salla/push/SallaPush;
	public static final fun initWithContext (Landroid/content/Context;Ljava/lang/String;)V
"
printf '\n## [3.1.0] - Not released\n' >>"$rel/CHANGELOG.md"
release 3.1.1; on_master; g -C "$rel" tag 3.1.1
expect "the release after a refused one" "" plan "$published" 3.1.1
expect_line "  ... is compared with the last published release, not the refused tag" "api_baseline=3.0.0"
expect "  ... so what the fix did not restore is still a break" "1 declaration(s) break the API" api 3.0.0 3.1.1
expect_output "  ... and is named" "getAppId"
expect "  ... which the refused tag as the baseline would have let through" "" api 3.1.0 3.1.1

# A deployment that still waits in the Central Portal: Maven Central lacks 4.0.0 until a maintainer publishes it, and
# no CHANGELOG.md says that it was not released, so 4.0.1 waits for it, then keeps its API.
release 4.0.0; on_master; g -C "$rel" tag 4.0.0
release 4.0.1; on_master; g -C "$rel" tag 4.0.1
expect "a stable tag that Maven Central lacks stops the next stable release" \
  "Maven Central does not hold 4.0.0, a stable version tagged below 4.0.1" plan "$published" 4.0.1
expect "  ... until Maven Central holds it" "" plan "$published 4.0.0" 4.0.1
expect_line "  ... and its API is the baseline" "api_baseline=4.0.0"

echo "== publication-state.sh (curl is a stand-in for Maven Central that answers per file)"
stub="$scratch/stub-bin"
mkdir -p "$stub"
cat >"$stub/curl" <<'EOF'
#!/usr/bin/env bash
# Stands in for curl: prints the HTTP status the test chose for the file, records its arguments and its configuration.
for argument in "$@"; do url="$argument"; done
printf '%s\n' "$*" >>"$STUB_LOG.argv"
cat >>"$STUB_LOG.config"
case "$url" in
  *-sources.jar) printf '%s' "$STUB_SOURCES" ;;
  *-javadoc.jar) printf '%s' "$STUB_JAVADOC" ;;
  *.module) printf '%s' "$STUB_MODULE" ;;
  *.aar) printf '%s' "$STUB_AAR" ;;
  *.pom) printf '%s' "$STUB_POM" ;;
  *) exit 6 ;;
esac
EOF
chmod +x "$stub/curl"
state() {
  : >"$scratch/github-output"
  rm -f "$scratch/stub.argv" "$scratch/stub.config"
  env PATH="$stub:$PATH" STUB_LOG="$scratch/stub" GITHUB_OUTPUT="$scratch/github-output" \
    STUB_POM="$1" STUB_AAR="$2" STUB_MODULE="$3" STUB_SOURCES="$4" STUB_JAVADOC="$5" \
    bash "$top/.github/scripts/publication-state.sh" https://repo1.maven.org/maven2 com/salla mobile-push 1.0.0
}
expect_state() {
  if grep -qxF -- "published=$2" "$scratch/github-output"; then ok "$1"; else not_ok "$1" "expected published=$2"; fi
}
expect "a complete publication is found" "" state 200 200 200 200 200
expect_state "  ... and skipped" true
expect_output "  ... and says so" "is already on Maven Central"
if [ -s "$scratch/stub.config" ] || grep -qiE -- 'user|authorization|--config' "$scratch/stub.argv"; then
  not_ok "  ... read anonymously" "curl got credentials"
else
  ok "  ... read anonymously"
fi
expect "a version that is not published yet" "" state 404 404 404 404 404
expect_state "  ... is published" false
expect "a version that Maven Central is still syncing fails" "Maven Central serves only some files of 1.0.0" \
  state 404 200 404 404 404
expect "  ... the javadoc jar too" "missing: mobile-push-1.0.0-javadoc.jar" state 200 200 200 200 404
expect "an unexpected answer" "" state 404 404 500 404 404
expect_state "  ... uploads, and the Central Portal refuses a version it already holds" false
expect_output "  ... with a warning" "::warning::"
expect "no GITHUB_OUTPUT fails" "needs GITHUB_OUTPUT" env -u GITHUB_OUTPUT \
  bash "$top/.github/scripts/publication-state.sh" https://repo1.maven.org/maven2 com/salla mobile-push 1.0.0

echo "== release-notes.sh"
mkdir -p "$scratch/notes"
notes_changelog="$scratch/notes/CHANGELOG.md"
printf '# Changelog\n\n## [Unreleased]\n\n- Next.\n\n## [1.2.0] - 2026-10-20\n\n### Added\n\n- A change.\n\n## [1.1.0] - 2026-10-01\n\n- An older change.\n\n## [1.0.0]\n' \
  >"$notes_changelog"
notes() {
  bash "$top/.github/scripts/release-notes.sh" "$@"
}
expect "the notes of a release are its CHANGELOG entry" "" notes 1.2.0 "$notes_changelog"
if [ "$(sed -n 1p "$out")" = "### Added" ] && [ "$(sed -n '$p' "$out")" = "- A change." ]; then
  ok "  ... from its first line to its last, without its heading"
else
  not_ok "  ... from its first line to its last, without its heading" "got: $(tr '\n' '|' <"$out")"
fi
expect "a version without an entry fails" "must have one '## [1.3.0]' heading" notes 1.3.0 "$notes_changelog"
expect "an empty entry fails" "the CHANGELOG.md entry of 1.0.0 is empty" notes 1.0.0 "$notes_changelog"
printf '\n## [1.1.0] - 2026-10-02\n\n- Twice.\n' >>"$notes_changelog"
expect "a version with two entries fails" "must have one '## [1.1.0]' heading" notes 1.1.0 "$notes_changelog"

echo
echo "self-test.sh: $passed passed, $failed failed"
[ "$failed" -eq 0 ]
