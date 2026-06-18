@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlin.native.setUnhandledExceptionHook
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.time

private const val ERROR_LOG_FILE_NAME = "error-log.txt"

// Candidate locations, tried in order. The executable directory is the most discoverable
// for the user; it may be read-only (e.g. installed under Program Files), so fall back to
// the per-user temp directory.
private val errorLogPaths: List<String> by lazy {
    listOf(
        resolveRuntimePath(".\\$ERROR_LOG_FILE_NAME"),
        resolveTempPath(".\\$ERROR_LOG_FILE_NAME"),
    ).distinct()
}

/**
 * Installs a process-wide handler that records any unhandled Kotlin exception to the error
 * log before the runtime terminates. On Windows an unhandled exception triggers a fail-fast
 * that surfaces as "缓冲区溢出" (STATUS_STACK_BUFFER_OVERRUN) on Win10 or a window that never
 * appears on Win11; capturing it here leaves a diagnosable trail instead of a silent crash.
 */
fun installGlobalErrorReporting() {
    runCatching {
        setUnhandledExceptionHook { throwable ->
            // The hook itself must never throw, or it would mask the original failure.
            runCatching { recordError("FATAL", "未捕获的异常导致程序终止", throwable) }
        }
    }
}

/** Appends a timestamped, optionally exception-annotated entry to the error log. Never throws. */
fun recordError(tag: String, message: String, throwable: Throwable? = null) {
    runCatching {
        val entry = buildString {
            append('[').append(currentTimestamp()).append("] ")
            append('[').append(tag).append("] ")
            append(message)
            if (throwable != null) {
                append('\n')
                append(throwable.stackTraceToString())
            }
            append('\n')
        }
        appendToErrorLog(entry)
    }
}

private fun appendToErrorLog(entry: String) {
    val bytes = entry.encodeToByteArray()
    for (path in errorLogPaths) {
        if (runCatching { appendBytesToFile(path, bytes) }.getOrDefault(false)) return
    }
}

// POSIX append, mirroring the proven file-write paths elsewhere in this project. It spawns no
// subprocess, so it stays safe to call from the crash hook while the runtime is terminating.
private fun appendBytesToFile(path: String, bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return true
    val file = fopen(path.replace('/', '\\'), "ab") ?: return false
    return try {
        bytes.usePinned { pinned ->
            fwrite(pinned.addressOf(0), 1.convert(), bytes.size.convert(), file)
        }
        true
    } finally {
        fclose(file)
    }
}

// Pure-Kotlin epoch -> readable UTC formatting so the timestamp depends only on the bedrock
// POSIX time() symbol (no localtime/tm bindings). Algorithm: civil date from days since epoch.
private fun currentTimestamp(): String = formatUtcTimestamp(time(null).toLong())

private fun formatUtcTimestamp(epochSeconds: Long): String {
    var dayCount = epochSeconds / 86400
    var secondOfDay = epochSeconds % 86400
    if (secondOfDay < 0) {
        secondOfDay += 86400
        dayCount -= 1
    }
    val hour = (secondOfDay / 3600).toInt()
    val minute = ((secondOfDay % 3600) / 60).toInt()
    val second = (secondOfDay % 60).toInt()

    var z = dayCount + 719468
    val era = (if (z >= 0) z else z - 146096) / 146097
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val year0 = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (year0 + if (month <= 2) 1 else 0).toInt()

    fun p2(value: Int) = value.toString().padStart(2, '0')
    return "$year-${p2(month)}-${p2(day)} ${p2(hour)}:${p2(minute)}:${p2(second)} UTC"
}
