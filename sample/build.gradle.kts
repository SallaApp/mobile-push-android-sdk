import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// scripts/size-report.sh also builds this app as a size baseline: the same app without SallaPush, as an app that uses
// Firebase Messaging (-Psallapush.sample.sizeBaseline=fcm) or one without push (=bare). A baseline builds into its own
// folder, so it never replaces the sample's outputs.
val sizeBaseline: String? = providers.gradleProperty("sallapush.sample.sizeBaseline").orNull
if (sizeBaseline != null) {
    if (sizeBaseline != "fcm" && sizeBaseline != "bare") {
        throw GradleException("sallapush.sample.sizeBaseline is \"$sizeBaseline\"; use fcm or bare.")
    }
    layout.buildDirectory.set(layout.projectDirectory.dir("build/size-baseline/$sizeBaseline"))
}

// Optional: your own Firebase project, without the google-services plugin and without committing google-services.json.
// These are the string resources the plugin would generate from it; Firebase reads them when the app starts.
val firebaseProperties = mapOf(
    "google_app_id" to "sallapush.sample.firebase.appId",
    "google_api_key" to "sallapush.sample.firebase.apiKey",
    "project_id" to "sallapush.sample.firebase.projectId",
    "gcm_defaultSenderId" to "sallapush.sample.firebase.senderId",
)
val firebaseValues = firebaseProperties.mapValues { (_, property) -> providers.gradleProperty(property).orNull }
if (firebaseValues.values.any { it != null } && firebaseValues.values.any { it == null }) {
    throw GradleException("Set all of ${firebaseProperties.values.joinToString()}, or none of them.")
}

android {
    namespace = "com.salla.push.sample"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.salla.push.sample"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
        firebaseValues.forEach { (resource, value) -> if (value != null) resValue("string", resource, value) }
    }

    buildTypes {
        release {
            // R8 shrinks and obfuscates the release build with Android's default optimize rules and the libraries'
            // consumer rules, SallaPush's included: the app adds none of its own.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Signed with the debug key, so that the minified build installs on a device. Never publish it.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        abortOnError = true
        // The sample keeps the SDK's pinned toolchain; "a newer version exists" is not actionable here.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }

    if (sizeBaseline != null) {
        val withFcm = sizeBaseline == "fcm"
        sourceSets.getByName("main") {
            java.setSrcDirs(listOfNotNull("src/sizeBaseline/java", if (withFcm) "src/sizeBaselineFcm/java" else null))
            manifest.srcFile(
                if (withFcm) "src/sizeBaselineFcm/AndroidManifest.xml" else "src/sizeBaseline/AndroidManifest.xml",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    when (sizeBaseline) {
        null -> implementation(project(":sallapush"))
        // The version SallaPush itself depends on, so that the baseline holds the same Firebase code; size-report.sh
        // fails when the two differ.
        "fcm" -> implementation("com.google.firebase:firebase-messaging:24.1.0")
        else -> Unit
    }
}
