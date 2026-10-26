// README.md's repository block, verbatim. ../../settings.gradle.kts includes this build, so every consumer check
// evaluates the block as an app's settings script: a typo in it fails the build. Nothing is resolved from it.
// scripts/check-readme-snippets.sh fails when README.md's block differs.
// snippet:repositories
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
    }
}
// end-snippet

rootProject.name = "readme-repositories-kotlin"
