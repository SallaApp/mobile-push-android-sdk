#!/usr/bin/env bash
# Keeps this SDK's log codes the shared catalogue's. A code is stable API that apps match on in their log handlers,
# always logged at one level and with one category (scripts/log-catalogue.txt), so this check:
#   1. reads every code, with its level and category, from the source: the LogCode enum of
#      sallapush/src/main/java/com/salla/push/internal/log/LogCode.kt with the constants of its LogCategory object;
#   2. fails when LogCode.kt differs from the catalogue's lines for "both" and "android" in any way: a code the
#      catalogue lacks or lists for the iOS SDK only, a code of the catalogue that the SDK lacks, another level, another
#      category;
#   3. fails when sallapush/src/test/resources/log-catalog-android.txt, the list the unit tests check LogCode.kt and the
#      emitted codes against, differs from those lines in codes or levels.
# It reads the sources as text, without a build. Comments, string literals and templates are read as the compiler
# reads them, so a quote or a brace inside them cannot shift what the check sees. A source whose shape it does not
# know fails the check, with the line: a check that cannot read the codes must never pass.
#
#   scripts/check-log-catalogue.sh    run from anywhere (scripts/verify.sh catalogue)
set -euo pipefail

top="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"

if [ "$#" -ne 0 ]; then
  echo 'usage: scripts/check-log-catalogue.sh' >&2
  exit 2
fi
command -v python3 >/dev/null 2>&1 || {
  echo 'check-log-catalogue.sh: python3 is required' >&2
  exit 1
}

python3 - "$top" scripts/log-catalogue.txt sallapush/src/main/java/com/salla/push/internal/log/LogCode.kt \
  sallapush/src/test/resources/log-catalog-android.txt <<'PY'
import os
import re
import sys

top, catalogue_path, kotlin_path, test_path = sys.argv[1:5]
PROGRAM = "check-log-catalogue.sh"
LEVELS = ("error", "warn", "info", "debug", "verbose")
PLATFORMS = ("both", "ios", "android")
ANDROID = ("both", "android")
CATEGORIES = ("lifecycle", "registration", "identity", "notifications", "events", "tags", "network", "storage",
              "consent", "extension")
CODE = re.compile(r"[a-z][a-z0-9_]*\.[a-z][a-z0-9_]*\Z")


class Unreadable(Exception):
    """Something this check cannot read with certainty; line is None when the problem has no line."""

    def __init__(self, message, line=None):
        super().__init__(message)
        self.line = line


# ---------------------------------------------------------------------------------------------------
# Kotlin tokens: names, string literals (decoded), numbers, characters and punctuation.

class Token:
    __slots__ = ("kind", "text", "line", "plain")

    def __init__(self, kind, text, line, plain=True):
        self.kind = kind  # name, quoted-name, string, number, character or punct
        self.text = text
        self.line = line
        # A string literal whose text is exactly its value: no template.
        self.plain = plain


LONG_PUNCTUATION = ("...", "..<", "->", "!=", "==", "<=", ">=", "&&", "||", "::", "?.", "?:", "!!",
                    "+=", "-=", "*=", "/=", "%=")
ESCAPES = {"t": "\t", "b": "\b", "n": "\n", "r": "\r", "'": "'", '"': '"', "\\": "\\", "$": "$"}


class Lexer:
    def __init__(self, text):
        self.text = text
        self.i = 0
        self.line = 1

    def fail(self, message, line=None):
        raise Unreadable(message, line or self.line)

    def tokens(self):
        result = []
        while True:
            token = self.next()
            if token is None:
                return result
            result.append(token)

    def peek(self, offset=0):
        index = self.i + offset
        return self.text[index] if index < len(self.text) else ""

    def skip_blank(self):
        text, n = self.text, len(self.text)
        while self.i < n:
            c = text[self.i]
            if c == "\n":
                self.line += 1
                self.i += 1
            elif c in " \t\r\f\v":
                self.i += 1
            elif text.startswith("//", self.i):
                end = text.find("\n", self.i)
                self.i = n if end < 0 else end
            elif text.startswith("/*", self.i):
                # Kotlin nests block comments.
                start, depth = self.line, 1
                self.i += 2
                while depth:
                    if self.i >= n:
                        self.fail("a block comment never ends", start)
                    if text.startswith("/*", self.i):
                        depth += 1
                        self.i += 2
                    elif text.startswith("*/", self.i):
                        depth -= 1
                        self.i += 2
                    else:
                        if text[self.i] == "\n":
                            self.line += 1
                        self.i += 1
            else:
                return

    def next(self):
        self.skip_blank()
        text, n = self.text, len(self.text)
        if self.i >= n:
            return None
        c, line = text[self.i], self.line
        if c == '"':
            return self.string()
        if c == "'":
            return self.character()
        if c == "`":
            end = text.find("`", self.i + 1)
            if end < 0 or "\n" in text[self.i:end]:
                self.fail("a quoted name never ends")
            name = text[self.i + 1:end]
            self.i = end + 1
            return Token("quoted-name", name, line)
        if c.isalpha() or c == "_":
            end = self.i + 1
            while end < n and (text[end].isalnum() or text[end] in "_$"):
                end += 1
            name = text[self.i:end]
            self.i = end
            return Token("name", name, line)
        if c.isdigit():
            end = self.i + 1
            while end < n and (text[end].isalnum() or text[end] == "_" or
                               (text[end] == "." and end + 1 < n and text[end + 1].isdigit())):
                end += 1
            number = text[self.i:end]
            self.i = end
            return Token("number", number, line)
        for punctuation in LONG_PUNCTUATION:
            if text.startswith(punctuation, self.i):
                self.i += len(punctuation)
                return Token("punct", punctuation, line)
        self.i += 1
        return Token("punct", c, line)

    def character(self):
        start, line = self.i, self.line
        self.i += 1
        if self.text.startswith("\\u", self.i):
            self.i += 6
        elif self.peek() == "\\":
            self.i += 2
        else:
            self.i += 1
        if self.peek() != "'":
            self.fail("a character literal is malformed", line)
        self.i += 1
        return Token("character", self.text[start:self.i], line)

    def group(self, opening, closing):
        """Skips a template: from its opening brace to the one that closes it."""
        start, depth = self.line, 1
        self.i += 1
        while depth:
            token = self.next()
            if token is None:
                self.fail("a template never ends", start)
            if token.kind == "punct" and token.text == opening:
                depth += 1
            elif token.kind == "punct" and token.text == closing:
                depth -= 1

    def string(self):
        """A string literal, at its opening quote: "..." with escapes, or a raw string \"\"\"...\"\"\"."""
        text, n = self.text, len(self.text)
        start = self.line
        raw = text.startswith('"""', self.i)
        self.i += 3 if raw else 1
        closing = '"""' if raw else '"'
        parts, templated = [], False
        while True:
            if self.i >= n:
                self.fail("a string literal never ends", start)
            if text.startswith(closing, self.i):
                if raw:
                    # A raw string ends at the last three quotes of a longer run: """a"""" holds a".
                    run = 3
                    while self.peek(run) == '"':
                        run += 1
                    parts.append('"' * (run - 3))
                    self.i += run
                else:
                    self.i += 1
                return Token("string", "".join(parts), start, not templated)
            c = text[self.i]
            if c == "\\" and not raw:
                kind = self.peek(1)
                if kind == "u":
                    digits = text[self.i + 2:self.i + 6]
                    if not re.fullmatch(r"[0-9A-Fa-f]{4}", digits):
                        self.fail("a \\u escape is malformed")
                    parts.append(chr(int(digits, 16)))
                    self.i += 6
                    continue
                if kind not in ESCAPES:
                    self.fail(f"the escape \\{kind} is not one Kotlin knows")
                parts.append(ESCAPES[kind])
                self.i += 2
                continue
            if c == "$" and self.peek(1) == "{":
                self.i += 1
                self.group("{", "}")
                templated = True
                continue
            if c == "$" and (self.peek(1).isalpha() or self.peek(1) == "_"):
                self.i += 1
                while self.i < n and (text[self.i].isalnum() or text[self.i] == "_"):
                    self.i += 1
                templated = True
                continue
            if c == "\n":
                if not raw:
                    self.fail("a string literal never ends", start)
                self.line += 1
            parts.append(c)
            self.i += 1


# ---------------------------------------------------------------------------------------------------
# Structure helpers over a token list.

OPENING = {"(": ")", "[": "]", "{": "}"}


def is_name(token, text=None):
    return token.kind in ("name", "quoted-name") and (text is None or token.text == text)


def is_keyword(token, text):
    return token.kind == "name" and token.text == text


def is_punct(token, text):
    return token.kind == "punct" and token.text == text


def is_plain_string(token):
    return token.kind == "string" and token.plain


def bracket_pairs(tokens):
    """Maps the index of every opening bracket to the index of the bracket that closes it."""
    pairs, stack = {}, []
    for index, token in enumerate(tokens):
        if token.kind != "punct":
            continue
        if token.text in OPENING:
            stack.append(index)
        elif token.text in (")", "]", "}"):
            if not stack or OPENING[tokens[stack[-1]].text] != token.text:
                raise Unreadable(f"an unbalanced {token.text}", token.line)
            pairs[stack.pop()] = index
    if stack:
        raise Unreadable(f"a {tokens[stack[-1]].text} that never closes", tokens[stack[-1]].line)
    return pairs


def top_level(pairs, start, end):
    """The indices in [start, end) outside any bracket; a bracketed group yields only its opening index."""
    index = start
    while index < end:
        yield index
        index = pairs[index] + 1 if index in pairs else index + 1


def split_top_level(tokens, pairs, start, end, separator=","):
    """The [start, end) ranges between the separators outside any bracket."""
    ranges, begin = [], start
    for index in top_level(pairs, start, end):
        if is_punct(tokens[index], separator):
            ranges.append((begin, index))
            begin = index + 1
    ranges.append((begin, end))
    return ranges


def first_top_level(tokens, pairs, start, end, punctuation):
    return next((index for index in top_level(pairs, start, end) if is_punct(tokens[index], punctuation)), None)


def text_of(tokens):
    return " ".join('"%s"' % token.text if token.kind == "string" else token.text for token in tokens)


def qualified(tokens, owner):
    """The last name of NAME or OWNER.NAME (with any package before OWNER); None for anything else."""
    if len(tokens) == 1 and is_name(tokens[0]):
        return tokens[0].text
    if (len(tokens) >= 3 and len(tokens) % 2 == 1 and all(is_name(token) for token in tokens[0::2])
            and all(is_punct(token, ".") for token in tokens[1::2]) and tokens[-3].text == owner):
        return tokens[-1].text
    return None


# ---------------------------------------------------------------------------------------------------
# enum class LogCode in LogCode.kt, with the constants of object LogCategory.

KOTLIN_SHAPE = ("this check reads the entries of enum class LogCode, each NAME(code, level, category) with a "
                "string literal code, a SallaPushLogLevel constant such as WARN and a string literal or a "
                "LogCategory constant, in the order of the constructor's code, level and category or by name")


def read_kotlin(text):
    tokens = Lexer(text).tokens()
    pairs = bracket_pairs(tokens)

    constants = {}
    objects = [index for index in range(len(tokens) - 1)
               if is_keyword(tokens[index], "object") and is_name(tokens[index + 1], "LogCategory")]
    if len(objects) > 1:
        raise Unreadable("object LogCategory is declared twice", tokens[objects[1]].line)
    if objects:
        brace = objects[0] + 2
        while brace < len(tokens) and not is_punct(tokens[brace], "{"):
            brace = pairs[brace] + 1 if brace in pairs else brace + 1
        if brace >= len(tokens):
            raise Unreadable("object LogCategory has no body", tokens[objects[0]].line)
        for index in top_level(pairs, brace + 1, pairs[brace]):
            if not (is_keyword(tokens[index], "val") and index + 1 < pairs[brace] and is_name(tokens[index + 1])):
                continue
            # val NAME = "value", or val NAME: String = "value"
            value = index + 2
            if value + 1 < pairs[brace] and is_punct(tokens[value], ":") and is_name(tokens[value + 1], "String"):
                value += 2
            if value + 1 < pairs[brace] and is_punct(tokens[value], "=") and is_plain_string(tokens[value + 1]):
                constants[tokens[index + 1].text] = tokens[value + 1].text

    found = [index for index in range(len(tokens) - 2) if is_keyword(tokens[index], "enum")
             and is_keyword(tokens[index + 1], "class") and is_name(tokens[index + 2], "LogCode")]
    if len(found) != 1:
        raise Unreadable(f"expected one enum class LogCode, found {len(found)}")
    header = found[0] + 3
    while header < len(tokens) and not is_punct(tokens[header], "(") and not is_punct(tokens[header], "{"):
        header += 1
    if header >= len(tokens) or not is_punct(tokens[header], "("):
        raise Unreadable("enum class LogCode has no primary constructor", tokens[found[0]].line)
    parameters = []
    for start, end in split_top_level(tokens, pairs, header + 1, pairs[header]):
        colon = first_top_level(tokens, pairs, start, end, ":")
        if colon is None or colon == start or not is_name(tokens[colon - 1]):
            raise Unreadable("a parameter of the constructor of LogCode has no name", tokens[header].line)
        parameters.append(tokens[colon - 1].text)
    for required in ("code", "level", "category"):
        if required not in parameters:
            raise Unreadable(f"the constructor of LogCode has no parameter {required}", tokens[header].line)
    opening = pairs[header] + 1
    while opening < len(tokens) and not is_punct(tokens[opening], "{"):
        opening = pairs[opening] + 1 if opening in pairs else opening + 1
    if opening >= len(tokens):
        raise Unreadable("enum class LogCode has no body", tokens[found[0]].line)
    closing = pairs[opening]
    entries_end = first_top_level(tokens, pairs, opening + 1, closing, ";") or closing

    result, names = {}, set()
    for start, end in split_top_level(tokens, pairs, opening + 1, entries_end):
        if start == end:
            continue
        at = start
        while at < end and is_punct(tokens[at], "@"):
            at += 2
            while at + 1 < end and is_punct(tokens[at], ".") and is_name(tokens[at + 1]):
                at += 2
            if at < end and is_punct(tokens[at], "("):
                at = pairs[at] + 1
        if at >= end or not is_name(tokens[at]):
            raise Unreadable("an entry of LogCode without a name; " + KOTLIN_SHAPE, tokens[start].line)
        entry = tokens[at]
        if not (at + 1 < end and is_punct(tokens[at + 1], "(")):
            raise Unreadable(f"LogCode.{entry.text} passes no code, level and category", entry.line)
        arguments_end = pairs[at + 1]
        rest = arguments_end + 1
        if rest < end and is_punct(tokens[rest], "{"):
            rest = pairs[rest] + 1
        if rest != end:
            raise Unreadable(f"LogCode.{entry.text} holds more than its arguments", entry.line)
        arguments = {}
        for position, (a, b) in enumerate(split_top_level(tokens, pairs, at + 2, arguments_end)):
            if a == b:
                if b == arguments_end and position > 0:
                    continue
                raise Unreadable(f"LogCode.{entry.text} has an empty argument", entry.line)
            if b - a >= 2 and is_name(tokens[a]) and is_punct(tokens[a + 1], "="):
                key, value = tokens[a].text, tokens[a + 2:b]
            elif position < len(parameters):
                key, value = parameters[position], tokens[a:b]
            else:
                raise Unreadable(f"LogCode.{entry.text} passes more arguments than the constructor takes", entry.line)
            if key in arguments:
                raise Unreadable(f"LogCode.{entry.text} passes {key} twice", entry.line)
            arguments[key] = value
        for required in ("code", "level", "category"):
            if required not in arguments:
                raise Unreadable(f"LogCode.{entry.text} passes no {required}", entry.line)

        code = arguments["code"]
        if not (len(code) == 1 and is_plain_string(code[0])):
            raise Unreadable(f"LogCode.{entry.text} passes a code that is not a plain string literal", entry.line)
        level = qualified(arguments["level"], "SallaPushLogLevel")
        if level == "OFF":
            raise Unreadable(f"LogCode.{entry.text} passes OFF, which no code is logged at", entry.line)
        if level is None or level.lower() not in LEVELS or level != level.upper():
            raise Unreadable(f"LogCode.{entry.text} passes the level {text_of(arguments['level'])}, not ERROR, WARN, "
                             "INFO, DEBUG or VERBOSE", entry.line)
        category = arguments["category"]
        if len(category) == 1 and is_plain_string(category[0]):
            category_value = category[0].text
        else:
            constant = qualified(category, "LogCategory")
            if constant is None or constant not in constants:
                raise Unreadable(f"LogCode.{entry.text} passes the category {text_of(category)}, which is neither a "
                                 "string literal nor a constant of object LogCategory", entry.line)
            category_value = constants[constant]
        if entry.text in names:
            raise Unreadable(f"LogCode.{entry.text} is declared twice", entry.line)
        if code[0].text in result:
            raise Unreadable(f"the code {code[0].text} belongs to two entries of LogCode", entry.line)
        names.add(entry.text)
        result[code[0].text] = {"level": level.lower(), "category": category_value, "line": entry.line}
    if not result:
        raise Unreadable("enum class LogCode has no entries", tokens[found[0]].line)
    return result


# ---------------------------------------------------------------------------------------------------
# The catalogues.

def read_catalogue(text):
    """log-catalogue.txt: code, level, platform and category per line; # starts a comment line."""
    rows = {}
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split()
        if len(fields) != 4:
            raise Unreadable(f"expected 4 columns (code, level, platform, category), found {len(fields)}", number)
        code, level, platform, category = fields
        if not CODE.match(code):
            raise Unreadable(f"{code} is not a code: <area>.<name>, in lowercase letters, digits and underscores",
                             number)
        if level not in LEVELS:
            raise Unreadable(f"{code} has the level {level}; the levels are {', '.join(LEVELS)}", number)
        if platform not in PLATFORMS:
            raise Unreadable(f"{code} has the platform {platform}; the platforms are {', '.join(PLATFORMS)}", number)
        if category not in CATEGORIES:
            raise Unreadable(f"{code} has the category {category}; the categories are {', '.join(CATEGORIES)}",
                             number)
        if code in rows:
            raise Unreadable(f"{code} is listed twice (first on line {rows[code]['line']})", number)
        rows[code] = {"level": level, "platform": platform, "category": category, "line": number}
    if not rows:
        raise Unreadable("it lists no code")
    return rows


def read_test_catalogue(text):
    """log-catalog-android.txt: code and level per line; # starts a comment line."""
    rows = {}
    for number, raw in enumerate(text.splitlines(), 1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        fields = line.split()
        if len(fields) != 2:
            raise Unreadable(f"expected 2 columns (code, level), found {len(fields)}", number)
        code, level = fields
        if not CODE.match(code):
            raise Unreadable(f"{code} is not a code: <area>.<name>, in lowercase letters, digits and underscores",
                             number)
        if level not in LEVELS:
            raise Unreadable(f"{code} has the level {level}; the levels are {', '.join(LEVELS)}", number)
        if code in rows:
            raise Unreadable(f"{code} is listed twice (first on line {rows[code]['line']})", number)
        rows[code] = {"level": level, "line": number}
    if not rows:
        raise Unreadable("it lists no code")
    return rows


# ---------------------------------------------------------------------------------------------------
# The comparison.

def compare(catalogue, sdk, listed):
    problems = []
    for code in sorted(set(sdk) | set(catalogue)):
        row, found = catalogue.get(code), sdk.get(code)
        if row is None:
            problems.append(f"{code} exists in the SDK ({kotlin_path}:{found['line']}), but {catalogue_path} does not "
                            "list it; add it with its level, platform and category")
            continue
        if row["platform"] not in ANDROID:
            if found is not None:
                problems.append(f"{code} exists in the SDK ({kotlin_path}:{found['line']}), but {catalogue_path} lists "
                                f"it for the iOS SDK only (line {row['line']})")
            continue
        if found is None:
            problems.append(f"{catalogue_path} lists {code} for {'both SDKs' if row['platform'] == 'both' else 'the Android SDK only'} "
                            f"(line {row['line']}), but {kotlin_path} has no such code")
            continue
        if found["level"] != row["level"]:
            problems.append(f"{code}: the SDK logs it at {found['level']} ({kotlin_path}:{found['line']}); "
                            f"{catalogue_path} says {row['level']} (line {row['line']})")
        if found["category"] != row["category"]:
            problems.append(f"{code}: the SDK gives it the category {found['category']} ({kotlin_path}:{found['line']}); "
                            f"{catalogue_path} says {row['category']} (line {row['line']})")
    for code in sorted(set(listed) | {code for code, row in catalogue.items() if row["platform"] in ANDROID}):
        row, entry = catalogue.get(code), listed.get(code)
        if entry is None:
            problems.append(f"{test_path} does not list {code}, which {catalogue_path} lists for the Android SDK "
                            f"(line {row['line']})")
        elif row is None or row["platform"] not in ANDROID:
            problems.append(f"{test_path} lists {code} (line {entry['line']}), which {catalogue_path} does not list for "
                            "the Android SDK")
        elif entry["level"] != row["level"]:
            problems.append(f"{code}: {test_path} says {entry['level']} (line {entry['line']}); {catalogue_path} says "
                            f"{row['level']} (line {row['line']})")
    return problems


SOURCES = (
    (catalogue_path, read_catalogue, "each line holds a code, its level, its platform and its category"),
    (kotlin_path, read_kotlin, KOTLIN_SHAPE),
    (test_path, read_test_catalogue, "each line holds a code and its level"),
)
read, unreadable = [], []
for path, reader, shape in SOURCES:
    try:
        with open(os.path.join(top, path), encoding="utf-8") as handle:
            text = handle.read()
    except FileNotFoundError:
        unreadable.append(f"{path} does not exist")
        continue
    except (OSError, UnicodeDecodeError) as error:
        unreadable.append(f"cannot read {path}: {error}")
        continue
    try:
        read.append(reader(text))
    except Unreadable as error:
        unreadable.append(f"{path}:{error.line}: {error}" if error.line else f"{path}: {error}")
        if path == kotlin_path:
            unreadable.append(f"{path}: {shape}; if its shape changed on purpose, change scripts/check-log-catalogue.sh "
                              "with it")
if unreadable:
    for message in unreadable:
        print(f"{PROGRAM}: {message}", file=sys.stderr)
    print(f"{PROGRAM}: cannot compare the log codes; nothing may be merged or released until it can", file=sys.stderr)
    sys.exit(1)
catalogue, sdk, listed = read
problems = compare(catalogue, sdk, listed)
if problems:
    for problem in problems:
        print(f"{PROGRAM}: {problem}", file=sys.stderr)
    print(f"{PROGRAM}: {len(problems)} problem(s). A log code is stable API: add or change it in {kotlin_path}, "
          f"{test_path} and {catalogue_path} in the same pull request.", file=sys.stderr)
    sys.exit(1)
counts = {platform: sum(1 for row in catalogue.values() if row["platform"] == platform) for platform in PLATFORMS}
print(f"{PROGRAM}: the SDK logs {len(sdk)} codes, each at the level and with the category of {catalogue_path} "
      f"({counts['both']} codes of both SDKs and {counts['android']} of the Android SDK only; {counts['ios']} of the "
      f"iOS SDK only are not this SDK's), and {test_path} lists the same {len(listed)} codes at the same levels")
PY
