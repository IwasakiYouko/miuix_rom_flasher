import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.rawValue
import kotlinx.coroutines.delay
import platform.windows.FindWindowA
import platform.windows.ICON_BIG
import platform.windows.ICON_SMALL
import platform.windows.IMAGE_ICON
import platform.windows.LR_DEFAULTSIZE
import platform.windows.LR_LOADFROMFILE
import platform.windows.LoadImageA
import platform.windows.SendMessageA
import platform.windows.WM_SETICON

@Composable
fun BindWindowIcon(windowTitle: String = "WinFlasher") {
    LaunchedEffect(windowTitle) {
        val iconPath = listOf(
            resolveRuntimeMetaInfPath(".\\bin\\app_icon.ico"),
            resolveRuntimeBinPath(".\\app_icon.ico"),
            resolveRuntimePath(".\\bin\\app_icon.ico"),
            resolveRuntimePath(".\\app_icon.ico"),
        ).firstOrNull(::windowIconFileExists) ?: return@LaunchedEffect

        repeat(40) {
            if (applyWindowIcon(windowTitle, iconPath)) {
                return@LaunchedEffect
            }
            delay(250)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun applyWindowIcon(windowTitle: String, iconPath: String): Boolean {
    val hwnd = FindWindowA(null, windowTitle) ?: return false
    val iconHandle = memScoped {
        LoadImageA(
            null,
            iconPath.cstr.getPointer(this),
            IMAGE_ICON.toUInt(),
            0,
            0,
            (LR_LOADFROMFILE or LR_DEFAULTSIZE).toUInt(),
        )
    } ?: return false

    val iconValue = iconHandle.rawValue.toLong()
    SendMessageA(hwnd, WM_SETICON.toUInt(), ICON_BIG.toULong(), iconValue)
    SendMessageA(hwnd, WM_SETICON.toUInt(), ICON_SMALL.toULong(), iconValue)
    return true
}

private fun windowIconFileExists(path: String): Boolean =
    platform.posix.access(path, 0) == 0
