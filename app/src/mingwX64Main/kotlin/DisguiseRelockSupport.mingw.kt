import kotlin.random.Random

private const val DISGUISE_RELOCK_ABL_URL = "https://example.com/placeholder/abl.img"
private const val DISGUISE_RELOCK_EFISP_URL = "https://example.com/placeholder/efisp.img"

data class DisguiseRelockImages(
    val ablImagePath: String,
    val efispImagePath: String,
)

fun locateDisguiseRelockImages(
    appendLog: (String) -> Unit,
): DisguiseRelockImages? {
    val cacheDir = resolveRuntimePath(".\\.codex-cache\\DisguiseRelock")
    val ablPath = "$cacheDir\\abl.img"
    val efispPath = "$cacheDir\\efisp.img"

    if (!fileExistsUnicodeSafe(ablPath)) {
        appendLog("本地未找到伪装回锁 abl.img，开始下载")
        if (!downloadDisguiseRelockImage(DISGUISE_RELOCK_ABL_URL, ablPath, "abl.img", appendLog)) {
            appendLog("下载 abl.img 失败")
            return null
        }
    }
    if (!fileExistsUnicodeSafe(efispPath)) {
        appendLog("本地未找到伪装回锁 efisp.img，开始下载")
        if (!downloadDisguiseRelockImage(DISGUISE_RELOCK_EFISP_URL, efispPath, "efisp.img", appendLog)) {
            appendLog("下载 efisp.img 失败")
            return null
        }
    }

    appendLog("伪装回锁镜像已就绪")
    return DisguiseRelockImages(
        ablImagePath = ablPath.replace('/', '\\'),
        efispImagePath = efispPath.replace('/', '\\'),
    )
}

private fun downloadDisguiseRelockImage(
    url: String,
    outputPath: String,
    fileName: String,
    appendLog: (String) -> Unit,
): Boolean {
    ensureDirectoryUnicodeSafe(resolveRuntimePath(".\\.codex-cache\\DisguiseRelock"))
    val scriptPath = resolveTempPath(".\\__winflasher_disguise_${Random.nextInt(100000, 999999)}.ps1")
    val script = """
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        ${'$'}url = '$url'
        ${'$'}out = '${outputPath.replace("'", "''")}'
        try {
            ${'$'}request = [System.Net.HttpWebRequest]::Create(${'$'}url)
            ${'$'}response = ${'$'}request.GetResponse()
            ${'$'}stream = ${'$'}response.GetResponseStream()
            ${'$'}fileStream = [System.IO.File]::Open(${'$'}out, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
            try {
                ${'$'}buffer = New-Object byte[] 131072
                while ((${'$'}read = ${'$'}stream.Read(${'$'}buffer, 0, ${'$'}buffer.Length)) -gt 0) {
                    ${'$'}fileStream.Write(${'$'}buffer, 0, ${'$'}read)
                }
            } finally {
                ${'$'}fileStream.Dispose()
                ${'$'}stream.Dispose()
            }
            ${'$'}response.Close()
            exit 0
        } catch {
            [Console]::Out.WriteLine(${'$'}_.Exception.Message)
            exit 1
        }
    """.trimIndent()

    if (!writeUtf8BomFileUnicodeSafe(scriptPath, script)) return false
    val result = runStreamingShellCommand(
        command = "powershell -NoProfile -ExecutionPolicy Bypass -File \"${scriptPath.replace('/', '\\')}\"",
        onLine = { line -> if (line.isNotBlank()) appendLog(line) },
    )
    platform.posix.remove(scriptPath)
    if (result.exitCode != 0) return false
    return fileExistsUnicodeSafe(outputPath)
}
