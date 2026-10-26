import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.vanniktech.maven.publish")
}

val sdkGroup: String = providers.gradleProperty("GROUP").get()
val sdkVersion: String = providers.gradleProperty("VERSION_NAME").get()

group = sdkGroup
version = sdkVersion

android {
    namespace = "com.salla.push"
    compileSdk = 35

    defaultConfig {
        minSdk = 23
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            all {
                it.maxHeapSize = "2g"
                it.systemProperty("sallapush.logCodesFile", layout.buildDirectory.file("log-codes/${it.name}.txt").get().asFile.path)
            }
        }
    }

    lint {
        abortOnError = true
        checkDependencies = false
        // Dependency and plugin versions are pinned on purpose; "a newer version exists" is not actionable here.
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable")
    }
}

kotlin {
    explicitApi()
    coreLibrariesVersion = "1.9.24"
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_1_8)
        languageVersion.set(KotlinVersion.KOTLIN_1_9)
        apiVersion.set(KotlinVersion.KOTLIN_1_9)
        // No Kotlin null-check intrinsics: a Java null must reach the guarded body of every public entry instead of throwing first.
        freeCompilerArgs.addAll("-Xno-param-assertions", "-Xno-call-assertions", "-Xno-receiver-assertions", "-Xjvm-default=all")
    }
}

dependencies {
    api("com.google.firebase:firebase-messaging:24.1.0")
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.work:work-runtime:2.9.1")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

// The publication Maven Central requires: the release AAR with its sources jar and its javadoc jar (which the Android
// Gradle plugin makes with Dokka), signed, and a POM with the project's name, description, URL, licence, developer and
// source repository.
mavenPublishing {
    configure(AndroidSingleVariantLibrary(variant = "release", sourcesJar = true, publishJavadocJar = true))
    coordinates(sdkGroup, "mobile-push", sdkVersion)
    // The upload makes a deployment in the Central Portal, which a maintainer then publishes there: nothing reaches
    // Maven Central before that last step.
    publishToMavenCentral(automaticRelease = false)
    // The release signs with the key in the signingInMemoryKey Gradle property (ORG_GRADLE_PROJECT_signingInMemoryKey,
    // with signingInMemoryKeyPassword). Builds without it, local ones and CI's checks, publish unsigned to the local
    // Maven repository; Maven Central refuses an unsigned upload.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }
    pom {
        name.set("SallaPush")
        description.set("SallaPush Android SDK: push notifications for the Android apps of Salla stores: registration, customer identity, tags, events and notification display.")
        url.set("https://github.com/SallaApp/mobile-push-android-sdk")
        inceptionYear.set("2026")
        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/license/mit")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("salla")
                name.set("Salla")
                url.set("https://salla.sa")
                organization.set("Salla")
                organizationUrl.set("https://salla.sa")
            }
        }
        organization {
            name.set("Salla")
            url.set("https://salla.sa")
        }
        scm {
            url.set("https://github.com/SallaApp/mobile-push-android-sdk")
            connection.set("scm:git:https://github.com/SallaApp/mobile-push-android-sdk.git")
            developerConnection.set("scm:git:https://github.com/SallaApp/mobile-push-android-sdk.git")
        }
        issueManagement {
            system.set("GitHub Issues")
            url.set("https://github.com/SallaApp/mobile-push-android-sdk/issues")
        }
    }
}

// Publishing to Maven Central runs only in this repository's release workflow (.github/workflows/publish.yml);
// publishToMavenLocal works anywhere.
tasks.withType<PublishToMavenRepository>().configureEach {
    onlyIf { System.getenv("GITHUB_REPOSITORY") == "SallaApp/mobile-push-android-sdk" }
}

// Log catalogue coverage (scripts/log-catalogue.txt): every Android code in the catalogue is emitted by some test, and
// every emitted code is catalogued. The test JVM records the codes it emitted in build/log-codes/<test task>.txt.
val logCatalogue = layout.projectDirectory.file("src/test/resources/log-catalog-android.txt")
listOf("Debug", "Release").forEach { variant ->
    val testTaskName = "test${variant}UnitTest"
    val emittedCodes = layout.buildDirectory.file("log-codes/$testTaskName.txt")
    tasks.matching { it.name == testTaskName }.configureEach {
        doFirst { emittedCodes.get().asFile.delete() }
    }
    val verify = tasks.register("verify${variant}LogCatalog") {
        group = "verification"
        description = "Checks that the $variant unit tests emit every catalogued log code and nothing else."
        dependsOn(testTaskName)
        inputs.file(logCatalogue)
        doLast {
            val catalogued = logCatalogue.asFile.readLines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .map { it.substringBefore(' ') }
                .toSortedSet()
            val emittedFile = emittedCodes.get().asFile
            val emitted = if (emittedFile.isFile) emittedFile.readLines().map { it.trim() }.filter { it.isNotEmpty() }.toSortedSet() else sortedSetOf()
            val neverEmitted = catalogued - emitted
            val notCatalogued = emitted - catalogued
            if (neverEmitted.isNotEmpty() || notCatalogued.isNotEmpty()) {
                throw GradleException("Log catalogue coverage failed. Never emitted by a test: $neverEmitted. Emitted but not catalogued: $notCatalogued.")
            }
        }
    }
    tasks.named("check") { dependsOn(verify) }
}
