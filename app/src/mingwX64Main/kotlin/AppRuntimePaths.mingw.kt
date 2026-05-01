import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import kotlinx.cinterop.toKStringFromUtf16
import platform.posix.getenv
import platform.windows.GetModuleFileNameW

private val appRuntimeBaseDir: String by lazy(::detectAppRuntimeBaseDir)
private val appRuntimeMetaInfDir: String by lazy { "$appRuntimeBaseDir\\META-INF" }
private val appRuntimeBinDir: String by lazy { "$appRuntimeBaseDir\\bin" }
private val appRuntimeTempDir: String by lazy(::detectAppRuntimeTempDir)

fun resolveRuntimePath(path: String): String {
    val normalized = path.replace('/', '\\')
    if (isAbsoluteWindowsPath(normalized)) return normalized

    val trimmed = normalizeRelativeRuntimePath(normalized)

    return if (trimmed.isBlank()) {
        appRuntimeBaseDir
    } else {
        "$appRuntimeBaseDir\\$trimmed"
    }
}

fun resolveRuntimeBinPath(path: String): String {
    val normalized = path.replace('/', '\\')
    if (isAbsoluteWindowsPath(normalized)) return normalized

    val trimmed = normalizeRelativeRuntimePath(normalized)

    return if (trimmed.isBlank()) {
        appRuntimeBinDir
    } else {
        "$appRuntimeBinDir\\$trimmed"
    }
}

fun resolveRuntimeMetaInfPath(path: String): String {
    val normalized = path.replace('/', '\\')
    if (isAbsoluteWindowsPath(normalized)) return normalized

    val trimmed = normalizeRelativeRuntimePath(normalized)

    return if (trimmed.isBlank()) {
        appRuntimeMetaInfDir
    } else {
        "$appRuntimeMetaInfDir\\$trimmed"
    }
}

fun resolveTempPath(path: String): String {
    val normalized = path.replace('/', '\\')
    if (isAbsoluteWindowsPath(normalized)) return normalized

    val trimmed = normalizeRelativeRuntimePath(normalized)

    return if (trimmed.isBlank()) {
        appRuntimeTempDir
    } else {
        "$appRuntimeTempDir\\$trimmed"
    }
}

fun resolveCandidatePaths(path: String): List<String> {
    val normalized = path.replace('/', '\\')
    if (isAbsoluteWindowsPath(normalized)) return listOf(normalized)
    return listOf(
        resolveRuntimePath(normalized),
        resolveRuntimeBinPath(normalized),
        normalized,
    ).distinct()
}

private fun isAbsoluteWindowsPath(path: String): Boolean =
    path.length >= 3 && path[1] == ':' && (path[2] == '\\' || path[2] == '/')

private fun normalizeRelativeRuntimePath(path: String): String {
    val trimmed = path.trim()
    if (trimmed == "." || trimmed == ".\\") return ""
    return trimmed
        .removePrefix(".\\")
        .removePrefix("./")
        .removePrefix("\\")
}

@OptIn(ExperimentalForeignApi::class)
private fun detectAppRuntimeBaseDir(): String = memScoped {
    val bufferSize = 32768
    val buffer = allocArray<UShortVar>(bufferSize)
    val length = GetModuleFileNameW(null, buffer, bufferSize.toUInt())
    if (length == 0u) return@memScoped "."
    val executablePath = buffer.toKStringFromUtf16().replace('/', '\\')
    executablePath.substringBeforeLast('\\', ".")
}

@OptIn(ExperimentalForeignApi::class)
private fun detectAppRuntimeTempDir(): String {
    val tempRoot = getenv("TEMP")?.toKString()
        ?: getenv("TMP")?.toKString()
        ?: "$appRuntimeBaseDir\\tmp"
    return tempRoot.replace('/', '\\').trimEnd('\\') + "\\WinFlasher"
}
