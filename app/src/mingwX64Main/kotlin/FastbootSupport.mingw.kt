import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen

data class FastbootDevice(
    val serial: String,
    val state: String,
)

data class FastbootScanResult(
    val commandText: String,
    val devices: List<FastbootDevice>,
    val outputLines: List<String>,
)

@OptIn(ExperimentalForeignApi::class)
fun scanFastbootDevices(): FastbootScanResult {
    val localBinary = resolveRuntimePath(".\\META-INF\\fastboot.exe")
    val runtimeDir = resolveRuntimePath(".")
    val hasLocalBinary = pathExists(localBinary)
    val commandText = if (hasLocalBinary) ".\\META-INF\\fastboot.exe devices" else "fastboot devices"
    val outputLines = runStreamingShellCommand(
        command = commandText,
        workingDirectory = runtimeDir,
    ).outputLines
    return FastbootScanResult(
        commandText = commandText,
        devices = outputLines.mapNotNull(::parseFastbootDevice),
        outputLines = outputLines,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun pathExists(path: String): Boolean {
    return fileExistsUnicodeSafe(path)
}

@OptIn(ExperimentalForeignApi::class)
private fun readLines(path: String): List<String> =
    readUtf8LinesUnicodeSafe(path).map { it.trimEnd('\r', '\n') }

private fun parseFastbootDevice(line: String): FastbootDevice? {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return null
    val parts = trimmed.split(Regex("\\s+"), limit = 2)
    if (parts.size < 2) return null
    val serial = parts[0]
    if (serial.contains("\\") || serial.contains("/") || serial.contains(":") || serial.startsWith("'")) return null
    return FastbootDevice(serial = serial, state = parts[1])
}
