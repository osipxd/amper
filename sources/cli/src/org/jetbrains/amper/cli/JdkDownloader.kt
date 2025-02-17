/*
 * Copyright 2000-2024 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
 */

package org.jetbrains.amper.cli

import org.jetbrains.amper.cli.JdkDownloader.Arch
import org.jetbrains.amper.cli.JdkDownloader.OS
import org.jetbrains.amper.core.extract.ExtractOptions
import org.jetbrains.amper.downloader.Downloader
import org.jetbrains.amper.downloader.extractFileToCacheLocation
import java.net.URL
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

class Jdk(
    /**
     * The root directory of this JDK installation (which could be used as JAVA_HOME).
     */
    val homeDir: Path,
    /**
     * The URL from which this JDK was downloaded.
     */
    val downloadUrl: URL,
    /**
     * The full version number, including major/minor/path, but also potential extra numbers.
     */
    val version: String,
) {
    // not lazy so we immediately validate that the java executable is present
    val javaExecutable: Path = bin("java")
    val javacExecutable: Path by lazy { bin("javac") }

    private fun bin(executableName: String): Path {
        val plain = homeDir.resolve("bin/$executableName")
        if (plain.exists()) return plain
        
        val exe = homeDir.resolve("bin/$executableName.exe")
        if (exe.exists()) return exe

        error("No $executableName executables were found under $homeDir")
    }
}

// TODO so far, no version selection, need to design it a little

object JdkDownloader {

    // private because we don't want other classes to use these versions, they're an implementation detail right now
    private const val correttoJdkVersion = "21.0.1.12.1"
    private const val microsoftJdkVersion = "21.0.1"

    suspend fun getJdk(userCacheRoot: AmperUserCacheRoot): Jdk = getJdk(userCacheRoot, OS.current, Arch.current)

    internal suspend fun getJdk(userCacheRoot: AmperUserCacheRoot, os: OS, arch: Arch): Jdk {
        val downloadUri = jdkDownloadUrlFor(os, arch)
        val jdkArchive = Downloader.downloadFileToCacheLocation(downloadUri.toString(), userCacheRoot)
        val jdkExtracted = extractFileToCacheLocation(jdkArchive, userCacheRoot, ExtractOptions.STRIP_ROOT)

        // Corretto archives for macOS contain the JDK under amazon-corretto-X.jdk/Contents/Home
        val contentsHome = jdkExtracted.resolve("Contents/Home")
        val jdkHome: Path = if (contentsHome.isDirectory()) contentsHome else jdkExtracted

        return Jdk(homeDir = jdkHome, downloadUrl = downloadUri, version = hardcodedVersionFor(os, arch))
    }

    private fun jdkDownloadUrlFor(os: OS, arch: Arch): URL =
        // No Corretto build for Windows Arm64, so use Microsoft's JDK
        if (os == OS.WINDOWS && arch == Arch.ARM64) {
            microsoftJdkUrlWindowsArm64(hardcodedVersionFor(os, arch))
        } else {
            correttoJdkUrl(os, arch, hardcodedVersionFor(os, arch))
        }
    
    private fun hardcodedVersionFor(os: OS, arch: Arch) = if (os == OS.WINDOWS && arch == Arch.ARM64) {
        microsoftJdkVersion
    } else {
        correttoJdkVersion
    }

    // See releases: https://learn.microsoft.com/en-ca/java/openjdk/download
    private fun microsoftJdkUrlWindowsArm64(version: String): URL =
        URL("https://aka.ms/download-jdk/microsoft-jdk-$version-windows-aarch64.zip")

    // See releases: https://docs.aws.amazon.com/corretto/latest/corretto-21-ug/downloads-list.html
    private fun correttoJdkUrl(os: OS, arch: Arch, version: String): URL {
        val ext = if (os == OS.WINDOWS) "-jdk.zip" else ".tar.gz"
        val osString: String = os.forCorrettoUrl()
        val archString: String = arch.forCorrettoUrl()
        return URL("https://corretto.aws/downloads/resources/$version/amazon-corretto-$version-$osString-$archString$ext")
    }

    internal enum class OS {
        WINDOWS,
        MACOSX,
        LINUX;

        companion object {
            val current: OS
                get() {
                    val osName = System.getProperty("os.name").lowercase()
                    return when {
                        osName.startsWith("mac") -> MACOSX
                        osName.startsWith("linux") -> LINUX
                        osName.startsWith("windows") -> WINDOWS
                        else -> throw IllegalStateException("Only Mac/Linux/Windows are supported now, current os: $osName")
                    }
                }
        }
    }

    internal enum class Arch {
        X86_64,
        ARM64;

        companion object {
            val current: Arch
                get() {
                    val arch = System.getProperty("os.arch").lowercase()
                    if ("x86_64" == arch || "amd64" == arch) return X86_64
                    if ("aarch64" == arch || "arm64" == arch) return ARM64
                    throw IllegalStateException("Only X86_64 and ARM64 are supported, current arch: $arch")
                }
        }
    }
}

private fun Arch.forCorrettoUrl() = when (this) {
    Arch.X86_64 -> "x64"
    Arch.ARM64 -> "aarch64"
}

private fun OS.forCorrettoUrl() = when (this) {
    OS.WINDOWS -> "windows"
    OS.MACOSX -> "macosx"
    OS.LINUX -> "linux"
}
