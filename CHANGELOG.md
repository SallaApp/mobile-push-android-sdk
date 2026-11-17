# Changelog

All notable changes to the SallaPush Android SDK (`com.salla:mobile-push`). The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and versions follow [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Security

- The SDK builds with the Kotlin Gradle plugin 2.4.21, up from 2.0.21, which fixes CVE-2026-53914 (unsafe
  deserialization of the KAPT build cache). The SDK doesn't use KAPT and its release job disables build caches, so
  1.1.0 isn't affected.

### Changed

- The library is compiled for Kotlin 2.0 (language and API version), up from 1.9, the oldest that the Kotlin Gradle
  plugin 2.4 supports, and depends on `kotlin-stdlib` 2.0.21, up from 1.9.24. Apps on Kotlin 1.9 still compile
  against it, and the public API is unchanged.

## [1.1.0] - 2026-11-03

The first public release of the SallaPush Android SDK, for the mobile apps of Salla stores.

### Added

- The `com.salla:mobile-push` library on Maven Central, signed, with sources and javadoc jars, for apps on
  Android 6.0 (API 23) or later, in Kotlin or Java, with Firebase Cloud Messaging.
- Customer login and logout, the notification permission, click and foreground listeners, tags, the push
  subscription, consent, logging and the app icon badge. The README shows the setup and each task, and
  `docs/REFERENCE.md` the API in detail.
- The MIT License.

## [1.0.4] - 2026-10-19

Versions before 1.1.0 were not public releases: use 1.1.0 or later.
