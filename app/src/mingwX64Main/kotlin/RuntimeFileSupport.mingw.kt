import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlin.random.Random
import platform.posix.fflush
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.mkdir

private const val MaxRetainedLogBytes = 30 * 1024 * 1024

fun trimLogLinesToRecent(logLines: List<String>, maxBytes: Int = MaxRetainedLogBytes): List<String> {
    if (logLines.isEmpty()) return emptyList()
    var totalBytes = 0
    val retained = ArrayDeque<String>()
    for (line in logLines.asReversed()) {
        val lineBytes = line.encodeToByteArray().size + 2
        if (retained.isNotEmpty() && totalBytes + lineBytes > maxBytes) break
        retained.addFirst(line)
        totalBytes += lineBytes
    }
    return retained.toList()
}

@OptIn(ExperimentalForeignApi::class)
fun exportLogLines(logLines: List<String>): String? {
    val targetDir = resolveRuntimeBinPath("")
    ensureDirectoryUnicodeSafe(targetDir)

    val targetPath = resolveRuntimeBinPath("winflasher_log_${Random.nextInt(100000, 999999)}.txt")
    return if (writeLogLinesToPath(targetPath, logLines)) targetPath else null
}

@OptIn(ExperimentalForeignApi::class)
fun writeAutoLogLines(logLines: List<String>): String? {
    val targetDir = resolveRuntimeBinPath("")
    ensureDirectoryUnicodeSafe(targetDir)

    val targetPath = resolveRuntimeBinPath("winflasher_auto_log.txt")
    return if (writeLogLinesToPath(targetPath, logLines)) targetPath else null
}

@OptIn(ExperimentalForeignApi::class)
private fun writeLogLinesToPath(targetPath: String, logLines: List<String>): Boolean {
    val trimmedLines = trimLogLinesToRecent(logLines)
    val content = trimmedLines.joinToString(separator = "\r\n")
    val bytes = content.encodeToByteArray()
    val bom = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    return writeBytesUnicodeSafe(targetPath, bom + bytes)
}
