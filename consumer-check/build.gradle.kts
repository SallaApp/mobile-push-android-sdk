// The oldest toolchain README.md promises: Kotlin 1.9 with compileSdk 34 (app/build.gradle.kts), on the SDK's own
// Android Gradle Plugin.
plugins {
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
