# SallaPush Android SDK. Run the targets from the repository's root. The Android SDK comes from ANDROID_HOME (default:
# ~/Library/Android/sdk, Android Studio's on macOS), never from local.properties; Gradle runs without a daemon.
ANDROID_HOME ?= $(HOME)/Library/Android/sdk
export ANDROID_HOME

GRADLE := ./gradlew --no-daemon
STEPS ?=

.PHONY: help verify self-test shellcheck test check lint api api-dump publish-local consumer pom gates snippets size clean

help:
	@echo "verify         every check of a pull request (scripts/verify.sh); STEPS=\"build pom\" runs only those steps"
	@echo "self-test      the checks catch what they exist to catch (scripts/self-test.sh; no Gradle)"
	@echo "shellcheck     shellcheck every script, as CI's Shell scripts job does (needs shellcheck 0.11 on PATH)"
	@echo "test           unit tests (debug and release)"
	@echo "check          unit tests, Android lint, apiCheck and the log catalogue coverage"
	@echo "lint           Android lint"
	@echo "api            compare the public API with sallapush/api/sallapush.api"
	@echo "api-dump       regenerate sallapush/api/sallapush.api after an intended API change"
	@echo "publish-local  publish com.salla:mobile-push to ~/.m2"
	@echo "consumer       the consumer check, against the AAR in ~/.m2 (run publish-local first)"
	@echo "pom            the published POM, sources and javadoc jars, as Maven Central takes them (run publish-local first)"
	@echo "gates          the crash-safety gates (compiles the release classes first)"
	@echo "snippets       README.md's code blocks equal the code that builds"
	@echo "size           what SallaPush adds to an app, gated at 5 MB"

verify:
	scripts/verify.sh $(STEPS)

self-test:
	scripts/self-test.sh

shellcheck:
	shellcheck -x -S style scripts/*.sh .github/scripts/*.sh

test:
	$(GRADLE) :sallapush:testDebugUnitTest :sallapush:testReleaseUnitTest

check:
	$(GRADLE) check :sallapush:apiCheck

lint:
	$(GRADLE) :sallapush:lint

api:
	$(GRADLE) :sallapush:apiCheck

api-dump:
	$(GRADLE) :sallapush:apiDump

publish-local:
	$(GRADLE) :sallapush:publishToMavenLocal

consumer:
	scripts/verify.sh consumer

pom:
	scripts/verify.sh pom

gates:
	$(GRADLE) :sallapush:compileReleaseKotlin
	scripts/verify.sh gates

snippets:
	scripts/verify.sh snippets

size:
	scripts/verify.sh size

clean:
	$(GRADLE) clean
