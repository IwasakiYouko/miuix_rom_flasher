import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlin.math.max
import kotlin.random.Random
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.remove
import platform.posix.rewind
import platform.windows.Sleep

private data class CommandResult(
    val exitCode: Int,
    val commandText: String,
    val outputLines: List<String>,
)

private data class PreparedFlashImage(
    val imagePath: String,
    val partition: String,
    val description: String,
)

private data class FlashImageTarget(
    val partition: String,
    val imagePath: String,
    val displayName: String,
    val sourceDescription: String,
)

data class RootPreparationResult(
    val imagePath: String,
    val partition: String,
    val description: String,
)

fun executeFlashWorkflow(
    serial: String,
    flashMode: FlashMode,
    rootMode: RootMode,
    rootManagerVersion: String?,
    rootDebugMode: Boolean,
    autoReboot: Boolean,
    clearGoogleLock: Boolean,
    verifyPackage: Boolean,
    romPackageInfo: RomPackageInfo,
    disguiseRelock: Boolean,
    appendLog: (String) -> Unit,
    onProgressUpdate: (FlashPartitionProgress) -> Unit = {},
    onExtractionProgressUpdate: (ZstdExtractionProgress) -> Unit = {},
    onWaitProgressUpdate: (WaitProgress) -> Unit = {},
    onManagerDownloadProgressUpdate: (ManagerDownloadProgress) -> Unit = {},
): Boolean {
    appendLog("开始处理刷机任务")
    appendLog("目标设备 $serial")
    appendLog("刷写方式 ${flashMode.title}")
    appendLog("ROOT 方案 ${rootMode.title}")
    onProgressUpdate(FlashPartitionProgress())
    onWaitProgressUpdate(WaitProgress())
    onManagerDownloadProgressUpdate(ManagerDownloadProgress())

    if (verifyPackage && !romPackageInfo.verifiedUser) {
        appendLog("已启用包校验，但当前 ROM 包未通过验证")
        return false
    }

    cleanupLegacyTempDirectory(appendLog)
    val zstdPackages = listFilesInDirectory(resolveRuntimePath("."), "*.zstd")
    if (!extractZstdPackages(zstdPackages, appendLog, onExtractionProgressUpdate)) {
        return false
    }
    warnIfMissingLogicalPartitions(zstdPackages.isNotEmpty(), appendLog)
    onExtractionProgressUpdate(ZstdExtractionProgress())

    val preparedImage = when (rootMode) {
        RootMode.MagiskPatch -> prepareMagiskPatchedImage(rootManagerVersion, appendLog)
        RootMode.KernelSuLkm -> {
            appendLog("KernelSU-LKM 不在 fastboot 阶段预修补")
            appendLog("将先完成 ROM 刷入，随后再进入后置 ROOT 流程")
            null
        }
        RootMode.FolkPatch -> prepareFolkPatchedImage(rootManagerVersion, appendLog)
        RootMode.KeepOriginal -> null
        RootMode.HfTeamGki -> {
            appendLog("管理器版本 ${rootManagerVersion ?: "默认版本"}")
            appendLog("HF-Team-GKI 内核流程暂未接入")
            return false
        }
    }
    if (rootMode == RootMode.MagiskPatch && preparedImage == null) {
        return false
    }
    if (rootMode == RootMode.FolkPatch && preparedImage == null) {
        return false
    }

    var flashTargets = collectFlashImageTargets(
        preparedImage = preparedImage,
        appendLog = appendLog,
        skipSuper = rootDebugMode && rootMode == RootMode.KernelSuLkm,
    ).toMutableList()
    if (flashTargets.isEmpty()) {
        appendLog("未在 firmware-update 中找到可刷写镜像")
        return false
    }

    if (!disguiseRelock) {
        val removed = flashTargets.removeAll { it.partition.equals("efisp", ignoreCase = true) }
        if (removed) appendLog("未启用伪装回锁，已跳过 efisp 分区")
    }

    if (disguiseRelock) {
        val firmwareDir = resolveRuntimePath(".\\firmware-update")
        val ablLoadElistPath = "$firmwareDir\\abl_loadelisp.img"
        val hasAblLoadElist = fileExistsUnicodeSafe(ablLoadElistPath)
        val disguiseImages = locateDisguiseRelockImages(appendLog)

        if (hasAblLoadElist) {
            val ablIndex = flashTargets.indexOfFirst { it.partition.equals("abl", ignoreCase = true) }
            val customTarget = FlashImageTarget(
                partition = "abl",
                imagePath = ablLoadElistPath,
                displayName = "abl_loadelisp.img",
                sourceDescription = "伪装回锁 abl (内置 abl_loadelisp)",
            )
            if (ablIndex >= 0) {
                flashTargets[ablIndex] = customTarget
            } else {
                flashTargets += customTarget
            }
            appendLog("检测到 abl_loadelisp.img，已替换 abl 分区镜像")
        } else if (disguiseImages != null) {
            val ablIndex = flashTargets.indexOfFirst { it.partition.equals("abl", ignoreCase = true) }
            val customTarget = FlashImageTarget(
                partition = "abl",
                imagePath = disguiseImages.ablImagePath,
                displayName = fileNameOf(disguiseImages.ablImagePath),
                sourceDescription = "伪装回锁定制 abl",
            )
            if (ablIndex >= 0) {
                flashTargets[ablIndex] = customTarget
            } else {
                flashTargets += customTarget
            }
            appendLog("已替换 abl.img 为伪装回锁版本")
        } else {
            appendLog("========================================")
            appendLog("伪装回锁所需镜像缺失，请将以下文件放入 firmware-update 文件夹：")
            appendLog("  abl_loadelisp.img（abl 与 efisp 合并镜像）")
            appendLog("  或分别放置 abl.img 和 efisp.img")
            appendLog("放置完成后请重新执行刷机")
            appendLog("========================================")
            return false
        }

        if (disguiseImages != null) {
            val hasEfisp = flashTargets.any { it.partition.equals("efisp", ignoreCase = true) }
            if (!hasEfisp) {
                flashTargets += FlashImageTarget(
                    partition = "efisp",
                    imagePath = disguiseImages.efispImagePath,
                    displayName = fileNameOf(disguiseImages.efispImagePath),
                    sourceDescription = "伪装回锁 efisp",
                )
                appendLog("已追加伪装回锁 efisp.img")
            }
        } else {
            appendLog("伪装回锁 efisp 镜像下载失败，中断刷机")
            return false
        }
    }

    val stages = buildPartitionStages(
        flashMode = flashMode,
        rootMode = rootMode,
        rootDebugMode = rootDebugMode,
        flashTargets = flashTargets,
        autoReboot = autoReboot,
        clearGoogleLock = clearGoogleLock,
    )
    var completedCount = 0

    fun activateStage(stage: String) {
        val currentIndex = stages.indexOf(stage).takeIf { it >= 0 } ?: return
        onProgressUpdate(
            FlashPartitionProgress(
                stages = stages,
                currentIndex = currentIndex,
                completedCount = completedCount,
            ),
        )
    }

    fun completeStage(stage: String) {
        val currentIndex = stages.indexOf(stage).takeIf { it >= 0 } ?: return
        completedCount = max(completedCount, currentIndex + 1)
        onProgressUpdate(
            FlashPartitionProgress(
                stages = stages,
                currentIndex = if (completedCount >= stages.size) stages.lastIndex else completedCount,
                completedCount = completedCount,
            ),
        )
    }

    if (stages.isNotEmpty()) {
        onProgressUpdate(FlashPartitionProgress(stages = stages, currentIndex = 0, completedCount = 0))
    }

    appendLog("=========================== 开始刷机 =================================")
    if (!runFastboot(serial, listOf("set_active", "a"), appendLog)) {
        appendLog("设置活动槽位 a 失败，继续刷机")
    }

    for (target in flashTargets) {
        activateStage(target.partition)
        appendLog("刷入分区: ${target.displayName}")
        if (!flashPartitionWithFallback(serial, target, appendLog)) {
            appendLog("刷入 ${target.partition} 失败")
            return false
        }
        completeStage(target.partition)
    }

    if (flashMode == FlashMode.CleanData) {
        activateStage("userdata")
        appendLog("用户数据正在清除中...")
        if (!runFastboot(serial, listOf("erase", "userdata"), appendLog)) {
            appendLog("清除 userdata 失败，继续执行")
        }
        if (!runFastboot(serial, listOf("erase", "metadata"), appendLog)) {
            appendLog("清除 metadata 失败，继续执行")
        }
        completeStage("userdata")
    }

    if (rootMode == RootMode.KernelSuLkm) {
        if (rootDebugMode) activateStage("ROOT")
        if (!executeKernelSuLkmPostFlashRoot(
                serial = serial,
                rootManagerVersion = rootManagerVersion,
                appendLog = appendLog,
                onWaitProgressUpdate = onWaitProgressUpdate,
                onManagerDownloadProgressUpdate = onManagerDownloadProgressUpdate,
            )
        ) {
            appendLog("KernelSU-LKM 后置 ROOT 失败")
            return false
        }
        if (rootDebugMode) completeStage("ROOT")
    }

    appendLog("分区刷写阶段完成")
    if (!runFastboot(serial, listOf("set_active", "a"), appendLog)) {
        appendLog("再次设置活动槽位 a 失败，继续执行")
    }

    if (clearGoogleLock) {
        activateStage("frp")
        appendLog("正在清除谷歌锁 frp")
        if (!runFastboot(serial, listOf("erase", "frp"), appendLog)) {
            appendLog("清除 frp 失败")
            return false
        }
        completeStage("frp")
    }

    if (autoReboot) {
        activateStage("reboot")
        appendLog("发送重启命令")
        if (!runFastboot(serial, listOf("reboot"), appendLog)) {
            appendLog("重启命令执行失败")
            return false
        }
        completeStage("reboot")
    } else {
        appendLog("已关闭自动开机")
    }

    appendLog("刷机流程执行完成")
    return true
}

private fun buildPartitionStages(
    flashMode: FlashMode,
    rootMode: RootMode,
    rootDebugMode: Boolean,
    flashTargets: List<FlashImageTarget>,
    autoReboot: Boolean,
    clearGoogleLock: Boolean,
): List<String> {
    val stages = mutableListOf<String>()
    stages += flashTargets.map { it.partition }
    if (flashMode == FlashMode.CleanData) stages += "userdata"
    if (rootDebugMode && rootMode == RootMode.KernelSuLkm) stages += "ROOT"
    if (clearGoogleLock) stages += "frp"
    if (autoReboot) stages += "reboot"
    return stages.distinct()
}

private fun cleanupLegacyTempDirectory(appendLog: (String) -> Unit) {
    val legacyTemp = resolveRuntimePath(".\\Temp")
    if (directoryExists(legacyTemp)) {
        appendLog("清理旧的 Temp 目录")
        removePath(legacyTemp)
    }
}

private fun extractZstdPackages(
    zstdPackages: List<String>,
    appendLog: (String) -> Unit,
    onExtractionProgressUpdate: (ZstdExtractionProgress) -> Unit,
): Boolean {
    if (zstdPackages.isEmpty()) return true

    val brxPath = resolveFirstExistingPath(listOf(".\\META-INF\\brx.exe"))
    if (brxPath == null) {
        appendLog("未找到 brx.exe，无法解压 .zstd 线刷包")
        return false
    }

    val firmwareDir = resolveRuntimePath(".\\firmware-update")
    if (!ensureDirectory(firmwareDir)) {
        appendLog("无法创建 firmware-update 目录")
        return false
    }

    zstdPackages.forEachIndexed { index, packagePath ->
        val partition = fileNameWithoutExtension(fileNameOf(packagePath))
        val outputImage = "$firmwareDir\\$partition.img"
        removePath(outputImage)
        appendLog("线刷文件 $partition 正在准备中...")
        onExtractionProgressUpdate(
            ZstdExtractionProgress(
                isActive = true,
                currentFile = "$partition.zstd",
                completedCount = index,
                totalCount = zstdPackages.size,
            ),
        )
        val result = runCommand(
            executable = brxPath,
            args = listOf("-d", packagePath, "-o", outputImage),
            appendLog = appendLog,
        )
        if (result.exitCode != 0 || !fileExists(outputImage)) {
            appendLog("$partition.zstd 解压失败")
            onExtractionProgressUpdate(
                ZstdExtractionProgress(
                    isActive = false,
                    currentFile = "$partition.zstd",
                    completedCount = index,
                    totalCount = zstdPackages.size,
                ),
            )
            return false
        }
        removePath(packagePath)
        onExtractionProgressUpdate(
            ZstdExtractionProgress(
                isActive = true,
                currentFile = "$partition.zstd",
                completedCount = index + 1,
                totalCount = zstdPackages.size,
            ),
        )
    }
    return true
}

private fun warnIfMissingLogicalPartitions(
    hasZstdPackages: Boolean,
    appendLog: (String) -> Unit,
) {
    val superImagePath = resolveRuntimePath(".\\firmware-update\\super.img")
    if (!hasZstdPackages && !fileExists(superImagePath)) {
        appendLog("未找到 .zstd 或 firmware-update\\super.img")
        appendLog("当前刷机包不包含逻辑分区")
        appendLog("有可能只会刷入底层分区，而非系统分区")
    }
}

private fun collectFlashImageTargets(
    preparedImage: PreparedFlashImage?,
    appendLog: (String) -> Unit,
    skipSuper: Boolean = false,
): List<FlashImageTarget> {
    val firmwareDir = resolveRuntimePath(".\\firmware-update")
    val firmwareFiles = listFilesInDirectory(firmwareDir)
    val targets = firmwareFiles.map { path ->
        val normalizedPath = normalizePath(path)
        val partition = partitionNameForFile(fileNameOf(normalizedPath))
        FlashImageTarget(
            partition = partition,
            imagePath = normalizedPath,
            displayName = fileNameOf(normalizedPath),
            sourceDescription = "ROM 原始镜像",
        )
    }
        .filterNot { skipSuper && it.partition.equals("super", ignoreCase = true) }
        .sortedWith(
            compareBy<FlashImageTarget> { it.partition.equals("super", ignoreCase = true) }
                .thenBy { it.partition.lowercase() },
        )
        .toMutableList()

    if (preparedImage != null) {
        val patchedTarget = FlashImageTarget(
            partition = preparedImage.partition,
            imagePath = normalizePath(preparedImage.imagePath),
            displayName = fileNameOf(preparedImage.imagePath),
            sourceDescription = preparedImage.description,
        )
        val existingIndex = targets.indexOfFirst { it.partition.equals(preparedImage.partition, ignoreCase = true) }
        if (existingIndex >= 0) {
            targets[existingIndex] = patchedTarget
        } else {
            targets += patchedTarget
        }
        appendLog("已使用 ${preparedImage.partition} 修补镜像覆盖原始镜像")
    }

    if (skipSuper) {
        appendLog("ROOT-debug 模式已开启，已跳过 super 镜像")
    }

    targets.sortWith(
        compareBy<FlashImageTarget> { it.partition.equals("super", ignoreCase = true) }
            .thenBy { it.partition.lowercase() },
    )
    return targets
}

private fun flashPartitionWithFallback(
    serial: String,
    target: FlashImageTarget,
    appendLog: (String) -> Unit,
): Boolean {
    val imagePath = normalizePath(target.imagePath)
    val partition = target.partition

    if (runFastboot(serial, listOf("flash", partition, imagePath), appendLog)) {
        return true
    }
    if (runFastboot(serial, listOf("flash", "${partition}_ab", imagePath), appendLog)) {
        return true
    }

    runFastboot(serial, listOf("flash", "${partition}_a", imagePath), appendLog)
    if (runFastboot(serial, listOf("flash", "${partition}_b", imagePath), appendLog)) {
        return true
    }

    runFastboot(serial, listOf("flash", "${partition}1", imagePath), appendLog)
    if (runFastboot(serial, listOf("flash", "${partition}_2", imagePath), appendLog)) {
        return true
    }

    appendLog("【【【【【【【刷入 $partition 失败】】】】】】】")
    return false
}

private fun partitionNameForFile(fileName: String): String {
    val rawName = fileName.substringBeforeLast('.', fileName)
    return if (rawName.equals("preloader_raw", ignoreCase = true)) "preloader" else rawName
}

private fun fileNameWithoutExtension(fileName: String): String =
    fileName.substringBeforeLast('.', fileName)

private fun listFilesInDirectory(
    path: String,
    filter: String? = null,
): List<String> {
    if (!directoryExists(path)) return emptyList()
    val outputPath = resolveTempPath(".\\__winflasher_files_${Random.nextInt(100000, 999999)}.txt")
    val filterClause = filter?.let { " -Filter '${escapeForPowerShell(it)}'" } ?: ""
    val script = """
        Get-ChildItem -LiteralPath '${escapeForPowerShell(normalizePath(path))}' -File$filterClause -ErrorAction SilentlyContinue |
            Sort-Object Name |
            ForEach-Object FullName |
            Set-Content -LiteralPath '${escapeForPowerShell(outputPath)}' -Encoding utf8
    """.trimIndent()
    runPowerShellScript(script)
    val files = readTextLines(outputPath)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    remove(outputPath)
    return files
}

private fun removePath(path: String): Boolean {
    val normalized = normalizePath(path)
    if (!fileExists(normalized) && !directoryExists(normalized)) return true
    val script = """
        Remove-Item -LiteralPath '${escapeForPowerShell(normalized)}' -Force -Recurse -ErrorAction SilentlyContinue
    """.trimIndent()
    return runPowerShellScript(script) == 0
}

private fun prepareOriginalImage(appendLog: (String) -> Unit): PreparedFlashImage? {
    val locatedImage = locateBootImage(preferInitBoot = true)
    if (locatedImage == null) {
        appendLog("未找到可刷写的 boot 或 init_boot 镜像")
        return null
    }
    appendLog("使用原始镜像 ${locatedImage.imagePath}")
    return locatedImage
}

private fun prepareFolkPatchedImage(
    rootManagerVersion: String?,
    appendLog: (String) -> Unit,
): PreparedFlashImage? {
    val sourceImage = locateBootImage(preferInitBoot = false)
    if (sourceImage == null) {
        appendLog("FolkPatch 需要 boot.img，但当前目录未找到")
        return null
    }

    val kptoolsCandidates = buildList {
        rootManagerVersion
            ?.takeUnless { it == "default" }
            ?.let { add(".\\.codex-cache\\FolkTool\\kp_versions\\$it\\windows\\kptools.exe") }
        add(".\\META-INF\\tools\\folktool\\kptools.exe")
        add(".\\.codex-cache\\FolkTool\\kptools\\kptools.exe")
        add(".\\.codex-cache\\FolkTool\\kp_versions\\0.13.0\\windows\\kptools.exe")
    }
    val kptoolsPath = resolveFirstExistingPath(kptoolsCandidates)
    val kpimgPath = resolveFirstExistingPath(
        listOf(
            ".\\META-INF\\tools\\folktool\\kpimg",
            ".\\.codex-cache\\FolkTool\\assets\\kpimg",
        ),
    )

    if (kptoolsPath == null || kpimgPath == null) {
        appendLog("未找到 FolkPatch 所需的 kptools 或 kpimg")
        return null
    }

    val workDir = createWorkDirectory("folkpatch") ?: return null.also {
        appendLog("无法创建 FolkPatch 工作目录")
    }
    val workBootImage = "$workDir\\${fileNameOf(sourceImage.imagePath)}"
    val kernelPath = "$workDir\\kernel"
    val kernelBackupPath = "$workDir\\kernel.ori"
    val outputImage = "$workDir\\folkpatch_patched_boot.img"

    if (!copyFile(sourceImage.imagePath, workBootImage)) {
        appendLog("复制 boot.img 到工作目录失败")
        return null
    }

    appendLog("开始执行 FolkPatch 修补")
    appendLog("管理器版本 ${rootManagerVersion ?: "0.13.0"}")
    val unpackResult = runCommand(
        executable = kptoolsPath,
        args = listOf("unpack", workBootImage),
        workDir = workDir,
        appendLog = appendLog,
    )
    if (unpackResult.exitCode != 0 || !fileExists(kernelPath)) {
        appendLog("FolkPatch 解包失败")
        return null
    }

    if (!copyFile(kernelPath, kernelBackupPath)) {
        appendLog("备份原始 kernel 失败")
        return null
    }

    val patchResult = runCommand(
        executable = kptoolsPath,
        args = listOf(
            "-p",
            "-i", kernelBackupPath,
            "-k", kpimgPath,
            "-o", kernelPath,
            "-S", "HanfyHyperOS",
        ),
        workDir = workDir,
        appendLog = appendLog,
    )
    if (patchResult.exitCode != 0) {
        appendLog("FolkPatch 修补失败")
        return null
    }

    val repackResult = runCommand(
        executable = kptoolsPath,
        args = listOf("repack", workBootImage),
        workDir = workDir,
        appendLog = appendLog,
    )
    if (repackResult.exitCode != 0 || !fileExists("$workDir\\new-boot.img")) {
        appendLog("FolkPatch 回封失败")
        return null
    }

    if (!copyFile("$workDir\\new-boot.img", outputImage)) {
        appendLog("复制 FolkPatch 输出镜像失败")
        return null
    }

    appendLog("FolkPatch 输出完成")
    return PreparedFlashImage(
        imagePath = normalizePath(outputImage),
        partition = "boot",
        description = "FolkPatch 修补后的 boot 镜像",
    )
}

private fun prepareMagiskPatchedImage(
    rootManagerVersion: String?,
    appendLog: (String) -> Unit,
): PreparedFlashImage? {
    val sourceImage = locateBootImage(preferInitBoot = true)
    if (sourceImage == null) {
        appendLog("未找到可供 Magisk 修补的 boot 或 init_boot 镜像")
        return null
    }

    val patcherPath = resolveFirstExistingPath(
        listOf(
            ".\\META-INF\\tools\\magiskpatcher\\MagiskPatcher.exe",
            ".\\.codex-cache\\MagiskPatcher\\publish\\MagiskPatcher.exe",
        ),
    )
    val sevenZipPath = resolveFirstExistingPath(
        listOf(
            ".\\META-INF\\tools\\magiskpatcher\\7z.exe",
            ".\\.codex-cache\\MagiskPatcher\\dependencies\\7z.exe",
        ),
    )
    val sevenZipDllPath = resolveFirstExistingPath(
        listOf(
            ".\\META-INF\\tools\\magiskpatcher\\7z.dll",
            ".\\.codex-cache\\MagiskPatcher\\dependencies\\7z.dll",
        ),
    )
    val magiskbootPath = resolveFirstExistingPath(
        listOf(
            ".\\META-INF\\tools\\magiskpatcher\\magiskboot.exe",
            ".\\META-INF\\magiskboot.exe",
            ".\\.codex-cache\\MagiskPatcher\\dependencies\\magiskboot.exe",
        ),
    )
    val configPath = resolveFirstExistingPath(
        listOf(
            ".\\META-INF\\tools\\magiskpatcher\\MagiskPatcher.csv",
            ".\\.codex-cache\\MagiskPatcher\\dependencies\\MagiskPatcher.csv",
        ),
    )
    val magiskApkPath = locateMagiskApk(rootManagerVersion)

    if (patcherPath == null || sevenZipPath == null || sevenZipDllPath == null || magiskbootPath == null || configPath == null) {
        appendLog("未找到 Magisk 修补所需工具")
        return null
    }
    if (magiskApkPath == null) {
        appendLog("未找到 Magisk.apk")
        return null
    }

    if (!ensureMagiskRuntimeDependencies(sevenZipPath, sevenZipDllPath, appendLog)) {
        appendLog("Magisk 运行时依赖准备失败")
        return null
    }

    val workDir = createWorkDirectory("magisk") ?: return null.also {
        appendLog("无法创建 Magisk 工作目录")
    }
    val outputImage = "$workDir\\magisk_patched_${fileNameOf(sourceImage.imagePath)}"

    appendLog("开始执行 Magisk 修补")
    appendLog("管理器版本 ${rootManagerVersion ?: "自动检测"}")
    val result = runCommand(
        executable = patcherPath,
        args = listOf(
            magiskApkPath,
            sourceImage.imagePath,
            "-out=$outputImage",
            "-wd=$workDir",
            "-7z=$sevenZipPath",
            "-mb=$magiskbootPath",
            "-cfg=$configPath",
            "-cl=true",
        ),
        appendLog = appendLog,
    )

    if (result.exitCode != 0 || !fileExists(outputImage)) {
        appendLog("Magisk 修补失败")
        return null
    }

    appendLog("Magisk 输出完成")
    return PreparedFlashImage(
        imagePath = normalizePath(outputImage),
        partition = sourceImage.partition,
        description = "Magisk 修补后的 ${sourceImage.partition} 镜像",
    )
}

private fun ensureMagiskRuntimeDependencies(
    sevenZipPath: String,
    sevenZipDllPath: String,
    appendLog: (String) -> Unit,
): Boolean {
    val normalizedExe = normalizePath(sevenZipPath)
    val normalizedDll = normalizePath(sevenZipDllPath)
    val targetDll = normalizedExe.substringBeforeLast('\\') + "\\7z.dll"
    if (normalizedDll.equals(targetDll, ignoreCase = true)) {
        return true
    }
    if (fileExists(targetDll)) {
        return true
    }
    val copied = copyFile(normalizedDll, targetDll)
    if (copied) {
        appendLog("已补齐 MagiskPatcher 运行时依赖 7z.dll")
    }
    return copied
}

private data class KernelSuLkmFlowConfig(
    val id: String,
    val title: String,
    val patchedGlob: String,
    val uninstallPackages: List<String>,
    val soMap: List<Pair<String, String>>,
)

fun executeKernelSuLkmRootDebug(
    serial: String,
    rootManagerVersion: String?,
    appendLog: (String) -> Unit,
    onWaitProgressUpdate: (WaitProgress) -> Unit = {},
    onManagerDownloadProgressUpdate: (ManagerDownloadProgress) -> Unit = {},
): Boolean = executeKernelSuLkmPostFlashRoot(
    serial = serial,
    rootManagerVersion = rootManagerVersion,
    appendLog = appendLog,
    onWaitProgressUpdate = onWaitProgressUpdate,
    onManagerDownloadProgressUpdate = onManagerDownloadProgressUpdate,
)

private fun executeKernelSuLkmPostFlashRoot(
    serial: String,
    rootManagerVersion: String?,
    appendLog: (String) -> Unit,
    onWaitProgressUpdate: (WaitProgress) -> Unit = {},
    onManagerDownloadProgressUpdate: (ManagerDownloadProgress) -> Unit = {},
): Boolean {
    appendLog("开始执行 KernelSU-LKM 后置 ROOT")
    appendLog("如设备刚刷完，请确保系统已开机、USB 调试已开启，并允许 USB 安装")

    if (!ensureKernelSuDeviceBootedToAndroid(serial, appendLog)) {
        return false
    }

    val managerApk = locateKernelSuLkmManagerApk(
        managerId = rootManagerVersion,
        appendLog = appendLog,
        onDownloadProgressUpdate = onManagerDownloadProgressUpdate,
    )
    if (managerApk == null) {
        appendLog("未找到可用的 KernelSU-LKM 管理器 APK")
        return false
    }

    val preparedImage = waitAndPrepareKernelSuLkmPatchedImage(
        serial = serial,
        rootManagerVersion = rootManagerVersion,
        managerApk = managerApk,
        appendLog = appendLog,
        onWaitProgressUpdate = onWaitProgressUpdate,
        onManagerDownloadProgressUpdate = onManagerDownloadProgressUpdate,
    ) ?: return false

    if (!rebootKernelSuDeviceToBootloader(serial, appendLog)) {
        return false
    }

    appendLog("刷入分区: ${fileNameOf(preparedImage.imagePath)}")

    val fastbootSerial = waitForFastbootDevice(
        preferredSerial = serial,
        appendLog = appendLog,
        onWaitProgressUpdate = onWaitProgressUpdate,
    ) ?: return false

    if (!flashPartitionWithFallback(
            serial = fastbootSerial,
            target = FlashImageTarget(
                partition = preparedImage.partition,
                imagePath = preparedImage.imagePath,
                displayName = fileNameOf(preparedImage.imagePath),
                sourceDescription = preparedImage.description,
            ),
            appendLog = appendLog,
        )
    ) {
        appendLog("ROOT 修补镜像刷入失败")
        return false
    }

    appendLog("KernelSU-LKM 后置 ROOT 完成")
    return true
}

private fun ensureKernelSuDeviceBootedToAndroid(
    preferredFastbootSerial: String,
    appendLog: (String) -> Unit,
): Boolean {
    if (preferredFastbootSerial.isBlank()) {
        return true
    }

    val fastbootSerial = resolveFastbootSerial(preferredFastbootSerial, appendLog) ?: return true
    appendLog("设备仍在 Fastboot，先重启到系统")
    if (!runFastboot(fastbootSerial, listOf("reboot"), appendLog)) {
        appendLog("设备重启到系统失败")
        return false
    }
    return true
}

private fun rebootKernelSuDeviceToBootloader(
    preferredSerial: String,
    appendLog: (String) -> Unit,
): Boolean {
    val adbSerial = resolveAdbSerial(preferredSerial, appendLog)
    if (adbSerial == null) {
        appendLog("未发现可用 ADB 设备")
        return false
    }
    appendLog("已连接 ADB 设备 $adbSerial")
    appendLog("发送 adb reboot bootloader")
    val result = runAdb(adbSerial, listOf("reboot", "bootloader"), appendLog)
    if (result.exitCode != 0) {
        appendLog("切换到 Fastboot 失败")
        return false
    }
    return true
}

private fun waitAndPrepareKernelSuLkmPatchedImage(
    serial: String,
    rootManagerVersion: String?,
    managerApk: String,
    appendLog: (String) -> Unit,
    onWaitProgressUpdate: (WaitProgress) -> Unit = {},
    onManagerDownloadProgressUpdate: (ManagerDownloadProgress) -> Unit = {},
): PreparedFlashImage? {
    val adbSerial = waitForAdbBootCompletedDevice(
        preferredSerial = serial,
        appendLog = appendLog,
        onWaitProgressUpdate = onWaitProgressUpdate,
    ) ?: return null

    prepareKernelSuLkmPatchedImage(
        adbSerial = adbSerial,
        rootManagerVersion = rootManagerVersion,
        managerApk = managerApk,
        appendLog = appendLog,
        onManagerDownloadProgressUpdate = onManagerDownloadProgressUpdate,
    )?.let { prepared ->
        onWaitProgressUpdate(WaitProgress())
        return prepared
    }

    onWaitProgressUpdate(WaitProgress())
    return null
}

private fun waitForAdbBootCompletedDevice(
    preferredSerial: String,
    appendLog: (String) -> Unit,
    onWaitProgressUpdate: (WaitProgress) -> Unit = {},
): String? {
    val attempts = 60
    var unauthorizedHintShown = false
    appendLog("等待系统开机")
    onWaitProgressUpdate(
        WaitProgress(
            isActive = true,
            title = "等待系统启动",
            currentStep = 0,
            totalSteps = attempts,
        ),
    )

    repeat(attempts) { index ->
        val deviceStates = scanAdbDeviceStatesQuiet()
        val adbSerial = choosePreferredAdbSerial(preferredSerial, deviceStates)
        val unauthorized = deviceStates.any { it.state.contains("unauthorized", ignoreCase = true) }
        if (unauthorized && !unauthorizedHintShown) {
            appendLog("ADB 未授权，请在手机上允许这台电脑")
            unauthorizedHintShown = true
        }

        if (adbSerial != null) {
            val bootCompleted = readAdbPropertyQuiet(adbSerial, "sys.boot_completed")
            if (bootCompleted == "1") {
                appendLog("设备已完全开机")
                onWaitProgressUpdate(WaitProgress())
                return adbSerial
            }
        }

        onWaitProgressUpdate(
            WaitProgress(
                isActive = true,
                title = "等待系统启动",
                currentStep = index + 1,
                totalSteps = attempts,
            ),
        )
        if (index != attempts - 1) {
            Sleep(3000u)
        }
    }

    onWaitProgressUpdate(WaitProgress())
    appendLog("等待系统启动超时")
    return null
}

private fun waitForFastbootDevice(
    preferredSerial: String,
    appendLog: (String) -> Unit,
    onWaitProgressUpdate: (WaitProgress) -> Unit = {},
): String? {
    val attempts = 60
    onWaitProgressUpdate(
        WaitProgress(
            isActive = true,
            title = "等待 Fastboot",
            currentStep = 0,
            totalSteps = attempts,
        ),
    )
    repeat(attempts) { index ->
        resolveFastbootSerial(preferredSerial, appendLog)?.let { serial ->
            appendLog("已连接 Fastboot 设备 $serial")
            onWaitProgressUpdate(WaitProgress())
            return serial
        }
        if (index == 0) {
            appendLog("等待 Fastboot")
        }
        onWaitProgressUpdate(
            WaitProgress(
                isActive = true,
                title = "等待 Fastboot",
                currentStep = index + 1,
                totalSteps = attempts,
            ),
        )
        if (index != attempts - 1) {
            Sleep(3000u)
        }
    }
    onWaitProgressUpdate(WaitProgress())
    appendLog("未检测到 Fastboot 设备")
    return null
}

private fun resolveFastbootSerial(preferredSerial: String, appendLog: (String) -> Unit): String? {
    val result = scanFastbootDevices()
    result.outputLines.filter { it.isNotBlank() }.forEach(appendLog)
    val devices = result.devices.map { it.serial }
    if (devices.isEmpty()) return null
    if (preferredSerial.isNotBlank() && preferredSerial in devices) return preferredSerial
    return devices.firstOrNull()?.also {
        if (preferredSerial.isNotBlank() && it != preferredSerial) {
            appendLog("已切换到 Fastboot 设备 $it")
        }
    }
}

private fun prepareKernelSuLkmPatchedImage(
    adbSerial: String,
    rootManagerVersion: String?,
    managerApk: String,
    appendLog: (String) -> Unit,
    onManagerDownloadProgressUpdate: (ManagerDownloadProgress) -> Unit = {},
): PreparedFlashImage? {
    val sourceImage = locateBootImage(preferInitBoot = true, allowRecursiveFallback = false)
    if (sourceImage == null) {
        appendLog("KernelSU-LKM 需要 boot 或 init_boot 镜像")
        return null
    }
    onManagerDownloadProgressUpdate(ManagerDownloadProgress())

    val config = kernelSuLkmFlowConfig(rootManagerVersion)
    val workDir = createWorkDirectory("ksulkm") ?: return null.also {
        appendLog("无法创建 KernelSU-LKM 工作目录")
    }
    val outputImage = "$workDir\\${sourceImage.partition}_kernelsu_patched.img"
    val tmpDir = "/data/local/tmp"
    val remoteWorkDir = "/data/local/tmp/root"
    val remoteToolDir = "$remoteWorkDir/kpm-tools"
    val remotePatchDir = "$remoteWorkDir/patch-work"
    val markerPath = "$tmpDir/hanfy"
    val remoteApkPath = "$tmpDir/root.apk"
    val remoteBootPath = "$tmpDir/boot.img"
    val cleanupScript = buildKernelSuLkmCleanupScript(tmpDir, remoteWorkDir)

    appendLog("开始执行 ${config.title} ROOT 流程")
    appendLog("当前管理器 ${fileNameOf(managerApk)}")

    if (!runAdbShell(adbSerial, "touch ${quoteForPosixShell(markerPath)}", appendLog)) {
        appendLog("${config.title} 无法写入设备临时目录")
        return null
    }
    runAdbShell(adbSerial, cleanupScript, appendLog)
    if (!runAdbPush(adbSerial, sourceImage.imagePath, remoteBootPath, appendLog)) return null
    if (!runAdbPush(adbSerial, managerApk, remoteApkPath, appendLog)) return null
    if (!runAdbShell(adbSerial, "mkdir -p ${quoteForPosixShell(remoteWorkDir)}", appendLog)) {
        appendLog("${config.title} 无法创建设备工作目录")
        return null
    }
    if (config.id == "sukisu-ultra" &&
        !runAdbShell(
            adbSerial,
            "mkdir -p ${quoteForPosixShell(remoteToolDir)} ${quoteForPosixShell(remotePatchDir)}",
            appendLog,
        )
    ) {
        appendLog("${config.title} 鏃犳硶鍒涘缓 SukiSU 宸ュ叿鐩綍")
        return null
    }

    config.uninstallPackages.forEach { pkg ->
        runAdb(adbSerial, listOf("uninstall", pkg), {})
    }

    /*
    if (!runAdbShell(
            adbSerial,
            "unzip -o ${quoteForPosixShell(remoteApkPath)} -d ${quoteForPosixShell(remoteWorkDir)}",
            appendLog,
        )
    ) {
        appendLog("${config.title} 解压管理器失败")
        return null
    }

    config.soMap.forEach { (src, dst) ->
        moveRemoteFileIfExists(
            serial = adbSerial,
            sourcePath = "$remoteWorkDir/$src",
            targetPath = "$remoteWorkDir/$dst",
            appendLog = appendLog,
        )
    }
    if (config.id == "sukisu-ultra") {
        appendLog("${config.title} 寮€濮嬭澶囩淇ˉ")
        val remotePatchedPath = runSukiSuUltraBootPatch(
            serial = adbSerial,
            remoteToolDir = remoteToolDir,
            remotePatchDir = remotePatchDir,
            remoteBootPath = remoteBootPath,
            appendLog = appendLog,
        )
        if (remotePatchedPath == null) {
            appendLog("${config.title} 璁惧绔慨琛ュけ璐?)
            return null
        }
        if (!runAdbPull(adbSerial, remotePatchedPath, outputImage, appendLog)) {
            appendLog("${config.title} 鎷夊彇淇ˉ闀滃儚澶辫触")
            return null
        }

        val originalSize = readAllBytes(sourceImage.imagePath).size.toLong()
        val patchedSize = readAllBytes(outputImage).size.toLong()
        if (patchedSize <= 1024L || (originalSize > 0L && patchedSize <= originalSize / 2L)) {
            appendLog("${config.title} 淇ˉ缁撴灉寮傚父锛岄暅鍍忚繃灏?)
            return null
        }

        appendLog("璇峰湪鎵嬫満涓婂厑璁?USB 瀹夎锛屾帴涓嬫潵瀹夎 ROOT 绠＄悊鍣?)
        val installResult = runAdb(
            adbSerial,
            listOf("install", "-r", managerApk),
            appendLog,
        )
        if (installResult.exitCode == 0) {
            appendLog("${config.title} 绠＄悊鍣ㄥ畨瑁呮垚鍔?)
        } else {
            appendLog("${config.title} 绠＄悊鍣ㄥ畨瑁呭け璐?)
        }

        runAdbShell(adbSerial, cleanupScript, appendLog)
        appendLog("${config.title} 淇ˉ瀹屾垚")
        return PreparedFlashImage(
            imagePath = normalizePath(outputImage),
            partition = sourceImage.partition,
            description = "${config.title} 淇ˉ鍚庣殑 ${sourceImage.partition} 闀滃儚",
        )
    }
    */
    /*
    if (!runAdbShell(
            adbSerial,
            "unzip -o ${quoteForPosixShell(remoteApkPath)} -d ${quoteForPosixShell(remoteWorkDir)}",
            appendLog,
        )
    ) {
        appendLog("${config.title} 解压管理器失败")
        return null
    }

    config.soMap.forEach { (src, dst) ->
        moveRemoteFileIfExists(
            serial = adbSerial,
            sourcePath = "$remoteWorkDir/$src",
            targetPath = "$remoteWorkDir/$dst",
            appendLog = appendLog,
        )
    }
    if (config.id == "sukisu-ultra") {
        appendLog("${config.title} 开始设备端修补")
        val remotePatchedPath = runSukiSuUltraBootPatch(
            serial = adbSerial,
            remoteToolDir = remoteToolDir,
            remotePatchDir = remotePatchDir,
            remoteBootPath = remoteBootPath,
            appendLog = appendLog,
        )
        if (remotePatchedPath == null) {
            appendLog("${config.title} 设备端修补失败")
            return null
        }
        if (!runAdbPull(adbSerial, remotePatchedPath, outputImage, appendLog)) {
            appendLog("${config.title} 拉取修补镜像失败")
            return null
        }

        val originalSize = readAllBytes(sourceImage.imagePath).size.toLong()
        val patchedSize = readAllBytes(outputImage).size.toLong()
        if (patchedSize <= 1024L || (originalSize > 0L && patchedSize <= originalSize / 2L)) {
            appendLog("${config.title} 修补结果异常，镜像过小")
            return null
        }

        appendLog("请在手机上允许 USB 安装，接下来安装 ROOT 管理器")
        val installResult = runAdb(
            adbSerial,
            listOf("install", "-r", managerApk),
            appendLog,
        )
        if (installResult.exitCode == 0) {
            appendLog("${config.title} 管理器安装成功")
        } else {
            appendLog("${config.title} 管理器安装失败")
        }

        runAdbShell(adbSerial, cleanupScript, appendLog)
        appendLog("${config.title} 修补完成")
        return PreparedFlashImage(
            imagePath = normalizePath(outputImage),
            partition = sourceImage.partition,
            description = "${config.title} 修补后的 ${sourceImage.partition} 镜像",
        )
    }
    */
    if (!runAdbShell(
            adbSerial,
            "unzip -o ${quoteForPosixShell(remoteApkPath)} -d ${quoteForPosixShell(remoteWorkDir)}",
            appendLog,
        )
    ) {
        appendLog("${config.title} manager unzip failed")
        return null
    }

    config.soMap.forEach { (src, dst) ->
        moveRemoteFileIfExists(
            serial = adbSerial,
            sourcePath = "$remoteWorkDir/$src",
            targetPath = "$remoteWorkDir/$dst",
            appendLog = appendLog,
        )
    }
    if (config.id == "sukisu-ultra") {
        appendLog("${config.title} device-side patch started")
        val remotePatchedPath = runSukiSuUltraBootPatch(
            serial = adbSerial,
            remoteToolDir = remoteToolDir,
            remotePatchDir = remotePatchDir,
            remoteBootPath = remoteBootPath,
            appendLog = appendLog,
        )
        if (remotePatchedPath == null) {
            appendLog("${config.title} device-side patch failed")
            return null
        }
        if (!runAdbPull(adbSerial, remotePatchedPath, outputImage, appendLog)) {
            appendLog("${config.title} patched image pull failed")
            return null
        }

        val originalSize = readAllBytes(sourceImage.imagePath).size.toLong()
        val patchedSize = readAllBytes(outputImage).size.toLong()
        if (patchedSize <= 1024L || (originalSize > 0L && patchedSize <= originalSize / 2L)) {
            appendLog("${config.title} patched image looks invalid")
            return null
        }

        appendLog("Allow USB install on the phone. Installing root manager next.")
        val installResult = runAdb(
            adbSerial,
            listOf("install", "-r", managerApk),
            appendLog,
        )
        if (installResult.exitCode == 0) {
            appendLog("${config.title} manager install succeeded")
        } else {
            appendLog("${config.title} manager install failed")
        }

        runAdbShell(adbSerial, cleanupScript, appendLog)
        appendLog("${config.title} patch completed")
        return PreparedFlashImage(
            imagePath = normalizePath(outputImage),
            partition = sourceImage.partition,
            description = "${config.title} patched ${sourceImage.partition} image",
        )
    }
    if (!runAdbShell(
            adbSerial,
            "chmod 755 ${quoteForPosixShell("$remoteWorkDir/ksud")} ${quoteForPosixShell("$remoteWorkDir/magiskboot")}",
            appendLog,
        )
    ) {
        appendLog("${config.title} 无法设置设备端执行权限")
        return null
    }

    appendLog("${config.title} 开始设备端修补")
    var patchSucceeded = runKernelSuBootPatch(
        serial = adbSerial,
        remoteWorkDir = remoteWorkDir,
        remoteBootPath = remoteBootPath,
        appendLog = appendLog,
    )
    var remotePatchedPath = if (patchSucceeded) {
        findRemoteKernelSuPatchedImage(adbSerial, remoteWorkDir, config.patchedGlob, appendLog)
    } else {
        null
    }
    if (remotePatchedPath == null) {
        val kmi = buildKmiStringForKernelSu(adbSerial, appendLog)
        if (kmi != null) {
            appendLog("使用 KMI $kmi 重试")
            patchSucceeded = runKernelSuBootPatch(
                serial = adbSerial,
                remoteWorkDir = remoteWorkDir,
                remoteBootPath = remoteBootPath,
                kmi = kmi,
                appendLog = appendLog,
            )
            remotePatchedPath = if (patchSucceeded) {
                findRemoteKernelSuPatchedImage(adbSerial, remoteWorkDir, config.patchedGlob, appendLog)
            } else {
                null
            }
        }
    }
    if (!patchSucceeded) {
        appendLog("${config.title} 设备端修补失败")
        return null
    }
    if (remotePatchedPath == null) {
        appendLog("${config.title} 未找到修补产物")
        return null
    }

    if (!runAdbShell(
            adbSerial,
            "mv ${quoteForPosixShell(remotePatchedPath)} ${quoteForPosixShell("$remoteWorkDir/new-boot.img")}",
            appendLog,
        )
    ) {
        appendLog("${config.title} 重命名修补镜像失败")
        return null
    }
    if (!runAdbPull(adbSerial, "$remoteWorkDir/new-boot.img", outputImage, appendLog)) {
        appendLog("${config.title} 拉取修补镜像失败")
        return null
    }

    val originalSize = readAllBytes(sourceImage.imagePath).size.toLong()
    val patchedSize = readAllBytes(outputImage).size.toLong()
    if (patchedSize <= 1024L || (originalSize > 0L && patchedSize <= originalSize / 2L)) {
        appendLog("${config.title} 修补结果异常，镜像过小")
        return null
    }

    appendLog("请在手机上允许 USB 安装，接下来安装 ROOT 管理器")
    val installResult = runAdb(
        adbSerial,
        listOf("install", "-r", managerApk),
        appendLog,
    )
    if (installResult.exitCode == 0) {
        appendLog("${config.title} 管理器安装成功")
    } else {
        appendLog("${config.title} 管理器安装失败")
    }

    runAdbShell(adbSerial, cleanupScript, appendLog)
    appendLog("${config.title} 修补完成")
    return PreparedFlashImage(
        imagePath = normalizePath(outputImage),
        partition = sourceImage.partition,
        description = "${config.title} 修补后的 ${sourceImage.partition} 镜像",
    )
}

private fun runFastboot(
    serial: String,
    args: List<String>,
    appendLog: (String) -> Unit,
): Boolean {
    val fastbootPath = resolveFirstExistingPath(listOf(".\\META-INF\\fastboot.exe")) ?: "fastboot"
    val result = runCommand(
        executable = fastbootPath,
        args = buildList {
            add("-s")
            add(serial)
            addAll(args)
        },
        appendLog = null,
    )
    if (result.exitCode != 0) {
        result.outputLines.filter { it.isNotBlank() }.forEach(appendLog)
    }
    return result.exitCode == 0
}

private fun locateBootImage(
    preferInitBoot: Boolean,
    allowRecursiveFallback: Boolean = true,
): PreparedFlashImage? {
    val names = if (preferInitBoot) {
        listOf("init_boot.img", "boot.img")
    } else {
        listOf("boot.img", "init_boot.img")
    }

    names.forEach { name ->
        resolveFirstExistingPath(listOf(".\\firmware-update\\$name", ".\\$name"))?.let { path ->
            return preparedImageFor(path)
        }
    }

    if (!allowRecursiveFallback) return null

    names.forEach { name ->
        findFirstFileByName(name)?.let { path ->
            return preparedImageFor(path)
        }
    }

    return null
}

private fun preparedImageFor(path: String): PreparedFlashImage {
    val normalized = normalizePath(path)
    val isInitBoot = fileNameOf(normalized).equals("init_boot.img", ignoreCase = true)
    return PreparedFlashImage(
        imagePath = normalized,
        partition = if (isInitBoot) "init_boot" else "boot",
        description = if (isInitBoot) "原始 init_boot 镜像" else "原始 boot 镜像",
    )
}

private fun locateMagiskApk(selectedVersion: String?): String? {
    if (!selectedVersion.isNullOrBlank() && selectedVersion != "auto" && fileExists(selectedVersion)) {
        return normalizePath(selectedVersion)
    }
    return resolveFirstExistingPath(listOf(".\\Magisk.apk", ".\\META-INF\\Magisk.apk")) ?: findFirstFileByName("Magisk.apk")
}

private fun kernelSuLkmFlowConfig(managerId: String?): KernelSuLkmFlowConfig =
    when (managerId) {
        "sukisu-ultra" -> KernelSuLkmFlowConfig(
            id = "sukisu-ultra",
            title = "SukiSU-Ultra",
            patchedGlob = "new-boot.img",
            uninstallPackages = listOf("com.rifsxd.ksunext", "me.weishu.kernelsu", "com.sukisu.ultra"),
            soMap = listOf(
                "assets/kptools" to "kpm-tools/kptools",
                "assets/kpimg" to "kpm-tools/kpimg",
                "lib/arm64-v8a/libzakoboot.so" to "kpm-tools/zakoboot.so",
            ),
        )
        "resukisu" -> KernelSuLkmFlowConfig(
            id = "resukisu",
            title = "ResukiSU",
            patchedGlob = "kernelsu_next_patched_*.img",
            uninstallPackages = listOf("com.rifsxd.ksunext"),
            soMap = listOf(
                "lib/arm64-v8a/libandroidx.graphics.path.so" to "androidx.graphics.path",
                "lib/arm64-v8a/libkernelsu.so" to "kernelsu",
                "lib/arm64-v8a/libksud.so" to "ksud",
                "lib/arm64-v8a/libmagiskboot.so" to "magiskboot",
            ),
        )
        "kernelsu-next" -> KernelSuLkmFlowConfig(
            id = "kernelsu-next",
            title = "KernelSU-Next",
            patchedGlob = "kernelsu_next_patched_*.img",
            uninstallPackages = listOf("com.rifsxd.ksunext"),
            soMap = listOf(
                "lib/arm64-v8a/libandroidx.graphics.path.so" to "androidx.graphics.path",
                "lib/arm64-v8a/libkernelsu.so" to "kernelsu",
                "lib/arm64-v8a/libksud.so" to "ksud",
                "lib/arm64-v8a/libmagiskboot.so" to "magiskboot",
            ),
        )
        else -> KernelSuLkmFlowConfig(
            id = "kernelsu-official",
            title = "KernelSU-Official",
            patchedGlob = "kernelsu_patched_*.img",
            uninstallPackages = listOf("com.rifsxd.ksunext", "me.weishu.kernelsu", "com.sukisu.ultra"),
            soMap = listOf(
                "lib/arm64-v8a/libandroidx.graphics.path.so" to "androidx.graphics.path",
                "lib/arm64-v8a/libkernelsu.so" to "kernelsu",
                "lib/arm64-v8a/libksud.so" to "ksud",
                "lib/arm64-v8a/libmagiskboot.so" to "magiskboot",
            ),
        )
    }

private fun runSukiSuUltraBootPatch(
    serial: String,
    remoteToolDir: String,
    remotePatchDir: String,
    remoteBootPath: String,
    appendLog: (String) -> Unit,
): String? {
    /*
    if (!runAdbShell(
            serial,
            "chmod a+rx ${quoteForPosixShell("$remoteToolDir/kptools")} ${quoteForPosixShell("$remoteToolDir/zakoboot.so")}",
            appendLog,
        )
    ) {
        appendLog("SukiSU-Ultra 宸ュ叿璁剧疆鏉冮檺澶辫触")
        return null
    }
    if (!runAdbShell(
            serial,
            "cd ${quoteForPosixShell(remotePatchDir)} && /system/bin/linker64 ${quoteForPosixShell("$remoteToolDir/zakoboot.so")} unpack ${quoteForPosixShell(remoteBootPath)}",
            appendLog,
        )
    ) {
        appendLog("SukiSU-Ultra boot 瑙ｅ寘澶辫触")
        return null
    }
    if (!runAdbShell(
            serial,
            "cd ${quoteForPosixShell(remotePatchDir)} && ${quoteForPosixShell("$remoteToolDir/kptools")} -p -s 123 -i kernel -k ${quoteForPosixShell("$remoteToolDir/kpimg")} -o oImage && mv oImage kernel",
            appendLog,
        )
    ) {
        appendLog("SukiSU-Ultra kernel 淇ˉ澶辫触")
        return null
    }
    if (!runAdbShell(
            serial,
            "cd ${quoteForPosixShell(remotePatchDir)} && /system/bin/linker64 ${quoteForPosixShell("$remoteToolDir/zakoboot.so")} repack ${quoteForPosixShell(remoteBootPath)} new-boot.img",
            appendLog,
        )
    ) {
        appendLog("SukiSU-Ultra boot 鍥炲皝澶辫触")
        return null
    }

    val patchedPath = "$remotePatchDir/new-boot.img"
    return if (remoteFileExists(serial, patchedPath, appendLog)) patchedPath else null
    */
    if (!runAdbShell(
            serial,
            "chmod a+rx ${quoteForPosixShell("$remoteToolDir/kptools")} ${quoteForPosixShell("$remoteToolDir/zakoboot.so")}",
            appendLog,
        )
    ) {
        appendLog("SukiSU-Ultra tool permission setup failed")
        return null
    }
    if (!runAdbShell(
            serial,
            "cd ${quoteForPosixShell(remotePatchDir)} && /system/bin/linker64 ${quoteForPosixShell("$remoteToolDir/zakoboot.so")} unpack ${quoteForPosixShell(remoteBootPath)}",
            appendLog,
        )
    ) {
        appendLog("SukiSU-Ultra boot unpack failed")
        return null
    }
    if (!runAdbShell(
            serial,
            "cd ${quoteForPosixShell(remotePatchDir)} && ${quoteForPosixShell("$remoteToolDir/kptools")} -p -s 123 -i kernel -k ${quoteForPosixShell("$remoteToolDir/kpimg")} -o oImage && mv oImage kernel",
            appendLog,
        )
    ) {
        appendLog("SukiSU-Ultra kernel patch failed")
        return null
    }
    if (!runAdbShell(
            serial,
            "cd ${quoteForPosixShell(remotePatchDir)} && /system/bin/linker64 ${quoteForPosixShell("$remoteToolDir/zakoboot.so")} repack ${quoteForPosixShell(remoteBootPath)} new-boot.img",
            appendLog,
        )
    ) {
        appendLog("SukiSU-Ultra boot repack failed")
        return null
    }

    val patchedPath = "$remotePatchDir/new-boot.img"
    return if (remoteFileExists(serial, patchedPath, appendLog)) patchedPath else null
}

private fun findFirstFileByName(fileName: String): String? {
    val outputPath = resolveTempPath(".\\__winflasher_find_${Random.nextInt(100000, 999999)}.txt")
    val script = """
        Set-Location -LiteralPath '${escapeForPowerShell(".")}'
        ${'$'}match = Get-ChildItem -LiteralPath . -Recurse -File -Filter '${escapeForPowerShell(fileName)}' -ErrorAction SilentlyContinue |
            Sort-Object FullName |
            Select-Object -First 1 -ExpandProperty FullName
        if (${'$'}match) {
            [System.IO.File]::WriteAllText('${escapeForPowerShell(outputPath)}', ${'$'}match, [System.Text.UTF8Encoding]::new(${'$'}false))
        }
    """.trimIndent()
    runPowerShellScript(script)
    val result = readTextLines(outputPath).firstOrNull()?.trim().orEmpty()
    remove(outputPath)
    return result.takeIf(::fileExists)
}

private data class AdbDeviceState(
    val serial: String,
    val state: String,
)

private fun resolveAdbSerial(preferredSerial: String, appendLog: (String) -> Unit): String? {
    val deviceStates = scanAdbDeviceStates(appendLog)
    val devices = deviceStates.filter { it.state.equals("device", ignoreCase = true) }.map { it.serial }
    if (devices.isEmpty()) return null
    if (preferredSerial.isNotBlank() && preferredSerial in devices) return preferredSerial
    return devices.firstOrNull()?.also {
        if (preferredSerial.isNotBlank() && it != preferredSerial) {
            appendLog("已切换到 ADB 设备 $it")
        }
    }
}

private fun scanAdbDevices(appendLog: (String) -> Unit): List<String> =
    scanAdbDeviceStates(appendLog)
        .filter { it.state.equals("device", ignoreCase = true) }
        .map { it.serial }

private fun scanAdbDeviceStates(appendLog: (String) -> Unit): List<AdbDeviceState> {
    val adbPath = resolveFirstExistingPath(listOf(".\\META-INF\\adb.exe")) ?: "adb"
    val result = runCommand(adbPath, listOf("devices"), appendLog = appendLog)
    appendAdbAuthorizationHintIfNeeded(result.outputLines, appendLog)
    return result.outputLines.mapNotNull(::parseAdbDeviceStateLine)
}

private fun parseAdbDeviceStateLine(line: String): AdbDeviceState? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("List of devices")) return null
    val parts = trimmed.split(Regex("\\s+"), limit = 2)
    if (parts.size < 2) return null
    return AdbDeviceState(serial = parts[0], state = parts[1])
}

private fun runAdb(
    serial: String,
    args: List<String>,
    appendLog: (String) -> Unit,
): CommandResult {
    val adbPath = resolveFirstExistingPath(listOf(".\\META-INF\\adb.exe")) ?: "adb"
    val result = runCommand(
        executable = adbPath,
        args = buildList {
            if (serial.isNotBlank()) {
                add("-s")
                add(serial)
            }
            addAll(args)
        },
        preservePosixAfterShell = true,
        appendLog = appendLog,
    )
    appendAdbAuthorizationHintIfNeeded(result.outputLines, appendLog)
    return result
}

private fun scanAdbDeviceStatesQuiet(): List<AdbDeviceState> {
    val adbPath = resolveFirstExistingPath(listOf(".\\META-INF\\adb.exe")) ?: "adb"
    val result = runCommand(adbPath, listOf("devices"), appendLog = null)
    return result.outputLines.mapNotNull(::parseAdbDeviceStateLine)
}

private fun choosePreferredAdbSerial(
    preferredSerial: String,
    deviceStates: List<AdbDeviceState>,
): String? {
    val devices = deviceStates.filter { it.state.equals("device", ignoreCase = true) }.map { it.serial }
    if (devices.isEmpty()) return null
    if (preferredSerial.isNotBlank() && preferredSerial in devices) return preferredSerial
    return devices.firstOrNull()
}

private fun readAdbPropertyQuiet(
    serial: String,
    propertyName: String,
): String {
    val result = runAdb(serial, listOf("shell", "getprop", propertyName), {})
    return result.outputLines.joinToString("\n").trim()
}

private fun appendAdbAuthorizationHintIfNeeded(
    outputLines: List<String>,
    appendLog: (String) -> Unit,
) {
    val unauthorized = outputLines.any { line ->
        val trimmed = line.trim()
        trimmed.contains("unauthorized", ignoreCase = true) &&
            !trimmed.startsWith("* daemon", ignoreCase = true)
    }
    if (unauthorized) {
        appendLog("ADB 未授权，请在手机上允许这台电脑")
    }
}

private fun runAdbShell(
    serial: String,
    script: String,
    appendLog: (String) -> Unit,
): Boolean = runAdbShellCommand(serial, script, appendLog).exitCode == 0

private fun runAdbShellCommand(
    serial: String,
    script: String,
    appendLog: (String) -> Unit,
): CommandResult = runAdb(
    serial,
    listOf("shell", script),
    appendLog,
)

private fun runAdbPush(
    serial: String,
    localPath: String,
    remotePath: String,
    appendLog: (String) -> Unit,
): Boolean = runAdb(serial, listOf("push", localPath, remotePath), appendLog).exitCode == 0

private fun runAdbPull(
    serial: String,
    remotePath: String,
    localPath: String,
    appendLog: (String) -> Unit,
): Boolean = runAdb(serial, listOf("pull", remotePath, localPath), appendLog).exitCode == 0

private fun remoteFileExists(
    serial: String,
    remotePath: String,
    appendLog: (String) -> Unit,
): Boolean = runAdbShell(
    serial,
    "test -f ${quoteForPosixShell(remotePath)}",
    appendLog,
)

private fun quoteForPosixShell(value: String): String =
    "'${value.replace("'", "'\\''")}'"

private fun buildKernelSuLkmCleanupScript(tmpDir: String, remoteWorkDir: String): String {
    val paths = listOf(remoteWorkDir, "$tmpDir/root.apk", "$tmpDir/boot.img", "$tmpDir/hanfy")
    return "rm -rf ${paths.joinToString(" ") { quoteForPosixShell(it) }}"
}

private fun runKernelSuBootPatch(
    serial: String,
    remoteWorkDir: String,
    remoteBootPath: String,
    appendLog: (String) -> Unit,
    kmi: String? = null,
): Boolean {
    val args = buildList {
        add(quoteForPosixShell("$remoteWorkDir/ksud"))
        add("boot-patch")
        add("-b")
        add(quoteForPosixShell(remoteBootPath))
        add("-o")
        add(quoteForPosixShell("$remoteWorkDir/"))
        add("--magiskboot")
        add(quoteForPosixShell("$remoteWorkDir/magiskboot"))
        if (!kmi.isNullOrBlank()) {
            add("--kmi")
            add(quoteForPosixShell(kmi))
        }
    }
    return runAdbShell(serial, args.joinToString(" "), appendLog)
}

private fun moveRemoteFileIfExists(
    serial: String,
    sourcePath: String,
    targetPath: String,
    appendLog: (String) -> Unit,
) {
    runAdbShell(
        serial,
        "if [ -f ${quoteForPosixShell(sourcePath)} ]; then mv ${quoteForPosixShell(sourcePath)} ${quoteForPosixShell(targetPath)}; fi",
        appendLog,
    )
}

private fun findRemoteKernelSuPatchedImage(
    serial: String,
    remoteWorkDir: String,
    patchedGlob: String,
    appendLog: (String) -> Unit,
): String? {
    val result = runAdbShellCommand(
        serial,
        "find ${quoteForPosixShell(remoteWorkDir)} -maxdepth 1 -type f -name ${quoteForPosixShell(patchedGlob)}",
        appendLog,
    )
    return result.outputLines.firstOrNull { it.isNotBlank() }?.trim()
}

private fun buildKmiStringForKernelSu(
    serial: String,
    appendLog: (String) -> Unit,
): String? {
    val apiResult = runAdb(serial, listOf("shell", "getprop", "ro.build.version.sdk"), appendLog)
    val kernelResult = runAdb(serial, listOf("shell", "uname", "-r"), appendLog)
    val api = apiResult.outputLines.firstOrNull()?.trim().orEmpty()
    val androidVer = when (api) {
        "32" -> "android12"
        "33" -> "android13"
        "34" -> "android14"
        "35" -> "android15"
        "36" -> "android16"
        else -> return null
    }
    val kernelFull = kernelResult.outputLines.firstOrNull()?.trim().orEmpty()
    val dotParts = kernelFull.split('.')
    if (dotParts.size < 2) return null
    return "$androidVer-${dotParts[0]}.${dotParts[1]}"
}

private fun resolveFirstExistingPath(candidates: List<String>): String? =
    candidates
        .flatMap(::resolveCandidatePaths)
        .map(::normalizePath)
        .distinct()
        .firstOrNull(::fileExists)

private fun createWorkDirectory(prefix: String): String? {
    val baseDir = resolveTempPath(".\\WinFlasherWork")
    if (!ensureDirectory(baseDir)) return null
    repeat(8) {
        val candidate = "$baseDir\\${prefix}_${Random.nextInt(100000, 999999)}"
        if (ensureDirectory(candidate)) return candidate
    }
    return null
}

private fun ensureDirectory(path: String): Boolean {
    val normalized = normalizePath(path)
    if (directoryExists(normalized)) return true
    val script = """
        [System.IO.Directory]::CreateDirectory('${escapeForPowerShell(normalized)}') | Out-Null
    """.trimIndent()
    return runPowerShellScript(script) == 0 && directoryExists(normalized)
}

private fun copyFile(source: String, target: String): Boolean {
    val normalizedSource = normalizePath(source)
    val normalizedTarget = normalizePath(target)
    val script = """
        Copy-Item -LiteralPath '${escapeForPowerShell(normalizedSource)}' -Destination '${escapeForPowerShell(normalizedTarget)}' -Force
    """.trimIndent()
    return runPowerShellScript(script) == 0 && fileExists(normalizedTarget)
}

private fun runCommand(
    executable: String,
    args: List<String>,
    workDir: String? = null,
    preservePosixAfterShell: Boolean = false,
    appendLog: ((String) -> Unit)? = null,
): CommandResult {
    val normalizedWorkDir = normalizePath(workDir ?: ".")
    val executionExecutable = toExecutionPath(executable, normalizedWorkDir)
    val executionArgs = prepareExecutionArgs(
        args = args,
        baseDir = normalizedWorkDir,
        preservePosixAfterShell = preservePosixAfterShell,
    )
    val displayCommandText = buildDisplayCommandText(executionExecutable, executionArgs)
    appendLog?.invoke("执行 $displayCommandText")

    val scriptPath = resolveTempPath(".\\__winflasher_exec_${Random.nextInt(100000, 999999)}.ps1")
    val script = """
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
        Set-Location -LiteralPath '${escapeForPowerShell(normalizedWorkDir)}'
        ${'$'}exe = '${escapeForPowerShell(executionExecutable)}'
        ${'$'}args = @(${buildPowerShellArrayLiteral(executionArgs)})
        & ${'$'}exe @args 2>&1 | ForEach-Object {
            ${'$'}text = if (${'$'}_ -is [System.Management.Automation.ErrorRecord]) {
                ${'$'}_.ToString()
            } else {
                [string]${'$'}_
            }
            if (-not [string]::IsNullOrWhiteSpace(${'$'}text)) {
                [Console]::Out.WriteLine(${'$'}text)
                [Console]::Out.Flush()
            }
        }
        exit ${'$'}LASTEXITCODE
    """.trimIndent()

    if (!writeUtf8BomFile(scriptPath, script)) {
        appendLog?.invoke("无法创建命令代理脚本")
        return CommandResult(exitCode = -1, commandText = displayCommandText, outputLines = emptyList())
    }

    val commandResult = runStreamingShellCommand(
        command = "powershell -NoProfile -ExecutionPolicy Bypass -File ${quoteForCmd(scriptPath)}",
        workingDirectory = normalizedWorkDir,
        onLine = appendLog,
    )
    remove(scriptPath)
    return CommandResult(
        exitCode = commandResult.exitCode,
        commandText = displayCommandText,
        outputLines = commandResult.outputLines,
    )
}

private fun buildPowerShellArrayLiteral(args: List<String>): String =
    args.joinToString(", ") { "'${escapeForPowerShell(it)}'" }

private fun prepareExecutionArgs(
    args: List<String>,
    baseDir: String,
    preservePosixAfterShell: Boolean,
): List<String> {
    var shellSeen = false
    val adbCommandIndex = when {
        args.size >= 3 && args[0] == "-s" -> 2
        else -> 0
    }
    val adbCommand = args.getOrNull(adbCommandIndex)

    return args.mapIndexed { index, arg ->
        val preservePosixPath = preservePosixAfterShell && (
            shellSeen ||
                (adbCommand == "push" && index == args.lastIndex) ||
                (adbCommand == "pull" && index == adbCommandIndex + 1)
            )

        if (preservePosixPath) {
            arg.replace('\\', '/')
        } else {
            if (preservePosixAfterShell && arg == "shell") {
                shellSeen = true
            }
            toExecutionPath(arg, baseDir)
        }
    }
}

private fun toExecutionPath(value: String, baseDir: String): String {
    val normalized = value.replace('/', '\\')
    val equalsIndex = normalized.indexOf('=')
    if (equalsIndex > 0 && equalsIndex < normalized.lastIndex) {
        val prefix = normalized.substring(0, equalsIndex + 1)
        val suffix = normalized.substring(equalsIndex + 1)
        if (isAbsoluteWindowsStylePath(suffix)) {
            return prefix + relativizeWindowsPath(suffix, baseDir)
        }
    }
    return if (isAbsoluteWindowsStylePath(normalized)) {
        relativizeWindowsPath(normalized, baseDir)
    } else {
        normalized
    }
}

private fun relativizeWindowsPath(targetPath: String, baseDir: String): String {
    val normalizedTarget = targetPath.replace('/', '\\').trimEnd('\\')
    val normalizedBase = baseDir.replace('/', '\\').trimEnd('\\')
    if (!isAbsoluteWindowsStylePath(normalizedTarget) || !isAbsoluteWindowsStylePath(normalizedBase)) {
        return normalizedTarget
    }

    val targetDrive = normalizedTarget.substring(0, 2)
    val baseDrive = normalizedBase.substring(0, 2)
    if (!targetDrive.equals(baseDrive, ignoreCase = true)) {
        return normalizedTarget
    }

    val targetParts = normalizedTarget.substring(3).split('\\').filter { it.isNotEmpty() }
    val baseParts = normalizedBase.substring(3).split('\\').filter { it.isNotEmpty() }

    var shared = 0
    while (
        shared < targetParts.size &&
        shared < baseParts.size &&
        targetParts[shared].equals(baseParts[shared], ignoreCase = true)
    ) {
        shared++
    }

    val upward = List(baseParts.size - shared) { ".." }
    val downward = targetParts.drop(shared)
    val relativeParts = upward + downward
    return when {
        relativeParts.isEmpty() -> "."
        relativeParts.firstOrNull() == ".." -> relativeParts.joinToString("\\")
        else -> ".\\" + relativeParts.joinToString("\\")
    }
}

private fun buildCommandText(executable: String, args: List<String>): String =
    buildList {
        add(quoteForShell(normalizePath(executable)))
        addAll(args.map(::quoteForShell))
    }.joinToString(" ")

private fun buildDisplayCommandText(executable: String, args: List<String>): String =
    buildList {
        add(quoteForShell(displayPathForLog(executable)))
        addAll(args.map { quoteForShell(displayPathForLog(it)) })
    }.joinToString(" ")

private fun displayPathForLog(value: String): String {
    val normalized = value.replace('/', '\\')
    if (!isAbsoluteWindowsStylePath(normalized)) {
        return if (value.contains('/')) value else normalized
    }

    val runtimeBase = resolveRuntimePath(".").trimEnd('\\')
    val runtimeTemp = resolveTempPath("").trimEnd('\\')

    return when {
        normalized.equals(runtimeBase, ignoreCase = true) -> "."
        normalized.startsWith("$runtimeBase\\", ignoreCase = true) ->
            ".\\" + normalized.substring(runtimeBase.length + 1)
        normalized.equals(runtimeTemp, ignoreCase = true) -> "%TEMP%\\WinFlasher"
        normalized.startsWith("$runtimeTemp\\", ignoreCase = true) ->
            "%TEMP%\\WinFlasher\\" + normalized.substring(runtimeTemp.length + 1)
        else -> normalized
    }
}

private fun isAbsoluteWindowsStylePath(path: String): Boolean =
    path.length >= 3 && path[1] == ':' && (path[2] == '\\' || path[2] == '/')

private fun quoteForShell(value: String): String {
    if (value.isEmpty()) return "\"\""
    val shouldQuote = value.any { it.isWhitespace() || it == '"' || it == '&' || it == '(' || it == ')' }
    return if (!shouldQuote) value else "\"${value.replace("\"", "\\\"")}\""
}

private fun fileNameOf(path: String): String =
    normalizePath(path).substringAfterLast('\\')

private fun normalizePath(path: String): String =
    resolveRuntimePath(path)

private fun quoteForCmd(value: String): String =
    "\"${value.replace("\"", "\"\"")}\""

@OptIn(ExperimentalForeignApi::class)
private fun directoryExists(path: String): Boolean {
    val outputPath = resolveTempPath(".\\__winflasher_dir_${Random.nextInt(100000, 999999)}.txt")
    val script = """
        if ([System.IO.Directory]::Exists('${escapeForPowerShell(normalizePath(path))}')) {
            [System.IO.File]::WriteAllText('${escapeForPowerShell(outputPath)}', '1', [System.Text.UTF8Encoding]::new(${'$'}false))
        }
    """.trimIndent()
    runPowerShellScript(script)
    val exists = readTextLines(outputPath).firstOrNull()?.trim() == "1"
    remove(outputPath)
    return exists
}

@OptIn(ExperimentalForeignApi::class)
private fun fileExists(path: String): Boolean {
    return fileExistsUnicodeSafe(normalizePath(path))
}

@OptIn(ExperimentalForeignApi::class)
private fun writeUtf8BomFile(path: String, content: String): Boolean {
    return writeUtf8BomFileUnicodeSafe(normalizePath(path), content)
}

@OptIn(ExperimentalForeignApi::class)
private fun readTextLines(path: String): List<String> {
    if (!fileExists(path)) return emptyList()
    val bytes = readAllBytes(path)
    if (bytes.isEmpty()) return emptyList()
    val text = bytes.decodeAsUtf8()
        .removePrefix("\uFEFF")
        .replace("\r\n", "\n")
        .replace('\r', '\n')
    return text.split('\n')
}

@OptIn(ExperimentalForeignApi::class)
private fun readAllBytes(path: String): ByteArray {
    return readAllBytesUnicodeSafe(normalizePath(path))
}

private fun ByteArray.decodeAsUtf8(): String {
    if (isEmpty()) return ""
    return decodeToString()
}

private fun escapeForPowerShell(value: String): String =
    value.replace("'", "''")

private fun runPowerShellScript(script: String): Int {
    val scriptPath = resolveTempPath(".\\__winflasher_ps_${Random.nextInt(100000, 999999)}.ps1")
    if (!writeUtf8BomFile(scriptPath, script)) return -1
    val exitCode = runStreamingShellCommand(
        "powershell -NoProfile -ExecutionPolicy Bypass -File ${quoteForCmd(scriptPath)}",
    ).exitCode
    remove(scriptPath)
    return exitCode
}
