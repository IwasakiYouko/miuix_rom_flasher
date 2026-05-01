import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.random.Random
import org.jetbrains.skia.Image as SkiaImage
import platform.posix.SEEK_END
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fread
import platform.posix.fseek
import platform.posix.ftell
import platform.posix.remove
import platform.posix.rewind

@Composable
fun GithubAvatar(
    username: String,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    var imageBitmap by remember(username) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(username) {
        imageBitmap = withContext(Dispatchers.Default) {
            loadGithubAvatarBitmap(username)
        }
    }

    if (imageBitmap != null) {
        Image(
            bitmap = imageBitmap!!,
            contentDescription = contentDescription,
            modifier = modifier.clip(CircleShape),
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier
                .clip(CircleShape)
                .background(Color(0x14000000)),
        )
    }
}

private fun loadGithubAvatarBitmap(username: String): ImageBitmap? {
    val cachePath = resolveTempPath(".\\github_avatar_${username.lowercase()}.png")
    if (!fileExists(cachePath)) {
        downloadGithubAvatar(username, cachePath)
    }
    val bytes = readAvatarBytes(cachePath)
    if (bytes.isEmpty()) return null
    return runCatching { SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
}

private fun downloadGithubAvatar(username: String, outputPath: String) {
    val scriptPath = resolveTempPath(".\\__winflasher_avatar_${Random.nextInt(100000, 999999)}.ps1")
    val script = """
        ${'$'}url = 'https://github.com/${escapeAvatarForPowerShell(username)}.png'
        ${'$'}out = '${escapeAvatarForPowerShell(outputPath)}'
        try {
            Invoke-WebRequest -UseBasicParsing ${'$'}url -OutFile ${'$'}out
            exit 0
        } catch {
            exit 1
        }
    """.trimIndent()
    if (!writeAvatarScript(scriptPath, script)) return
    runStreamingShellCommand(
        command = "powershell -NoProfile -ExecutionPolicy Bypass -File \"${scriptPath.replace('/', '\\')}\"",
        onLine = null,
    )
    remove(scriptPath)
}

private fun escapeAvatarForPowerShell(value: String): String =
    value.replace("'", "''")

@OptIn(ExperimentalForeignApi::class)
private fun writeAvatarScript(path: String, content: String): Boolean {
    return writeUtf8BomFileUnicodeSafe(path.replace('/', '\\'), content)
}

@OptIn(ExperimentalForeignApi::class)
private fun readAvatarBytes(path: String): ByteArray {
    return readAllBytesUnicodeSafe(path.replace('/', '\\'))
}

@OptIn(ExperimentalForeignApi::class)
private fun fileExists(path: String): Boolean {
    return fileExistsUnicodeSafe(path.replace('/', '\\'))
}
