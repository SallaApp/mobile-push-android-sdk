import com.android.build.api.artifact.SingleArtifact
import javax.xml.parsers.DocumentBuilderFactory

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// No build feature is turned on (so no BuildConfig class, since Android Gradle Plugin 8): the snippets of README.md and
// docs/ must compile in an app that turned nothing on.
android {
    namespace = "com.salla.push.consumercheck"
    // The lowest compileSdk SallaPush's dependencies accept: androidx.core 1.13.1 and androidx.work 2.9.1 need 34.
    compileSdk = 34

    defaultConfig {
        applicationId = "com.salla.push.consumercheck"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }
}

// snippet:dependency
dependencies {
    implementation("com.salla:mobile-push:1.1.0")
}
// end-snippet

// The app's own WorkManager dependency, which docs/REFERENCE.md asks for when the app initialises WorkManager on
// demand (App.kt).
// snippet:work-dependency
dependencies {
    implementation("androidx.work:work-runtime:2.9.1")
}
// end-snippet

/**
 * Checks a variant's merged manifest: SallaPush's components arrived with the published AAR, docs/REFERENCE.md's
 * removal line took SallaPush's fallback FCM service out, FCM delivers to one of the app's own services
 * (StorePushService, at priority 1), and docs/REFERENCE.md's on-demand lines took WorkManager's startup initializer out
 * (src/main/AndroidManifest.xml).
 */
abstract class CheckMergedManifest : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val mergedManifest: RegularFileProperty

    @TaskAction
    fun check() {
        val document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(mergedManifest.get().asFile)
        val android = "http://schemas.android.com/apk/res/android"
        fun children(parent: org.w3c.dom.Element?, tag: String): List<org.w3c.dom.Element> {
            val nodes = parent?.getElementsByTagName(tag) ?: document.getElementsByTagName(tag)
            return (0 until nodes.length).map { nodes.item(it) as org.w3c.dom.Element }
        }
        fun name(element: org.w3c.dom.Element): String = element.getAttributeNS(android, "name")
        fun names(tag: String): Set<String> = children(null, tag).map(::name).toSet()
        val problems = mutableListOf<String>()
        if ("com.salla.push.internal.click.SallaPushNotificationOpenActivity" !in names("activity")) {
            problems += "SallaPush's notification open activity is missing: the AAR's manifest did not merge"
        }
        if ("android.permission.POST_NOTIFICATIONS" !in names("uses-permission")) {
            problems += "POST_NOTIFICATIONS is missing: the AAR's manifest did not merge"
        }
        val services = names("service")
        if ("com.salla.push.SallaPushFirebaseMessagingService" in services) {
            problems += "SallaPushFirebaseMessagingService is still declared: docs/REFERENCE.md's removal line did not remove it"
        }
        if ("com.salla.push.consumercheck.readme.MyFirebaseMessagingService" !in services) {
            problems += "the app's own FirebaseMessagingService is missing"
        }
        // FCM hands each message to the MESSAGING_EVENT service with the highest intent-filter priority (0 when unset).
        val fcmServices = children(null, "service").mapNotNull { service ->
            val filter = children(service, "intent-filter").firstOrNull { filter ->
                children(filter, "action").any { name(it) == "com.google.firebase.MESSAGING_EVENT" }
            }
            filter?.let { name(service) to (it.getAttributeNS(android, "priority").toIntOrNull() ?: 0) }
        }
        val first = fcmServices.maxByOrNull { it.second }
        if (first == null || first.first != "com.salla.push.consumercheck.StorePushService" || first.second != 1) {
            problems += "FCM would deliver to ${first?.first ?: "no service"}, not to the app's own StorePushService at priority 1"
        }
        if ("androidx.work.WorkManagerInitializer" in names("meta-data")) {
            problems += "WorkManagerInitializer is still declared: docs/REFERENCE.md's on-demand lines did not remove it"
        }
        if (problems.isNotEmpty()) throw GradleException("Merged manifest check failed: ${problems.joinToString("; ")}.")
    }
}

// The included build's task (../settings.gradle.kts): plugin's resolves SallaPush through the projects' own
// repositories, in a build whose plugin adds repositories to every project.
val readmeRepositoryChecks = listOf(
    gradle.includedBuild("plugin").task(":app:checkSallaPushResolves"),
)

androidComponents {
    onVariants { variant ->
        val variantName = variant.name.replaceFirstChar { it.uppercase() }
        val check = tasks.register<CheckMergedManifest>("check${variantName}MergedManifest") {
            mergedManifest.set(variant.artifacts.get(SingleArtifact.MERGED_MANIFEST))
        }
        tasks.matching { it.name == "assemble$variantName" }.configureEach {
            dependsOn(check)
            dependsOn(readmeRepositoryChecks)
        }
    }
}
