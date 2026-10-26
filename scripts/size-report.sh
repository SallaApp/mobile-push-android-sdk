#!/usr/bin/env bash
# Measures what SallaPush adds to an Android app, and fails when it adds more than the budget (5 MB, the number
# CONTRIBUTING.md states). It builds the sample's release APK (R8 and resource shrinking, as an app's store
# build) three times: as it is, and as two size baselines, the same app without SallaPush, with and without Firebase
# Messaging (sample/build.gradle.kts). Then it prints:
#   - the AAR, and every artifact an app resolves with it (the sample's release runtime classpath), with its size and
#     the SallaPush dependencies that bring it;
#   - the three APK sizes, what each part of them weighs, and what SallaPush adds;
#   - where the SallaPush app's dex bytes go, by artifact (the dex files are parsed here, R8's mapping undoes the
#     renaming);
#   - WorkManager's share (SallaPush depends on WorkManager for durable retries) and what SallaPush adds to the
#     merged manifest;
#   - the budget check, last. The budget is for SallaPush's own APK delta, without Firebase Messaging and without the
#     AndroidX libraries the app already ships. The check measures what SallaPush adds to the release APK of the app
#     with Firebase Messaging, which also counts every AndroidX library SallaPush brings (WorkManager among them): an
#     upper bound of that measure.
# It also fails when a build fails, and when the Firebase Messaging baseline no longer has the version SallaPush
# resolves, which would make the comparison wrong.
#
#   scripts/size-report.sh      run from anywhere; needs JDK 17, the Android SDK (ANDROID_HOME) and python3.
#                               The report goes to stdout and to sample/build/size-report/report.txt, the Gradle output
#                               to sample/build/size-report/gradle.log, and the measured files to a folder per build
#                               beside them.
#
# Environment:
#   SIZE_REPORT_BUDGET_BYTES    the budget in bytes (default: 5242880, the 5 MB ceiling)
set -euo pipefail

android="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
out="$android/sample/build/size-report"
budget="${SIZE_REPORT_BUDGET_BYTES:-5242880}"

case "$budget" in
  '' | *[!0-9]*)
    echo "size-report: SIZE_REPORT_BUDGET_BYTES must be a whole number of bytes, got '$budget'" >&2
    exit 1
    ;;
esac

command -v python3 >/dev/null 2>&1 || {
  echo "size-report: python3 not found" >&2
  exit 1
}
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -n "${ANDROID_SDK_ROOT:-}" ]; then
    export ANDROID_HOME="$ANDROID_SDK_ROOT"
  elif [ -d "$HOME/Library/Android/sdk" ]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  else
    echo "size-report: set ANDROID_HOME to the Android SDK" >&2
    exit 1
  fi
fi

rm -rf "$out"
mkdir -p "$out"

gradle() {
  local step="$1"
  shift
  echo "size-report: building $step" >&2
  if ! (cd "$android" && ./gradlew --no-daemon --console=plain "$@") >>"$out/gradle.log" 2>&1; then
    tail -n 40 "$out/gradle.log" >&2
    echo "size-report: building $step failed; the whole log is $out/gradle.log" >&2
    exit 1
  fi
}

# The three builds run the same :sample tasks, and Gradle may delete what a task produced for one build when it runs
# the task for the next, so each build's APK, R8 mapping and manifest merge blame report are copied to $out/<name>
# right after it.
keep() {
  local name="$1" build="$2" apk count=0 blame
  mkdir -p "$out/$name"
  for apk in "$build"/outputs/apk/release/*.apk; do
    [ -f "$apk" ] || continue
    count=$((count + 1))
    cp "$apk" "$out/$name/release.apk"
  done
  if [ "$count" -ne 1 ]; then
    echo "size-report: expected one release APK in $build/outputs/apk/release, found $count" >&2
    exit 1
  fi
  if [ -f "$build/outputs/mapping/release/mapping.txt" ]; then
    cp "$build/outputs/mapping/release/mapping.txt" "$out/$name/mapping.txt"
  fi
  if [ -d "$build/intermediates" ]; then
    blame="$(find "$build/intermediates" -name manifest-merger-blame-release-report.txt -print -quit)"
    if [ -n "$blame" ]; then
      cp "$blame" "$out/$name/manifest-merger-blame-release-report.txt"
    fi
  fi
}

gradle "the sample with SallaPush" -I "$android/scripts/size-report.init.gradle.kts" \
  :sallapush:assembleRelease :sample:assembleRelease :sample:sallapushSizeClasspath \
  "-PsallapushSizeClasspathFile=$out/classpath.tsv"
aar="$android/sallapush/build/outputs/aar/sallapush-release.aar"
if [ ! -f "$aar" ]; then
  echo "size-report: $aar was not built" >&2
  exit 1
fi
cp "$aar" "$out/sallapush-release.aar"
keep sallapush "$android/sample/build"
gradle "the sample without SallaPush, with Firebase Messaging" :sample:assembleRelease \
  -Psallapush.sample.sizeBaseline=fcm
keep fcm "$android/sample/build/size-baseline/fcm"
gradle "the sample without SallaPush and Firebase" :sample:assembleRelease -Psallapush.sample.sizeBaseline=bare
keep bare "$android/sample/build/size-baseline/bare"

python3 - "$android" "$out" "$budget" <<'PY' | tee "$out/report.txt"
import io
import os
import re
import struct
import sys
import zipfile
from xml.parsers import expat

android, out, budget = sys.argv[1], sys.argv[2], int(sys.argv[3])
# What each build left in $out/<name>: release.apk, and mapping.txt and the manifest blame report when they exist.
builds = {name: os.path.join(out, name) for name in ("sallapush", "fcm", "bare")}


def fail(message):
    print(f"size-report: {message}", file=sys.stderr)
    sys.exit(1)


def number(value):
    return f"{value:,}"


def kib(value):
    return f"{value / 1024:,.1f} KiB"


def mib(value):
    return f"{value / 1048576:,.2f} MiB"


def brought_by(labels):
    """The SallaPush dependencies that bring an artifact, by artifact name."""
    return ", ".join(label.split(":")[-1] for label in labels) or "-"


def signed(value):
    return ("+" if value >= 0 else "-") + number(abs(value))


properties = open(os.path.join(android, "gradle.properties"), encoding="utf-8").read()
version = re.search(r"^\s*VERSION_NAME\s*=\s*(\S+)\s*$", properties, re.MULTILINE).group(1)
group = re.search(r"^\s*GROUP\s*=\s*(\S+)\s*$", properties, re.MULTILINE).group(1)
sdk = f"{group}:mobile-push:{version}"
aar = os.path.join(out, "sallapush-release.aar")
apks = {name: os.path.join(build, "release.apk") for name, build in builds.items()}

# --- Classes of each artifact ---------------------------------------------------------------------------------------


def class_names(path):
    names = set()

    def scan(archive):
        for entry in archive.namelist():
            if (entry.endswith(".class") and not entry.startswith("META-INF/")
                    and not entry.endswith("module-info.class")):
                names.add(entry[:-len(".class")].replace("/", "."))

    with zipfile.ZipFile(path) as archive:
        if path.endswith(".aar"):
            for entry in archive.namelist():
                if entry == "classes.jar" or (entry.startswith("libs/") and entry.endswith(".jar")):
                    with zipfile.ZipFile(io.BytesIO(archive.read(entry))) as jar:
                        scan(jar)
        else:
            scan(archive)
    return names


classpath = {}
for line in open(os.path.join(out, "classpath.tsv"), encoding="utf-8"):
    if not line.strip():
        continue
    coordinate, path, required = line.rstrip("\n").split("\t")
    classpath.setdefault(coordinate, {"files": [], "required": [entry for entry in required.split(",") if entry]})
    if path not in classpath[coordinate]["files"]:
        classpath[coordinate]["files"].append(path)
# The FCM baseline must hold the Firebase Messaging that SallaPush resolves, or its difference would count as
# SallaPush's.
baseline_fcm = re.search(r'"fcm"\s*->\s*implementation\("com\.google\.firebase:firebase-messaging:([^"]+)"\)',
                         open(os.path.join(android, "sample", "build.gradle.kts"), encoding="utf-8").read())
if baseline_fcm is None:
    fail("sample/build.gradle.kts declares no firebase-messaging dependency for the fcm baseline")
resolved_fcm = sorted(coordinate.rsplit(":", 1)[1] for coordinate in classpath
                      if coordinate.startswith("com.google.firebase:firebase-messaging:"))
if resolved_fcm != [baseline_fcm.group(1)]:
    fail(f"the fcm baseline uses firebase-messaging {baseline_fcm.group(1)}, but SallaPush resolves "
         f"{', '.join(resolved_fcm) or 'none'}; give the baseline in sample/build.gradle.kts SallaPush's version")
direct = sorted({label for entry in classpath.values() for label in entry["required"]})
owner = {}
for coordinate in sorted(classpath):
    for path in classpath[coordinate]["files"]:
        for name in class_names(path):
            owner.setdefault(name, coordinate)
sdk_classes = class_names(aar)
for name in sdk_classes:
    owner[name] = sdk
required_by = {coordinate: entry["required"] for coordinate, entry in classpath.items()}
required_by[sdk] = ["SallaPush"]


def owner_of(name):
    """The artifact a class comes from: R8's synthetic classes belong to the class they were made for."""
    if name in owner:
        return owner[name]
    candidate = name.split("$$", 1)[0]
    while True:
        if candidate in owner:
            return owner[candidate]
        if "$" not in candidate:
            break
        candidate = candidate[:candidate.rfind("$")]
    if name.startswith("com.salla.push.sample."):
        return "the sample's own code"
    return None


# --- Dex files ------------------------------------------------------------------------------------------------------


def uleb(data, offset):
    result, shift = 0, 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        if byte < 0x80:
            return result, offset
        shift += 7


def sleb(data, offset):
    result, shift = 0, 0
    while True:
        byte = data[offset]
        offset += 1
        result |= (byte & 0x7F) << shift
        shift += 7
        if byte < 0x80:
            return (result - (1 << shift) if byte & 0x40 else result), offset


def code_item_size(data, offset):
    tries, _, instructions = struct.unpack_from("<HII", data, offset + 6)
    size = 16 + 2 * instructions
    if tries:
        size += (2 if instructions % 2 else 0) + 8 * tries
        handlers_start = offset + size
        count, cursor = uleb(data, handlers_start)
        for _ in range(count):
            handlers, cursor = sleb(data, cursor)
            for _ in range(abs(handlers)):
                _, cursor = uleb(data, cursor)
                _, cursor = uleb(data, cursor)
            if handlers <= 0:
                _, cursor = uleb(data, cursor)
        size += cursor - handlers_start
    return size


def dex_class_sizes(data):
    """Bytes per class defined in a dex file: its class definition, class data and code."""
    if data[:4] != b"dex\n":
        fail("a classes*.dex entry of the APK is not a dex file")
    (_, string_ids_offset, _, type_ids_offset, _, _, _, _, _, _, class_count, class_defs_offset) = \
        struct.unpack_from("<12I", data, 0x38)

    def type_name(index):
        string_index = struct.unpack_from("<I", data, type_ids_offset + 4 * index)[0]
        offset = struct.unpack_from("<I", data, string_ids_offset + 4 * string_index)[0]
        _, offset = uleb(data, offset)
        return data[offset:data.index(b"\0", offset)].decode("utf-8", "replace")

    sizes = {}
    for index in range(class_count):
        definition = struct.unpack_from("<8I", data, class_defs_offset + 32 * index)
        class_index, class_data_offset = definition[0], definition[6]
        size = 32
        if class_data_offset:
            cursor = class_data_offset
            counts = []
            for _ in range(4):
                value, cursor = uleb(data, cursor)
                counts.append(value)
            for _ in range(counts[0] + counts[1]):
                _, cursor = uleb(data, cursor)
                _, cursor = uleb(data, cursor)
            code_offsets = []
            for _ in range(counts[2] + counts[3]):
                _, cursor = uleb(data, cursor)
                _, cursor = uleb(data, cursor)
                code_offset, cursor = uleb(data, cursor)
                if code_offset:
                    code_offsets.append(code_offset)
            size += cursor - class_data_offset + sum(code_item_size(data, offset) for offset in code_offsets)
        descriptor = type_name(class_index)
        sizes[descriptor[1:-1].replace("/", ".")] = size
    return sizes


def apk_parts(path):
    parts = {"dex": 0, "resources.arsc": 0, "res/": 0, "AndroidManifest.xml": 0, "other": 0}
    dex_uncompressed, dex_files = 0, []
    with zipfile.ZipFile(path) as archive:
        for info in archive.infolist():
            name = info.filename
            if re.fullmatch(r"classes\d*\.dex", name):
                key = "dex"
                dex_uncompressed += info.file_size
                dex_files.append(archive.read(name))
            elif name in ("resources.arsc", "AndroidManifest.xml"):
                key = name
            elif name.startswith("res/"):
                key = "res/"
            else:
                key = "other"
            parts[key] += info.compress_size
    return {"size": os.path.getsize(path), "parts": parts, "dex_uncompressed": dex_uncompressed, "dex": dex_files}


# --- Merged manifests -----------------------------------------------------------------------------------------------

COMPONENTS = {"activity", "activity-alias", "service", "receiver", "provider"}
# A blame line: "<merged line>--><[library] ><file>:<line>:<column>[-<line>[:<column>]]". A merged line that only closes
# a comment ("12-->") has no location, so it is content.
BLAME = re.compile(r"^(\d+)-->(?:\[([^\]]+)\] )?(.+):(\d+):(\d+)(?:-(\d+)(?::(\d+))?)?$")


def manifest_entries(build):
    """(kind, name) -> the library that brought it, from the merge blame report of a release build."""
    path = os.path.join(build, "manifest-merger-blame-release-report.txt")
    if not os.path.isfile(path):
        return None
    content, sources = {}, {}
    for raw in open(path, encoding="utf-8"):
        line = raw.rstrip("\n")
        blame = BLAME.match(line)
        if blame:
            sources.setdefault(int(blame.group(1)), blame.group(2) or "the app")
            continue
        text = re.match(r"^(\d+)(.*)$", line)
        if text:
            content[int(text.group(1))] = text.group(2)
    entries, stack = {}, []
    parser = expat.ParserCreate()

    def start(tag, attributes):
        parent = stack[-1] if stack else None
        stack.append(tag)
        name = attributes.get("android:name")
        if name is None:
            return
        if tag in ("uses-permission", "permission") or tag in COMPONENTS:
            kind = tag
        elif tag == "meta-data" and parent == "provider":
            kind = "startup initializer"
        else:
            return
        library = sources.get(parser.CurrentLineNumber, "the app")
        entries[(kind, name)] = sdk if library == ":sallapush" else library

    parser.StartElementHandler = start
    parser.EndElementHandler = lambda tag: stack.pop()
    parser.Parse("\n".join(content.get(index, "") for index in range(1, max(content) + 1)), True)
    return entries


def module(coordinate):
    """group:name of a group:name:version coordinate; other labels as they are."""
    parts = coordinate.split(":")
    return ":".join(parts[:2]) if len(parts) == 3 else coordinate


def dex_by_module(name):
    """Dex bytes per artifact (group:name) of one build, its classes named back through that build's R8 mapping."""
    renamed = {}
    mapping = os.path.join(builds[name], "mapping.txt")
    if os.path.isfile(mapping):
        for line in open(mapping, encoding="utf-8"):
            if line[:1] in (" ", "\t", "#") or " -> " not in line or not line.rstrip().endswith(":"):
                continue
            original, obfuscated = line.rstrip()[:-1].split(" -> ", 1)
            renamed[obfuscated.strip()] = original.strip()
    sizes, attributed = {}, 0
    for data in measured[name]["dex"]:
        for class_name, size in dex_class_sizes(data).items():
            artifact = owner_of(renamed.get(class_name, class_name))
            key = module(artifact) if artifact else "classes R8 created that match no artifact"
            sizes[key] = sizes.get(key, 0) + size
            attributed += size
    total = sum(len(data) for data in measured[name]["dex"])
    return sizes, total - attributed, total


# --- The report -----------------------------------------------------------------------------------------------------

measured = {name: apk_parts(path) for name, path in apks.items()}
print(f"SallaPush Android size report: {sdk}")
print("Release builds of the sample: R8 with no rule of the app's own (Android's default optimize rules and the")
print("libraries' consumer rules, SallaPush's included) and resource shrinking. Sizes in bytes.")
print()

with zipfile.ZipFile(aar) as archive:
    classes_jar = archive.getinfo("classes.jar").file_size
print("The library")
print(f"  {'sallapush-release.aar':<58}{number(os.path.getsize(aar)):>12}  ({kib(os.path.getsize(aar))})")
print(f"  {'  classes.jar inside it (' + number(len(sdk_classes)) + ' classes)':<58}{number(classes_jar):>12}")
print()

print("What an app resolves with it: the sample's release runtime classpath")
print(f"  {'artifact':<58}{'size':>12}  brought by")
total = 0
for coordinate in sorted(classpath):
    size = sum(os.path.getsize(path) for path in classpath[coordinate]["files"])
    total += size
    print(f"  {coordinate:<58}{number(size):>12}  {brought_by(classpath[coordinate]['required'])}")
print(f"  {'Total, ' + str(len(classpath)) + ' artifacts besides SallaPush':<58}{number(total):>12}  ({kib(total)})")
print(f"  SallaPush's own dependencies, as \"brought by\" names them: {', '.join(direct)}")
print()

labels = {"bare": "the app without push", "fcm": "the app with Firebase Messaging",
          "sallapush": "the app with SallaPush"}
print("The sample's release APK")
print(f"  {'':<34}{'APK':>11}{'dex':>11}{'arsc':>10}{'res/':>10}{'other':>10}{'dex unzipped':>14}")
for name in ("bare", "fcm", "sallapush"):
    entry = measured[name]
    parts = entry["parts"]
    print(f"  {labels[name]:<34}{number(entry['size']):>11}{number(parts['dex']):>11}"
          f"{number(parts['resources.arsc']):>10}"
          f"{number(parts['res/']):>10}{number(parts['AndroidManifest.xml'] + parts['other']):>10}"
          f"{number(entry['dex_uncompressed']):>14}")
over_fcm = measured["sallapush"]["size"] - measured["fcm"]["size"]
over_bare = measured["sallapush"]["size"] - measured["bare"]["size"]
fcm_cost = measured["fcm"]["size"] - measured["bare"]["size"]
print(f"  SallaPush adds {signed(over_fcm)} bytes ({kib(over_fcm)}) to an app that already uses Firebase Messaging,")
print(f"  and {signed(over_bare)} bytes ({kib(over_bare)}) to an app without push (Firebase Messaging alone: "
      f"{signed(fcm_cost)}).")
print()

before, before_shared, before_total = dex_by_module("fcm")
after, after_shared, after_total = dex_by_module("sallapush")
brought = {module(coordinate): labels_ for coordinate, labels_ in required_by.items()}
rows = sorted(set(before) | set(after), key=lambda key: (-(after.get(key, 0) - before.get(key, 0)), key))
changed = [key for key in rows if after.get(key, 0) != before.get(key, 0)]
same = [key for key in rows if after.get(key, 0) == before.get(key, 0)]
print("Where the unzipped dex bytes go, by artifact: the app with Firebase Messaging, and with SallaPush")
print("  (class definitions, class data and code, named back through R8's mappings; R8 merges and inlines across "
      "classes,")
print("  so the split is approximate, while the totals are exact)")
print(f"  {'artifact':<54}{'FCM app':>10}{'SallaPush app':>15}{'added':>11}  brought by")
for key in changed:
    added_bytes = after.get(key, 0) - before.get(key, 0)
    print(f"  {key:<54}{number(before.get(key, 0)):>10}{number(after.get(key, 0)):>15}{signed(added_bytes):>11}  "
          f"{brought_by(brought.get(key, []))}")
print(f"  {str(len(same)) + ' artifacts the same in both apps':<54}"
      f"{number(sum(before.get(key, 0) for key in same)):>10}"
      f"{number(sum(after.get(key, 0) for key in same)):>15}{'0':>11}")
print(f"  {'shared tables (strings, types, member references)':<54}{number(before_shared):>10}"
      f"{number(after_shared):>15}{signed(after_shared - before_shared):>11}")
print(f"  {'Total dex':<54}{number(before_total):>10}{number(after_total):>15}{signed(after_total - before_total):>11}")
print()

work_only = sorted(key for key, labels_ in brought.items() if labels_ == ["androidx.work:work-runtime"])
work_added = sum(after.get(key, 0) - before.get(key, 0) for key in work_only)
grown = [key for key in changed if key not in work_only and module(sdk) != key and key in brought
         and "androidx.work:work-runtime" in brought[key] and after.get(key, 0) > before.get(key, 0)]
print("WorkManager's share")
print(f"  Dex added by the artifacts that only androidx.work:work-runtime brings: {signed(work_added)} "
      f"({100.0 * work_added / (after_total - before_total) if after_total > before_total else 0:.1f}% of what "
      "SallaPush adds)")
for key in work_only:
    print(f"    {key:<52}{signed(after.get(key, 0) - before.get(key, 0)):>11}")
if grown:
    print("  Artifacts WorkManager shares with SallaPush or Firebase that grew (part of their growth is "
          "WorkManager's):")
    for key in grown:
        print(f"    {key:<52}{signed(after.get(key, 0) - before.get(key, 0)):>11}")

with_sdk = manifest_entries(builds["sallapush"])
without_sdk = manifest_entries(builds["fcm"])
if with_sdk is None or without_sdk is None:
    print("  (no manifest merge blame report was found, so the manifest is not compared)")
else:
    added = {key: library for key, library in with_sdk.items() if key not in without_sdk}
    work_libraries = {coordinate for coordinate, labels_ in required_by.items()
                      if labels_ == ["androidx.work:work-runtime"]}
    work_entries = sorted(key for key, library in added.items() if library in work_libraries)
    print(f"  Its manifest entries ({len(work_entries)}):")
    for kind, name in work_entries:
        print(f"    {kind} {name}")
    print()
    print("What SallaPush adds to the merged manifest, compared with the app with Firebase Messaging")
    for library in sorted(set(added.values())):
        print(f"  from {library}:")
        for kind, name in sorted(key for key, owner_ in added.items() if owner_ == library):
            print(f"    {kind} {name}")

# The budget: SallaPush's own APK delta, without Firebase Messaging and the AndroidX libraries the app already ships,
# stays under it. What SallaPush adds to the app with Firebase Messaging bounds it from above.
print()
print("Budget")
print("  Measured: what SallaPush adds to the release APK of an app that already uses Firebase Messaging. It also")
print("  counts every AndroidX library SallaPush brings, which many apps ship already: an upper bound of SallaPush's")
print("  own share.")
within = over_fcm <= budget
spare = abs(budget - over_fcm)
margin = f"{number(spare)} byte{'' if spare == 1 else 's'} {'to spare' if within else 'over it'}"
print(f"{'PASS' if within else 'FAIL'}: SallaPush adds {number(over_fcm)} bytes ({kib(over_fcm)}) to the release "
      f"APK of an app that already uses Firebase Messaging; the budget is {number(budget)} bytes ({mib(budget)}): "
      f"{margin}.")
sys.exit(0 if within else 1)
PY
