// A separate Gradle build that consumes SallaPush the way README.md tells an app to, from the AAR that
// `./gradlew :sallapush:publishToMavenLocal` publishes. Run it from the SDK folder: ./gradlew -p consumer-check assembleDebug
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// README.md's repository block, plus an exclusiveContent part that takes com.salla:mobile-push from mavenLocal() only:
// the AAR that publishToMavenLocal publishes stands in for Maven Central's. scripts/check-readme-snippets.sh fails when
// the block, without that part, differs from README.md's.
// snippet:repositories
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        exclusiveContent {
            forRepository {
                mavenLocal()
            }
            filter { includeModule("com.salla", "mobile-push") }
        }
    }
}
// end-snippet

rootProject.name = "sallapush-consumer-check"
include(":app")

// Small builds of their own, which this one includes:
// - kotlin and groovy hold README.md's repository block as it is, in a Kotlin and a Groovy settings script. Nothing is
//   resolved through them.
// - plugin is shaped like a React Native app: a plugin adds repositories to every project, so Gradle ignores the
//   settings' repositories, and SallaPush resolves through the projects' own, with the same mavenLocal() stand-in for
//   Maven Central as above.
// Gradle evaluates an included build's settings script in every run; app/build.gradle.kts makes every consumer check
// run a task of plugin, so its build scripts are evaluated too.
includeBuild("readme-repositories/kotlin")
includeBuild("readme-repositories/groovy")
includeBuild("readme-repositories/plugin")
