package com.retrsoft.bilisponsorskip

import android.app.Application
import android.os.Build
import android.os.Process
import java.io.File
import java.util.zip.ZipFile

internal object DexKitNativeLoader {
    @Volatile
    private var loaded = false

    fun ensureLoaded(application: Application) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                System.loadLibrary(LIBRARY_NAME)
                loaded = true
                Log.d("DexKit native library loaded from the module class loader")
                return
            } catch (classLoaderFailure: UnsatisfiedLinkError) {
                val moduleApk = resolveModuleApk(application) ?: throw classLoaderFailure
                val abi = findPackagedAbi(moduleApk) ?: throw UnsatisfiedLinkError(
                    "No DexKit library matches the ${if (Process.is64Bit()) 64 else 32}-bit process",
                ).also { it.addSuppressed(classLoaderFailure) }
                val directPath = "$moduleApk!/lib/$abi/lib$LIBRARY_NAME.so"
                try {
                    System.load(directPath)
                } catch (directLoadFailure: UnsatisfiedLinkError) {
                    directLoadFailure.addSuppressed(classLoaderFailure)
                    throw directLoadFailure
                }
                loaded = true
                Log.d(
                    "DexKit native library loaded directly from module APK: " +
                        "abi=$abi; process=${if (Process.is64Bit()) 64 else 32}-bit",
                )
            }
        }
    }

    private fun resolveModuleApk(application: Application): String? {
        @Suppress("DEPRECATION")
        val packagePath = runCatching {
            application.packageManager.getApplicationInfo(SettingsContract.MODULE_PACKAGE, 0).sourceDir
        }.getOrNull()
        if (packagePath?.let(::File)?.isFile == true) return packagePath

        return MODULE_PATH_REGEX.find(DexKitNativeLoader::class.java.classLoader?.toString().orEmpty())
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { File(it).isFile }
    }

    private fun findPackagedAbi(moduleApk: String): String? = ZipFile(moduleApk).use { zip ->
        processAbiCandidates().firstOrNull { abi ->
            zip.getEntry("lib/$abi/lib$LIBRARY_NAME.so") != null
        }
    }

    private fun processAbiCandidates(): List<String> = buildList {
        when (System.getProperty("os.arch")?.lowercase()) {
            "aarch64", "arm64" -> add("arm64-v8a")
            "arm", "armv7", "armv7l" -> add("armeabi-v7a")
            "x86_64", "amd64" -> add("x86_64")
            "x86", "i386", "i686" -> add("x86")
        }
        addAll(if (Process.is64Bit()) Build.SUPPORTED_64_BIT_ABIS else Build.SUPPORTED_32_BIT_ABIS)
        addAll(if (Process.is64Bit()) listOf("arm64-v8a", "x86_64") else listOf("armeabi-v7a", "x86"))
    }.distinct()

    private const val LIBRARY_NAME = "dexkit"
    private val MODULE_PATH_REGEX = Regex("module=([^,\\]]+\\.apk)")
}
