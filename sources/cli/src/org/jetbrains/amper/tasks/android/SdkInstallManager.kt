/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

@file:OptIn(ExperimentalPathApi::class)

package org.jetbrains.amper.tasks.android

import com.android.repository.api.ConsoleProgressIndicator
import com.android.repository.api.LocalPackage
import com.android.repository.api.RemotePackage
import com.android.repository.api.RepoManager
import com.android.repository.api.RepoPackage
import com.android.repository.api.Repository
import com.android.repository.impl.meta.LocalPackageImpl
import com.android.repository.impl.meta.SchemaModuleUtil
import com.android.sdklib.repository.AndroidSdkHandler
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.coroutineScope
import org.jetbrains.amper.cli.AmperUserCacheRoot
import org.jetbrains.amper.concurrency.withDoubleLock
import org.jetbrains.amper.core.extract.ExtractOptions
import org.jetbrains.amper.downloader.Downloader
import org.jetbrains.amper.downloader.extractFileToCacheLocation
import org.jetbrains.amper.downloader.httpClient
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Path
import javax.xml.bind.JAXBElement
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.copyToRecursively
import kotlin.io.path.createDirectories
import kotlin.io.path.createParentDirectories
import kotlin.io.path.div
import kotlin.io.path.exists
import kotlin.io.path.outputStream

private const val androidRepositoryUrl = "https://dl.google.com/"
private const val androidRepositoryBasePath = "/android/repository/"
private const val androidSystemImagePath = "/sys-img/android/"

private val androidRepositoryUrlBuilder: URLBuilder
    get() = URLBuilder(androidRepositoryUrl)
        .also { it.appendPathSegments(androidRepositoryBasePath) }
private val androidSystemImagesRepositoryUrlBuilder: URLBuilder
    get() = URLBuilder(androidRepositoryUrl)
        .also { it.appendPathSegments(androidRepositoryBasePath) }
        .also { it.appendPathSegments(androidSystemImagePath) }

class SdkInstallManager(private val userCacheRoot: AmperUserCacheRoot, private val androidSdkPath: Path) {

    init {
        if (!androidSdkPath.exists()) {
            androidSdkPath.createDirectories()
        }
    }

    suspend fun install(packagePath: String): RepoPackage {
        return if (packagePath.contains("system-images")) {
            installSystemImage(packagePath)
        } else {
            installPackage(packagePath)
        }
    }

    suspend fun installPackage(packagePath: String): RepoPackage =
        withDoubleLock(packagePath.hashCode(), androidSdkPath / "$packagePath.lock") {
            val localFileSystemPackagePath = packagePath
                .split(";")
                .fold(androidSdkPath) { path, component -> path.resolve(component) }

            if (localFileSystemPackagePath.exists()) {
                val packageManifest = localFileSystemPackagePath / "package.xml"
                if (packageManifest.exists()) {
                    val repo = packageManifest.toFile().inputStream().unmarshal<Repository>()
                    repo.localPackage
                } else {
                    error("Package is corrupted: $packagePath")
                }
            } else {
                installPackage(packagePath, localFileSystemPackagePath)
            }
        }

    private suspend fun installPackage(packagePath: String, localPackagePath: Path): RepoPackage {
        val pkg =
            packages().remotePackage.firstOrNull { it.path == packagePath } ?: error("Package $packagePath not found")
        val url = androidRepositoryUrlBuilder.appendPathSegments(pkg.archive.complete.url).build()
        val path = Downloader.downloadFileToCacheLocation(url.toString(), userCacheRoot)
        val cachePath = extractFileToCacheLocation(path, userCacheRoot, ExtractOptions.STRIP_ROOT)
        localPackagePath.createParentDirectories()
        cachePath.copyToRecursively(localPackagePath, followLinks = true)
        return writePackageXml(pkg, localPackagePath)
    }

    suspend fun installSystemImage(packagePath: String): RepoPackage =
        withDoubleLock(packagePath.hashCode(), androidSdkPath / "$packagePath.lock") {
            val localFileSystemPackagePath = packagePath
                .split(";")
                .fold(androidSdkPath) { path, component -> path.resolve(component) }

            if (localFileSystemPackagePath.exists()) {
                val packageManifest = localFileSystemPackagePath / "package.xml"
                if (packageManifest.exists()) {
                    val repo = packageManifest.toFile().inputStream().unmarshal<Repository>()
                    repo.localPackage
                } else {
                    error("Package is corrupted: $packagePath")
                }
            } else {
                installSystemImage(packagePath, localFileSystemPackagePath)
            }
        }

    private suspend fun installSystemImage(packagePath: String, localPackagePath: Path): RepoPackage {
        val pkg = systemImages().remotePackage.firstOrNull { it.path == packagePath }
            ?: error("Package $packagePath not found")
        val url = androidSystemImagesRepositoryUrlBuilder.appendPathSegments(pkg.archive.complete.url).build()
        val path = Downloader.downloadFileToCacheLocation(url.toString(), userCacheRoot)
        val cachePath = extractFileToCacheLocation(path, userCacheRoot, ExtractOptions.STRIP_ROOT)
        localPackagePath.createParentDirectories()
        cachePath.copyToRecursively(localPackagePath, followLinks = true)
        return writePackageXml(pkg, localPackagePath)
    }

    suspend fun packages(): Repository = coroutineScope {
        val urlBuilder = androidRepositoryUrlBuilder.appendPathSegments("/repository2-3.xml")
        val xmlStream = httpClient.get(urlBuilder.build()).bodyAsChannel().toInputStream()
        xmlStream.unmarshal()
    }

    suspend fun systemImages(): Repository = coroutineScope {
        val urlBuilder = androidSystemImagesRepositoryUrlBuilder.appendPathSegments("/sys-img2-3.xml")
        val xmlStream = httpClient.get(urlBuilder.build()).bodyAsChannel().toInputStream()
        xmlStream.unmarshal()
    }

    private fun writePackageXml(pkg: RemotePackage, localPackagePath: Path): LocalPackage {
        val localPackage = LocalPackageImpl.create(pkg)
        val factory = pkg.createFactory()
        val repo = factory.createRepositoryType()
        repo.setLocalPackage(localPackage)
        repo.addLicense(pkg.license)
        (localPackagePath / "package.xml").outputStream().marshal(factory.generateRepository(repo))
        return localPackage
    }

    private fun <T> InputStream.unmarshal(): T = SchemaModuleUtil.unmarshal(
        this,
        listOf(
            AndroidSdkHandler.getRepositoryModule(),
            AndroidSdkHandler.getAddonModule(),
            AndroidSdkHandler.getSysImgModule(),
            AndroidSdkHandler.getCommonModule(),
            RepoManager.getGenericModule(),
            RepoManager.getCommonModule(),
        ),
        true,
        ConsoleProgressIndicator(),
        ""
    ) as T

    private fun <T> OutputStream.marshal(obj: T) {
        val allModules = setOf(
            AndroidSdkHandler.getRepositoryModule(),
            AndroidSdkHandler.getAddonModule(),
            AndroidSdkHandler.getSysImgModule(),
            AndroidSdkHandler.getCommonModule(),
            RepoManager.getGenericModule(),
            RepoManager.getCommonModule(),
        )
        val resourceResolver = SchemaModuleUtil.createResourceResolver(allModules, ConsoleProgressIndicator())
        SchemaModuleUtil.marshal(
            obj as JAXBElement<*>,
            allModules,
            this,
            resourceResolver,
            ConsoleProgressIndicator()
        )
    }
}
