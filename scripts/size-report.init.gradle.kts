// Used by scripts/size-report.sh only. Adds the task sallapushSizeClasspath to :sample. It writes the sample's release
// runtime classpath, which is what an app gets with SallaPush, to the file named by the Gradle property
// sallapushSizeClasspathFile: one line per artifact, "<group:name:version>\t<file>\t<SallaPush dependencies that bring
// it, comma-separated>".
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentSelector
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

allprojects {
    if (path == ":sample") {
        val sample = this
        tasks.register("sallapushSizeClasspath") {
            val output = providers.gradleProperty("sallapushSizeClasspathFile")
            doLast {
                val target = output.orNull ?: throw GradleException("Set -PsallapushSizeClasspathFile=<file>.")
                val classpath = sample.configurations.getByName("releaseRuntimeClasspath")
                val sdk = classpath.incoming.resolutionResult.root.dependencies
                    .filterIsInstance<ResolvedDependencyResult>()
                    .map { it.selected }
                    .firstOrNull { (it.id as? ProjectComponentIdentifier)?.projectPath == ":sallapush" }
                    ?: throw GradleException("The sample's release runtime classpath has no :sallapush.")
                val requiredBy = mutableMapOf<String, MutableSet<String>>()
                for (direct in sdk.dependencies.filterIsInstance<ResolvedDependencyResult>()) {
                    if (direct.isConstraint) continue
                    val selector = direct.requested as? ModuleComponentSelector ?: continue
                    val label = "${selector.group}:${selector.module}"
                    val seen = mutableSetOf<String>()
                    val queue = ArrayDeque<ResolvedComponentResult>()
                    queue.add(direct.selected)
                    while (queue.isNotEmpty()) {
                        val component = queue.removeFirst()
                        val id = component.id as? ModuleComponentIdentifier ?: continue
                        val key = "${id.group}:${id.module}:${id.version}"
                        if (!seen.add(key)) continue
                        requiredBy.getOrPut(key) { sortedSetOf() }.add(label)
                        for (next in component.dependencies.filterIsInstance<ResolvedDependencyResult>()) {
                            if (!next.isConstraint) queue.add(next.selected)
                        }
                    }
                }
                val lines = classpath.incoming.artifactView { componentFilter { it is ModuleComponentIdentifier } }
                    .artifacts.map { artifact ->
                        val id = artifact.id.componentIdentifier as ModuleComponentIdentifier
                        val key = "${id.group}:${id.module}:${id.version}"
                        "$key\t${artifact.file.absolutePath}\t${requiredBy[key].orEmpty().joinToString(",")}"
                    }
                File(target).writeText(lines.sorted().joinToString("\n", postfix = "\n"))
            }
        }
    }
}
