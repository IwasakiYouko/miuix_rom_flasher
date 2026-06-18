@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlin.random.Random
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.getenv
import platform.posix.mkdir
import platform.posix.remove
import platform.posix.rewind

private val unicodeIoTempRoot: String by lazy(::detectUnicodeIoTempRoot)

fun hasNonAsciiChars(value: String): Boolean =
    value.any { it.code > 0x7F }

fun detectExecutableDirectoryViaPowerShell(): String {
    val outputPath = createUnicodeIoTempPath("exe", "txt")
    val script = """
        ${'$'}path = [System.Diagnostics.Process]::GetCurrentProcess().MainModule.FileName
        [System.IO.File]::WriteAllText('${escapeUnicodeIoForPowerShell(outputPath)}', ${'$'}path, [System.Text.UTF8Encoding]::new(${'$'}false))
    """.trimIndent()
    if (runUnicodeIoPowerShell(script) != 0) return "."
    val path = readAsciiFileBytes(outputPath).decodeUnicodeIoUtf8().trim()
    removeAsciiFile(outputPath)
    if (path.isBlank()) return "."
    return path.replace('/', '\\').substringBeforeLast('\\', ".")
}

fun ensureDirectoryUnicodeSafe(path: String): Boolean {
    val normalized = normalizeUnicodeIoPath(path)
    val script = """
        [System.IO.Directory]::CreateDirectory('${escapeUnicodeIoForPowerShell(normalized)}') | Out-Null
    """.trimIndent()
    return runUnicodeIoPowerShell(script) == 0
}

fun fileExistsUnicodeSafe(path: String): Boolean {
    val normalized = normalizeUnicodeIoPath(path)
    if (!hasNonAsciiChars(normalized)) {
        val file = fopen(normalized, "rb") ?: return false
        fclose(file)
        return true
    }
    val script = """
        if (Test-Path -LiteralPath '${escapeUnicodeIoForPowerShell(normalized)}' -PathType Leaf) {
            exit 0
        }
        exit 1
    """.trimIndent()
    return runUnicodeIoPowerShell(script) == 0
}

fun readAllBytesUnicodeSafe(path: String): ByteArray {
    val normalized = normalizeUnicodeIoPath(path)
    if (!hasNonAsciiChars(normalized)) {
        return readAsciiFileBytes(normalized)
    }

    val tempCopyPath = createUnicodeIoTempPath("read", "bin")
    val script = """
        Copy-Item -LiteralPath '${escapeUnicodeIoForPowerShell(normalized)}' -Destination '${escapeUnicodeIoForPowerShell(tempCopyPath)}' -Force
    """.trimIndent()
    if (runUnicodeIoPowerShell(script) != 0) return ByteArray(0)
    val bytes = readAsciiFileBytes(tempCopyPath)
    removeAsciiFile(tempCopyPath)
    return bytes
}

fun writeBytesUnicodeSafe(path: String, bytes: ByteArray): Boolean {
    val normalized = normalizeUnicodeIoPath(path)
    if (!hasNonAsciiChars(normalized)) {
        return writeAsciiFileBytes(normalized, bytes)
    }

    val tempPath = createUnicodeIoTempPath("write", "bin")
    if (!writeAsciiFileBytes(tempPath, bytes)) return false
    val script = """
        ${'$'}target = '${escapeUnicodeIoForPowerShell(normalized)}'
        ${'$'}parent = Split-Path -Parent ${'$'}target
        if (-not [string]::IsNullOrWhiteSpace(${'$'}parent)) {
            [System.IO.Directory]::CreateDirectory(${'$'}parent) | Out-Null
        }
        Copy-Item -LiteralPath '${escapeUnicodeIoForPowerShell(tempPath)}' -Destination ${'$'}target -Force
    """.trimIndent()
    val success = runUnicodeIoPowerShell(script) == 0
    removeAsciiFile(tempPath)
    return success
}

fun writeUtf8BomFileUnicodeSafe(path: String, content: String): Boolean {
    val bytes = content.encodeToByteArray()
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    return writeBytesUnicodeSafe(path, bom + bytes)
}

fun readUtf8LinesUnicodeSafe(path: String): List<String> {
    val bytes = readAllBytesUnicodeSafe(path)
    if (bytes.isEmpty()) return emptyList()
    val text = bytes.decodeUnicodeIoUtf8()
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    return text.split('\n')
}

private fun detectUnicodeIoTempRoot(): String {
    val tempRoot = getenv("TEMP")?.toKString()
        ?: getenv("TMP")?.toKString()
        ?: "C:\\Windows\\Temp"
    val normalized = tempRoot.replace('/', '\\').trimEnd('\\') + "\\WinFlasher"
    mkdir(normalized)
    return normalized
}

private fun createUnicodeIoTempPath(prefix: String, extension: String): String =
    "$unicodeIoTempRoot\\__winflasher_${prefix}_${Random.nextInt(100000, 999999)}.$extension"

private fun normalizeUnicodeIoPath(path: String): String =
    path.replace('/', '\\')

private fun escapeUnicodeIoForPowerShell(value: String): String =
    value.replace("'", "''")

private fun runUnicodeIoPowerShell(script: String): Int {
    val scriptPath = createUnicodeIoTempPath("unicode_io", "ps1")
    val fullScript = """
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
        ${script.trimIndent()}
    """.trimIndent()
    if (!writeAsciiUtf8BomFile(scriptPath, fullScript)) return -1
    val result = runStreamingShellCommand(
        command = "powershell -NoProfile -ExecutionPolicy Bypass -File \"${scriptPath.replace('/', '\\')}\"",
        onLine = null,
    )
    removeAsciiFile(scriptPath)
    return result.exitCode
}

@OptIn(ExperimentalForeignApi::class)
private fun writeAsciiUtf8BomFile(path: String, content: String): Boolean {
    val bytes = content.encodeToByteArray()
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    return writeAsciiFileBytes(path, bom + bytes)
}

@OptIn(ExperimentalForeignApi::class)
private fun writeAsciiFileBytes(path: String, bytes: ByteArray): Boolean {
    val file = fopen(normalizeUnicodeIoPath(path), "wb") ?: return false
    try {
        bytes.usePinned { pinned ->
            platform.posix.fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
        }
        return true
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readAsciiFileBytes(path: String): ByteArray {
    val file = fopen(normalizeUnicodeIoPath(path), "rb") ?: return ByteArray(0)
    try {
        fseek(file, 0, SEEK_END)
        val size = ftell(file)
        if (size <= 0L) return ByteArray(0)
        rewind(file)
        val bytes = ByteArray(size.toInt())
        val readSize = bytes.usePinned { pinned ->
            fread(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file).toInt()
        }
        return if (readSize == bytes.size) bytes else bytes.copyOf(readSize)
    } finally {
        fclose(file)
    }
}

private fun ByteArray.decodeUnicodeIoUtf8(): String {
    if (isEmpty()) return ""
    return decodeToString()
}

private fun removeAsciiFile(path: String) {
    remove(normalizeUnicodeIoPath(path))
}
