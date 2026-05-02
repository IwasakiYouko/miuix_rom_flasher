import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.LoadedFont
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.rewind
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.SpinnerEntry
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.isRenderEffectSupported
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.blur.layerBackdrop
import top.yukonga.miuix.kmp.blur.rememberLayerBackdrop
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Download
import top.yukonga.miuix.kmp.icon.extended.SearchDevice
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.icon.extended.Tasks
import top.yukonga.miuix.kmp.preference.CheckboxLocation
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.preference.WindowSpinnerPreference
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.TextStyles
import top.yukonga.miuix.kmp.theme.defaultTextStyles

private val disguiseRelockSupportedDevices = setOf(
    "pudding", "pandora", "popsicle", "nezha", "myron",
)

enum class SampleThemeMode { System, Light, Dark, MonetSystem, MonetLight, MonetDark }
enum class WorkPage { Flash, Devices, Settings }
enum class NavigationLabelMode { IconOnly, IconAndLabel }
enum class FlashMode { KeepData, CleanData }
enum class RootMode { MagiskPatch, KernelSuLkm, HfTeamGki, FolkPatch, KeepOriginal }

val SampleThemeMode.title: String
    get() = when (this) {
        SampleThemeMode.System -> "跟随系统"
        SampleThemeMode.Light -> "浅色"
        SampleThemeMode.Dark -> "深色"
        SampleThemeMode.MonetSystem -> "动态跟随系统"
        SampleThemeMode.MonetLight -> "动态浅色"
        SampleThemeMode.MonetDark -> "动态深色"
    }

val FlashMode.title: String
    get() = if (this == FlashMode.KeepData) "保留数据刷写" else "清除数据刷写"

val RootMode.title: String
    get() = when (this) {
        RootMode.MagiskPatch -> "使用 Magisk 修补"
        RootMode.KernelSuLkm -> "使用 KernelSU-LKM"
        RootMode.HfTeamGki -> "使用 HF-Team-GKI 内核"
        RootMode.FolkPatch -> "使用 FolkPatch 修补"
        RootMode.KeepOriginal -> "保留系统原始状态"
    }

private fun buildPreviewPartitionProgress(
    flashMode: FlashMode,
    rootMode: RootMode,
    rootDebugMode: Boolean,
    autoReboot: Boolean,
    firmwareImageInfo: FirmwareImageInfo,
    disguiseRelock: Boolean = false,
): FlashPartitionProgress {
    val stages = buildList {
        val imagePartitions = firmwareImageInfo.partitionNames
            .let { partitions ->
                if (rootDebugMode && rootMode == RootMode.KernelSuLkm) {
                    partitions.filterNot { it.equals("super", ignoreCase = true) }
                } else {
                    partitions
                }
            }
            .let { partitions ->
                val (superStages, otherStages) = partitions.partition { it.equals("super", ignoreCase = true) }
                otherStages + superStages
            }
        if (imagePartitions.isNotEmpty()) {
            addAll(imagePartitions)
        } else {
            add("等待镜像")
        }
        if (disguiseRelock) {
            if (none { it.equals("abl", ignoreCase = true) }) add("abl")
            if (none { it.equals("efisp", ignoreCase = true) }) add("efisp")
        }
        if (flashMode == FlashMode.CleanData) add("userdata")
        if (rootDebugMode && rootMode == RootMode.KernelSuLkm) add("ROOT")
        if (autoReboot) add("reboot")
    }

    val preferredStage = when (rootMode) {
        RootMode.FolkPatch -> "boot"
        RootMode.MagiskPatch -> if (firmwareImageInfo.hasInitBootImage) "init_boot" else "boot"
        RootMode.KernelSuLkm -> if (firmwareImageInfo.hasInitBootImage) "init_boot" else "boot"
        RootMode.KeepOriginal -> if (firmwareImageInfo.hasInitBootImage) "init_boot" else "boot"
        RootMode.HfTeamGki -> "boot"
    }.let { preferred ->
        if (flashMode == FlashMode.CleanData) listOf("userdata", preferred) else listOf(preferred)
    }

    val currentIndex = preferredStage
        .mapNotNull(stages::indexOf)
        .firstOrNull { it >= 0 }
        ?: (stages.size / 2)

    return FlashPartitionProgress(
        stages = stages,
        currentIndex = currentIndex,
        completedCount = 0,
    )
}

fun main() = application {
    Window(
        title = "WinFlasher",
        size = DpSize(1280.dp, 820.dp),
    ) {
        SampleApp()
    }
}

@OptIn(ExperimentalTextApi::class)
private fun loadMiSansFontFamilyOrNull(): FontFamily? {
    val fontPath = listOf(
        resolveRuntimeMetaInfPath(".\\composeResources\\winflasher.resources\\font\\misans_regular.ttf"),
        resolveRuntimeBinPath(".\\composeResources\\winflasher.resources\\font\\misans_regular.ttf"),
        resolveRuntimePath(".\\composeResources\\winflasher.resources\\font\\misans_regular.ttf"),
        ".\\app\\src\\commonMain\\composeResources\\font\\misans_regular.ttf",
    ).firstOrNull(::fontFileExists) ?: return null

    return runCatching {
        FontFamily(
            LoadedFont(
                identity = "MiSans",
                weight = FontWeight.Normal,
                style = FontStyle.Normal,
                getData = { readFontFileBytes(fontPath) },
            ),
        )
    }.getOrNull()
}

private fun buildMiSansTextStyles(fontFamily: FontFamily): TextStyles {
    val defaults = defaultTextStyles()
    fun TextStyle.withMiSans() = copy(fontFamily = fontFamily)
    return defaults.copy(
        main = defaults.main.withMiSans(),
        paragraph = defaults.paragraph.withMiSans(),
        body1 = defaults.body1.withMiSans(),
        body2 = defaults.body2.withMiSans(),
        button = defaults.button.withMiSans(),
        footnote1 = defaults.footnote1.withMiSans(),
        footnote2 = defaults.footnote2.withMiSans(),
        headline1 = defaults.headline1.withMiSans(),
        headline2 = defaults.headline2.withMiSans(),
        subtitle = defaults.subtitle.withMiSans(),
        title1 = defaults.title1.withMiSans(),
        title2 = defaults.title2.withMiSans(),
        title3 = defaults.title3.withMiSans(),
        title4 = defaults.title4.withMiSans(),
    )
}

@Composable
private fun SampleApp() {
    var currentPage by remember { mutableStateOf(WorkPage.Flash) }
    var themeMode by remember { mutableStateOf(SampleThemeMode.Light) }
    var navigationLabelMode by remember { mutableStateOf(NavigationLabelMode.IconAndLabel) }
    var keepWindowOnTop by remember { mutableStateOf(true) }
    var verifyPackage by remember { mutableStateOf(true) }
    var autoExportLogs by remember { mutableStateOf(false) }
    var debugMode by remember { mutableStateOf(false) }
    var rootDebugMode by remember { mutableStateOf(false) }
    var flashMode by remember { mutableStateOf(FlashMode.KeepData) }
    var rootMode by remember { mutableStateOf(RootMode.KeepOriginal) }
    var magiskManagerVersion by remember {
        mutableStateOf(rootManagerOptionsFor(RootMode.MagiskPatch).firstOrNull()?.id ?: "auto")
    }
    var folkPatchManagerVersion by remember {
        mutableStateOf(rootManagerOptionsFor(RootMode.FolkPatch).firstOrNull()?.id ?: "0.13.0")
    }
    var kernelSuLkmManagerVersion by remember {
        mutableStateOf("kernelsu-official")
    }
    var hfTeamGkiManagerVersion by remember {
        mutableStateOf(rootManagerOptionsFor(RootMode.HfTeamGki).firstOrNull()?.id ?: "default")
    }
    var autoReboot by remember { mutableStateOf(true) }
    var disguiseRelock by remember { mutableStateOf(false) }
    var fastbootDevices by remember { mutableStateOf(emptyList<FastbootDevice>()) }
    var selectedDeviceIndex by remember { mutableIntStateOf(0) }
    var isScanningDevices by remember { mutableStateOf(false) }
    var isFlashing by remember { mutableStateOf(false) }
    var isUpdatingPlatformTools by remember { mutableStateOf(false) }
    var platformToolsProgress by remember { mutableStateOf<Float?>(null) }
    var platformToolsProgressText by remember { mutableStateOf("未安装") }
    val romPackageInfo = remember { loadRomPackageInfo() }
    val firmwareImageInfo = remember { loadFirmwareImageInfo() }
    var platformToolsState by remember { mutableStateOf(readPlatformToolsState()) }
    var resolvedDeviceDisplayName by remember(romPackageInfo.deviceName) {
        mutableStateOf(romPackageInfo.deviceName)
    }
    val previewPartitionProgress = remember(
        flashMode,
        rootMode,
        rootDebugMode,
        autoReboot,
        firmwareImageInfo,
        disguiseRelock,
    ) {
        buildPreviewPartitionProgress(
            flashMode = flashMode,
            rootMode = rootMode,
            rootDebugMode = rootDebugMode,
            autoReboot = autoReboot,
            firmwareImageInfo = firmwareImageInfo,
            disguiseRelock = disguiseRelock,
        )
    }
    val rootManagerOptions = remember(rootMode) { rootManagerOptionsFor(rootMode) }
    val rootManagerVersion = when (rootMode) {
        RootMode.MagiskPatch -> magiskManagerVersion
        RootMode.KernelSuLkm -> kernelSuLkmManagerVersion
        RootMode.FolkPatch -> folkPatchManagerVersion
        RootMode.HfTeamGki -> hfTeamGkiManagerVersion
        RootMode.KeepOriginal -> null
    }
    val logLines = remember { mutableStateListOf<String>() }
    var logByteCount by remember { mutableIntStateOf(0) }
    var logSnapshotRevision by remember { mutableIntStateOf(0) }
    var partitionProgress by remember { mutableStateOf(previewPartitionProgress) }
    var extractionProgress by remember { mutableStateOf(ZstdExtractionProgress()) }
    var waitProgress by remember { mutableStateOf(WaitProgress()) }
    var managerDownloadProgress by remember { mutableStateOf(ManagerDownloadProgress()) }
    val uiScope = rememberCoroutineScope()

    fun appendLog(message: String) {
        val normalizedLines = message
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .ifEmpty { listOf(message) }

        normalizedLines.forEach { line ->
            logLines += line
            logByteCount += line.encodeToByteArray().size + 2
        }

        while (logByteCount > 30 * 1024 * 1024 && logLines.isNotEmpty()) {
            val removed = logLines.removeAt(0)
            logByteCount -= removed.encodeToByteArray().size + 2
        }
        if (logByteCount < 0) logByteCount = 0
        logSnapshotRevision++
    }

    fun appendLogFromWorker(message: String) {
        uiScope.launch {
            appendLog(message)
        }
    }

    fun exportLogs() {
        val path = exportLogLines(logLines)
        appendLog(if (path != null) "日志已导出 $path" else "日志导出失败")
    }

    fun refreshDevices() {
        if (isScanningDevices) return
        isScanningDevices = true
        appendLog("正在扫描 Fastboot 设备")
        uiScope.launch {
            try {
                val result = withContext(Dispatchers.Default) {
                    scanFastbootDevices()
                }
                fastbootDevices = result.devices
                selectedDeviceIndex = when {
                    result.devices.isEmpty() -> 0
                    selectedDeviceIndex in result.devices.indices -> selectedDeviceIndex
                    else -> 0
                }
                appendLog("执行 ${result.commandText}")
                if (result.devices.isEmpty()) {
                    if (result.outputLines.isEmpty()) appendLog("未发现可用设备")
                    result.outputLines.filter { it.isNotBlank() }.forEach(::appendLog)
                } else {
                    result.devices.forEach { appendLog("发现设备 ${it.serial} ${it.state}") }
                }
            } finally {
                isScanningDevices = false
            }
        }
    }

    fun startFlash() {
        if (isFlashing) return
        val selectedDevice = fastbootDevices.getOrNull(selectedDeviceIndex)
        if (selectedDevice == null && !debugMode) {
            appendLog("未选择目标设备")
            return
        }
        appendLog(if (debugMode) "开始执行 Debug 模式刷机任务" else "开始执行真实刷机任务")
        isFlashing = true
        partitionProgress = previewPartitionProgress
        extractionProgress = ZstdExtractionProgress()
        waitProgress = WaitProgress()
        managerDownloadProgress = ManagerDownloadProgress()
        uiScope.launch {
            try {
                withContext(Dispatchers.Default) {
                    if (debugMode) {
                        appendLogFromWorker("Debug 模式已开启，开始模拟刷机")
                        appendLogFromWorker("目标设备 DEBUG-SIMULATOR")
                        when (rootMode) {
                            RootMode.MagiskPatch -> {
                                appendLogFromWorker("开始模拟 Magisk 修补")
                                appendLogFromWorker("管理器版本 ${rootManagerOptions.firstOrNull { it.id == rootManagerVersion }?.title ?: "自动检测"}")
                                delay(500)
                                appendLogFromWorker("模拟解包 boot / init_boot")
                                delay(500)
                                appendLogFromWorker("模拟执行 Magisk 补丁")
                                delay(500)
                                appendLogFromWorker("模拟生成 Magisk 修补镜像")
                                delay(500)
                            }
                            RootMode.KernelSuLkm -> {
                                appendLogFromWorker("已选择 KernelSU-LKM 管理器 ${rootManagerOptions.firstOrNull { it.id == rootManagerVersion }?.title ?: "KernelSU-Official"}")
                            }
                            RootMode.FolkPatch -> {
                                appendLogFromWorker("开始模拟 FolkPatch 修补")
                                appendLogFromWorker("管理器版本 ${rootManagerOptions.firstOrNull { it.id == rootManagerVersion }?.title ?: "默认版本"}")
                                delay(500)
                                appendLogFromWorker("模拟解包 boot.img")
                                delay(500)
                                appendLogFromWorker("模拟执行 kernel 修补")
                                delay(500)
                                appendLogFromWorker("模拟回封 boot.img")
                                delay(500)
                            }
                            RootMode.HfTeamGki -> {
                                appendLogFromWorker("开始模拟 HF-Team-GKI 内核替换")
                                appendLogFromWorker("管理器版本 ${rootManagerOptions.firstOrNull { it.id == rootManagerVersion }?.title ?: "默认版本"}")
                                delay(500)
                                appendLogFromWorker("模拟载入 GKI 内核镜像")
                                delay(500)
                                appendLogFromWorker("模拟写入 boot 内核")
                                delay(500)
                            }
                            RootMode.KeepOriginal -> {
                                appendLogFromWorker("保留原始系统状态，跳过 ROOT 修补")
                                delay(500)
                            }
                        }
                        previewPartitionProgress.stages.forEachIndexed { index, stage ->
                            uiScope.launch {
                                partitionProgress = FlashPartitionProgress(
                                    stages = previewPartitionProgress.stages,
                                    currentIndex = index,
                                    completedCount = index,
                                )
                            }
                            appendLogFromWorker("模拟刷写分区 $stage")
                            delay(500)
                        }
                        if (rootMode == RootMode.KernelSuLkm) {
                            if (rootDebugMode) {
                                appendLogFromWorker("ROOT-debug 模式已开启，开始执行真实 LKM 修补")
                                val prepared = executeKernelSuLkmRootDebug(
                                    serial = selectedDevice?.serial.orEmpty(),
                                    rootManagerVersion = rootManagerVersion,
                                    appendLog = ::appendLogFromWorker,
                                    onWaitProgressUpdate = { progress ->
                                        uiScope.launch {
                                            waitProgress = progress
                                        }
                                    },
                                    onManagerDownloadProgressUpdate = { progress ->
                                        uiScope.launch {
                                            managerDownloadProgress = progress
                                        }
                                    },
                                )
                                if (prepared) {
                                    appendLogFromWorker("ROOT-debug 修补完成，可继续验证修补产物")
                                } else {
                                    appendLogFromWorker("ROOT-debug 修补失败")
                                }
                            } else {
                                appendLogFromWorker("开始模拟 LKM 后置 ROOT 流程")
                                appendLogFromWorker("LKM 类需要在刷入 ROM 后打开 USB 调试，并允许 USB 安装")
                                delay(500)
                                appendLogFromWorker("模拟推送 ROOT 管理器 APK")
                                delay(500)
                                appendLogFromWorker("模拟设备端 ksud boot-patch 修补")
                                delay(500)
                                appendLogFromWorker("模拟安装管理器并准备刷入 ramdisk")
                                delay(500)
                            }
                        }
                        uiScope.launch {
                            partitionProgress = FlashPartitionProgress(
                                stages = previewPartitionProgress.stages,
                                currentIndex = previewPartitionProgress.stages.lastIndex.coerceAtLeast(0),
                                completedCount = previewPartitionProgress.stages.size,
                            )
                        }
                        appendLogFromWorker("Debug 模式模拟完成")
                    } else {
                        executeFlashWorkflow(
                            serial = selectedDevice!!.serial,
                            flashMode = flashMode,
                            rootMode = rootMode,
                            rootManagerVersion = rootManagerVersion,
                            rootDebugMode = rootDebugMode,
                            autoReboot = autoReboot,
                            verifyPackage = verifyPackage,
                            romPackageInfo = romPackageInfo,
                            disguiseRelock = disguiseRelock,
                            appendLog = ::appendLogFromWorker,
                            onProgressUpdate = { progress ->
                                uiScope.launch {
                                    partitionProgress = progress
                                }
                            },
                            onExtractionProgressUpdate = { progress ->
                                uiScope.launch {
                                    extractionProgress = progress
                                }
                            },
                            onWaitProgressUpdate = { progress ->
                                uiScope.launch {
                                    waitProgress = progress
                                }
                            },
                            onManagerDownloadProgressUpdate = { progress ->
                                uiScope.launch {
                                    managerDownloadProgress = progress
                                }
                            },
                        )
                    }
                }
            } catch (t: Throwable) {
                appendLog("刷机流程异常 ${t.message ?: t::class.simpleName.orEmpty()}")
            } finally {
                isFlashing = false
                waitProgress = WaitProgress()
                managerDownloadProgress = ManagerDownloadProgress()
                if (!extractionProgress.isActive) {
                    extractionProgress = extractionProgress.copy(currentFile = extractionProgress.currentFile)
                }
            }
        }
    }

    val controller = remember(themeMode) {
        when (themeMode) {
            SampleThemeMode.System -> ThemeController(ColorSchemeMode.System)
            SampleThemeMode.Light -> ThemeController(ColorSchemeMode.Light)
            SampleThemeMode.Dark -> ThemeController(ColorSchemeMode.Dark)
            SampleThemeMode.MonetSystem -> ThemeController(ColorSchemeMode.MonetSystem, keyColor = Color(0xFFF3C6A6))
            SampleThemeMode.MonetLight -> ThemeController(ColorSchemeMode.MonetLight, keyColor = Color(0xFFF3C6A6))
            SampleThemeMode.MonetDark -> ThemeController(ColorSchemeMode.MonetDark, keyColor = Color(0xFFF3C6A6))
        }
    }

    fun updatePlatformTools(autoTriggered: Boolean = false) {
        if (isUpdatingPlatformTools) return
        isUpdatingPlatformTools = true
        platformToolsProgress = 0f
        platformToolsProgressText = if (autoTriggered) "准备自动加载" else "准备更新"
        if (autoTriggered) {
            appendLog("未检测到 platform-tools，开始自动加载")
        } else {
            appendLog("开始更新 platform-tools")
        }
        uiScope.launch {
            try {
                val success = withContext(Dispatchers.Default) {
                    installOrUpdatePlatformTools(
                        onProgress = { progress, status ->
                            uiScope.launch {
                                platformToolsProgress = progress
                                platformToolsProgressText = status
                            }
                        },
                        appendLog = ::appendLogFromWorker,
                    )
                }
                platformToolsState = readPlatformToolsState()
                appendLog(
                    when {
                        success -> "platform-tools 已更新到 META-INF"
                        autoTriggered -> "platform-tools 自动加载失败"
                        else -> "platform-tools 更新失败"
                    },
                )
            } finally {
                isUpdatingPlatformTools = false
                platformToolsProgress = null
                platformToolsProgressText = platformToolsState.statusText
            }
        }
    }
    val miSansFontFamily = remember { loadMiSansFontFamilyOrNull() }
    val textStyles = remember(miSansFontFamily) {
        miSansFontFamily?.let(::buildMiSansTextStyles) ?: defaultTextStyles()
    }

    val tabs = remember {
        listOf(
            LiquidBarTab(WorkPage.Flash, "刷写", MiuixIcons.Heavy.Download, MiuixIcons.Light.Download),
            LiquidBarTab(WorkPage.Settings, "设置", MiuixIcons.Settings, MiuixIcons.Light.Settings),
        )
    }

    LaunchedEffect(Unit) {
        if (logLines.isEmpty()) {
            appendLog("WinFlasher 已启动")
            refreshDevices()
        }
        platformToolsProgressText = platformToolsState.statusText
    }

    LaunchedEffect(autoExportLogs, logSnapshotRevision) {
        if (!autoExportLogs) return@LaunchedEffect
        delay(160)
        withContext(Dispatchers.Default) {
            writeAutoLogLines(logLines.toList())
        }
    }

    LaunchedEffect(romPackageInfo.deviceName) {
        resolvedDeviceDisplayName = withContext(Dispatchers.Default) {
            resolveRemoteDeviceDisplayName(romPackageInfo.deviceName)
        }
    }

    LaunchedEffect(rootMode, rootManagerOptions) {
        if (rootMode == RootMode.KeepOriginal || rootManagerOptions.isEmpty()) return@LaunchedEffect
        val selectedVersion = rootManagerVersion
        if (selectedVersion !in rootManagerOptions.map { it.id }) {
            val fallback = when (rootMode) {
                RootMode.KernelSuLkm -> rootManagerOptions.firstOrNull { it.id == "kernelsu-official" }?.id
                else -> null
            } ?: rootManagerOptions.first().id
            when (rootMode) {
                RootMode.MagiskPatch -> magiskManagerVersion = fallback
                RootMode.KernelSuLkm -> kernelSuLkmManagerVersion = fallback
                RootMode.FolkPatch -> folkPatchManagerVersion = fallback
                RootMode.HfTeamGki -> hfTeamGkiManagerVersion = fallback
                RootMode.KeepOriginal -> Unit
            }
        }
    }

    LaunchedEffect(Unit) {
        if (!platformToolsState.installed) {
            updatePlatformTools(autoTriggered = true)
        }
    }

    LaunchedEffect(previewPartitionProgress, isFlashing) {
        if (!isFlashing) {
            partitionProgress = previewPartitionProgress
        }
    }

    MiuixTheme(controller = controller, textStyles = textStyles) {
        val backdrop = rememberLayerBackdrop()
        val density = LocalDensity.current
        val windowInfo = LocalWindowInfo.current
        val isWideLayout = with(density) { windowInfo.containerSize.width.toDp() >= 1120.dp }
        val showNavigationLabel = navigationLabelMode == NavigationLabelMode.IconAndLabel
        val bottomBarPadding = if (showNavigationLabel) 112.dp else 96.dp

        Scaffold { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(
                        start = 20.dp,
                        top = 20.dp,
                        end = 20.dp,
                        bottom = 20.dp,
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .layerBackdrop(backdrop),
                ) {
                    Crossfade(targetState = currentPage, label = "workPage") { page ->
                        when (page) {
                              WorkPage.Flash -> FlashPage(
                                  isWideLayout = isWideLayout,
                                  bottomBarPadding = bottomBarPadding,
                                  romPackageInfo = romPackageInfo,
                                  resolvedDeviceDisplayName = resolvedDeviceDisplayName,
                                  firmwareImageInfo = firmwareImageInfo,
                                  flashMode = flashMode,
                                  onFlashModeChange = { flashMode = it },
                                  rootMode = rootMode,
                                  onRootModeChange = { rootMode = it },
                                  rootManagerVersion = rootManagerVersion,
                                  rootManagerOptions = rootManagerOptions,
                                  onRootManagerVersionChange = {
                                      when (rootMode) {
                                          RootMode.MagiskPatch -> magiskManagerVersion = it
                                          RootMode.KernelSuLkm -> kernelSuLkmManagerVersion = it
                                          RootMode.FolkPatch -> folkPatchManagerVersion = it
                                          RootMode.HfTeamGki -> hfTeamGkiManagerVersion = it
                                          RootMode.KeepOriginal -> Unit
                                      }
                                  },
                                  autoReboot = autoReboot,
                                  onAutoRebootChange = { autoReboot = it },
                                fastbootDevices = fastbootDevices,
                                selectedDeviceIndex = selectedDeviceIndex,
                                onSelectDeviceIndex = {
                                    selectedDeviceIndex = it
                                    fastbootDevices.getOrNull(it)?.let { device ->
                                        appendLog("已选定设备 ${device.serial}")
                                    }
                                },
                                isScanningDevices = isScanningDevices,
                                onRefreshDevices = ::refreshDevices,
                                  onStartFlash = ::startFlash,
                                  onClearLogs = {
                                      logLines.clear()
                                      logByteCount = 0
                                      logSnapshotRevision++
                                  },
                                  onExportLogs = ::exportLogs,
                                  logLines = logLines,
                                  partitionProgress = partitionProgress,
                                  extractionProgress = extractionProgress,
                                  waitProgress = waitProgress,
                                  managerDownloadProgress = managerDownloadProgress,
                                  rootDebugMode = rootDebugMode,
                                  isFlashing = isFlashing,
                                  disguiseRelock = disguiseRelock,
                                  onDisguiseRelockChange = { disguiseRelock = it },
                            )
                            WorkPage.Devices -> DevicesPage(
                                isWideLayout = isWideLayout,
                                bottomBarPadding = bottomBarPadding,
                                fastbootDevices = fastbootDevices,
                                selectedDeviceIndex = selectedDeviceIndex,
                                onSelectDeviceIndex = { selectedDeviceIndex = it },
                                isScanningDevices = isScanningDevices,
                                onRefreshDevices = ::refreshDevices,
                            )
                            WorkPage.Settings -> SettingsPage(
                                isWideLayout = isWideLayout,
                                bottomBarPadding = bottomBarPadding,
                                themeMode = themeMode,
                                onThemeModeChange = { themeMode = it },
                                navigationLabelMode = navigationLabelMode,
                                onNavigationLabelModeChange = { navigationLabelMode = it },
                                verifyPackage = verifyPackage,
                                onVerifyPackageChange = { verifyPackage = it },
                                autoExportLogs = autoExportLogs,
                                onAutoExportLogsChange = { autoExportLogs = it },
                                debugMode = debugMode,
                                onDebugModeChange = { debugMode = it },
                                rootDebugMode = rootDebugMode,
                                onRootDebugModeChange = { rootDebugMode = it },
                                keepWindowOnTop = keepWindowOnTop,
                                onKeepWindowOnTopChange = { keepWindowOnTop = it },
                                platformToolsState = platformToolsState,
                                isUpdatingPlatformTools = isUpdatingPlatformTools,
                                platformToolsProgress = platformToolsProgress,
                                platformToolsProgressText = platformToolsProgressText,
                                onUpdatePlatformTools = { updatePlatformTools(autoTriggered = false) },
                            )
                        }
                    }
                }

                FloatingNavigationBar(
                    tabs = tabs,
                    selectedKey = currentPage,
                    onSelect = { currentPage = it },
                    showLabel = showNavigationLabel,
                    backdrop = backdrop,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .zIndex(10f)
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun fontFileExists(path: String): Boolean {
    val file = fopen(path, "rb") ?: return false
    fclose(file)
    return true
}

@OptIn(ExperimentalForeignApi::class)
private fun readFontFileBytes(path: String): ByteArray {
    val file = fopen(path, "rb") ?: return ByteArray(0)
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

@Composable
private fun FlashPage(
    isWideLayout: Boolean,
    bottomBarPadding: Dp,
    romPackageInfo: RomPackageInfo,
    resolvedDeviceDisplayName: String,
    firmwareImageInfo: FirmwareImageInfo,
    flashMode: FlashMode,
    onFlashModeChange: (FlashMode) -> Unit,
    rootMode: RootMode,
    onRootModeChange: (RootMode) -> Unit,
    rootManagerVersion: String?,
    rootManagerOptions: List<RootManagerOption>,
    onRootManagerVersionChange: (String) -> Unit,
    autoReboot: Boolean,
    onAutoRebootChange: (Boolean) -> Unit,
    fastbootDevices: List<FastbootDevice>,
    selectedDeviceIndex: Int,
    onSelectDeviceIndex: (Int) -> Unit,
    isScanningDevices: Boolean,
    onRefreshDevices: () -> Unit,
    onStartFlash: () -> Unit,
    onClearLogs: () -> Unit,
    onExportLogs: () -> Unit,
    logLines: List<String>,
    partitionProgress: FlashPartitionProgress,
    extractionProgress: ZstdExtractionProgress,
    waitProgress: WaitProgress,
    managerDownloadProgress: ManagerDownloadProgress,
    rootDebugMode: Boolean,
    isFlashing: Boolean,
    disguiseRelock: Boolean,
    onDisguiseRelockChange: (Boolean) -> Unit,
) {
    if (isWideLayout) {
        val contentScrollState = rememberScrollState()
        Row(
            modifier = Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MergedRomInfoHeroPanel(
                    logoText = romPackageInfo.logoText,
                    deviceName = romPackageInfo.deviceName,
                    deviceCpu = romPackageInfo.deviceCpu,
                    author = romPackageInfo.author,
                    structure = romPackageInfo.structure,
                    sdkRevision = romPackageInfo.sdkRevision,
                    firmwareImageInfo = firmwareImageInfo,
                    isWideLayout = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(contentScrollState),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        Column(
                            modifier = Modifier.widthIn(min = 320.dp, max = 360.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            FlashModePanel(flashMode = flashMode, onFlashModeChange = onFlashModeChange)
                            RootModePanel(rootMode = rootMode, onRootModeChange = onRootModeChange)
                            if (rootMode != RootMode.KeepOriginal && rootManagerVersion != null && rootManagerOptions.isNotEmpty()) {
                                RootManagerVersionPanel(
                                    rootMode = rootMode,
                                    rootManagerVersion = rootManagerVersion,
                                    rootManagerOptions = rootManagerOptions,
                                    onRootManagerVersionChange = onRootManagerVersionChange,
                                )
                            }
                            if (rootMode == RootMode.KernelSuLkm) {
                                LkmModeNoticePanel()
                            }
                            DeviceSettingsPanel(
                                autoReboot = autoReboot,
                                onAutoRebootChange = onAutoRebootChange,
                                fastbootDevices = fastbootDevices,
                                selectedDeviceIndex = selectedDeviceIndex,
                                onSelectDeviceIndex = onSelectDeviceIndex,
                                isScanningDevices = isScanningDevices,
                            )
                            if (romPackageInfo.deviceName.lowercase() in disguiseRelockSupportedDevices) {
                                DisguiseRelockPanel(
                                    disguiseRelock = disguiseRelock,
                                    onDisguiseRelockChange = onDisguiseRelockChange,
                                )
                            }
                        }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .widthIn(max = 720.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            DeviceAboutPanel(
                                romPackageInfo = romPackageInfo,
                                displayDeviceName = resolvedDeviceDisplayName,
                                firmwareImageInfo = firmwareImageInfo,
                            )
                            FlashActionPanel(
                                fastbootDevices = fastbootDevices,
                                selectedDeviceIndex = selectedDeviceIndex,
                                onRefreshDevices = onRefreshDevices,
                                onStartFlash = onStartFlash,
                                onClearLogs = onClearLogs,
                                isFlashing = isFlashing,
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(bottomBarPadding))
                }
            }
            FlashLogPanel(
                logLines = logLines,
                partitionProgress = partitionProgress,
                extractionProgress = extractionProgress,
                waitProgress = waitProgress,
                managerDownloadProgress = managerDownloadProgress,
                isAnimating = isFlashing,
                onExportLogs = onExportLogs,
                modifier = Modifier
                    .widthIn(min = 340.dp, max = 400.dp)
                    .fillMaxHeight(),
            )
        }
    } else {
        val scrollState = rememberScrollState()
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            MergedRomInfoHeroPanel(
                logoText = romPackageInfo.logoText,
                deviceName = romPackageInfo.deviceName,
                deviceCpu = romPackageInfo.deviceCpu,
                author = romPackageInfo.author,
                structure = romPackageInfo.structure,
                sdkRevision = romPackageInfo.sdkRevision,
                firmwareImageInfo = firmwareImageInfo,
                isWideLayout = false,
                modifier = Modifier.fillMaxWidth(),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                FlashModePanel(flashMode = flashMode, onFlashModeChange = onFlashModeChange)
                RootModePanel(rootMode = rootMode, onRootModeChange = onRootModeChange)
                if (rootMode != RootMode.KeepOriginal && rootManagerVersion != null && rootManagerOptions.isNotEmpty()) {
                    RootManagerVersionPanel(
                        rootMode = rootMode,
                        rootManagerVersion = rootManagerVersion,
                        rootManagerOptions = rootManagerOptions,
                        onRootManagerVersionChange = onRootManagerVersionChange,
                    )
                }
                if (rootMode == RootMode.KernelSuLkm) {
                    LkmModeNoticePanel()
                }
                DeviceSettingsPanel(
                    autoReboot = autoReboot,
                    onAutoRebootChange = onAutoRebootChange,
                    fastbootDevices = fastbootDevices,
                    selectedDeviceIndex = selectedDeviceIndex,
                    onSelectDeviceIndex = onSelectDeviceIndex,
                    isScanningDevices = isScanningDevices,
                )
                if (romPackageInfo.deviceName.lowercase() in disguiseRelockSupportedDevices) {
                    DisguiseRelockPanel(
                        disguiseRelock = disguiseRelock,
                        onDisguiseRelockChange = onDisguiseRelockChange,
                    )
                }
                DeviceAboutPanel(
                    romPackageInfo = romPackageInfo,
                    displayDeviceName = resolvedDeviceDisplayName,
                    firmwareImageInfo = firmwareImageInfo,
                )
                FlashActionPanel(
                    fastbootDevices = fastbootDevices,
                    selectedDeviceIndex = selectedDeviceIndex,
                    onRefreshDevices = onRefreshDevices,
                    onStartFlash = onStartFlash,
                    onClearLogs = onClearLogs,
                    isFlashing = isFlashing,
                )
                FlashLogPanel(
                    logLines = logLines,
                    partitionProgress = partitionProgress,
                    extractionProgress = extractionProgress,
                    waitProgress = waitProgress,
                    managerDownloadProgress = managerDownloadProgress,
                    isAnimating = isFlashing,
                    onExportLogs = onExportLogs,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 360.dp),
                )
                Spacer(modifier = Modifier.height(bottomBarPadding))
            }
        }
    }
}

@Composable
private fun MergedRomInfoHeroPanel(
    logoText: String,
    deviceName: String,
    deviceCpu: String,
    author: String,
    structure: String,
    sdkRevision: String,
    firmwareImageInfo: FirmwareImageInfo,
    isWideLayout: Boolean,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(30.dp)
    val borderColor = Color(0xFFE8D6F5)
    val logoColor = Color(0xFF8FA2C2)
    val fontSize = if (isWideLayout) 58.sp else 44.sp
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        val horizontalPadding = if (isWideLayout) 48.dp else 32.dp
        val verticalPadding = if (isWideLayout) 40.dp else 30.dp
        val measuredLogo = textMeasurer.measure(
            text = logoText,
            style = TextStyle(
                fontSize = fontSize,
                fontWeight = FontWeight.Black,
                fontFamily = MiuixTheme.textStyles.title1.fontFamily,
            ),
            constraints = Constraints(
                maxWidth = with(density) { (maxWidth - horizontalPadding * 2).roundToPx() }.coerceAtLeast(0),
            ),
        )
        val cardHeight = with(density) {
            (measuredLogo.size.height.toDp() + verticalPadding * 2)
                .coerceAtLeast(if (isWideLayout) 150.dp else 132.dp)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(cardHeight)
                .clip(shape)
                .border(width = 1.dp, color = borderColor.copy(alpha = 0.9f), shape = shape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
            ) {
                ForegroundBlurHeroBackground()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.linearGradient(
                            colors = listOf(
                                Color(0xFFF5D8FF).copy(alpha = 0.22f),
                                Color.White.copy(alpha = 0.10f),
                                Color(0xFFFFE7C2).copy(alpha = 0.24f),
                            ),
                            start = Offset.Zero,
                            end = Offset(1400f, 0f),
                        ),
                    ),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.18f),
                                Color.Transparent,
                            ),
                            center = Offset(420f, 120f),
                            radius = 380f,
                        ),
                    ),
            )

            Text(
                text = logoText,
                color = logoColor,
                fontSize = fontSize,
                fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun RomInfoHeroPanel(
    title: String,
    accentText: String,
    metrics: List<Pair<String, String>>,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(30.dp)
    val primary = MiuixTheme.colorScheme.primary
    val surface = MiuixTheme.colorScheme.surface
    val cardBaseBrush = Brush.linearGradient(
        colors = listOf(
            Color(0xFFF4D6FF).copy(alpha = 0.92f),
            Color(0xFFFFF7F3).copy(alpha = 0.96f),
            Color(0xFFFFE7C8).copy(alpha = 0.86f),
        ),
    )
    val blurEnabled = isRenderEffectSupported()
    val heroBackdrop = rememberLayerBackdrop {
        drawRect(surface)
        drawContent()
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = title)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp)
                .clip(shape)
                .background(cardBaseBrush, shape)
                .border(width = 1.dp, color = primary.copy(alpha = 0.14f), shape = shape)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.24f),
                                Color(0xFFFFF7EE).copy(alpha = 0.14f),
                            ),
                        ),
                    ),
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .layerBackdrop(heroBackdrop),
            ) {
                ForegroundBlurHeroBackground()
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape)
                    .textureBlur(
                        backdrop = heroBackdrop,
                        shape = shape,
                        blurRadiusX = 150f,
                        blurRadiusY = 150f,
                        noiseCoefficient = 0.001f,
                        colors = BlurColors(
                            blendColors = listOf(
                                BlendColorEntry(Color(0xFFFFC3F4).copy(alpha = 0.50f), BlurBlendMode.SoftLight),
                                BlendColorEntry(Color(0xFFFFD8AE).copy(alpha = 0.62f), BlurBlendMode.Screen),
                                BlendColorEntry(Color(0xFFE0B4FF).copy(alpha = 0.42f), BlurBlendMode.Overlay),
                                BlendColorEntry(Color(0xFFFFF0DF).copy(alpha = 0.18f), BlurBlendMode.PlusLighter),
                            ),
                            brightness = 0.05f,
                            contrast = 1.14f,
                            saturation = 1.24f,
                        ),
                        enabled = blurEnabled,
                    )
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.14f),
                                Color(0xFFFFF6EF).copy(alpha = 0.10f),
                                surface.copy(alpha = 0.08f),
                            ),
                        ),
                    ),
            )

            Text(
                text = accentText,
                color = primary.copy(alpha = 0.30f),
                fontSize = 60.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 18.dp, top = 18.dp),
            )

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 18.dp, end = 18.dp, top = 76.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                metrics.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        rowItems.forEach { (label, value) ->
                            RomMetricCard(
                                label = label,
                                value = value,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        if (rowItems.size == 1) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ForegroundBlurHeroBackground() {
    val transition = rememberInfiniteTransition(label = "hero-background")
    val driftA by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 18000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift-a",
    )
    val driftB by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 24000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "drift-b",
    )
    val glowShift by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 14000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "glow-shift",
    )

    val baseStart = Offset(10f + 80f * driftA, 420f - 60f * driftB)
    val baseEnd = Offset(920f - 90f * driftA, 20f + 40f * driftB)
    val whiteGlowCenter = Offset(360f + 70f * glowShift, 210f - 26f * driftA)
    val purpleBlobCenter = Offset(120f + 80f * driftA, 420f - 54f * driftB)
    val orangeBlobCenter = Offset(760f - 86f * driftB, 120f + 32f * driftA)
    val lowerLeftBlobCenter = Offset(210f + 40f * driftA, 520f - 24f * driftB)
    val lowerRightBlobCenter = Offset(790f - 42f * driftA, 520f - 18f * driftB)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color(0xFFF7DDF9).copy(alpha = 0.98f),
                        Color(0xFFFFF8F6).copy(alpha = 0.99f),
                        Color(0xFFFFEDD3).copy(alpha = 0.98f),
                    ),
                    start = baseStart,
                    end = baseEnd,
                ),
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.30f + 0.06f * glowShift),
                            Color.Transparent,
                        ),
                        center = whiteGlowCenter,
                        radius = 500f + 20f * driftA,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFD8C8FF).copy(alpha = 0.78f + 0.08f * driftA),
                            Color(0xFFF2DEFF).copy(alpha = 0.30f + 0.05f * glowShift),
                            Color.Transparent,
                        ),
                        center = purpleBlobCenter,
                        radius = 360f + 36f * driftB,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFDDA8).copy(alpha = 0.72f + 0.10f * driftB),
                            Color(0xFFFFF0DA).copy(alpha = 0.28f + 0.06f * driftA),
                            Color.Transparent,
                        ),
                        center = orangeBlobCenter,
                        radius = 300f + 26f * glowShift,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFCFC8FF).copy(alpha = 0.42f + 0.08f * driftB),
                            Color(0xFFE2DBFF).copy(alpha = 0.20f + 0.04f * driftA),
                            Color.Transparent,
                        ),
                        center = lowerLeftBlobCenter,
                        radius = 330f + 26f * driftA,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFDCEBFF).copy(alpha = 0.40f + 0.08f * driftB),
                            Color(0xFFEAF3FF).copy(alpha = 0.22f + 0.04f * driftA),
                            Color.Transparent,
                        ),
                        center = lowerRightBlobCenter,
                        radius = 320f + 20f * driftA,
                    ),
                ),
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = 0.08f),
                            Color.Transparent,
                        ),
                        start = Offset(180f, 40f),
                        end = Offset(620f, 420f),
                    ),
                ),
        )
    }
}

@Composable
private fun RomMetricCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = label, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Text(text = value, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
    }
}

@Composable
private fun FlashModePanel(
    flashMode: FlashMode,
    onFlashModeChange: (FlashMode) -> Unit,
) {
    DropdownPanel(
        title = "刷写方式",
        summary = "选择刷写流程",
        items = FlashMode.entries.map { SpinnerEntry(title = it.title) },
        selectedIndex = flashMode.ordinal,
        onSelectedIndexChange = { onFlashModeChange(FlashMode.entries[it]) },
    )
}

@Composable
private fun RootModePanel(
    rootMode: RootMode,
    onRootModeChange: (RootMode) -> Unit,
) {
    DropdownPanel(
        title = "ROOT 方案",
        summary = "选择修补或保留方式",
        items = RootMode.entries.map { SpinnerEntry(title = it.title) },
        selectedIndex = rootMode.ordinal,
        onSelectedIndexChange = { onRootModeChange(RootMode.entries[it]) },
    )
}

@Composable
private fun DropdownPanel(
    title: String,
    summary: String,
    items: List<SpinnerEntry>,
    selectedIndex: Int,
    onSelectedIndexChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = title)
        Card {
            WindowSpinnerPreference(
                title = title,
                summary = summary,
                items = items,
                selectedIndex = selectedIndex,
                onSelectedIndexChange = onSelectedIndexChange,
            )
        }
    }
}

@Composable
private fun DeviceSettingsPanel(
    autoReboot: Boolean,
    onAutoRebootChange: (Boolean) -> Unit,
    fastbootDevices: List<FastbootDevice>,
    selectedDeviceIndex: Int,
    onSelectDeviceIndex: (Int) -> Unit,
    isScanningDevices: Boolean,
    modifier: Modifier = Modifier,
) {
    val deviceEntries = if (fastbootDevices.isEmpty()) {
        listOf(SpinnerEntry(title = "未发现设备"))
    } else {
        fastbootDevices.map { device -> SpinnerEntry(title = device.serial, summary = device.state) }
    }
    val safeSelectedIndex = if (fastbootDevices.isEmpty()) 0 else selectedDeviceIndex.coerceIn(0, fastbootDevices.lastIndex)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = "设备设置")
        Card {
            WindowSpinnerPreference(
                title = "设备端口",
                summary = if (isScanningDevices) "正在通过 fastboot devices 扫描" else "通过 fastboot devices 选择目标设备",
                items = deviceEntries,
                selectedIndex = safeSelectedIndex,
                onSelectedIndexChange = onSelectDeviceIndex,
                enabled = fastbootDevices.isNotEmpty(),
            )
            CheckboxPreference(
                checkboxLocation = CheckboxLocation.End,
                title = "刷完自动开机",
                checked = autoReboot,
                onCheckedChange = onAutoRebootChange,
            )
        }
    }
}

@Composable
private fun FlashActionPanel(
    fastbootDevices: List<FastbootDevice>,
    selectedDeviceIndex: Int,
    onRefreshDevices: () -> Unit,
    onStartFlash: () -> Unit,
    onClearLogs: () -> Unit,
    isFlashing: Boolean,
    modifier: Modifier = Modifier,
) {
    val selectedDevice = fastbootDevices.getOrNull(selectedDeviceIndex)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = "执行")
        Card {
            Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = selectedDevice?.serial ?: "未选择目标设备",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    TextButton(text = "刷新设备", modifier = Modifier.weight(1f), onClick = onRefreshDevices)
                    TextButton(text = "清空日志", modifier = Modifier.weight(1f), onClick = onClearLogs)
                }
                if (isFlashing) {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            SweepRingProgressIndicator()
                            Text(
                                text = "刷机进行中",
                                color = MiuixTheme.colorScheme.onSurface,
                            )
                            Text(
                                text = "任务已启动，开始入口已锁定",
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                            )
                        }
                    }
                } else {
                    TextButton(
                        text = "开始刷机",
                        modifier = Modifier.fillMaxWidth(),
                        onClick = onStartFlash,
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                    )
                }
            }
        }
    }
}

@Composable
private fun FlashSummaryPanel(
    flashMode: FlashMode,
    rootMode: RootMode,
    autoReboot: Boolean,
    fastbootDevices: List<FastbootDevice>,
    selectedDeviceIndex: Int,
    modifier: Modifier = Modifier,
) {
    val selectedDevice = fastbootDevices.getOrNull(selectedDeviceIndex)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = "刷写摘要")
        Card {
            BasicComponent(
                title = "目标设备",
                endActions = {
                    Text(
                        text = selectedDevice?.serial ?: "未选择",
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                },
            )
            BasicComponent(
                title = "刷写方式",
                endActions = {
                    Text(
                        text = flashMode.title,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                },
            )
            BasicComponent(
                title = "ROOT 方案",
                endActions = {
                    Text(
                        text = rootMode.title,
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                },
            )
            BasicComponent(
                title = "自动开机",
                endActions = {
                    Text(
                        text = if (autoReboot) "开启" else "关闭",
                        color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                    )
                },
            )
        }
    }
}

@Composable
private fun FlashLogPanel(
    logLines: List<String>,
    partitionProgress: FlashPartitionProgress,
    extractionProgress: ZstdExtractionProgress,
    waitProgress: WaitProgress,
    managerDownloadProgress: ManagerDownloadProgress,
    isAnimating: Boolean,
    onExportLogs: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val logScrollState = rememberScrollState()
    val combinedLogs = logLines.joinToString(separator = "\n")

    LaunchedEffect(logLines.size) {
        logScrollState.scrollTo(logScrollState.maxValue)
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SmallTitle(text = "刷机日志")
            TextButton(text = "导出", onClick = onExportLogs)
        }
        Card(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FlashPartitionProgressPanel(
                    partitionProgress = partitionProgress,
                    isAnimating = isAnimating,
                    modifier = Modifier.fillMaxWidth().weight(0.42f),
                )
                if (extractionProgress.isActive || extractionProgress.totalCount > 0) {
                    ZstdExtractionProgressPanel(
                        extractionProgress = extractionProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (waitProgress.isActive || waitProgress.totalSteps > 0) {
                    WaitProgressPanel(
                        waitProgress = waitProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (managerDownloadProgress.isActive || managerDownloadProgress.totalBytes > 0L || managerDownloadProgress.downloadedBytes > 0L) {
                    ManagerDownloadProgressPanel(
                        downloadProgress = managerDownloadProgress,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(MiuixTheme.colorScheme.outline.copy(alpha = 0.18f)),
                )
                Column(
                    modifier = Modifier.fillMaxWidth().weight(0.58f),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(text = "Fastboot")
                    Box(modifier = Modifier.fillMaxWidth().weight(1f).verticalScroll(logScrollState)) {
                        if (logLines.isEmpty()) {
                            Text(text = "暂无日志", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                        } else {
                            SelectionContainer {
                                Text(text = combinedLogs, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ZstdExtractionProgressPanel(
    extractionProgress: ZstdExtractionProgress,
    modifier: Modifier = Modifier,
) {
    val title = if (extractionProgress.isActive) "正在解压逻辑分区" else "逻辑分区解压完成"
    val progressText = buildString {
        append(extractionProgress.completedCount)
        append("/")
        append(extractionProgress.totalCount)
        if (extractionProgress.currentFile.isNotBlank()) {
            append("  ")
            append(extractionProgress.currentFile)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = title)
            Text(
                text = "${(extractionProgress.fraction * 100f).toInt()}%",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        LinearProgressIndicator(
            progress = extractionProgress.fraction.coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth(),
        )
        if (progressText.isNotBlank()) {
            Text(
                text = progressText,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun WaitProgressPanel(
    waitProgress: WaitProgress,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = waitProgress.title.ifBlank { "等待设备" })
            Text(
                text = "${(waitProgress.fraction.coerceIn(0f, 1f) * 100f).toInt()}%",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        LinearProgressIndicator(
            progress = waitProgress.fraction.coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "${waitProgress.currentStep}/${waitProgress.totalSteps}",
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
        )
    }
}

@Composable
private fun ManagerDownloadProgressPanel(
    downloadProgress: ManagerDownloadProgress,
    modifier: Modifier = Modifier,
) {
    val bytesText = if (downloadProgress.totalBytes > 0L) {
        "${formatTransferSize(downloadProgress.downloadedBytes)} / ${formatTransferSize(downloadProgress.totalBytes)}"
    } else {
        formatTransferSize(downloadProgress.downloadedBytes)
    }
    val detailText = buildString {
        if (downloadProgress.fileName.isNotBlank()) {
            append(downloadProgress.fileName)
        }
        if (bytesText.isNotBlank()) {
            if (isNotEmpty()) append("  ")
            append(bytesText)
        }
    }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = downloadProgress.title.ifBlank { "下载 ROOT 管理器" })
            Text(
                text = "${(downloadProgress.fraction.coerceIn(0f, 1f) * 100f).toInt()}%",
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
        LinearProgressIndicator(
            progress = downloadProgress.fraction.coerceIn(0f, 1f),
            modifier = Modifier.fillMaxWidth(),
        )
        if (detailText.isNotBlank()) {
            Text(
                text = detailText,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

private fun formatTransferSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = listOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1024.0 && unitIndex < units.lastIndex) {
        value /= 1024.0
        unitIndex++
    }
    val text = if (unitIndex == 0) {
        value.toInt().toString()
    } else {
        val rounded = kotlin.math.round(value * 10.0) / 10.0
        rounded.toString()
    }
    return "$text ${units[unitIndex]}"
}

@Composable
private fun DeviceAboutPanel(
    romPackageInfo: RomPackageInfo,
    displayDeviceName: String,
    firmwareImageInfo: FirmwareImageInfo,
    modifier: Modifier = Modifier,
) {
    val ramdiskValue = when {
        firmwareImageInfo.hasInitBootImage -> firmwareImageInfo.initBootImageName.removeSuffix(".img")
        firmwareImageInfo.hasBootImage -> firmwareImageInfo.bootImageName.removeSuffix(".img")
        else -> "未找到"
    }
    val metrics = listOf(
        "处理器" to romPackageInfo.deviceCpu,
        "刷入工具版本" to "0.10.Pre",
        "ramdisk" to ramdiskValue,
        "作者" to romPackageInfo.author,
    )

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = "关于设备")
        Card {
            Column(
                modifier = Modifier.padding(horizontal = 22.dp, vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
            ) {
                Text(
                    text = displayDeviceName,
                    fontSize = 30.sp,
                    fontWeight = FontWeight.Black,
                )
                metrics.chunked(2).forEach { rowItems ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        rowItems.forEach { (label, value) ->
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                Text(
                                    text = value,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = label,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                )
                            }
                        }
                        repeat(2 - rowItems.size) {
                            Box(modifier = Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FlashPartitionProgressPanel(
    partitionProgress: FlashPartitionProgress,
    isAnimating: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        AnimatedContent(
            targetState = partitionProgress.currentIndex,
            transitionSpec = {
                slideInVertically(
                    animationSpec = tween(durationMillis = 320),
                    initialOffsetY = { fullHeight -> fullHeight / 2 },
                ) + fadeIn(animationSpec = tween(durationMillis = 220)) togetherWith
                    slideOutVertically(
                        animationSpec = tween(durationMillis = 320),
                        targetOffsetY = { fullHeight -> -fullHeight / 2 },
                    ) + fadeOut(animationSpec = tween(durationMillis = 180))
            },
            label = "partitionProgressSlide",
        ) {
            val completedStages = partitionProgress.completedStages.takeLast(3)
            val currentStage = partitionProgress.currentStage
            val pendingStages = partitionProgress.pendingStages.take(3)

            if (partitionProgress.isEmpty || currentStage == null) {
                Text(
                    text = "等待刷机",
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
            } else {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    completedStages.forEach { stage ->
                        Text(
                            text = stage,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.68f),
                            fontSize = 18.sp,
                        )
                    }
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(18.dp),
                    ) {
                        Text(
                            text = currentStage,
                            color = MiuixTheme.colorScheme.primary,
                            fontSize = 30.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        if (isAnimating) {
                            DotRingProgressIndicator()
                        }
                    }
                    pendingStages.forEach { stage ->
                        Text(
                            text = stage,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.68f),
                            fontSize = 18.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DotRingProgressIndicator(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "dotRing")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1080, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dotRingRotation",
    )
    val indicatorColor = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.78f)
    val trackColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.28f)

    Canvas(modifier = modifier.size(44.dp)) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        val ringRadius = size.minDimension * 0.34f
        val strokeWidth = size.minDimension * 0.08f
        val dotRadius = size.minDimension * 0.07f
        val angle = (rotation - 90f) * (PI / 180f).toFloat()

        drawCircle(
            color = trackColor,
            radius = ringRadius,
            center = Offset(centerX, centerY),
            style = Stroke(width = strokeWidth),
        )
        drawCircle(
            color = indicatorColor,
            radius = dotRadius,
            center = Offset(
                x = centerX + cos(angle) * ringRadius,
                y = centerY + sin(angle) * ringRadius,
            ),
        )
    }
}

@Composable
private fun SweepRingProgressIndicator(
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "sweepRing")
    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 920, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "sweepRingRotation",
    )
    val indicatorColor = MiuixTheme.colorScheme.primary
    val trackColor = MiuixTheme.colorScheme.outline.copy(alpha = 0.20f)

    Canvas(modifier = modifier.size(42.dp)) {
        val strokeWidth = size.minDimension * 0.10f
        drawArc(
            color = trackColor,
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = strokeWidth),
        )
        drawArc(
            color = indicatorColor,
            startAngle = rotation - 90f,
            sweepAngle = 78f,
            useCenter = false,
            style = Stroke(width = strokeWidth),
        )
    }
}

@Composable
private fun DevicesPage(
    isWideLayout: Boolean,
    bottomBarPadding: Dp,
    fastbootDevices: List<FastbootDevice>,
    selectedDeviceIndex: Int,
    onSelectDeviceIndex: (Int) -> Unit,
    isScanningDevices: Boolean,
    onRefreshDevices: () -> Unit,
) {
    val selectedDevice = fastbootDevices.getOrNull(selectedDeviceIndex)
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (isWideLayout) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                DeviceSettingsPanel(
                    autoReboot = true,
                    onAutoRebootChange = {},
                    fastbootDevices = fastbootDevices,
                    selectedDeviceIndex = selectedDeviceIndex,
                    onSelectDeviceIndex = onSelectDeviceIndex,
                    isScanningDevices = isScanningDevices,
                    modifier = Modifier.widthIn(min = 360.dp, max = 420.dp),
                )
                DeviceInventoryPanel(
                    fastbootDevices = fastbootDevices,
                    selectedDevice = selectedDevice,
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            DeviceSettingsPanel(
                autoReboot = true,
                onAutoRebootChange = {},
                fastbootDevices = fastbootDevices,
                selectedDeviceIndex = selectedDeviceIndex,
                onSelectDeviceIndex = onSelectDeviceIndex,
                isScanningDevices = isScanningDevices,
                modifier = Modifier.fillMaxWidth(),
            )
            DeviceInventoryPanel(
                fastbootDevices = fastbootDevices,
                selectedDevice = selectedDevice,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(bottomBarPadding))
    }
}

@Composable
private fun DeviceInventoryPanel(
    fastbootDevices: List<FastbootDevice>,
    selectedDevice: FastbootDevice?,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = "设备状态")
        Card {
            BasicComponent(title = "当前设备", endActions = { Text(text = selectedDevice?.serial ?: "未选择") })
            BasicComponent(title = "连接状态", endActions = { Text(text = selectedDevice?.state ?: "空闲") })
            BasicComponent(title = "设备数量", endActions = { Text(text = "${fastbootDevices.size}") })
        }

        SmallTitle(text = "已发现设备")
        Card {
            if (fastbootDevices.isEmpty()) {
                BasicComponent(title = "暂无设备")
            } else {
                fastbootDevices.forEach { device ->
                    BasicComponent(
                        title = device.serial,
                        endActions = {
                            Text(
                                text = device.state,
                                color = if (device == selectedDevice) MiuixTheme.colorScheme.primary else MiuixTheme.colorScheme.onSurfaceVariantActions,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsPage(
    isWideLayout: Boolean,
    bottomBarPadding: Dp,
    themeMode: SampleThemeMode,
    onThemeModeChange: (SampleThemeMode) -> Unit,
    navigationLabelMode: NavigationLabelMode,
    onNavigationLabelModeChange: (NavigationLabelMode) -> Unit,
    verifyPackage: Boolean,
    onVerifyPackageChange: (Boolean) -> Unit,
    autoExportLogs: Boolean,
    onAutoExportLogsChange: (Boolean) -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    rootDebugMode: Boolean,
    onRootDebugModeChange: (Boolean) -> Unit,
    keepWindowOnTop: Boolean,
    onKeepWindowOnTopChange: (Boolean) -> Unit,
    platformToolsState: PlatformToolsState,
    isUpdatingPlatformTools: Boolean,
    platformToolsProgress: Float?,
    platformToolsProgressText: String,
    onUpdatePlatformTools: () -> Unit,
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(scrollState),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (isWideLayout) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        PreferencesPanel(
                            verifyPackage = verifyPackage,
                            onVerifyPackageChange = onVerifyPackageChange,
                            autoExportLogs = autoExportLogs,
                            onAutoExportLogsChange = onAutoExportLogsChange,
                            debugMode = debugMode,
                            onDebugModeChange = onDebugModeChange,
                            rootDebugMode = rootDebugMode,
                            onRootDebugModeChange = onRootDebugModeChange,
                            keepWindowOnTop = keepWindowOnTop,
                            onKeepWindowOnTopChange = onKeepWindowOnTopChange,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    Column(
                        modifier = Modifier.widthIn(min = 280.dp, max = 320.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        ThemePanel(themeMode = themeMode, onThemeModeChange = onThemeModeChange, modifier = Modifier.fillMaxWidth())
                        NavigationBarPanel(
                            navigationLabelMode = navigationLabelMode,
                            onNavigationLabelModeChange = onNavigationLabelModeChange,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        AcknowledgementsPanel(modifier = Modifier.fillMaxWidth())
                        PlatformToolsPanel(
                            platformToolsState = platformToolsState,
                            isUpdating = isUpdatingPlatformTools,
                            progress = platformToolsProgress,
                            progressText = platformToolsProgressText,
                            onUpdate = onUpdatePlatformTools,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            } else {
                PreferencesPanel(
                    verifyPackage = verifyPackage,
                    onVerifyPackageChange = onVerifyPackageChange,
                    autoExportLogs = autoExportLogs,
                    onAutoExportLogsChange = onAutoExportLogsChange,
                    debugMode = debugMode,
                    onDebugModeChange = onDebugModeChange,
                    rootDebugMode = rootDebugMode,
                    onRootDebugModeChange = onRootDebugModeChange,
                    keepWindowOnTop = keepWindowOnTop,
                    onKeepWindowOnTopChange = onKeepWindowOnTopChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                ThemePanel(themeMode = themeMode, onThemeModeChange = onThemeModeChange, modifier = Modifier.fillMaxWidth())
                NavigationBarPanel(
                    navigationLabelMode = navigationLabelMode,
                    onNavigationLabelModeChange = onNavigationLabelModeChange,
                    modifier = Modifier.fillMaxWidth(),
                )
                AcknowledgementsPanel(modifier = Modifier.fillMaxWidth())
                PlatformToolsPanel(
                    platformToolsState = platformToolsState,
                    isUpdating = isUpdatingPlatformTools,
                    progress = platformToolsProgress,
                    progressText = platformToolsProgressText,
                    onUpdate = onUpdatePlatformTools,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        Spacer(modifier = Modifier.height(bottomBarPadding))
    }
}

@Composable
private fun PreferencesPanel(
    verifyPackage: Boolean,
    onVerifyPackageChange: (Boolean) -> Unit,
    autoExportLogs: Boolean,
    onAutoExportLogsChange: (Boolean) -> Unit,
    debugMode: Boolean,
    onDebugModeChange: (Boolean) -> Unit,
    rootDebugMode: Boolean,
    onRootDebugModeChange: (Boolean) -> Unit,
    keepWindowOnTop: Boolean,
    onKeepWindowOnTopChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = "偏好")
        Card {
            SwitchPreference(title = "刷写前校验包", checked = verifyPackage, onCheckedChange = onVerifyPackageChange)
            SwitchPreference(title = "自动输出日志", checked = autoExportLogs, onCheckedChange = onAutoExportLogsChange)
            SwitchPreference(title = "Debug 模式", checked = debugMode, onCheckedChange = onDebugModeChange)
            SwitchPreference(title = "ROOT-debug 模式", checked = rootDebugMode, onCheckedChange = onRootDebugModeChange)
            CheckboxPreference(
                checkboxLocation = CheckboxLocation.End,
                title = "窗口置顶",
                checked = keepWindowOnTop,
                onCheckedChange = onKeepWindowOnTopChange,
            )
        }
    }
}

@Composable
private fun AcknowledgementsPanel(
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = "鸣谢")
        Card {
            AcknowledgementItem(
                avatarUser = "YuKongA",
                title = "YukongA",
                subtitle = "贡献仓库：miuix-mingw",
            )
            AcknowledgementItem(
                avatarUser = "thebanditsare",
                title = "HF-TEAM",
                subtitle = "头像：thebanditsare",
            )
            AcknowledgementItem(
                avatarUser = "google",
                title = "Google platform-tools",
                subtitle = "ADB / Fastboot 工具链",
            )
            AcknowledgementItem(
                avatarUser = "LyraVoid",
                title = "LyraVoid",
                subtitle = "FolkPatch 与 FolkLite 部分代码",
            )
        }
    }
}

@Composable
private fun PlatformToolsPanel(
    platformToolsState: PlatformToolsState,
    isUpdating: Boolean,
    progress: Float?,
    progressText: String,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = "platform-tools")
        Card {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (isUpdating) progressText else platformToolsState.statusText,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (isUpdating) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        LinearProgressIndicator(
                            progress = progress ?: 0f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = "${(((progress ?: 0f).coerceIn(0f, 1f)) * 100f).toInt()}%",
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        )
                    }
                }
                TextButton(
                    text = if (isUpdating) "更新中..." else "一键更新",
                    onClick = onUpdate,
                    enabled = !isUpdating,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun AcknowledgementItem(
    avatarUser: String,
    title: String,
    subtitle: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GithubAvatar(
            username = avatarUser,
            contentDescription = title,
            modifier = Modifier.size(44.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = subtitle,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}

@Composable
private fun ThemePanel(
    themeMode: SampleThemeMode,
    onThemeModeChange: (SampleThemeMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    DropdownPanel(
        title = "颜色系统",
        summary = "切换整体界面配色",
        items = SampleThemeMode.entries.map { SpinnerEntry(title = it.title) },
        selectedIndex = themeMode.ordinal,
        onSelectedIndexChange = { onThemeModeChange(SampleThemeMode.entries[it]) },
        modifier = modifier,
    )
}

@Composable
private fun RootManagerVersionPanel(
    rootMode: RootMode,
    rootManagerVersion: String,
    rootManagerOptions: List<RootManagerOption>,
    onRootManagerVersionChange: (String) -> Unit,
) {
    val title = when (rootMode) {
        RootMode.MagiskPatch -> "Magisk 管理器版本"
        RootMode.KernelSuLkm -> "KernelSU-LKM 管理器"
        RootMode.FolkPatch -> "FolkPatch 版本"
        RootMode.HfTeamGki -> "HF-Team-GKI 版本"
        RootMode.KeepOriginal -> "管理器版本"
    }
    DropdownPanel(
        title = title,
        summary = "选择当前 ROOT 方案对应的版本",
        items = rootManagerOptions.map { SpinnerEntry(title = it.title) },
        selectedIndex = rootManagerOptions.indexOfFirst { it.id == rootManagerVersion }.coerceAtLeast(0),
        onSelectedIndexChange = { onRootManagerVersionChange(rootManagerOptions[it].id) },
    )
}

@Composable
private fun LkmModeNoticePanel(
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = "LKM 提示")
        Card {
            Column(
                modifier = Modifier.padding(horizontal = 18.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(text = "LKM 类需要在刷入 ROM 后打开 USB 调试", color = MiuixTheme.colorScheme.onSurface)
                Text(text = "并允许 USB 安装，随后才会进入 ROOT 流程", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
private fun NavigationBarPanel(
    navigationLabelMode: NavigationLabelMode,
    onNavigationLabelModeChange: (NavigationLabelMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = "导航栏")
        Card {
            ThemeOption(title = "仅图标", selected = navigationLabelMode == NavigationLabelMode.IconOnly, onClick = { onNavigationLabelModeChange(NavigationLabelMode.IconOnly) })
            ThemeOption(title = "图标和名称", selected = navigationLabelMode == NavigationLabelMode.IconAndLabel, onClick = { onNavigationLabelModeChange(NavigationLabelMode.IconAndLabel) })
        }
    }
}

@Composable
private fun ThemeOption(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    BasicComponent(
        title = title,
        onClick = onClick,
        endActions = {
            if (selected) Text(text = "当前", color = MiuixTheme.colorScheme.primary)
        },
    )
}

@Composable
private fun DisguiseRelockPanel(
    disguiseRelock: Boolean,
    onDisguiseRelockChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SmallTitle(text = "伪装回锁")
        Card {
            SwitchPreference(
                title = "伪装回锁",
                checked = disguiseRelock,
                onCheckedChange = onDisguiseRelockChange,
            )
        }
    }
}
