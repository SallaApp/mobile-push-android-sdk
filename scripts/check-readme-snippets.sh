#!/usr/bin/env bash
# Keeps README.md's code identical to code that builds. Every Kotlin, Java, XML,
# Gradle and JSON code block in the README follows a marker line:
#
#   <!-- snippet:<path> -->              the block equals that file, byte for byte
#   <!-- snippet:<path>#<name> -->       the block equals the region between `// snippet:<name>` and `// end-snippet`
#                                        (`<!-- snippet:<name> -->` and `<!-- end-snippet -->` in XML) of that file,
#                                        without the region's common indentation
#   <!-- snippet:<path>#<name> mavenLocal=<path>#<name> -->
#                                        the same, and the second region is the block plus an exclusiveContent part
#                                        that takes com.salla:mobile-push from mavenLocal() only: the consumer check
#                                        resolves the README's repository block, with the local Maven repository
#                                        standing in for Maven Central
#   <!-- unchecked: <reason> -->         the block is not code a build can compile
#
# Paths are relative to this folder and stay inside sample/ or consumer-check/. A block counts only when its code is
# compiled or evaluated by a gating build: `./gradlew check :sample:assembleRelease` compiles the sample's main, debug
# and release source sets and evaluates its build script; `./gradlew -p consumer-check assembleDebug` compiles the
# consumer check's app (main and debug source sets) and evaluates its build scripts, the settings scripts of the builds
# it includes (consumer-check/readme-repositories), and the build scripts of those whose tasks it runs
# (gradle.includedBuild("<name>") in a consumer-check build script). Within such a file only code the compiler reads
# counts: the check fails when a region marker sits inside a /* */ comment, a string literal (a Kotlin raw string, a
# Java text block, a Groovy string) or, in XML, a comment, a CDATA section or a tag. A whole JSON file shown must parse.
#
# The check also fails:
#   - when the README does not show the sample's SampleMessagingService.kt in full, or the initialization lines of its
#     SampleApp.kt (the region 'initialize');
#   - when a repository block of the README doesn't declare mavenCentral(), where SallaPush is published;
#   - when the README's text names a log code LogCode.kt doesn't have, or gives a code another level ("DEBUG `code`",
#     "`code`, at INFO"); docs/LOGGING.md lists every code;
#   - when a version the README pins differs from gradle.properties' VERSION_NAME; when the README does not name each of
#     the library's dependencies with its version, or names another version; when it does not name the library's
#     minimum API level;
#   - when the docs advise a login from the login failure observer: README.md or docs/REFERENCE.md must have a
#     TOKEN_REJECTED row, every such row must say RELOGIN_ADVICE, and no code block of either that handles
#     TOKEN_REJECTED may call login().
#
#   scripts/check-readme-snippets.sh               run from anywhere; exits 1 on drift, with the reason per problem
#   scripts/check-readme-snippets.sh --self-test   proves, on scratch copies, that every kind of drift is caught
set -euo pipefail

android="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
case "${1:-}" in
  '' | --self-test) ;;
  *)
    echo "usage: scripts/check-readme-snippets.sh [--self-test]" >&2
    exit 2
    ;;
esac
command -v python3 >/dev/null 2>&1 || {
  echo "check-readme-snippets: python3 not found" >&2
  exit 1
}

python3 - "$android" "${1:-}" <<'PY'
import difflib
import json
import os
import re
import shutil
import sys
import tempfile

# The README shows this sample file in full, and these regions of the sample: its quick start is the sample's code.
REQUIRED_FILES = ("sample/src/main/java/com/salla/push/sample/SampleMessagingService.kt",)
REQUIRED_REGIONS = (("sample/src/main/java/com/salla/push/sample/SampleApp.kt", "initialize"),)
# The docs that may hold the login failures table; one of them must.
LOGIN_FAILURE_DOCS = ("README.md", "docs/REFERENCE.md")
SOURCE_ROOTS = ("sample", "consumer-check")
SOURCE_EXTENSIONS = (".kt", ".kts", ".java", ".xml", ".gradle")
SKIPPED_FOLDERS = {"build", ".gradle", ".kotlin", ".idea"}
CODE_LANGUAGES = {"kotlin", "kt", "kts", "java", "xml", "groovy", "gradle", "json"}
SIZE_SCRIPT = "scripts/size-report.sh"
LEVELS = ("error", "warn", "info", "debug", "verbose")
GATING_BUILDS = "`./gradlew check :sample:assembleRelease` or `./gradlew -p consumer-check assembleDebug`"
# A token that couldn't be checked is refused too, so a login sent again from onLoginFailure gets the same refusal, over
# and over. The TOKEN_REJECTED row says so in these words.
RELOGIN_ADVICE = "not from `onLoginFailure`"


def read_text(path):
    with open(path, encoding="utf-8") as handle:
        return handle.read()


def passage_end(lines, number):
    """Where the README passage that starts at line `number` ends (exclusive): a table row is one line, a bullet runs
    through its indented continuation lines, and a paragraph runs to the next blank line or block."""
    if lines[number].startswith("|"):
        return number + 1
    bullet = lines[number].startswith("- ")
    end = number + 1
    while end < len(lines) and lines[end].strip():
        if (not lines[end].startswith("  ") if bullet
                else lines[end].startswith(("- ", "|", "#", "<!--", "```", ">"))):
            break
        end += 1
    return end


def fenced_blocks(lines):
    """(line number of the opening fence, body lines) of each fenced code block of a Markdown document."""
    blocks, index = [], 0
    fence_open = re.compile(r"^\s*(`{3,}|~{3,})")
    while index < len(lines):
        opening = fence_open.match(lines[index])
        if not opening:
            index += 1
            continue
        fence, start, body = opening.group(1), index, []
        index += 1
        while index < len(lines) and not (lines[index].strip().startswith(fence[0] * len(fence))
                                          and set(lines[index].strip()) == {fence[0]}):
            body.append(lines[index])
            index += 1
        blocks.append((start + 1, body))
        index += 1
    return blocks


def dedent(lines):
    indents = [len(line) - len(line.lstrip(" ")) for line in lines if line.strip()]
    cut = min(indents) if indents else 0
    return [line[cut:] if line.strip() else "" for line in lines]


def as_text(lines):
    return "".join(line + "\n" for line in lines)


def braces(line):
    """The change in brace depth of a line, braces inside string literals not counted."""
    depth, quoted = 0, False
    for index, char in enumerate(line):
        if char == '"' and (index == 0 or line[index - 1] != "\\"):
            quoted = not quoted
        elif not quoted and char == "{":
            depth += 1
        elif not quoted and char == "}":
            depth -= 1
    return depth


def diff(expected, shown, label):
    return "".join(difflib.unified_diff(expected.splitlines(True), shown.splitlines(True), label, "README.md"))


# --- How the compiler reads a source file ---------------------------------------------------------------------------

CODE, LINE_COMMENT, BLOCK_COMMENT, STRING = "code", "a // comment", "a /* */ comment", "a string literal"
XML_TEXT, XML_COMMENT, XML_CDATA, XML_MARKUP = "text", "an XML comment", "a CDATA section", "a tag or declaration"


def language_of(path):
    if path.endswith((".kt", ".kts")):
        return "kotlin"
    if path.endswith(".java"):
        return "java"
    if path.endswith(".gradle"):
        return "groovy"
    if path.endswith(".xml"):
        return "xml"
    return None


def scan_source(text, language, blank_literals=False):
    """How the compiler reads a Kotlin (also .kts), Java or Groovy source: the context each line ("\\n"-separated)
    starts in (CODE, BLOCK_COMMENT or STRING), and the text with every comment blanked out, newlines kept; with
    blank_literals, the contents of string and character literals too, except the code of ${...} templates. The lexer
    knows each language's comments (Kotlin's /* */ nest), string and character literals, Kotlin raw strings, Java text
    blocks, Groovy's quoted and triple-quoted strings, and the ${...} templates of Kotlin and Groovy strings. Groovy's
    slashy strings are not recognised: telling one from a division takes a parser, and no snippet file has one."""
    kotlin, groovy = language == "kotlin", language == "groovy"
    contexts, blanked = [], list(text)
    # Innermost last: ["code", open braces of the ${...} template it is, or None], ["line"], ["block", depth],
    # ["string", quote, triple-quoted, has templates], ["char"] or ["name"] (a Kotlin `backtick` name).
    stack = [["code", None]]
    position, length = 0, len(text)

    def context():
        for frame in reversed(stack):
            if frame[0] == "block":
                return BLOCK_COMMENT
            if frame[0] in ("string", "char", "name"):
                return STRING
            if frame[0] == "line":
                return LINE_COMMENT
        return CODE

    def blank(start, end):
        for index in range(start, min(end, length)):
            if blanked[index] != "\n":
                blanked[index] = " "

    def escape_end(at):
        # A backslash and the character it escapes; a backslash before a newline leaves the newline to the main loop.
        return at + 2 if at + 1 < length and text[at + 1] != "\n" else at + 1

    contexts.append(context())
    while position < length:
        character, top = text[position], stack[-1]
        kind = top[0]
        if character == "\n":
            if kind in ("line", "char", "name") or (kind == "string" and not top[2]):
                stack.pop()
            position += 1
            contexts.append(context())
        elif kind == "line":
            blank(position, position + 1)
            position += 1
        elif kind == "block":
            if text.startswith("*/", position):
                blank(position, position + 2)
                position += 2
                top[1] -= 1
                if top[1] == 0:
                    stack.pop()
            elif kotlin and text.startswith("/*", position):
                blank(position, position + 2)
                position += 2
                top[1] += 1
            else:
                blank(position, position + 1)
                position += 1
        elif kind == "string":
            quote, triple, templates = top[1], top[2], top[3]
            if character == "\\" and not (kotlin and triple):
                end = escape_end(position)
                if blank_literals:
                    blank(position, end)
                position = end
            elif templates and text.startswith("${", position):
                stack.append(["code", 1])
                position += 2
            elif text.startswith(quote * 3 if triple else quote, position):
                position += 3 if triple else 1
                if kotlin and triple:
                    # In a run of more than three quotes, the last three close the raw string.
                    while position < length and text[position] == '"':
                        position += 1
                stack.pop()
            else:
                if blank_literals:
                    blank(position, position + 1)
                position += 1
        elif kind == "char":
            if character == "\\":
                end = escape_end(position)
                if blank_literals:
                    blank(position, end)
                position = end
            else:
                if character == "'":
                    stack.pop()
                elif blank_literals:
                    blank(position, position + 1)
                position += 1
        elif kind == "name":
            if character == "`":
                stack.pop()
            position += 1
        elif text.startswith("//", position):
            stack.append(["line"])
            blank(position, position + 2)
            position += 2
        elif text.startswith("/*", position):
            stack.append(["block", 1])
            blank(position, position + 2)
            position += 2
        elif text.startswith('"""', position):
            stack.append(["string", '"', True, language != "java"])
            position += 3
        elif character == '"':
            stack.append(["string", '"', False, language != "java"])
            position += 1
        elif groovy and text.startswith("'''", position):
            stack.append(["string", "'", True, False])
            position += 3
        elif character == "'":
            stack.append(["string", "'", False, False] if groovy else ["char"])
            position += 1
        elif kotlin and character == "`":
            stack.append(["name"])
            position += 1
        else:
            if top[1] is not None and character == "{":
                top[1] += 1
            elif top[1] is not None and character == "}":
                top[1] -= 1
                if top[1] == 0:
                    stack.pop()
            position += 1
    return contexts, "".join(blanked)


def scan_xml(text):
    """The context each line of an XML document starts in: XML_TEXT (between elements), or inside a comment, a CDATA
    section, or a tag, processing instruction or declaration (attribute values included)."""
    contexts, state, quote = [XML_TEXT], XML_TEXT, None
    position, length = 0, len(text)
    while position < length:
        character = text[position]
        if character == "\n":
            position += 1
            contexts.append(state)
        elif state == XML_TEXT:
            if text.startswith("<!--", position):
                state, position = XML_COMMENT, position + 4
            elif text.startswith("<![CDATA[", position):
                state, position = XML_CDATA, position + 9
            elif character == "<":
                state, position = XML_MARKUP, position + 1
            else:
                position += 1
        elif state == XML_COMMENT:
            if text.startswith("-->", position):
                state, position = XML_TEXT, position + 3
            else:
                position += 1
        elif state == XML_CDATA:
            if text.startswith("]]>", position):
                state, position = XML_TEXT, position + 3
            else:
                position += 1
        else:
            if quote:
                if character == quote:
                    quote = None
            elif character in "\"'":
                quote = character
            elif character == ">":
                state = XML_TEXT
            position += 1
    return contexts


def live_code(path):
    """A Kotlin, Java or Groovy file with its comments blanked out; "" when it does not exist."""
    if not os.path.isfile(path):
        return ""
    return scan_source(read_text(path), language_of(path))[1]


def calls_only(path):
    """A Kotlin or Java file with its comments and the contents of its literals blanked out: what is left are the
    names and calls the compiler compiles."""
    return scan_source(read_text(path), language_of(path), blank_literals=True)[1]


def closing(code, opening_at):
    """The index just after the bracket that closes the one at `opening_at` in `code`, a source whose comments and
    literals are blanked, so its brackets balance; None when it never closes."""
    pairs = {"(": ")", "{": "}"}
    opener, depth = code[opening_at], 0
    for index in range(opening_at, len(code)):
        if code[index] == opener:
            depth += 1
        elif code[index] == pairs[opener]:
            depth -= 1
            if depth == 0:
                return index + 1
    return None


def function_span(code, name):
    """Where the first Kotlin function called `name` (an extension's receiver allowed) is in `code`, a source whose
    comments and literals are blanked: (the index of `fun`, of its body's opening brace, and just after the brace that
    closes it). None when there is no such function, or it has an expression body."""
    found = re.search(r"\bfun\s+(?:[\w.<>]+\s*\.\s*)?" + re.escape(name) + r"\s*\(", code)
    if found is None:
        return None
    parameters_end = closing(code, found.end() - 1)
    body = re.compile(r"\s*(?::\s*[\w.<>?, ]+?)?\s*\{").match(code, parameters_end) if parameters_end else None
    end = closing(code, body.end() - 1) if body else None
    return (found.start(), body.end() - 1, end) if end else None


def function_body(code, name):
    """The text inside the block body of the first Kotlin function called `name` in `code` (see function_span), or
    None."""
    span = function_span(code, name)
    return code[span[1] + 1:span[2] - 1] if span else None


def invalid_argument_sites(path):
    """(line, message) for each place a Kotlin source logs api.invalid_argument: a log call with
    LogCode.API_INVALID_ARGUMENT, whose message is its lambda (or its arguments, without one), and a call of
    EntryGuard.invalidArgument, whose message is the reason it is given. EntryGuard.invalidArgument itself, which logs
    its callers' reasons, is not a site."""
    text = read_text(path)
    if "API_INVALID_ARGUMENT" not in text and "invalidArgument" not in text:
        return []
    code, bare = scan_source(text, "kotlin")[1], scan_source(text, "kotlin", blank_literals=True)[1]
    helper = function_span(bare, "invalidArgument")
    sites = []
    for found in re.finditer(r"\bLogCode\s*\.\s*API_INVALID_ARGUMENT\b|\binvalidArgument\s*\(", bare):
        if helper and helper[0] <= found.start() < helper[2]:
            continue
        if found.group(0).startswith("invalidArgument"):
            start = found.end() - 1
            end = closing(bare, start)
        else:
            # The end of the log call that takes the code, then its trailing lambda when it has one.
            depth, index = 0, found.end()
            while index < len(bare) and not (bare[index] == ")" and depth == 0):
                depth += {"(": 1, ")": -1}.get(bare[index], 0)
                index += 1
            trailing = re.compile(r"\s*\{").match(bare, index + 1)
            start, end = (trailing.end() - 1, closing(bare, trailing.end() - 1)) if trailing else (found.end(), index)
        message = re.sub(r"\s+", " ", code[start:end]) if end else ""
        sites.append((bare.count("\n", 0, found.start()) + 1, message))
    return sites


# --- What the gating builds compile or evaluate ---------------------------------------------------------------------


def included_projects(settings_code):
    """The project folders a settings script includes (`include(":app")`, `include ':app'`), relative to its build."""
    folders = []
    for call in re.findall(r"\binclude\b\s*\(?\s*((?:['\"][^'\"]+['\"]\s*,?\s*)+)\)?", settings_code):
        folders += [name.lstrip(":").replace(":", "/") for name in re.findall(r"['\"]([^'\"]+)['\"]", call)]
    return folders


def gating_files(android):
    """A function of a path (relative to the repository's root) that returns None when a gating build compiles or
    evaluates its code, and else why not, as a phrase that follows "comes from"."""
    consumer = "consumer-check"
    evaluated = {"sample/build.gradle.kts"}
    source_sets = {"sample": ("main", "debug", "release")}
    not_evaluated = {}
    settings = f"{consumer}/settings.gradle.kts"
    settings_code = live_code(os.path.join(android, settings))
    evaluated |= {settings, f"{consumer}/build.gradle.kts"}
    for module in included_projects(settings_code):
        evaluated.add(f"{consumer}/{module}/build.gradle.kts")
        source_sets[f"{consumer}/{module}"] = ("main", "debug")
    wired = set()
    for script in sorted(path for path in evaluated if path.startswith(consumer + "/")):
        code = live_code(os.path.join(android, script))
        wired |= set(re.findall(r'\bgradle\.includedBuild\(\s*"([^"]+)"\s*\)', code))
    included = []
    for folder in re.findall(r'\bincludeBuild\(\s*"([^"]+)"', settings_code):
        base, name = f"{consumer}/{folder.strip('/')}", os.path.basename(folder.strip("/"))
        included.append(base)
        build_settings = [f"{base}/settings.gradle", f"{base}/settings.gradle.kts"]
        evaluated |= set(build_settings)
        scripts = [f"{base}/build.gradle", f"{base}/build.gradle.kts"]
        code = "".join(live_code(os.path.join(android, path)) for path in build_settings)
        for module in included_projects(code):
            scripts += [f"{base}/{module}/build.gradle", f"{base}/{module}/build.gradle.kts"]
        if name in wired:
            evaluated |= set(scripts)
        else:
            for path in scripts:
                not_evaluated[path] = (f"the build script of the included build '{name}', which Gradle never "
                                       "evaluates: no consumer-check build script runs one of its tasks "
                                       f"(gradle.includedBuild(\"{name}\"))")

    def reason(path):
        if path in evaluated:
            return None
        if path in not_evaluated:
            return not_evaluated[path]
        for module, sets in source_sets.items():
            prefix = module + "/src/"
            if not path.startswith(prefix):
                continue
            source_set, rest = path[len(prefix):].split("/")[0], path[len(prefix):].split("/")[1:]
            if source_set not in sets:
                return (f"the {source_set} source set of {module}, which the gating builds don't compile into the app "
                        f"(they compile {', '.join(sets)})")
            if rest[:1] in (["java"], ["kotlin"]) and path.endswith((".kt", ".java")):
                return None
            if rest == ["AndroidManifest.xml"] or (len(rest) == 3 and rest[0] == "res" and path.endswith(".xml")):
                return None
            if rest[:1] == ["assets"]:
                return None
            return f"a file of {module}'s {source_set} source set that the build doesn't compile"
        if any(path.startswith(base + "/") for base in included):
            return "a file of an included build that is neither its settings script nor a build script"
        return "a file that no gating build compiles or evaluates"

    return reason


# --- The check ------------------------------------------------------------------------------------------------------


def check(android):
    """The problems of README.md against the code under `android`, and a summary line."""
    problems = []
    problem = problems.append
    readme_path = os.path.join(android, "README.md")
    library = os.path.join(android, "sallapush")
    library_build = os.path.join(library, "build.gradle.kts")
    library_sources = os.path.join(library, "src", "main", "java")
    library_manifest = os.path.join(library, "src", "main", "AndroidManifest.xml")
    log_codes_path = os.path.join(library_sources, "com", "salla", "push", "internal", "log", "LogCode.kt")
    flush_worker_path = os.path.join(library_sources, "com", "salla", "push", "internal", "work", "FlushWorker.kt")
    compiled = gating_files(android)

    # Regions in the sample and the consumer check, from markers the compiler reads.
    start_markers = {False: re.compile(r"^\s*//\s*snippet:([A-Za-z0-9_-]+)\s*$"),
                     True: re.compile(r"^\s*<!--\s*snippet:([A-Za-z0-9_-]+)\s*-->\s*$")}
    end_markers = {False: re.compile(r"^\s*//\s*end-snippet\s*$"),
                   True: re.compile(r"^\s*<!--\s*end-snippet\s*-->\s*$")}
    regions = {}
    for root in SOURCE_ROOTS:
        for folder, folders, files in os.walk(os.path.join(android, root)):
            folders[:] = sorted(name for name in folders if name not in SKIPPED_FOLDERS)
            for name in sorted(files):
                if not name.endswith(SOURCE_EXTENSIONS):
                    continue
                path = os.path.join(folder, name)
                relative = os.path.relpath(path, android).replace(os.sep, "/")
                text = read_text(path)
                xml = name.endswith(".xml")
                contexts = scan_xml(text) if xml else scan_source(text, language_of(name))[0]
                live = XML_TEXT if xml else CODE
                why_not = compiled(relative)
                current, body = None, []
                for number, line in enumerate(text.split("\n"), 1):
                    start = start_markers[xml].match(line)
                    end = end_markers[xml].match(line)
                    if (start or end) and contexts[number - 1] != live:
                        marker = f"region '{start.group(1)}' starts" if start else "end-snippet sits"
                        problem(f"{relative}:{number}: {marker} inside {contexts[number - 1]}, so the compiler never "
                                "reads the region's code; move the markers into code")
                        if current:
                            body.append(line)
                        continue
                    if start:
                        if current:
                            problem(f"{relative}:{number}: region '{start.group(1)}' starts inside region '{current}'")
                        current, body = start.group(1), []
                        if (relative, current) in regions:
                            problem(f"{relative}:{number}: region '{current}' is defined twice")
                        if why_not:
                            problem(f"{relative}:{number}: region '{current}' comes from {why_not}; README.md "
                                    f"shows only code that {GATING_BUILDS} compiles or evaluates")
                    elif end:
                        if not current:
                            problem(f"{relative}:{number}: end-snippet without a region")
                        else:
                            regions[(relative, current)] = as_text(dedent(body))
                            current = None
                    elif current:
                        body.append(line)
                if current:
                    problem(f"{relative}: region '{current}' has no end-snippet")

    # The library: what it depends on, its minimum API level.
    library_script = read_text(library_build)
    dependencies = dict(((group, artifact), version) for group, artifact, version in
                        re.findall(r'^\s*(?:api|implementation)\("([^:"]+):([^:"]+):([^:"]+)"\)', library_script,
                                   re.MULTILINE))
    # The Kotlin standard library is a dependency of the published POM too, at the version the build pins.
    stdlib = re.findall(r'^\s*coreLibrariesVersion\s*=\s*"([^"]+)"', library_script, re.MULTILINE)
    if len(stdlib) != 1:
        problem("sallapush/build.gradle.kts must set coreLibrariesVersion exactly once")
    else:
        dependencies[("org.jetbrains.kotlin", "kotlin-stdlib")] = stdlib[0]
    min_sdk = re.findall(r"^\s*minSdk\s*=\s*(\d+)\s*$", library_script, re.MULTILINE)

    def is_inside_roots(path):
        return not path.startswith("/") and ".." not in path.split("/") and path.split("/")[0] in SOURCE_ROOTS

    stand_in = ["exclusiveContent {", "forRepository {", "mavenLocal()", "}",
                'filter { includeModule("com.salla", "mobile-push") }', "}"]

    def without_maven_local(lines, label):
        """The consumer check's block without its stand-in for Maven Central: the exclusiveContent part that takes
        com.salla:mobile-push from mavenLocal() only."""
        for index, line in enumerate(lines):
            if line.strip() != "exclusiveContent {":
                continue
            depth, end = 1, index + 1
            while end < len(lines) and depth > 0:
                depth += braces(lines[end])
                end += 1
            if [entry.strip() for entry in lines[index:end] if entry.strip()] == stand_in:
                return lines[:index] + lines[end:]
        problem(f"{label} has no exclusiveContent part that takes com.salla:mobile-push from mavenLocal() only, the "
                "stand-in for Maven Central")
        return lines

    # Code blocks of the README.
    marker = re.compile(r"^\s*<!--\s*(snippet|unchecked):\s*(.*?)\s*-->\s*$")
    fence_open = re.compile(r"^(\s*)(`{3,}|~{3,})\s*([^\s`]*)")
    lines = read_text(readme_path).split("\n")
    shown_files, shown_regions = set(), set()
    counts = {"files": 0, "regions": 0, "unchecked": 0}
    blocks = []
    in_block = [False] * len(lines)
    index = 0
    while index < len(lines):
        opening = fence_open.match(lines[index])
        if not opening:
            found = marker.match(lines[index])
            if found and not (index + 1 < len(lines) and fence_open.match(lines[index + 1])):
                problem(f"README.md:{index + 1}: the {found.group(1)} marker must be followed by a fenced code block")
            index += 1
            continue
        indent, fence, language = len(opening.group(1)), opening.group(2), opening.group(3).lower()
        start = index
        index += 1
        body = []
        while index < len(lines) and not (lines[index].strip().startswith(fence[0] * len(fence))
                                          and set(lines[index].strip()) == {fence[0]}):
            # A fence indented inside a list item indents its content too (CommonMark strips up to that many spaces).
            line = lines[index]
            body.append(line[min(indent, len(line) - len(line.lstrip(" "))):])
            index += 1
        if index >= len(lines):
            problem(f"README.md:{start + 1}: the code block is not closed")
            break
        for covered in range(start, index + 1):
            in_block[covered] = True
        index += 1
        blocks.append((start + 1, language, body))
        found = marker.match(lines[start - 1]) if start > 0 else None
        if not found:
            if language in CODE_LANGUAGES:
                problem(f"README.md:{start + 1}: a {language} block needs a snippet or unchecked marker on the line "
                        "above")
            continue
        kind, argument = found.group(1), found.group(2)
        if kind == "unchecked":
            if not argument:
                problem(f"README.md:{start}: the unchecked marker needs a reason")
            counts["unchecked"] += 1
            continue
        target, _, option = argument.partition(" ")
        path, _, region = target.partition("#")
        option = option.strip()
        local_path = local_region = None
        if option:
            if not option.startswith("mavenLocal="):
                problem(f"README.md:{start}: unknown snippet option '{option}'")
                continue
            local_path, _, local_region = option[len("mavenLocal="):].partition("#")
            if not region or not local_region:
                problem(f"README.md:{start}: mavenLocal= compares a region with a region (<path>#<name>)")
                continue
            if not is_inside_roots(local_path):
                problem(f"README.md:{start}: snippet path {local_path} must be inside sample/ or consumer-check/")
                continue
        if not is_inside_roots(path):
            problem(f"README.md:{start}: snippet path {path} must be inside sample/ or consumer-check/")
            continue
        source = os.path.join(android, path)
        if not os.path.isfile(source):
            problem(f"README.md:{start}: {path} does not exist")
            continue
        label = f"{path}#{region}" if region else path
        shown = as_text(body)
        if region:
            expected = regions.get((path, region))
            if expected is None:
                problem(f"README.md:{start}: {path} has no region '{region}' (in code the compiler reads)")
                continue
            shown_regions.add((path, region))
            counts["regions"] += 1
        else:
            why_not = compiled(path)
            if why_not:
                problem(f"README.md:{start}: {path} comes from {why_not}; README.md shows only code that "
                        f"{GATING_BUILDS} compiles or evaluates")
            expected = read_text(source)
            if path.endswith(".json"):
                try:
                    json.loads(expected)
                except ValueError as error:
                    problem(f"README.md:{start}: {path} is not valid JSON ({error})")
            shown_files.add(path)
            counts["files"] += 1
        if shown != expected:
            problem(f"README.md:{start + 1}: the block differs from {label}:\n" + diff(expected, shown, label))
        if local_path:
            local_label = f"{local_path}#{local_region}"
            local_expected = regions.get((local_path, local_region))
            if local_expected is None:
                problem(f"README.md:{start}: {local_path} has no region '{local_region}' (in code the compiler reads)")
                continue
            shown_regions.add((local_path, local_region))
            local_without = as_text(without_maven_local(local_expected.splitlines(), local_label))
            if shown != local_without:
                problem(f"README.md:{start + 1}: the block differs from {local_label} without its mavenLocal() "
                        f"stand-in:\n" + diff(local_without, shown, local_label))

    for path in REQUIRED_FILES:
        if path not in shown_files:
            problem(f"README.md does not show {path} in full (<!-- snippet:{path} -->)")
    for path, region in REQUIRED_REGIONS:
        if (path, region) not in shown_regions:
            problem(f"README.md does not show the region '{region}' of {path} (<!-- snippet:{path}#{region} -->)")

    # The repository blocks: SallaPush is on Maven Central.
    repository_blocks = [(number, body) for number, language, body in blocks
                         if any(re.match(r"^\s*repositories\s*\{", line) for line in body)]
    if not repository_blocks:
        problem("README.md shows no repository block (a code block with repositories { … })")
    for number, body in repository_blocks:
        if not any(line.strip() == "mavenCentral()" for line in body):
            problem(f"README.md:{number}: the repository block must declare mavenCentral(), where SallaPush is "
                    "published")

    # The log codes: the README's text names only codes that LogCode.kt has, at their levels. docs/LOGGING.md lists
    # them all.
    catalogue = {code: level.lower() for code, level in
                 re.findall(r'^\s*[A-Z_]+\("([a-z_]+\.[a-z_]+)",\s*(ERROR|WARN|INFO|DEBUG|VERBOSE),',
                            read_text(log_codes_path), re.MULTILINE)}
    if not catalogue:
        problem("could not read the log codes and their levels from sallapush's LogCode.kt")
    code_prefixes = {code.split(".")[0] for code in catalogue}
    level_words = "|".join(level.upper() for level in LEVELS)
    level_before = re.compile(r"\b(" + level_words + r")\b `([a-z]+\.[a-z_]+)`")
    level_after = re.compile(r"`([a-z]+\.[a-z_]+)`\**,? at (" + level_words + r")\b")
    named_codes = set()
    for number, line in enumerate(lines, 1):
        if in_block[number - 1]:
            continue
        for code in re.findall(r"`([a-z]+\.[a-z_]+)`", line):
            if code.split(".")[0] in code_prefixes:
                if code not in catalogue:
                    problem(f"README.md:{number}: names the log code {code}, which sallapush's LogCode.kt does not have")
                else:
                    named_codes.add(code)
        for level, code in level_before.findall(line) + [(level, code) for code, level in level_after.findall(line)]:
            if code in catalogue and catalogue[code] != level.lower():
                problem(f"README.md:{number}: gives {code} the level {level}, but LogCode.kt logs it at "
                        f"{catalogue[code].upper()}")

    # The pinned version, the dependencies and the minimum API level.
    properties = read_text(os.path.join(android, "gradle.properties"))
    declared = re.findall(r"^\s*VERSION_NAME\s*=\s*(\S+)\s*$", properties, re.MULTILINE)
    version_token = re.compile(r"\b\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*)?")
    if len(declared) != 1:
        problem("gradle.properties must declare VERSION_NAME exactly once")
    else:
        pins = 0
        for number, line in enumerate(lines, 1):
            found = []
            if line.startswith("- **Version:**") or "com.salla:mobile-push" in line:
                found += version_token.findall(line)
            found += re.findall(r"SallaPush(?:-Android/| )(\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*)?)", line)
            for version in found:
                pins += 1
                if version != declared[0]:
                    problem(f"README.md:{number}: names version {version}, but gradle.properties declares "
                            f"{declared[0]}")
        if pins == 0:
            problem("README.md names no SallaPush version: its '- **Version:**' line and its dependency line must")
    named = set()
    coordinate = re.compile(r"\b([a-z][a-z0-9_.]*):([a-z][a-z0-9_-]*):(\d[0-9A-Za-z.-]*\d)")
    for number, line in enumerate(lines, 1):
        for group, artifact, version in coordinate.findall(line):
            expected = dependencies.get((group, artifact))
            if expected is None:
                continue
            named.add((group, artifact))
            if version != expected:
                problem(f"README.md:{number}: names {group}:{artifact}:{version}, but the library uses {expected}")
    for group, artifact in sorted(set(dependencies) - named):
        problem(f"README.md does not name the library's dependency {group}:{artifact}:"
                f"{dependencies[(group, artifact)]}")
    if len(min_sdk) != 1:
        problem("sallapush/build.gradle.kts must set minSdk exactly once")
    elif not any(f"(API {min_sdk[0]})" in line for line in lines):
        problem(f"README.md must name the minimum API level as (API {min_sdk[0]})")

    # A refused token is reported; the app logs in again with its next token, never from onLoginFailure. The README or
    # the API reference holds the login failures table.
    rejection_rows = 0
    for document in LOGIN_FAILURE_DOCS:
        document_path = os.path.join(android, document)
        if not os.path.isfile(document_path):
            continue
        document_lines = lines if document == "README.md" else read_text(document_path).split("\n")
        for row in (line for line in document_lines if line.startswith("| `TOKEN_REJECTED` |")):
            rejection_rows += 1
            if RELOGIN_ADVICE not in row:
                problem(f"{document}: the TOKEN_REJECTED row must say \"{RELOGIN_ADVICE}\"")
        for number, body in fenced_blocks(document_lines):
            body = "\n".join(body)
            if "TOKEN_REJECTED" in body and re.search(r"\blogin\s*\(", body):
                problem(f"{document}:{number}: a block that handles TOKEN_REJECTED calls login")
    if not rejection_rows:
        problem(f"no '| `TOKEN_REJECTED` |' row in a login failures table of {' or '.join(LOGIN_FAILURE_DOCS)}")

    summary = (f"README.md matches the code: {counts['files']} whole files and {counts['regions']} regions, all from "
               f"code the gating builds compile or evaluate, {counts['unchecked']} unchecked block(s), "
               f"{len(repository_blocks)} repository block(s) with mavenCentral(), "
               f"{len(named_codes)} log codes named at their catalogue levels, "
               f"version {declared[0] if len(declared) == 1 else '?'}, {len(named)} dependencies, "
               f"minimum API {min_sdk[0] if len(min_sdk) == 1 else '?'}, "
               f"TOKEN_REJECTED reported and never a login from onLoginFailure")
    return problems, summary

# --- Self-test: each kind of drift, made on a scratch copy, must fail the check -----------------------------------

README = "README.md"
APP = "sample/src/main/java/com/salla/push/sample/SampleApp.kt"
SERVICE = "sample/src/main/java/com/salla/push/sample/SampleMessagingService.kt"
REFERENCE = "docs/REFERENCE.md"
MANIFEST = "sample/src/main/AndroidManifest.xml"
STRINGS = "sample/src/main/res/values/strings.xml"
SNIPPETS = "consumer-check/app/src/main/java/com/salla/push/consumercheck/readme"
USER_SNIPPETS = SNIPPETS + "/UserSnippets.kt"
JAVA = SNIPPETS + "/ShopApplication.java"
LIBRARY = "sallapush/build.gradle.kts"
README_REPOSITORIES = "consumer-check/readme-repositories/kotlin/settings.gradle.kts"
CONSUMER_SETTINGS = "consumer-check/settings.gradle.kts"
LIBRARY_MANIFEST = "sallapush/src/main/AndroidManifest.xml"
COPIED = ("README.md", REFERENCE, "gradle.properties", SIZE_SCRIPT, "sample", "consumer-check", LIBRARY,
          LIBRARY_MANIFEST, "sallapush/src/main/java")


def self_test(android):
    def edit(root, relative, old, new, count=1):
        path = os.path.join(root, relative)
        content = read_text(path)
        if content.count(old) < count:
            raise AssertionError(f"the mutation does not apply: {relative} has no {old!r}" +
                                 (f" {count} times" if count > 1 else ""))
        with open(path, "w", encoding="utf-8") as handle:
            handle.write(content.replace(old, new, count))

    def append(root, relative, addition):
        path = os.path.join(root, relative)
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(addition)

    def edits(*changes):
        return lambda root: [edit(root, *change) for change in changes]

    def unsay(start, phrase):
        """Rewords `phrase` in the README passage that starts with `start`, wherever its lines wrap."""
        def mutate(root):
            path = os.path.join(root, README)
            text = read_text(path).split("\n")
            words = re.compile(r"\s+".join(re.escape(word) for word in phrase.split()))
            for number, line in enumerate(text):
                if line.startswith(start):
                    end = passage_end(text, number)
                    passage = "\n".join(text[number:end])
                    if words.search(passage):
                        text[number:end] = words.sub("as it was", passage, count=1).split("\n")
                        with open(path, "w", encoding="utf-8") as handle:
                            handle.write("\n".join(text))
                        return
            raise AssertionError(f"the mutation does not apply: no passage that starts {start!r} says {phrase!r}")
        return mutate

    def shown_region(source, name, code, before="", after="", language="kotlin", xml=False):
        """A region `name` with `code` appended to `source` between the lines `before` and `after`, and shown in the
        README."""
        start, end = ((f"<!-- snippet:{name} -->", "<!-- end-snippet -->") if xml
                      else (f"// snippet:{name}", "// end-snippet"))

        def mutate(root):
            append(root, source, f"\n{before}{start}\n{code}{end}\n{after}")
            append(root, README, f"\n<!-- snippet:{source}#{name} -->\n```{language}\n{code}```\n")
        return mutate

    missing_call = "SallaPush.User.setLanguageCode(languageCode, 42)\n"
    object_wrapper = ("object Probe {\n", "}\n")

    def commented_out_language_region(root):
        # The language region wrapped in /* */, then changed to call an API that does not exist: still caught.
        edit(root, USER_SNIPPETS, "        // snippet:language\n", "/*\n        // snippet:language\n")
        edit(root, USER_SNIPPETS, "        // end-snippet\n    }\n}\n", "        // end-snippet\n*/\n    }\n}\n")
        edit(root, USER_SNIPPETS, "SallaPush.User.setLanguage(languageCode)\n", missing_call)
        edit(root, README, "SallaPush.User.setLanguage(languageCode)\n", missing_call)

    def text_that_looks_like_structure(root):
        # Comment and marker text inside strings, a raw string that closes inside the region, a template with a nested
        # string, a backtick name and a comment inside the region are all fine.
        code = ('val text = "/* not a comment // nor this ${"}"} \\" still text"\n'
                'val raw = """\n'
                '    // text of the raw string, not a comment\n'
                '    /* text, ${listOf("*/").size}\n'
                '""""\n'
                "val quote = '\"'\n"
                "val `name with /* a comment marker` = 1\n"
                "/* A comment inside the region shows in the README as a comment. */\n")
        shown_region(USER_SNIPPETS, "lexer-text", code, *object_wrapper)(root)

    def java_text_that_looks_like_structure(root):
        code = ('String text = "/* not a comment // nor this \\" still text";\n'
                'String block = """\n'
                '    // text of the text block, not a comment\n'
                '    """;\n'
                "char quote = '\"';\n")
        shown_region(JAVA, "java-lexer-text", code, "class ProbeJava {\n    void probe() {\n", "    }\n}\n",
                     "java")(root)

    def xml_text_that_looks_like_structure(root):
        # A CDATA section that closes on its line, quotes in text, and a comment inside the region are all fine.
        code = ('<string name="probe_cdata"><![CDATA[<!-- not a comment --> ]]></string>\n'
                '<string name="probe_quote" translatable="false">"quoted" isn\'t a tag &gt;</string>\n'
                '<!-- A comment inside the region\n'
                '     shows in the README as a comment. -->\n')
        edit(root, STRINGS, "</resources>\n", "")
        shown_region(STRINGS, "xml-lexer-text", code, "", "</resources>\n", "xml", xml=True)(root)

    cases = [
        # The README's code against the sample and the consumer check.
        ("whole file drift, README side",
         lambda root: edit(root, README, "SallaPush.Notifications.onNewToken(applicationContext, token)",
                           "SallaPush.Notifications.onNewToken(this, token)"),
         "the block differs from sample/src/main/java/com/salla/push/sample/SampleMessagingService.kt"),
        ("whole file drift, source side",
         lambda root: edit(root, SERVICE, "class SampleMessagingService : FirebaseMessagingService() {",
                           "class SampleMessagingService : FirebaseMessagingService() { "),
         "the block differs from sample/src/main/java/com/salla/push/sample/SampleMessagingService.kt"),
        ("initialization region drift, README side",
         lambda root: edit(root, README, 'SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000002")\n// Added',
                           'SallaPush.initWithContext(this, "00000000-0000-4000-8000-000000000003")\n// Added'),
         "SampleApp.kt#initialize"),
        ("initialization region drift, source side",
         lambda root: edit(root, APP, "        // Added here, the click", "        // Added here first, the click"),
         "SampleApp.kt#initialize"),
        ("region drift, README side",
         lambda root: edit(root, README, "    showPermissionAnswer(granted)", "    showPermission(granted)"),
         "MainActivity.kt#request-permission"),
        ("region drift, source side",
         lambda root: edit(root, USER_SNIPPETS, 'addTag("add_to_cart"', 'addTag("added_to_cart"'),
         "UserSnippets.kt#tags"),
        ("XML region drift",
         lambda root: edit(root, MANIFEST, '@drawable/ic_stat_notification" />\n        <!-- end-snippet -->',
                           '@drawable/ic_stat_bell" />\n        <!-- end-snippet -->'),
         "AndroidManifest.xml#small-icon"),
        ("login region drift",
         lambda root: edit(root, USER_SNIPPETS, "        SallaPush.logout()\n", "        SallaPush.logout()\n        SallaPush.logout()\n"),
         "UserSnippets.kt#login-logout"),
        ("kotlin block without a marker",
         lambda root: edit(root, README, "## Troubleshooting\n",
                           "```kotlin\nSallaPush.logout()\n```\n\n## Troubleshooting\n"),
         "a kotlin block needs a snippet or unchecked marker"),
        ("unknown region",
         lambda root: edit(root, README, "UserSnippets.kt#tags", "UserSnippets.kt#tagz"),
         "has no region 'tagz'"),
        ("missing file",
         lambda root: edit(root, README, "snippet:sample/src/main/java/com/salla/push/sample/SampleApp.kt",
                           "snippet:sample/src/main/java/com/salla/push/sample/App.kt"),
         "App.kt does not exist"),
        ("escaping path",
         lambda root: edit(root, README, "snippet:sample/src/main/AndroidManifest.xml#small-icon",
                           "snippet:sample/../sample/src/main/AndroidManifest.xml#small-icon"),
         "must be inside sample/ or consumer-check/"),
        ("path outside sample/ and consumer-check/",
         lambda root: edit(root, README, "snippet:sample/src/main/AndroidManifest.xml#small-icon",
                           "snippet:sallapush/src/main/AndroidManifest.xml#small-icon"),
         "must be inside sample/ or consumer-check/"),
        ("required whole file not shown",
         lambda root: edit(root, README, f"snippet:{REQUIRED_FILES[0]} -->\n```kotlin",
                           "unchecked: shown elsewhere -->\n```kotlin"),
         "README.md does not show sample/src/main/java/com/salla/push/sample/SampleMessagingService.kt in full"),
        ("required region not shown",
         lambda root: edit(root, README, f"snippet:{APP}#initialize -->\n```kotlin",
                           "unchecked: shown elsewhere -->\n```kotlin"),
         "README.md does not show the region 'initialize' of sample/src/main/java/com/salla/push/sample/SampleApp.kt"),
        ("unchecked marker without a reason",
         lambda root: edit(root, README, "## Example app\n",
                           "<!-- unchecked: -->\n```bash\n./gradlew tasks\n```\n\n## Example app\n"),
         "the unchecked marker needs a reason"),
        ("marker not followed by a block",
         lambda root: edit(root, README, "## Example app\n",
                           "<!-- snippet:sample/src/main/AndroidManifest.xml#small-icon -->\n\n## Example app\n"),
         "the snippet marker must be followed by a fenced code block"),
        ("unclosed fence", lambda root: append(root, README, "\n```text\nopen\n"), "the code block is not closed"),
        # Only code the compiler reads.
        ("a region inside a nested Kotlin comment",
         shown_region(USER_SNIPPETS, "nested-comment", missing_call, "/* outer /* inner */ still outer\n", "*/\n"),
         "region 'nested-comment' starts inside a /* */ comment"),
        ("a region inside a Kotlin raw string",
         shown_region(USER_SNIPPETS, "raw-string", missing_call, 'val probe = """\n', '"""\n'),
         "region 'raw-string' starts inside a string literal"),
        ("a region inside a Java /* */ comment",
         shown_region(JAVA, "java-commented-out", "SallaPush.thisMethodDoesNotExist();\n", "/*\n", "*/\n", "java"),
         "region 'java-commented-out' starts inside a /* */ comment"),
        ("an XML region inside a comment",
         lambda root: (edit(root, STRINGS, "</resources>\n", ""),
                       shown_region(STRINGS, "xml-commented-out", '<string name="probe">x</string>\n',
                                    "<!--\n", "</resources>\n", "xml", xml=True)(root)),
         "region 'xml-commented-out' starts inside an XML comment"),
        ("comment and marker text inside Kotlin strings is not structure", text_that_looks_like_structure, None),
        ("comment and marker text inside Java strings is not structure", java_text_that_looks_like_structure, None),
        ("comment text inside XML text is not structure", xml_text_that_looks_like_structure, None),
        # The repository block: Maven Central, with the consumer check's mavenLocal() stand-in.
        ("the repository block drops mavenCentral() everywhere",
         edits((README, "        google()\n        mavenCentral()\n    }\n}\n", "        google()\n    }\n}\n"),
               (README_REPOSITORIES, "        mavenCentral()\n", ""),
               (CONSUMER_SETTINGS, "        mavenCentral()\n        exclusiveContent", "        exclusiveContent")),
         "the repository block must declare mavenCentral()"),
        ("the consumer check's block differs beyond its stand-in",
         lambda root: edit(root, CONSUMER_SETTINGS, "        mavenCentral()\n        exclusiveContent",
                           "        mavenCentral()\n        gradlePluginPortal()\n        exclusiveContent"),
         "differs from consumer-check/settings.gradle.kts#repositories without its mavenLocal() stand-in"),
        ("the consumer check's block loses its stand-in",
         lambda root: edit(root, CONSUMER_SETTINGS, "                mavenLocal()\n",
                           "                mavenCentral()\n"),
         "has no exclusiveContent part that takes com.salla:mobile-push from mavenLocal() only"),
        # Version, dependencies, minimum API level.
        ("the README names another version",
         lambda root: edit(root, README, "- **Version:** 1.1.0", "- **Version:** 9.9.9"),
         "names version 9.9.9"),
        ("the README names another dependency version",
         lambda root: edit(root, README, "androidx.core:core:1.13.1", "androidx.core:core:1.12.0"),
         "names androidx.core:core:1.12.0"),
        ("the README names another minimum API level",
         lambda root: edit(root, README, "(API 23)", "(API 21)"),
         "must name the minimum API level as (API 23)"),
        # Log codes.
        ("the README names a log code the library doesn't have",
         lambda root: edit(root, README, "`login.ok`", "`login.oks`"),
         "names the log code login.oks"),
        ("the README gives a log code another level",
         lambda root: append(root, README, "\nSallaPush logs WARN `register.ok` once.\n"),
         "gives register.ok the level WARN"),
        # Never a login from onLoginFailure on TOKEN_REJECTED.
        ("the TOKEN_REJECTED row advises a login at once",
         lambda root: edit(root, REFERENCE, "or at the next launch, not from `onLoginFailure`.", "or at once."),
         "docs/REFERENCE.md: the TOKEN_REJECTED row must say"),
        ("a README row advises a login at once",
         lambda root: append(root, README, "\n| `TOKEN_REJECTED` | Call `login` again at once. |\n"),
         "README.md: the TOKEN_REJECTED row must say"),
        ("no login failures table",
         lambda root: edit(root, REFERENCE, "| `TOKEN_REJECTED` |", "| `TOKEN_REFUSED` |"),
         "no '| `TOKEN_REJECTED` |' row"),
        ("a README block logs in again on TOKEN_REJECTED",
         lambda root: append(root, README, "\n<!-- unchecked: probe -->\n```kotlin\n"
                             "if (failure == SallaPushLoginFailure.TOKEN_REJECTED) SallaPush.login(id, token)\n```\n"),
         "a block that handles TOKEN_REJECTED calls login"),
        ("an API reference block logs in again on TOKEN_REJECTED",
         lambda root: append(root, REFERENCE, "\n```kotlin\n"
                             "if (failure == SallaPushLoginFailure.TOKEN_REJECTED) SallaPush.login(id, token)\n```\n"),
         "a block that handles TOKEN_REJECTED calls login"),
    ]
    failures = 0
    scratch = tempfile.mkdtemp(prefix="check-readme-snippets.")
    ignored = shutil.ignore_patterns(*SKIPPED_FOLDERS, "__pycache__", ".DS_Store")
    try:
        pristine = os.path.join(scratch, "pristine")
        for relative in COPIED:
            source, target = os.path.join(android, relative), os.path.join(pristine, relative)
            os.makedirs(os.path.dirname(target), exist_ok=True)
            if os.path.isdir(source):
                shutil.copytree(source, target, ignore=ignored)
            else:
                shutil.copyfile(source, target)
        baseline, _ = check(pristine)
        if baseline:
            print("FAIL the unchanged copy: the check must pass on it first, and reports:")
            for found in baseline[:5]:
                print("       " + found.split("\n")[0])
            return False
        print("ok   the unchanged copy passes")
        for name, mutate, expected in cases:
            root = os.path.join(scratch, "case")
            shutil.rmtree(root, ignore_errors=True)
            shutil.copytree(pristine, root)
            try:
                mutate(root)
                problems, _ = check(root)
            except AssertionError as error:
                print(f"FAIL {name}: {error}")
                failures += 1
                continue
            caught = not problems if expected is None else any(expected in found for found in problems)
            if caught:
                print(f"ok   {name}")
            else:
                failures += 1
                print(f"FAIL {name}: expected {'no problem' if expected is None else repr(expected)}, got:")
                for found in problems[:5] or ["no problem"]:
                    print("       " + found.split("\n")[0])
    finally:
        shutil.rmtree(scratch, ignore_errors=True)
    print(f"check-readme-snippets --self-test: {len(cases) - failures} of {len(cases)} cases behave as expected")
    return failures == 0


android, mode = sys.argv[1], sys.argv[2]
if mode == "--self-test":
    sys.exit(0 if self_test(android) else 1)
problems, summary = check(android)
if problems:
    for message in problems:
        print(f"check-readme-snippets: {message}", file=sys.stderr)
    print(f"check-readme-snippets: {len(problems)} problem(s)", file=sys.stderr)
    sys.exit(1)
print(f"check-readme-snippets: {summary}")
PY
