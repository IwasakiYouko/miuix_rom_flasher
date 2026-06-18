import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned

data class RootManagerOption(
    val id: String,
    val title: String,
)

data class KernelSuLkmSpec(
    val id: String,
    val title: String,
    val localPatterns: List<String>,
    val cloudPaths: List<String>,
)

private const val OpenListBaseUrl = "http://ol.hanfy.asia"
private const val OpenListUsername = "roottool"
private const val OpenListPassword = "roottool"
private const val ManagerProgressPrefix = "__WINFLASHER_MANAGER_PROGRESS__|"
private const val DefaultKernelSuLkmManagerId = "kernelsu-official"

fun rootManagerOptionsFor(rootMode: RootMode): List<RootManagerOption> =
    // Scanning touches the filesystem; never let a failure here crash startup composition.
    runCatching {
        when (rootMode) {
            RootMode.MagiskPatch -> scanMagiskManagerOptions().ifEmpty {
                listOf(RootManagerOption("auto", "自动检测"))
            }
            RootMode.KernelSuLkm -> kernelSuLkmSpecs().map { RootManagerOption(it.id, it.title) }
            RootMode.FolkPatch -> scanFolkPatchManagerOptions().ifEmpty {
                listOf(RootManagerOption("0.13.0", "0.13.0"))
            }
            RootMode.HfTeamGki -> listOf(RootManagerOption("default", "默认版本"))
            RootMode.KeepOriginal -> emptyList()
        }
    }.getOrElse {
        recordError("startup", "扫描 ROOT 管理器选项失败（$rootMode），使用默认项", it)
        when (rootMode) {
            RootMode.MagiskPatch -> listOf(RootManagerOption("auto", "自动检测"))
            RootMode.FolkPatch -> listOf(RootManagerOption("0.13.0", "0.13.0"))
            RootMode.HfTeamGki -> listOf(RootManagerOption("default", "默认版本"))
            else -> emptyList()
        }
    }

fun locateKernelSuLkmManagerApk(
    managerId: String?,
    appendLog: (String) -> Unit,
    onDownloadProgressUpdate: (ManagerDownloadProgress) -> Unit = {},
): String? {
    val spec = kernelSuLkmSpec(managerId) ?: defaultKernelSuLkmSpec()
    if (spec == null) {
        appendLog("未找到可用的 KernelSU-LKM 管理器配置")
        return null
    }
    val selectedManagerId = managerId?.trim().orEmpty()
    if (selectedManagerId.isNotBlank() && !selectedManagerId.equals(spec.id, ignoreCase = true)) {
        appendLog("未识别的 KernelSU-LKM 管理器 $selectedManagerId，已回退到 ${spec.title}")
    }
    val localMatch = findFilesByPatterns(
        patterns = spec.localPatterns,
        roots = listOf(
            resolveRuntimePath("."),
            resolveRuntimePath(".\\META-INF"),
            kernelSuLkmManagerCacheDir(spec.id),
        ),
        recursive = false,
    ).map(::normalizeRootSupportPath)
        .firstOrNull()
    if (localMatch != null) {
        onDownloadProgressUpdate(ManagerDownloadProgress())
        appendLog("已使用本地 ${spec.title} 管理器 ${fileNameOfRootSupport(localMatch)}")
        return localMatch
    }

    appendLog("本地未找到 ${spec.title} 管理器，开始从悍匪云盘获取")
    return downloadKernelSuManagerFromCloud(
        spec = spec,
        appendLog = appendLog,
        onDownloadProgressUpdate = onDownloadProgressUpdate,
    )
}

private fun scanMagiskManagerOptions(): List<RootManagerOption> {
    val apks = findFilesByPatterns(
        listOf(
            "Magisk*.apk",
            "Kitsune*.apk",
            "*magisk*.apk",
            "*kitsune*.apk",
        ),
    )
    val options = apks.map { path ->
        RootManagerOption(
            id = normalizeRootSupportPath(path),
            title = fileNameOfRootSupport(path),
        )
    }
    return listOf(RootManagerOption("auto", "自动检测")) + options.distinctBy { it.id }
}

private fun scanFolkPatchManagerOptions(): List<RootManagerOption> {
    val versionRoot = resolveRuntimePath(".\\.codex-cache\\FolkTool\\kp_versions")
    val versions = listDirectoryNames(versionRoot)
        .filter { it.count { ch -> ch == '.' } >= 1 }
        .sortedWith(::compareVersionTextDescending)
    return versions.map { version ->
        RootManagerOption(id = version, title = version)
    }
}

private fun kernelSuLkmSpecs(): List<KernelSuLkmSpec> = listOf(
    KernelSuLkmSpec(
        id = "sukisu-ultra",
        title = "SukiSU-Ultra",
        localPatterns = listOf("*SukiSU*Ultra*.apk", "*sukisu*ultra*.apk"),
        cloudPaths = listOf(
            "/[天翼云盘]小米移植包/RootTools/RootManager/KernelSU/SukiSU-Ultra",
        ),
    ),
    KernelSuLkmSpec(
        id = "resukisu",
        title = "ResukiSU",
        localPatterns = listOf("*ResukiSU*.apk", "*ReSukiSU*.apk", "*rezukisu*.apk"),
        cloudPaths = listOf(
            "/[天翼云盘]小米移植包/RootTools/RootManager/KernelSU/ResukiSU",
            "/[天翼云盘]小米移植包/RootTools/RootManager/KernelSU/ReSukiSU",
        ),
    ),
    KernelSuLkmSpec(
        id = "kernelsu-official",
        title = "KernelSU-Official",
        localPatterns = listOf("*KernelSU*Official*.apk", "*KernelSU*Offical*.apk", "*kernelsu*official*.apk"),
        cloudPaths = listOf(
            "/[天翼云盘]小米移植包/RootTools/RootManager/KernelSU/KernelSU-Offical",
            "/[天翼云盘]小米移植包/RootTools/RootManager/KernelSU/KernelSU-Official",
        ),
    ),
    KernelSuLkmSpec(
        id = "kernelsu-next",
        title = "KernelSU-Next",
        localPatterns = listOf("*KernelSU*Next*.apk", "*ksunext*.apk", "*KernelSU-Next*.apk"),
        cloudPaths = listOf(
            "/[天翼云盘]小米移植包/RootTools/RootManager/KernelSU/KernelSU-Next",
        ),
    ),
)

private fun kernelSuLkmSpec(id: String?): KernelSuLkmSpec? {
    val selected = id?.trim().orEmpty()
    if (selected.isBlank()) return defaultKernelSuLkmSpec()
    return kernelSuLkmSpecs().firstOrNull { spec ->
        spec.id.equals(selected, ignoreCase = true) ||
            spec.title.equals(selected, ignoreCase = true) ||
            selected.contains(spec.id, ignoreCase = true) ||
            selected.contains(spec.title, ignoreCase = true)
    }
}

private fun defaultKernelSuLkmSpec(): KernelSuLkmSpec? =
    kernelSuLkmSpecs().firstOrNull { it.id == DefaultKernelSuLkmManagerId } ?: kernelSuLkmSpecs().firstOrNull()

private fun downloadKernelSuManagerFromCloud(
    spec: KernelSuLkmSpec,
    appendLog: (String) -> Unit,
    onDownloadProgressUpdate: (ManagerDownloadProgress) -> Unit,
): String? {
    val cacheDir = kernelSuLkmManagerCacheDir(spec.id)
    ensureDirectoryRootSupport(cacheDir)
    val outputPath = resolveTempPath(".\\__winflasher_root_manager_${spec.id}.txt")
    val scriptPath = resolveTempPath(".\\__winflasher_root_manager_${spec.id}.ps1")
    val cloudPathsLiteral = spec.cloudPaths.joinToString(", ") { "'${it.replace("'", "''")}'" }
    val script = """
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
        ${'$'}baseUrl = '$OpenListBaseUrl'
        ${'$'}cacheDir = '${cacheDir.replace("'", "''")}'
        ${'$'}cloudPaths = @($cloudPathsLiteral)
        ${'$'}loginBody = @{
            username = '$OpenListUsername'
            password = '$OpenListPassword'
        } | ConvertTo-Json
        ${'$'}loginBytes = [System.Text.Encoding]::UTF8.GetBytes(${'$'}loginBody)
        ${'$'}loginResp = Invoke-RestMethod -Method Post -Uri (${'$'}baseUrl + '/api/auth/login') -ContentType 'application/json; charset=utf-8' -Body ${'$'}loginBytes
        ${'$'}token = ${'$'}loginResp.data.token
        if (-not ${'$'}token) { throw '登录 OpenList 失败' }
        ${'$'}headers = @{ Authorization = ${'$'}token }
        New-Item -ItemType Directory -Force -Path ${'$'}cacheDir | Out-Null

        foreach (${'$'}cloudPath in ${'$'}cloudPaths) {
            ${'$'}listBody = @{
                path = ${'$'}cloudPath
                password = ''
                refresh = ${'$'}false
                page = 1
                per_page = 100
            } | ConvertTo-Json
            ${'$'}listBytes = [System.Text.Encoding]::UTF8.GetBytes(${'$'}listBody)

            try {
                [Console]::Out.WriteLine("检查云盘版本")
                ${'$'}listResp = Invoke-RestMethod -Method Post -Uri (${'$'}baseUrl + '/api/fs/list') -Headers ${'$'}headers -ContentType 'application/json; charset=utf-8' -Body ${'$'}listBytes
                ${'$'}files = @(${ '$' }listResp.data.content | Where-Object { -not ${'$'}_.is_dir -and ${'$'}_.name -like '*.apk' } | Sort-Object name -Descending)
                if (${ '$' }files.Count -eq 0) {
                    [Console]::Out.WriteLine("云盘目录暂无可用 APK")
                    continue
                }

                ${'$'}file = ${'$'}files[0]
                ${'$'}target = Join-Path ${'$'}cacheDir ${'$'}file.name
                if (-not (Test-Path ${'$'}target)) {
                    ${'$'}encodedName = [System.Uri]::EscapeDataString(${ '$' }file.name)
                    ${'$'}downloadUrl = if (${'$'}file.sign) {
                        ${'$'}baseUrl + '/d' + ${'$'}cloudPath + '/' + ${'$'}encodedName + '?sign=' + ${'$'}file.sign
                    } else {
                        ${'$'}baseUrl + '/d' + ${'$'}cloudPath + '/' + ${'$'}encodedName
                    }
                    [Console]::Out.WriteLine("开始下载 $(${'$'}file.name)")
                    ${'$'}request = [System.Net.HttpWebRequest]::Create(${'$'}downloadUrl)
                    ${'$'}request.AllowAutoRedirect = ${'$'}true
                    ${'$'}request.AutomaticDecompression = [System.Net.DecompressionMethods]::GZip -bor [System.Net.DecompressionMethods]::Deflate
                    ${'$'}response = ${'$'}request.GetResponse()
                    try {
                        ${'$'}total = [int64]${'$'}response.ContentLength
                        ${'$'}stream = ${'$'}response.GetResponseStream()
                        ${'$'}output = [System.IO.File]::Open(${'$'}target, [System.IO.FileMode]::Create, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
                        try {
                            ${'$'}buffer = New-Object byte[] 131072
                            ${'$'}downloaded = [int64]0
                            [Console]::Out.WriteLine("$ManagerProgressPrefix" + ${'$'}file.name + "|0|" + ${'$'}total)
                            while ((${'$'}read = ${'$'}stream.Read(${'$'}buffer, 0, ${'$'}buffer.Length)) -gt 0) {
                                ${'$'}output.Write(${'$'}buffer, 0, ${'$'}read)
                                ${'$'}downloaded += ${'$'}read
                                [Console]::Out.WriteLine("$ManagerProgressPrefix" + ${'$'}file.name + "|" + ${'$'}downloaded + "|" + ${'$'}total)
                            }
                        } finally {
                            ${'$'}output.Dispose()
                            ${'$'}stream.Dispose()
                        }
                    } finally {
                        ${'$'}response.Close()
                    }
                } else {
                    [Console]::Out.WriteLine("使用缓存 $(${'$'}file.name)")
                }
                [System.IO.File]::WriteAllText('${outputPath.replace("'", "''")}', ${'$'}target, [System.Text.UTF8Encoding]::new(${'$'}false))
                break
            } catch {
                [Console]::Out.WriteLine("云盘目录检查失败")
            }
        }

        if (-not (Test-Path '${outputPath.replace("'", "''")}')) {
            throw '云盘未找到可用的 KernelSU-LKM 管理器 APK'
        }
    """.trimIndent()

    if (!writeUtf8BomFileRootSupport(scriptPath, script)) return null
    val result = runStreamingShellCommand(
        command = "powershell -NoProfile -ExecutionPolicy Bypass -File \"${normalizeRootSupportPath(scriptPath)}\"",
        onLine = { line ->
            parseManagerDownloadProgress(line)?.let(onDownloadProgressUpdate) ?: run {
                if (line.isNotBlank()) appendLog(line)
            }
        },
    )
    platform.posix.remove(scriptPath)
    onDownloadProgressUpdate(ManagerDownloadProgress())
    if (result.exitCode != 0) {
        appendLog("在线获取 ${spec.title} 失败")
        platform.posix.remove(outputPath)
        return null
    }
    val downloaded = readRootSupportLines(outputPath).firstOrNull()?.takeIf(::fileExistsRootSupport)
    return downloaded?.also {
        appendLog("已下载 ${spec.title} ${fileNameOfRootSupport(it)}")
    }
}

private fun parseManagerDownloadProgress(line: String): ManagerDownloadProgress? {
    if (!line.startsWith(ManagerProgressPrefix)) return null
    val payload = line.removePrefix(ManagerProgressPrefix)
    val parts = payload.split('|')
    if (parts.size < 3) return null
    val fileName = parts[0]
    val downloadedBytes = parts.getOrNull(1)?.toLongOrNull() ?: 0L
    val totalBytes = parts.getOrNull(2)?.toLongOrNull() ?: 0L
    return ManagerDownloadProgress(
        isActive = true,
        title = "下载 ROOT 管理器",
        fileName = fileName,
        downloadedBytes = downloadedBytes,
        totalBytes = totalBytes,
    )
}

private fun ensureDirectoryRootSupport(path: String) {
    val script = """
        New-Item -ItemType Directory -Force -Path '${path.replace("'", "''")}' | Out-Null
    """.trimIndent()
    runRootSupportScript(script)
}

private fun findFilesByPatterns(
    patterns: List<String>,
    roots: List<String> = listOf(
        resolveRuntimePath("."),
        resolveRuntimePath(".\\META-INF"),
        resolveRuntimePath(".\\.codex-cache"),
    ),
    recursive: Boolean = true,
): List<String> {
    val outputPath = resolveTempPath(".\\__winflasher_root_files.txt")
    val effectiveRoots = roots.map(::normalizeRootSupportPath).distinct()
    val patternsLiteral = patterns.joinToString(", ") { "'${it.replace("'", "''")}'" }
    val rootsLiteral = effectiveRoots.joinToString(", ") { "'${it.replace("'", "''")}'" }
    val recurseFlag = if (recursive) " -Recurse" else ""
    val script = """
        ${'$'}roots = @($rootsLiteral)
        ${'$'}patterns = @($patternsLiteral)
        ${'$'}result = New-Object System.Collections.Generic.List[string]
        foreach (${'$'}root in ${'$'}roots) {
            if (-not [System.IO.Directory]::Exists(${'$'}root)) { continue }
            foreach (${'$'}pattern in ${'$'}patterns) {
                Get-ChildItem -LiteralPath ${'$'}root$recurseFlag -File -Filter ${'$'}pattern -ErrorAction SilentlyContinue |
                    ForEach-Object { ${'$'}result.Add(${'$'}_.FullName) }
            }
        }
        ${'$'}result | Select-Object -Unique | Set-Content -LiteralPath '${outputPath.replace("'", "''")}' -Encoding utf8
    """.trimIndent()
    runRootSupportScript(script)
    return readRootSupportLines(outputPath)
}

private fun kernelSuLkmManagerCacheDir(id: String): String =
    resolveRuntimePath(".\\.codex-cache\\RootManagers\\KernelSU-LKM\\$id")

private fun quoteForPowerShellLiteral(value: String): String =
    "'${value.replace("'", "''")}'"

private fun listDirectoryNames(path: String): List<String> {
    val outputPath = resolveTempPath(".\\__winflasher_root_dirs.txt")
    val script = """
        ${'$'}path = '${path.replace("'", "''")}'
        if ([System.IO.Directory]::Exists(${'$'}path)) {
            Get-ChildItem -LiteralPath ${'$'}path -Directory -ErrorAction SilentlyContinue |
                ForEach-Object Name |
                Set-Content -LiteralPath '${outputPath.replace("'", "''")}' -Encoding utf8
        }
    """.trimIndent()
    runRootSupportScript(script)
    return readRootSupportLines(outputPath)
}

private fun fileNameOfRootSupport(path: String): String =
    path.replace('/', '\\').substringAfterLast('\\')

private fun normalizeRootSupportPath(path: String): String =
    path.replace('/', '\\')

private fun runRootSupportScript(script: String): Int {
    val scriptPath = resolveTempPath(".\\__winflasher_root_support.ps1")
    val fullScript = """
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
        ${script.trimIndent()}
    """.trimIndent()
    if (!writeUtf8BomFileRootSupport(scriptPath, fullScript)) return -1
    val result = runStreamingShellCommand(
        command = "powershell -NoProfile -ExecutionPolicy Bypass -File \"${normalizeRootSupportPath(scriptPath)}\"",
        onLine = null,
    )
    platform.posix.remove(scriptPath)
    return result.exitCode
}

@OptIn(ExperimentalForeignApi::class)
private fun readRootSupportLines(path: String): List<String> {
    return try {
        readUtf8LinesUnicodeSafe(path.replace('/', '\\'))
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    } finally {
        platform.posix.remove(path)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun writeUtf8BomFileRootSupport(path: String, content: String): Boolean {
    return writeUtf8BomFileUnicodeSafe(path.replace('/', '\\'), content)
}

@OptIn(ExperimentalForeignApi::class)
private fun fileExistsRootSupport(path: String): Boolean {
    return fileExistsUnicodeSafe(path.replace('/', '\\'))
}

private fun compareVersionTextDescending(left: String, right: String): Int {
    val leftParts = left.split('.').mapNotNull { it.toIntOrNull() }
    val rightParts = right.split('.').mapNotNull { it.toIntOrNull() }
    val maxSize = maxOf(leftParts.size, rightParts.size)
    for (index in 0 until maxSize) {
        val leftPart = leftParts.getOrElse(index) { 0 }
        val rightPart = rightParts.getOrElse(index) { 0 }
        if (leftPart != rightPart) return rightPart.compareTo(leftPart)
    }
    return 0
}
