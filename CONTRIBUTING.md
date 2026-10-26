# Contributing to the SallaPush Android SDK

Thank you for helping improve the SallaPush Android SDK.

## Issues

- **Bugs and feature requests:** open an issue at https://github.com/SallaApp/mobile-push-android-sdk/issues/new/choose.
  Say which SDK version, Android version and device you use, what you expected and what happened, and include the
  SallaPush log codes you saw.
- **Never paste** tokens, keys or customer data. Log codes are enough, and SallaPush never logs secrets.
- **Security problems:** don't open an issue. Report them privately, as [SECURITY.md](SECURITY.md) explains.
- **App ids:** Contact the Salla team for your app id.

## Pull requests

Pull requests are welcome.

- For a large change, open an issue first, so that we can agree on the approach before you write the code.
- Keep each pull request to one change. Update its tests and docs, and add a line under `## [Unreleased]` in
  [CHANGELOG.md](CHANGELOG.md).
- Name the branch like `feature/ABC-123-badge-count` (`feature`, `bugfix`, `hotfix` or `test`, then a ticket key and
  number), and title the pull request like `fix(mobile): keep the badge count after a logout`. The checks
  `Validate branch name` and `Validate PR title` ([lint-pr.yml](.github/workflows/lint-pr.yml)) say what they accept.
- Run the checks below before you push.
- A maintainer reviews your pull request. A maintainer may land it as a commit of their own that credits you as a
  co-author (`Co-authored-by`), and then close the pull request with a link to that commit.
- By contributing, you agree that your contribution is licensed under this repository's [MIT License](LICENSE). No
  contributor license agreement or sign-off is needed.

Everyone taking part in this project follows the [Code of Conduct](CODE_OF_CONDUCT.md).

## Set up

- JDK 17, on `PATH` or through `JAVA_HOME`.
- The Android SDK with platform 35, in `ANDROID_HOME`. On macOS, `~/Library/Android/sdk`, Android Studio's default, is
  used when `ANDROID_HOME` is not set. The builds never read `local.properties`.
- `python3` and `git`, for the checks.

## The sample app

[sample](sample) is a complete integration that builds without any secret. To run it against your Firebase project,
put your app id in `SampleApp.kt`, and these values of your `google-services.json` in `~/.gradle/gradle.properties`;
the sample needs no Google services plugin:

```properties
sallapush.sample.firebase.appId=<client.client_info.mobilesdk_app_id>
sallapush.sample.firebase.apiKey=<client.api_key.current_key>
sallapush.sample.firebase.projectId=<project_info.project_id>
sallapush.sample.firebase.senderId=<project_info.project_number>
```

[consumer-check](consumer-check) is a separate Gradle build that uses the published AAR as README.md tells an app to,
with the oldest toolchain SallaPush supports (Kotlin 1.9, compileSdk 34). It compiles the code blocks of README.md and
the docs that aren't from the sample.

## Checks

`scripts/verify.sh` runs every check a pull request must pass, in the order CI runs them, and stops at the first
failure. `scripts/verify.sh <step>` runs one step; the steps are described at the top of the script.

| Step | What it checks |
|---|---|
| `version` | `gradle.properties` declares a release version, and `CHANGELOG.md` has its entry |
| `goldens` | The golden payloads the tests read are the SallaPush server's contract (see [What needs care](#what-needs-care)) |
| `catalogue` | The log codes are the shared log catalogue's (`scripts/check-log-catalogue.sh`) |
| `links` | Every relative Markdown link resolves inside this repository |
| `tree` | What a commit takes: no secret, build output, symbolic link, nested repository or file over 5 MB; nothing that reaches outside the repository; no text that refers to a design document outside this repository; the files a build and a release need; every workflow action pinned to a commit |
| `wrapper` | The Gradle wrapper downloads an official, checksummed distribution, and its jar is Gradle's own |
| `build` | `./gradlew check :sallapush:apiCheck :sallapush:publishToMavenLocal :sample:assembleRelease`: unit tests, Android lint, the public API check, the log catalogue coverage, the AAR in your Maven repository, and the minified sample |
| `consumer` | `./gradlew -p consumer-check assembleDebug`: the published AAR, resolved and used as README.md tells an app |
| `pom` | The published POM's coordinates; the name, description, URL, MIT License, developer and source repository Maven Central requires; no dependency but `firebase-messaging`, `androidx.core`, `androidx.work` and the Kotlin standard library; and the sources jar and a javadoc jar with HTML pages beside it |
| `gates` | The crash-safety gates: no `!!`, `lateinit`, `error()`, `require()`, `check()`, `TODO()`, `runBlocking`, `GlobalScope` or `@JvmOverloads` in the library's code, no default argument in its public API, and no Kotlin null-check intrinsic in its release classes |
| `snippets` | Every code block of README.md equals the code that builds (`scripts/check-readme-snippets.sh`, then its self-test) |
| `size` | SallaPush adds at most 5 MB (5,242,880 bytes) to an app (`scripts/size-report.sh`) |

`scripts/self-test.sh` proves, on scratch copies of the repository, that these checks and the release checks still
catch what they exist to catch: run it when you change a script. CI (`.github/workflows/ci.yml`, on GitHub-hosted
runners) runs it and every step above on every pull request and every push to master, and shellcheck on every script
(`make shellcheck` runs the same). The `Makefile` has shortcuts (`make help`).

## What needs care

- **The public API.** `sallapush/api/sallapush.api` is the committed public API, and `apiCheck` fails when the library
  differs from it. After an intended API change, run `./gradlew --no-daemon :sallapush:apiDump` and commit the dump with
  the change. A stable release keeps every declaration of the previous release, the highest stable version below it on
  Maven Central, unless its major version grows; releases below 1.1.0, the first public release, are never a baseline.
- **Log codes** are stable API that apps match on. A code is in
  `sallapush/src/main/java/com/salla/push/internal/log/LogCode.kt`,
  `sallapush/src/test/resources/log-catalog-android.txt` and the shared catalogue `scripts/log-catalogue.txt`, which the
  `build` and `catalogue` checks keep equal, and in [docs/LOGGING.md](docs/LOGGING.md#log-code-reference). The catalogue
  is shared with the [iOS SDK](https://github.com/SallaApp/mobile-push-ios-sdk): a code both SDKs log keeps the same
  level and category there.
- **The golden payloads** in `sallapush/src/test/resources/golden` are copies of the SallaPush server's wire contract,
  without the store's credential fields; `scripts/common.sh` pins their sha256. Change one only when that contract
  changes, together with its sha256.
- **README.md's code blocks** are copies of compiled code (the sample and `consumer-check`). Change the code first, then
  copy it into README.md; `scripts/check-readme-snippets.sh` says what differs. The code blocks of `docs/` that follow a
  snippet marker are copies too, and no script compares them: update them with the code.
- **Dependencies and build tools** are pinned on purpose: the library's dependencies, the Gradle plugins, the Gradle
  wrapper (with its checksum, and its jar's sha256 in `scripts/common.sh`) and every workflow action, pinned to a commit
  with its version in a comment.

## Releasing

Maintainers release a version as [docs/RELEASING.md](docs/RELEASING.md) describes.
