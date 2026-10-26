# SallaPush Android SDK: versioning and releasing

How versions work, and how the SDK's maintainers release one.

Back to the [README](../README.md).

## Versioning

SallaPush follows [Semantic Versioning](https://semver.org). A release is a tag of this repository, the version itself
without a `v`, such as `1.2.0` or `1.2.0-beta.1`, and Maven Central serves it as `com.salla:mobile-push:<version>`.
Pre-releases end in `-alpha.N`, `-beta.N` or `-rc.N`. `SallaPush.sdkVersion` returns the version at run time, and
[CHANGELOG.md](../CHANGELOG.md) lists every change.

## Releasing

Pushing a release tag runs [the Publish workflow](../.github/workflows/publish.yml), which uploads that version to the
Central Portal; a maintainer then publishes it to Maven Central. A release tag is never moved or deleted.

1. **Prepare the release in a pull request,** and merge it. Set the new version wherever the current one is named,
   until `git grep -nF <current version>` finds it only in CHANGELOG.md and in lines that name a fixed release and never
   change: `SDK_LAST_RELEASE_BEFORE_MAVEN_CENTRAL` and `SDK_API_BASELINE_FLOOR` in `scripts/common.sh`, the release
   cases of `scripts/self-test.sh`, the first-public-release sentence of CONTRIBUTING.md, and the example versions of
   SECURITY.md, the bug form, this guide and the scripts' comments and messages. The places to change: `VERSION_NAME` in
   `gradle.properties` and the KDoc example of `SallaPush.sdkVersion`; README.md's `Version`, dependency, Groovy and
   version catalog lines; the dependency in `consumer-check/app/build.gradle.kts` and the lines that name it in
   `consumer-check/readme-repositories/plugin/app/build.gradle`; the unit tests that assert the version (`FacadeTest`,
   `RegistrationTest`) and the fake device facts of `testing/Fakes.kt`; and the case of
   `scripts/check-readme-snippets.sh --self-test` that edits README.md's version. Then add the version's entry to
   CHANGELOG.md, under a heading such as `## [1.2.0] - 2026-10-20`, with the release date.
2. **Tag the release's commit.** A stable version's commit must be on master: tag the merge commit.

   ```bash
   git tag -a 1.2.0 -m "SallaPush Android 1.2.0" <commit>
   git push origin 1.2.0
   ```

3. **Follow the Publish run** in the Actions tab. Its jobs run one after the other, and the last two wait for approval
   in the environment `release`, which holds the Central Portal token and the signing key:
   - **Release checks:** the tag is a release version, and its commit is on a branch of this repository and declares
     that `VERSION_NAME`, with its CHANGELOG entry. For a stable version, the commit is on master, Maven Central holds
     no later stable version, and the public API keeps every declaration of the previous release
     (`scripts/api-compat.sh`) unless the major version grows.
   - **Verify:** every check of a pull request, on the tagged commit.
   - **Publish to Maven Central:** the release checks again, then the signed AAR and its files, uploaded to the Central
     Portal as one deployment.
   - **GitHub Release:** the release of the tag, with the version's CHANGELOG entry as its notes, once Maven Central
     serves the version.
4. **Publish the deployment** in the Central Portal (**Publishing**, then **Deployments**): check its files, then press
   **Publish**, or **Drop** to discard it. Maven Central serves the version within about 30 minutes: then approve the
   GitHub Release job, or re-run it if it ran before.
5. **Update the apps** to the new version.

A pre-release may also be tagged on a commit of another branch of this repository, to try a change in an app before it
is merged. Never tag a commit from a fork: the release checks refuse a commit that no branch of this repository holds.

### Refused tags

The tag of a failed Publish run stays. When only a runner, a flaky check or a missing secret failed, re-run the
workflow. Otherwise, fix the cause in a pull request and release the next version, such as `1.2.1` after a refused
`1.2.0`, with a CHANGELOG entry that also says that `1.2.0` was not released, and the heading
`## [1.2.0] - Not released` in CHANGELOG.md. Without that heading, the release checks of `1.2.1` stop at `1.2.0`, since
Maven Central also lacks a version whose deployment still waits in the Central Portal: publish or drop each deployment
before the next stable version. Maven Central never changes a published version either: a defect in a release is fixed
by a new version.
