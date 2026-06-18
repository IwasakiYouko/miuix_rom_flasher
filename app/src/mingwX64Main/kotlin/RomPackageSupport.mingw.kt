import kotlinx.cinterop.ExperimentalForeignApi
import kotlin.random.Random
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.remove
import platform.posix.rewind

data class RomPackageInfo(
    val logoText: String,
    val deviceName: String,
    val deviceCpu: String,
    val author: String,
    val structure: String,
    val sdkRevision: String,
    val verifiedUser: Boolean,
    val hasFastboot: Boolean,
    val hasUpdaterScript: Boolean,
    val hasBootPatch: Boolean,
)

data class FirmwareImageInfo(
    val imageNames: List<String>,
) {
    val bootImageName: String
        get() = imageNames.firstOrNull { it.equals("boot.img", ignoreCase = true) } ?: "未找到"

    val initBootImageName: String
        get() = imageNames.firstOrNull { it.equals("init_boot.img", ignoreCase = true) } ?: "未找到"

    val hasBootImage: Boolean
        get() = bootImageName != "未找到"

    val hasInitBootImage: Boolean
        get() = initBootImageName != "未找到"

    val partitionNames: List<String>
        get() = imageNames.map { it.removeSuffix(".img") }
}

@OptIn(ExperimentalForeignApi::class)
fun loadRomPackageInfo(): RomPackageInfo {
    val metaDir = resolveRuntimePath(".\\META-INF")
    val sourceProperties = readKeyValues("$metaDir\\source.properties")
    return RomPackageInfo(
        logoText = readSingleLine("$metaDir\\logo.txt", "HanfyHyperOS"),
        deviceName = readSingleLine("$metaDir\\DeviceName", "unknown"),
        deviceCpu = readSingleLine("$metaDir\\DeviceCPU", "unknown"),
        author = readSingleLine("$metaDir\\FlashInfoAuthor", "unknown"),
        structure = readSingleLine("$metaDir\\Structure", "unknown"),
        sdkRevision = sourceProperties["Pkg.Revision"] ?: "unknown",
        verifiedUser = pathExists("$metaDir\\verified_user"),
        hasFastboot = pathExists("$metaDir\\fastboot.exe"),
        hasUpdaterScript = pathExists("$metaDir\\com\\google\\android\\updater-script"),
        hasBootPatch = pathExists("$metaDir\\boot_patch.sh"),
    )
}

fun loadFirmwareImageInfo(): FirmwareImageInfo =
    FirmwareImageInfo(
        imageNames = listFirmwareImageNames(),
    )

fun resolveRemoteDeviceDisplayName(deviceCode: String): String {
    val normalizedCode = deviceCode.trim()
    if (normalizedCode.isEmpty()) return "unknown"
    val remoteMap = loadRemoteDeviceCodeMap()
    val resolvedName = remoteMap[normalizedCode.lowercase()] ?: normalizedCode
    return localizeDeviceBrandName(resolvedName)
}

private fun listFirmwareImageNames(): List<String> {
    val outputPath = resolveTempPath(".\\__winflasher_firmware_${Random.nextInt(100000, 999999)}.txt")
    val firmwareDir = resolveRuntimePath(".\\firmware-update")
    val script = """
        ${'$'}firmwareDir = '${escapeForPowerShell(firmwareDir)}'
        if ([System.IO.Directory]::Exists(${'$'}firmwareDir)) {
            Get-ChildItem -LiteralPath ${'$'}firmwareDir -Filter *.img -File -ErrorAction SilentlyContinue |
                Sort-Object Name |
                ForEach-Object Name |
                Set-Content -LiteralPath '${escapeForPowerShell(outputPath)}' -Encoding utf8
        }
    """.trimIndent()

    runPowerShellScript(script)
    val names = readLines(outputPath)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
    remove(outputPath)
    return names
}

private fun loadRemoteDeviceCodeMap(): Map<String, String> {
    val outputPath = resolveTempPath(".\\__winflasher_devicecode_${Random.nextInt(100000, 999999)}.txt")
    val script = """
        ${'$'}url = 'https://raw.gitcode.com/hanfyt/devicecode/raw/main/dc'
        try {
            ${'$'}content = (Invoke-WebRequest -UseBasicParsing ${'$'}url).Content
            [System.IO.File]::WriteAllText('${escapeForPowerShell(outputPath)}', ${'$'}content, [System.Text.UTF8Encoding]::new(${'$'}false))
            exit 0
        } catch {
            exit 1
        }
    """.trimIndent()

    val exitCode = runPowerShellScript(script)
    if (exitCode != 0) {
        remove(outputPath)
        return emptyMap()
    }

    val mapping = readLines(outputPath)
        .mapNotNull(::parseDeviceCodeLine)
        .associate { (code, name) -> code to name }
    remove(outputPath)
    return mapping
}

private fun parseDeviceCodeLine(line: String): Pair<String, String>? {
    val match = Regex("""^\s*(.*?)\(([^)]+)\)\s*:\s*$""").matchEntire(line.trim()) ?: return null
    val rawName = match.groupValues[1].trim()
    val rawCode = match.groupValues[2].trim()
    if (rawName.isEmpty() || rawCode.isEmpty()) return null
    return rawCode.lowercase() to rawName
}

private fun localizeDeviceBrandName(name: String): String =
    name
        .replace("REDMI", "红米")
        .replace("Redmi", "红米")
        .replace("Xiaomi", "小米")
        .replace("MiPad", "小米平板")
        .replace(Regex("""\bMi\b"""), "小米")

@OptIn(ExperimentalForeignApi::class)
private fun pathExists(path: String): Boolean {
    return fileExistsUnicodeSafe(normalizeSupportPath(path))
}

private fun readSingleLine(path: String, fallback: String): String =
    readLines(path).firstOrNull { it.isNotBlank() } ?: fallback

private fun readKeyValues(path: String): Map<String, String> =
    readLines(path).mapNotNull { line ->
        val parts = line.split("=", limit = 2)
        if (parts.size != 2) return@mapNotNull null
        parts[0].trim() to parts[1].trim()
    }.toMap()

@OptIn(ExperimentalForeignApi::class)
private fun readLines(path: String): List<String> {
    val bytes = readBytes(path)
    if (bytes.isEmpty()) return emptyList()

    val text = if (containsOnlyAscii(bytes)) {
        asciiBytesToString(bytes)
    } else {
        readTextWithPowerShell(path).ifBlank { bytes.decodeUtf8Safe() }
    }

    return text
        .replace("\r\n", "\n")
        .replace('\r', '\n')
        .split('\n')
}

private fun containsOnlyAscii(bytes: ByteArray): Boolean =
    bytes.none { (it.toInt() and 0x80) != 0 }

private fun asciiBytesToString(bytes: ByteArray): String =
    buildString(bytes.size) {
        bytes.forEach { append((it.toInt() and 0xFF).toChar()) }
    }

@OptIn(ExperimentalForeignApi::class)
private fun readBytes(path: String): ByteArray {
    return readAllBytesUnicodeSafe(normalizeSupportPath(path))
}

@OptIn(ExperimentalForeignApi::class)
private fun readTextWithPowerShell(path: String): String {
    val outputFile = resolveTempPath(".\\__winflasher_rom_text_${Random.nextInt(100000, 999999)}.txt")
    val script = """
        ${'$'}path = '${escapeForPowerShell(normalizeSupportPath(path))}'
        ${'$'}bytes = [System.IO.File]::ReadAllBytes(${'$'}path)
        ${'$'}utf8 = [System.Text.UTF8Encoding]::new(${'$'}false, ${'$'}true)
        try {
            ${'$'}text = ${'$'}utf8.GetString(${'$'}bytes)
        } catch {
            ${'$'}text = [System.Text.Encoding]::GetEncoding(54936).GetString(${'$'}bytes)
        }
        [System.IO.File]::WriteAllText('${escapeForPowerShell(outputFile)}', ${'$'}text, [System.Text.UTF8Encoding]::new(${'$'}false))
    """.trimIndent()

    val exitCode = runPowerShellScript(script)
    if (exitCode != 0) return ""

    val text = readBytes(outputFile).decodeUtf8Safe()
    remove(outputFile)
    return text
}

private fun normalizeSupportPath(path: String): String =
    path.replace('/', '\\')

private fun escapeForPowerShell(value: String): String =
    value.replace("'", "''")

private fun runPowerShellScript(script: String): Int {
    val scriptPath = resolveTempPath(".\\__winflasher_support_${Random.nextInt(100000, 999999)}.ps1")
    val outputPath = resolveTempPath(".\\__winflasher_support_${Random.nextInt(100000, 999999)}.log")
    val fullScript = """
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new(${'$'}false)
        ${script.trimIndent()}
    """.trimIndent()
    if (!writeUtf8BomFile(scriptPath, fullScript)) return -1
    val result = runStreamingShellCommand(
        command = "powershell -NoProfile -ExecutionPolicy Bypass -File \"${normalizeSupportPath(scriptPath)}\"",
        onLine = null,
    )
    remove(scriptPath)
    remove(outputPath)
    return result.exitCode
}

@OptIn(ExperimentalForeignApi::class)
private fun writeUtf8BomFile(path: String, content: String): Boolean {
    return writeUtf8BomFileUnicodeSafe(normalizeSupportPath(path), content)
}
