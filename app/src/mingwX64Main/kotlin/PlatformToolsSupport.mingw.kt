import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlin.random.Random

data class PlatformToolsState(
    val installed: Boolean,
    val statusText: String,
)

fun readPlatformToolsState(): PlatformToolsState {
    val metaInfPath = resolveRuntimePath(".\\META-INF")
    val hasFastboot = fileExistsForPlatformTools("$metaInfPath\\fastboot.exe")
    val hasAdb = fileExistsForPlatformTools("$metaInfPath\\adb.exe")
    val installed = hasFastboot && hasAdb
    val statusText = if (installed) "已加载到 META-INF" else "未安装"
    return PlatformToolsState(installed = installed, statusText = statusText)
}

fun installOrUpdatePlatformTools(
    onProgress: (Float, String) -> Unit,
    appendLog: (String) -> Unit,
): Boolean {
    val tempZip = resolveTempPath(".\\platform-tools-${Random.nextInt(100000, 999999)}.zip")
    val tempExtractDir = resolveTempPath(".\\platform-tools-${Random.nextInt(100000, 999999)}")
    val metaInfPath = resolveRuntimePath(".\\META-INF")
    val scriptPath = resolveTempPath(".\\__winflasher_platform_tools_${Random.nextInt(100000, 999999)}.ps1")
    val script = """
        ${'$'}ErrorActionPreference = 'Stop'
        ${'$'}ProgressPreference = 'SilentlyContinue'
        ${'$'}url = 'https://dl.google.com/android/repository/platform-tools-latest-windows.zip'
        ${'$'}zip = '${escapePlatformToolsForPowerShell(tempZip)}'
        ${'$'}extract = '${escapePlatformToolsForPowerShell(tempExtractDir)}'
        ${'$'}target = '${escapePlatformToolsForPowerShell(metaInfPath)}'
        ${'$'}platformToolsDir = Join-Path ${'$'}extract 'platform-tools'
        ${'$'}bufferSize = 262144
        if (Test-Path ${'$'}zip) { Remove-Item -LiteralPath ${'$'}zip -Force -ErrorAction SilentlyContinue }
        if (Test-Path ${'$'}extract) { Remove-Item -LiteralPath ${'$'}extract -Recurse -Force -ErrorAction SilentlyContinue }
        New-Item -ItemType Directory -Force -Path ${'$'}target | Out-Null
        Write-Output '__PT_PROGRESS__:0.02|准备下载 platform-tools'
        ${'$'}request = [System.Net.HttpWebRequest]::Create(${ '$' }url)
        ${'$'}response = ${'$'}request.GetResponse()
        ${'$'}responseStream = ${'$'}response.GetResponseStream()
        ${'$'}fileStream = [System.IO.File]::Open(${ '$' }zip, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
        try {
            ${'$'}totalLength = ${'$'}response.ContentLength
            ${'$'}buffer = New-Object byte[] ${'$'}bufferSize
            ${'$'}downloaded = 0L
            while ((${'$'}read = ${'$'}responseStream.Read(${'$'}buffer, 0, ${'$'}buffer.Length)) -gt 0) {
                ${'$'}fileStream.Write(${'$'}buffer, 0, ${'$'}read)
                ${'$'}downloaded += ${'$'}read
                if (${ '$' }totalLength -gt 0) {
                    ${'$'}ratio = [Math]::Min([Math]::Max(${'$'}downloaded / ${'$'}totalLength, 0.0), 1.0)
                    ${'$'}mapped = 0.05 + ${'$'}ratio * 0.80
                    Write-Output ('__PT_PROGRESS__:{0}|正在下载 platform-tools {1:P0}' -f ${'$'}mapped, ${'$'}ratio)
                }
            }
        } finally {
            if (${ '$' }fileStream) { ${'$'}fileStream.Dispose() }
            if (${ '$' }responseStream) { ${'$'}responseStream.Dispose() }
            if (${ '$' }response) { ${'$'}response.Dispose() }
        }
        Write-Output '__PT_PROGRESS__:0.90|正在解压 platform-tools'
        Expand-Archive -LiteralPath ${'$'}zip -DestinationPath ${'$'}extract -Force
        Write-Output '__PT_PROGRESS__:0.96|正在写入 META-INF'
        Copy-Item -LiteralPath (Join-Path ${'$'}platformToolsDir '*') -Destination ${'$'}target -Recurse -Force
        Remove-Item -LiteralPath ${'$'}zip -Force -ErrorAction SilentlyContinue
        Remove-Item -LiteralPath ${'$'}extract -Recurse -Force -ErrorAction SilentlyContinue
        Write-Output '__PT_PROGRESS__:1.00|platform-tools 已更新'
    """.trimIndent()

    if (!writePlatformToolsScript(scriptPath, script)) return false
    return try {
        onProgress(0f, "准备更新")
        val result = runStreamingShellCommand(
            command = "powershell -NoProfile -ExecutionPolicy Bypass -File \"${scriptPath.replace('/', '\\')}\"",
            onLine = { line ->
                if (line.isBlank()) return@runStreamingShellCommand
                val progress = parsePlatformToolsProgress(line)
                if (progress != null) {
                    onProgress(progress.first, progress.second)
                } else {
                    appendLog(line)
                }
            },
        )
        if (result.exitCode == 0) {
            onProgress(1f, "更新完成")
        }
        result.exitCode == 0
    } finally {
        platform.posix.remove(scriptPath)
    }
}

private fun escapePlatformToolsForPowerShell(value: String): String =
    value.replace("'", "''")

private fun parsePlatformToolsProgress(line: String): Pair<Float, String>? {
    if (!line.startsWith("__PT_PROGRESS__:")) return null
    val payload = line.removePrefix("__PT_PROGRESS__:")
    val separatorIndex = payload.indexOf('|')
    if (separatorIndex <= 0) return null
    val progress = payload.substring(0, separatorIndex).toFloatOrNull() ?: return null
    val status = payload.substring(separatorIndex + 1).ifBlank { "更新中" }
    return progress.coerceIn(0f, 1f) to status
}

@OptIn(ExperimentalForeignApi::class)
private fun writePlatformToolsScript(path: String, content: String): Boolean {
    return writeUtf8BomFileUnicodeSafe(path, content)
}

@OptIn(ExperimentalForeignApi::class)
private fun fileExistsForPlatformTools(path: String): Boolean {
    return fileExistsUnicodeSafe(path)
}
