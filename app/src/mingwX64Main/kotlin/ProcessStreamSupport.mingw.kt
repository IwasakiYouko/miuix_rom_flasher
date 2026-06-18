import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.wcstr
import kotlin.random.Random
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.remove
import platform.posix.rewind
import platform.windows.CREATE_NO_WINDOW
import platform.windows.CloseHandle
import platform.windows.CreateDirectoryW
import platform.windows.CreateProcessW
import platform.windows.FALSE
import platform.windows.STARTF_USESHOWWINDOW
import platform.windows.STARTUPINFOW
import platform.windows.SW_HIDE
import platform.windows.Sleep
import platform.windows.WAIT_TIMEOUT
import platform.windows.WaitForSingleObject

data class StreamCommandResult(
    val exitCode: Int,
    val outputLines: List<String>,
)

private const val EXIT_MARKER_PREFIX = "__WINFLASHER_EXIT__:"

@OptIn(ExperimentalForeignApi::class)
fun runStreamingShellCommand(
    command: String,
    workingDirectory: String? = null,
    onLine: ((String) -> Unit)? = null,
): StreamCommandResult {
    val outputPath = resolveTempPath(".\\__winflasher_stream_${Random.nextInt(100000, 999999)}.log")
    ensureOutputDirectory(outputPath)
    val started = startHiddenCommand(
        command = command,
        outputPath = outputPath,
        workingDirectory = workingDirectory,
    ) ?: return StreamCommandResult(
        exitCode = -1,
        outputLines = listOf("无法启动命令: $command"),
    )

    val emittedLines = mutableListOf<String>()
    var lastVisibleSnapshot = emptyList<String>()
    try {
        while (true) {
            val (_, visibleSnapshot) = splitExitCode(readUtf8Lines(outputPath))
            emitSnapshotDiff(lastVisibleSnapshot, visibleSnapshot, emittedLines, onLine)
            lastVisibleSnapshot = visibleSnapshot

            if (WaitForSingleObject(started.processHandle, 0u) != WAIT_TIMEOUT.toUInt()) {
                val finalSnapshot = readUtf8Lines(outputPath)
                val (exitCode, visibleLines) = splitExitCode(finalSnapshot)
                emitSnapshotDiff(lastVisibleSnapshot, visibleLines, emittedLines, onLine)
                return StreamCommandResult(exitCode = exitCode, outputLines = emittedLines)
            }

            Sleep(80u)
        }
    } finally {
        CloseHandle(started.threadHandle)
        CloseHandle(started.processHandle)
        remove(outputPath)
    }
}

private fun emitSnapshotDiff(
    previous: List<String>,
    current: List<String>,
    emittedLines: MutableList<String>,
    onLine: ((String) -> Unit)?,
) {
    val sharedPrefixLength = previous.zip(current).takeWhile { (old, new) -> old == new }.count()
    current.drop(sharedPrefixLength).forEach { line ->
        if (line.isBlank()) return@forEach
        emittedLines += line
        onLine?.invoke(line)
    }
}

@OptIn(ExperimentalForeignApi::class)
private data class StartedProcess(
    val processHandle: COpaquePointer?,
    val threadHandle: COpaquePointer?,
)

@OptIn(ExperimentalForeignApi::class)
private fun startHiddenCommand(
    command: String,
    outputPath: String,
    workingDirectory: String?,
): StartedProcess? = memScoped {
    val startupInfo = alloc<STARTUPINFOW>().apply {
        cb = kotlinx.cinterop.sizeOf<STARTUPINFOW>().convert()
        dwFlags = STARTF_USESHOWWINDOW.convert()
        wShowWindow = SW_HIDE.toUShort()
    }
    val processInfo = alloc<platform.windows.PROCESS_INFORMATION>()

    val fullCommand = buildHiddenCommandLine(command, outputPath)
    val created = CreateProcessW(
        null,
        fullCommand.wcstr.ptr,
        null,
        null,
        FALSE,
        CREATE_NO_WINDOW.toUInt(),
        null,
        workingDirectory?.replace('/', '\\'),
        startupInfo.ptr,
        processInfo.ptr,
    )
    if (created == 0) return@memScoped null

    StartedProcess(
        processHandle = processInfo.hProcess,
        threadHandle = processInfo.hThread,
    )
}

private fun buildHiddenCommandLine(command: String, outputPath: String): String {
    val normalizedOutputPath = outputPath.replace('/', '\\')
    val sanitizedCommand = command
        .trim()
        .removeSuffix("2>&1")
        .trim()
    return "cmd.exe /d /c chcp 65001>nul & $sanitizedCommand > \"${normalizedOutputPath}\" 2>&1 & echo $EXIT_MARKER_PREFIX%ERRORLEVEL%>>\"${normalizedOutputPath}\""
}

@OptIn(ExperimentalForeignApi::class)
private fun ensureOutputDirectory(outputPath: String) {
    val directory = outputPath
        .replace('/', '\\')
        .substringBeforeLast('\\', missingDelimiterValue = "")
    if (directory.isNotBlank()) {
        CreateDirectoryW(directory, null)
    }
}

private fun splitExitCode(lines: List<String>): Pair<Int, List<String>> {
    val lastIndex = lines.indexOfLast { it.startsWith(EXIT_MARKER_PREFIX) }
    if (lastIndex < 0) return -1 to lines

    val exitCode = lines[lastIndex]
        .removePrefix(EXIT_MARKER_PREFIX)
        .trim()
        .toIntOrNull()
        ?: -1
    return exitCode to lines.filterIndexed { index, _ -> index != lastIndex }
}

@OptIn(ExperimentalForeignApi::class)
private fun readUtf8Lines(path: String): List<String> {
    val bytes = readAllBytes(path)
    if (bytes.isEmpty()) return emptyList()
    return bytes.decodeCommandText()
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
        .dropLastWhile { it.isEmpty() }
}

@OptIn(ExperimentalForeignApi::class)
private fun readAllBytes(path: String): ByteArray {
    val file = fopen(path.replace('/', '\\'), "rb") ?: return ByteArray(0)
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

private fun ByteArray.decodeAsUtf8(): String {
    if (isEmpty()) return ""
    // decodeToString() handles malformed UTF-8 with the replacement char instead of
    // crashing, and avoids pinning a native buffer / relying on a NUL terminator.
    return decodeToString()
}

private fun ByteArray.decodeCommandText(): String {
    if (size >= 3 &&
        this[0] == 0xEF.toByte() &&
        this[1] == 0xBB.toByte() &&
        this[2] == 0xBF.toByte()
    ) {
        return copyOfRange(3, size).decodeAsUtf8()
    }
    return decodeAsUtf8()
}
