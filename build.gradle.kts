plugins {
    id("com.android.library") version "8.7.3" apply false
    id("com.android.application") version "8.7.3" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Publishing to Maven Central (sallapush/build.gradle.kts): the newest release that supports Gradle 8.12 and the
    // Android Gradle plugin 8.7; 0.35.0 needs Gradle 8.13.
    id("com.vanniktech.maven.publish") version "0.34.0" apply false
    // Public API gate: `apiCheck` compares the library against the committed sallapush/api/sallapush.api.
    id("org.jetbrains.kotlinx.binary-compatibility-validator") version "0.16.3"
}

apiValidation {
    ignoredProjects += "sample"
    // AGP generates it in the library's package; it is not part of the SDK's API.
    ignoredClasses += "com.salla.push.BuildConfig"
}
