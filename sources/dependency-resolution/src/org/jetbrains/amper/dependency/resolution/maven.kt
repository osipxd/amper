/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.dependency.resolution

import org.jetbrains.amper.dependency.resolution.metadata.json.AvailableAt
import org.jetbrains.amper.dependency.resolution.metadata.json.Capability
import org.jetbrains.amper.dependency.resolution.metadata.json.Dependency
import org.jetbrains.amper.dependency.resolution.metadata.json.Variant
import org.jetbrains.amper.dependency.resolution.metadata.json.Version
import org.jetbrains.amper.dependency.resolution.metadata.json.parseMetadata
import org.jetbrains.amper.dependency.resolution.metadata.xml.Dependencies
import org.jetbrains.amper.dependency.resolution.metadata.xml.DependencyManagement
import org.jetbrains.amper.dependency.resolution.metadata.xml.Project
import org.jetbrains.amper.dependency.resolution.metadata.xml.expandTemplates
import org.jetbrains.amper.dependency.resolution.metadata.xml.parsePom
import org.jetbrains.amper.dependency.resolution.metadata.xml.plus
import kotlin.properties.ReadOnlyProperty
import kotlin.reflect.KProperty

/**
 * Serves as a holder for a dependency defined by Maven coordinates, namely, group, module, and version.
 * While each node in a graph is expected to be unique, its [dependency] can be shared across other nodes
 * as long as their groups and modules match.
 * A version discrepancy might occur if a conflict resolution algorithm intervenes and is expected.
 *
 * The node doesn't do actual work but simply delegate to the [dependency] that can change over time.
 * This allows reusing dependency resolution results but still holding information about the origin.
 *
 * It's a responsibility of the caller to set a parent for this node if none was provided via the constructor.
 *
 * @see [ModuleDependencyNode]
 */
class MavenDependencyNode internal constructor(
    templateContext: Context,
    dependency: MavenDependency,
    parentNode: DependencyNode? = null,
) : DependencyNode {

    constructor(
        templateContext: Context,
        group: String,
        module: String,
        version: String,
        parentNode: DependencyNode? = null,
    ) : this(
        templateContext,
        createOrReuseDependency(templateContext, group, module, version),
        parentNode,
    )

    var dependency: MavenDependency = dependency
        set(value) {
            assert(group == value.group) { "Groups don't match. Expected: $group, actual: ${value.group}" }
            assert(module == value.module) { "Modules don't match. Expected: $module, actual: ${value.module}" }
            field = value
        }

    val group: String = dependency.group
    val module: String = dependency.module
    val version: String = dependency.version

    override val context: Context = templateContext.copyWithNewNodeCache(parentNode)
    override val key: Key<*> = Key<MavenDependency>("$group:$module")
    override val state: ResolutionState
        get() = dependency.state
    override val children: List<DependencyNode> by PropertyWithDependency(
        value = listOf<MavenDependencyNode>(),
        dependency = listOf<DependencyNode>(),
        valueProvider = { thisRef ->
            thisRef.dependency.children.map { MavenDependencyNode(thisRef.context, it, this) }
        },
        dependencyProvider = { thisRef ->
            thisRef.dependency.children.toList()
        }
    )
    override val messages: List<Message>
        get() = dependency.messages

    override fun resolve(level: ResolutionLevel) {
        dependency.resolve(context, level)
    }

    override fun downloadDependencies() {
        dependency.downloadDependencies(context.settings)
    }

    override fun toString(): String = if (dependency.version == version) {
        dependency.toString()
    } else {
        "$group:$module:$version -> ${dependency.version}"
    }
}

/**
 * A lazy property that's recalculated if its dependency changes. Single-threaded only.
 */
class PropertyWithDependency<in T, out V, D>(
    private var value: V,
    private var dependency: D,
    val valueProvider: (T) -> V,
    val dependencyProvider: (T) -> D
) : ReadOnlyProperty<T, V> {
    override fun getValue(thisRef: T, property: KProperty<*>): V {
        val newDependency = dependencyProvider(thisRef)
        if (dependency != newDependency) {
            dependency = newDependency
            value = valueProvider(thisRef)
        }
        return value
    }
}

private fun createOrReuseDependency(
    context: Context,
    group: String,
    module: String,
    version: String
): MavenDependency = context.resolutionCache.computeIfAbsent(Key<MavenDependency>("$group:$module:$version")) {
    MavenDependency(context.settings.fileCache, group, module, version)
}

/**
 * An actual Maven dependency that can be resolved, that is, populated with children according to the requested
 * [ResolutionScope] and platform.
 * Its [resolve] method contains the resolution algorithm.
 *
 * @see [DependencyFile]
 */
class MavenDependency internal constructor(
    val fileCache: FileCache,
    val group: String,
    val module: String,
    val version: String
) {

    var state: ResolutionState = ResolutionState.INITIAL
    val children: MutableList<MavenDependency> = mutableListOf()
    var variant: Variant? = null
    var packaging: String = "jar"
    val messages: MutableList<Message> = mutableListOf()

    val metadata = getDependencyFile(this, getNameWithoutExtension(this), "module")
    val pom = getDependencyFile(this, getNameWithoutExtension(this), "pom")
    val files
        get() = buildMap {
            variant?.files?.forEach {
                val extension = it.name.substringAfterLast('.')
                put(
                    extension,
                    getDependencyFile(this@MavenDependency, getNameWithoutExtension(this@MavenDependency), extension)
                )
            }
            packaging.takeIf { it != "pom" }?.let {
                val extension = if (it == "bundle") "jar" else it
                put(
                    extension,
                    getDependencyFile(this@MavenDependency, getNameWithoutExtension(this@MavenDependency), extension)
                )
            }
        }

    override fun toString(): String = "$group:$module:$version"

    fun resolve(context: Context, level: ResolutionLevel) {
        val settings = context.settings
        // 1. Download pom.
        val pomText = if (pom.isDownloadedOrDownload(level, settings)) {
            pom.readText()
        } else {
            messages += Message(
                "Pom required for $this",
                settings.repositories.toString(),
                if (level == ResolutionLevel.NETWORK) Severity.ERROR else Severity.WARNING,
            )
            null
        }
        // 2. If pom is missing or mentions metadata, use it.
        if (pomText == null || pomText.contains("do_not_remove: published-with-gradle-metadata")) {
            if (metadata.isDownloadedOrDownload(level, settings)) {
                resolveUsingMetadata(context, level)
                return
            }
            if (pomText != null) {
                messages += Message(
                    "Pom provided but metadata required for $this",
                    context.settings.repositories.toString(),
                    if (level.state == ResolutionState.RESOLVED) Severity.ERROR else Severity.WARNING,
                )
            }
        }
        // 3. If can't use metadata, use pom.
        if (pomText != null) {
            resolveUsingPom(pomText, context, level)
        }
    }

    private fun resolveUsingMetadata(context: Context, level: ResolutionLevel) {
        val module = try {
            metadata.readText().parseMetadata()
        } catch (e: Exception) {
            messages += Message(
                "Unable to parse metadata file $metadata",
                e.toString(),
                Severity.ERROR,
            )
            return
        }
        module.variants.filter {
            it.capabilities.isEmpty() || it.capabilities == listOf(toCapability()) || it.isOneOfExceptions(context)
        }.filter {
            context.settings.platform.matches(it) && context.settings.scope.matches(it)
        }.also {
            if (it.size <= 1) {
                variant = it.singleOrNull()
            } else {
                messages += Message(
                    "More than a single variant provided",
                    it.joinToString { it.name },
                    Severity.WARNING,
                )
            }
        }.flatMap {
            it.dependencies + listOfNotNull(it.`available-at`?.asDependency())
        }.map {
            createOrReuseDependency(context, it.group, it.module, it.version.requires)
        }.let {
            children.addAll(it)
            state = level.state
        }
    }

    private fun Variant.isOneOfExceptions(context: Context) = isKotlinException() || isGuavaException(context)

    private fun Variant.isKotlinException() =
        isKotlinTestJunit() && capabilities.sortedBy { it.name } == listOf(
            Capability(group, "kotlin-test-framework-impl", version),
            toCapability()
        )

    private fun isKotlinTestJunit() =
        group == "org.jetbrains.kotlin" && (module in setOf("kotlin-test-junit", "kotlin-test-junit5"))

    private fun Variant.isGuavaException(context: Context) =
        isGuava() && capabilities.sortedBy { it.name } == listOf(
            Capability("com.google.collections", "google-collections", version),
            toCapability()
        ) && attributes["org.gradle.jvm.environment"]?.endsWith(context.settings.platform) == true

    private fun isGuava() = group == "com.google.guava" && module == "guava"

    private fun MavenDependency.toCapability() = Capability(group, module, version)

    private fun String.matches(variant: Variant) =
        variant.attributes["org.jetbrains.kotlin.platform.type"]?.let { it == this } ?: true

    private fun AvailableAt.asDependency() = Dependency(group, module, Version(version))

    private fun resolveUsingPom(text: String, context: Context, level: ResolutionLevel) {
        val project = try {
            text.parsePom().resolve(context, level)
        } catch (e: Exception) {
            messages += Message(
                "Unable to parse pom file ${this.pom}",
                e.toString(),
                Severity.ERROR,
            )
            return
        }
        project.packaging?.let { packaging = it }
        (project.dependencies?.dependencies ?: listOf()).filter {
            context.settings.scope.matches(it)
        }.filter {
            it.version != null && it.optional != true
        }.map {
            createOrReuseDependency(context, it.groupId, it.artifactId, it.version!!)
        }.let {
            children.addAll(it)
            state = level.state
        }
    }

    /**
     * Resolves a Maven project by recursively substituting references to parent projects and templates
     * with actual values.
     * Additionally, dependency versions are defined using dependency management.
     */
    private fun Project.resolve(
        context: Context,
        resolutionLevel: ResolutionLevel,
        depth: Int = 0,
        origin: Project = this
    ): Project {
        if (depth > 10) {
            messages += Message(
                "Project ${origin.name} has more than ten ancestors",
                severity = Severity.WARNING,
            )
            return this
        }
        val settings = context.settings
        val parentNode = parent?.let {
            createOrReuseDependency(context, it.groupId, it.artifactId, it.version)
        }
        val project = if (parentNode != null && (parentNode.pom.isDownloadedOrDownload(resolutionLevel, settings))) {
            val text = parentNode.pom.readText()
            val parentProject = text.parsePom().resolve(context, resolutionLevel, depth + 1, origin)
            copy(
                groupId = groupId ?: parentProject.groupId,
                artifactId = artifactId ?: parentProject.artifactId,
                version = version ?: parentProject.version,
                dependencies = dependencies + parentProject.dependencies,
                dependencyManagement = dependencyManagement + parentProject.dependencyManagement,
                properties = properties + parentProject.properties,
            )
        } else if (parent != null && (groupId == null || artifactId == null || version == null)) {
            copy(
                groupId = groupId ?: parent.groupId,
                artifactId = artifactId ?: parent.artifactId,
                version = version ?: parent.version,
            )
        } else {
            this
        }
        val dependencyManagement = project.dependencyManagement
            ?.dependencies
            ?.dependencies
            ?.map { it.expandTemplates(project) }
            ?.flatMap {
                if (it.scope == "import" && it.version != null) {
                    val dependency = createOrReuseDependency(context, it.groupId, it.artifactId, it.version)
                    if (dependency.pom.isDownloadedOrDownload(resolutionLevel, settings)) {
                        val text = dependency.pom.readText()
                        val dependencyProject = text.parsePom().resolve(context, resolutionLevel, depth + 1, origin)
                        dependencyProject.dependencyManagement?.dependencies?.dependencies?.let {
                            return@flatMap it
                        }
                    }
                }
                return@flatMap listOf(it)
            }
        val dependencies = project.dependencies
            ?.dependencies
            ?.map {
                if (it.version == null) {
                    val dependency = dependencyManagement?.find { dep ->
                        dep.groupId == it.groupId && dep.artifactId == it.artifactId
                    }
                    dependency?.version
                        ?.let { version -> it.copy(version = version) }
                        ?.let { return@map it }
                }
                return@map it
            }
            ?.map { it.expandTemplates(project) }
        return project.copy(
            dependencies = dependencies?.let { Dependencies(it) },
            dependencyManagement = dependencyManagement?.let { DependencyManagement(Dependencies(it)) },
        )
    }

    private fun DependencyFile.isDownloadedOrDownload(level: ResolutionLevel, settings: Settings) =
        isDownloaded() && hasMatchingChecksum(level, settings) || level == ResolutionLevel.NETWORK && download(settings)

    fun downloadDependencies(settings: Settings) {
        files.values
            .filter { !(it.isDownloaded() && it.hasMatchingChecksum(ResolutionLevel.NETWORK, settings)) }
            .forEach { it.download(settings) }
    }
}
