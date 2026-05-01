import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.blur.Backdrop
import top.yukonga.miuix.kmp.blur.BlendColorEntry
import top.yukonga.miuix.kmp.blur.BlurBlendMode
import top.yukonga.miuix.kmp.blur.BlurColors
import top.yukonga.miuix.kmp.blur.isRenderEffectSupported
import top.yukonga.miuix.kmp.blur.textureBlur
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs
import kotlin.math.sign

data class LiquidBarTab<T>(
    val key: T,
    val label: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector,
)

private val LocalFloatingBottomBarTabScale = staticCompositionLocalOf { { 1f } }

@Composable
fun <T> FloatingNavigationBar(
    tabs: List<LiquidBarTab<T>>,
    selectedKey: T,
    onSelect: (T) -> Unit,
    showLabel: Boolean,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return

    val blurEnabled = isRenderEffectSupported()
    val surface = MiuixTheme.colorScheme.surface
    val primary = MiuixTheme.colorScheme.primary
    val isLightSurface = surface.luminance() > 0.5f
    val selectedIndex = tabs.indexOfFirst { it.key == selectedKey }.let { if (it >= 0) it else 0 }
    val containerShape = RoundedCornerShape(34.dp)
    val indicatorShape = RoundedCornerShape(28.dp)
    val animationScope = rememberCoroutineScope()
    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr

    val outerBlurColors = remember(surface, primary, isLightSurface) {
        BlurColors(
            blendColors = listOf(
                BlendColorEntry(
                    color = if (isLightSurface) Color(0x66FFFFFF) else Color(0x332B2B2B),
                    mode = BlurBlendMode.PlusLighter,
                ),
                BlendColorEntry(
                    color = if (isLightSurface) primary.copy(alpha = 0.08f) else Color(0x4D111111),
                    mode = BlurBlendMode.Overlay,
                ),
                BlendColorEntry(
                    color = if (isLightSurface) Color(0x14FFFFFF) else Color(0x14000000),
                    mode = BlurBlendMode.Screen,
                ),
            ),
            brightness = if (isLightSurface) 0.02f else 0.03f,
            contrast = 1.04f,
            saturation = 1.08f,
        )
    }

    val indicatorBlurColors = remember(primary, isLightSurface) {
        BlurColors(
            blendColors = listOf(
                BlendColorEntry(
                    color = if (isLightSurface) Color(0x8AFFFFFF) else Color(0x26262626),
                    mode = BlurBlendMode.PlusLighter,
                ),
                BlendColorEntry(
                    color = primary.copy(alpha = if (isLightSurface) 0.16f else 0.22f),
                    mode = BlurBlendMode.Overlay,
                ),
                BlendColorEntry(
                    color = if (isLightSurface) Color(0x1AFFFFFF) else Color(0x1A000000),
                    mode = BlurBlendMode.Screen,
                ),
            ),
            brightness = if (isLightSurface) 0.04f else 0.02f,
            contrast = 1.08f,
            saturation = 1.12f,
        )
    }

    BoxWithConstraints(
        modifier = modifier,
        contentAlignment = Alignment.BottomCenter,
    ) {
        val maxBarWidth = if (showLabel) (tabs.size * 156).dp else (tabs.size * 112).dp
        val barHeight by animateDpAsState(
            targetValue = if (showLabel) 76.dp else 64.dp,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
            label = "barHeight",
        )
        val indicatorOverflow = if (showLabel) 10.dp else 8.dp
        val floatingHeight = barHeight + indicatorOverflow * 2
        val edgeInset = 4.dp
        val barWidth = if (maxWidth > maxBarWidth) maxBarWidth else maxWidth
        var tabWidthPx by remember { mutableFloatStateOf(0f) }
        var totalWidthPx by remember { mutableFloatStateOf(0f) }

        val offsetAnimation = remember { Animatable(0f) }
        val panelOffset by remember(density, totalWidthPx) {
            derivedStateOf {
                if (totalWidthPx == 0f) {
                    0f
                } else {
                    val fraction = (offsetAnimation.value / totalWidthPx).fastCoerceIn(-1f, 1f)
                    with(density) {
                        4.dp.toPx() * fraction.sign * androidx.compose.animation.core.EaseOut.transform(abs(fraction))
                    }
                }
            }
        }

        class Holder {
            var instance: DampedDragAnimation? = null
        }

        val holder = remember { Holder() }

        val dampedDragAnimation = remember(animationScope, density, tabs.size, isLtr, totalWidthPx, tabWidthPx) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..tabs.lastIndex.toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 78f / 56f,
                canDrag = { offset ->
                    val animation = holder.instance ?: return@DampedDragAnimation true
                    if (tabWidthPx == 0f) return@DampedDragAnimation false

                    val currentValue = animation.value
                    val indicatorX = currentValue * tabWidthPx
                    val padding = with(density) { edgeInset.toPx() }
                    val globalTouchX = if (isLtr) {
                        padding + indicatorX + offset.x
                    } else {
                        totalWidthPx - padding - tabWidthPx - indicatorX + offset.x
                    }
                    globalTouchX in 0f..totalWidthPx
                },
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().coerceIn(0, tabs.lastIndex)
                    animateToValue(targetIndex.toFloat())
                    onSelect(tabs[targetIndex].key)
                    animationScope.launch {
                        offsetAnimation.animateTo(0f, spring(1f, 300f, 0.5f))
                    }
                },
                onDrag = { _, dragAmount ->
                    if (tabWidthPx > 0f) {
                        updateValue(
                            (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                                .coerceIn(0f, tabs.lastIndex.toFloat()),
                        )
                        animationScope.launch {
                            offsetAnimation.snapTo(offsetAnimation.value + dragAmount.x)
                        }
                    }
                },
            ).also { holder.instance = it }
        }

        LaunchedEffect(dampedDragAnimation, selectedIndex) {
            dampedDragAnimation.animateToValue(selectedIndex.toFloat())
        }

        val interactiveHighlight = remember(animationScope, dampedDragAnimation, tabWidthPx, panelOffset, isLtr) {
            InteractiveHighlight(
                animationScope = animationScope,
                position = { size, _ ->
                    Offset(
                        x = if (isLtr) {
                            (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                        } else {
                            size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx + panelOffset
                        },
                        y = size.height / 2f,
                    )
                },
            )
        }
        val indicatorMotionProgress = (
            dampedDragAnimation.pressProgress * 0.72f +
                abs(dampedDragAnimation.velocity).coerceIn(0f, 1f) * 0.85f
            ).coerceIn(0f, 1f)
        val indicatorLift by animateDpAsState(
            targetValue = indicatorOverflow * indicatorMotionProgress,
            animationSpec = spring(dampingRatio = 0.78f, stiffness = 520f),
            label = "indicatorLift",
        )
        val indicatorHeight by animateDpAsState(
            targetValue = barHeight + indicatorLift,
            animationSpec = spring(dampingRatio = 0.80f, stiffness = 520f),
            label = "indicatorHeight",
        )

        val borderColor = if (isLightSurface) Color.White.copy(alpha = 0.62f) else Color.White.copy(alpha = 0.10f)
        val containerGradient = Brush.linearGradient(
            colors = listOf(
                surface.copy(alpha = if (isLightSurface) 0.28f else 0.36f),
                surface.copy(alpha = if (isLightSurface) 0.12f else 0.24f),
            ),
        )

        Box(
            modifier = Modifier
                .width(barWidth)
                .height(floatingHeight)
                .onGloballyPositioned { coordinates ->
                    totalWidthPx = coordinates.size.width.toFloat()
                    val contentWidthPx = totalWidthPx - with(density) { edgeInset.toPx() * 2f }
                    tabWidthPx = contentWidthPx / tabs.size
                }
                .graphicsLayer {
                    translationX = panelOffset
                },
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .width(barWidth)
                    .height(barHeight)
                    .clip(containerShape)
                    .textureBlur(
                        backdrop = backdrop,
                        shape = containerShape,
                        blurRadius = 92f,
                        colors = outerBlurColors,
                        enabled = blurEnabled,
                    )
                    .background(containerGradient, containerShape)
                    .border(width = 1.dp, color = borderColor, shape = containerShape)
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White.copy(alpha = if (isLightSurface) 0.12f else 0.05f),
                                    Color.Transparent,
                                    Color.Black.copy(alpha = if (isLightSurface) 0.02f else 0.06f),
                                ),
                            ),
                        )
                    }
                    .then(interactiveHighlight.modifier)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    )
                    .padding(edgeInset),
            ) {
                CompositionLocalProvider(
                    LocalFloatingBottomBarTabScale provides {
                        lerp(1f, 1.2f, dampedDragAnimation.pressProgress)
                    },
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .clearAndSetSemantics {},
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        tabs.forEach { tab ->
                            LiquidBottomBarItem(
                                label = tab.label,
                                showLabel = showLabel,
                                selected = tab.key == selectedKey,
                                selectedIcon = tab.selectedIcon,
                                unselectedIcon = tab.unselectedIcon,
                                onClick = { onSelect(tab.key) },
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            if (tabWidthPx > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .offset(x = edgeInset, y = -indicatorLift / 2)
                        .padding(vertical = 2.dp)
                        .width(with(density) { tabWidthPx.toDp() })
                        .height(indicatorHeight)
                        .graphicsLayer {
                            translationX = if (isLtr) {
                                dampedDragAnimation.value * tabWidthPx + panelOffset
                            } else {
                                -dampedDragAnimation.value * tabWidthPx + panelOffset
                            }

                            scaleX = dampedDragAnimation.scaleX
                            scaleY = dampedDragAnimation.scaleY

                            val velocity = dampedDragAnimation.velocity / 10f
                            scaleX /= 1f - (velocity * 0.75f).coerceIn(-0.2f, 0.2f)
                            scaleY *= 1f - (velocity * 0.25f).coerceIn(-0.2f, 0.2f)
                        }
                        .then(interactiveHighlight.gestureModifier)
                        .then(dampedDragAnimation.modifier),
                ) {
                    LiquidIndicator(
                        backdrop = backdrop,
                        shape = indicatorShape,
                        blurEnabled = blurEnabled,
                        blurColors = indicatorBlurColors,
                        motionProgress = indicatorMotionProgress,
                    )
                }
            }
        }
    }
}

@Composable
private fun LiquidIndicator(
    backdrop: Backdrop,
    shape: Shape,
    blurEnabled: Boolean,
    blurColors: BlurColors,
    motionProgress: Float,
) {
    val surface = MiuixTheme.colorScheme.surface
    val primary = MiuixTheme.colorScheme.primary
    val isLightSurface = surface.luminance() > 0.5f
    val blurActive = blurEnabled && motionProgress > 0.04f

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clip(shape)
            .textureBlur(
                backdrop = backdrop,
                shape = shape,
                blurRadius = 22f + 38f * motionProgress,
                colors = blurColors,
                enabled = blurActive,
            )
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(
                        primary.copy(
                            alpha = if (isLightSurface) {
                                0.10f + motionProgress * 0.10f
                            } else {
                                0.18f + motionProgress * 0.08f
                            },
                        ),
                        surface.copy(
                            alpha = if (isLightSurface) {
                                0.46f - motionProgress * 0.10f
                            } else {
                                0.22f - motionProgress * 0.06f
                            },
                        ),
                    ),
                ),
                shape = shape,
            )
            .border(
                width = 1.dp,
                color = primary.copy(
                    alpha = if (isLightSurface) {
                        0.14f + motionProgress * 0.14f
                    } else {
                        0.24f + motionProgress * 0.16f
                    },
                ),
                shape = shape,
            )
            .drawWithContent {
                drawContent()
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isLightSurface) 0.24f else 0.08f),
                            Color.Transparent,
                        ),
                    ),
                    radius = size.minDimension * (0.78f + motionProgress * 0.16f),
                    center = center.copy(x = size.width * 0.33f, y = size.height * 0.24f),
                    blendMode = BlendMode.Screen,
                )
            },
    )
}

@Composable
private fun LiquidBottomBarItem(
    label: String,
    showLabel: Boolean,
    selected: Boolean,
    selectedIcon: ImageVector,
    unselectedIcon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var pressed by remember { mutableStateOf(false) }
    val scale = LocalFloatingBottomBarTabScale.current
    val primary = MiuixTheme.colorScheme.primary
    val selectedText = MiuixTheme.colorScheme.onSurface
    val idleText = MiuixTheme.colorScheme.onSurfaceVariantSummary
    val iconTint by animateColorAsState(
        targetValue = if (selected) primary else idleText,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 600f),
        label = "iconTint",
    )
    val textTint by animateColorAsState(
        targetValue = if (selected) selectedText else idleText,
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 560f),
        label = "textTint",
    )
    val scaleValue by animateFloatAsState(
        targetValue = when {
            pressed -> 0.96f
            selected -> 1.02f
            else -> 1f
        },
        animationSpec = spring(dampingRatio = 0.74f, stiffness = 580f),
        label = "itemScale",
    )
    val iconSize by animateDpAsState(
        targetValue = when {
            showLabel && selected -> 23.dp
            showLabel -> 21.dp
            selected -> 24.dp
            else -> 22.dp
        },
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 620f),
        label = "iconSize",
    )

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(26.dp))
            .then(
                if (selected) {
                    Modifier
                } else {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        role = Role.Tab,
                        onClick = {
                            pressed = true
                            onClick()
                            pressed = false
                        },
                    )
                }
            )
            .fillMaxHeight()
            .graphicsLayer {
                val animatedScale = scale()
                scaleX = scaleValue * animatedScale
                scaleY = scaleValue * animatedScale
            }
            .padding(
                horizontal = if (showLabel) 6.dp else 4.dp,
                vertical = if (showLabel) 8.dp else 6.dp,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            imageVector = if (selected) selectedIcon else unselectedIcon,
            contentDescription = label,
            modifier = Modifier.size(iconSize),
            tint = iconTint,
        )
        if (showLabel) {
            Text(
                text = label,
                color = textTint,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }
}
