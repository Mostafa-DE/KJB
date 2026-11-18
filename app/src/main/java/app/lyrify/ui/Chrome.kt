package app.lyrify.ui

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.text.font.lerp
import androidx.compose.ui.graphics.lerp
import android.content.res.Resources
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.AccessibilityManager
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.lyrify.BuildConfig
import app.lyrify.R
import app.lyrify.settings.DockTab
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

// Glass

/** What glass chrome blurs: the screen's content, recorded by [glassSource]. */
@Stable
class GlassBackdrop internal constructor(internal val layer: GraphicsLayer) {
    internal var origin by mutableStateOf(Offset.Zero)
}

@Composable
fun rememberGlassBackdrop(): GlassBackdrop {
    val layer = rememberGraphicsLayer()
    return remember(layer) { GlassBackdrop(layer) }
}

/**
 * Records this content for the glass over it, and draws it as usual. On Android 12 and later the layer is a RenderNode,
 * so a scrolling list updates the glass without the glass recording again. Without blur ([enabled] false: Android 11,
 * Battery Saver, raised contrast) nothing is recorded: the glass is solid then and the content draws directly.
 */
fun Modifier.glassSource(backdrop: GlassBackdrop, enabled: Boolean): Modifier = if (!enabled) {
    this
} else {
    this
        .onGloballyPositioned { backdrop.origin = it.positionInRoot() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
        }
}

/**
 * Frosted glass: the content under it blurred (when [LocalGlassBlur]) under the translucent glass tint, a hairline and a
 * soft shadow; without blur the tint is nearly opaque, so text on it always has its contrast. [solid] (the Now bar and
 * the dock) never blurs: it is the opaque [ChromeColors.bar], with the same hairline and shadow.
 */
@Composable
fun GlassSurface(
    backdrop: GlassBackdrop?,
    shape: Shape,
    modifier: Modifier = Modifier,
    elevation: Dp = GLASS_SHADOW,
    solid: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val chrome = LocalChromeColors.current
    val under = MaterialTheme.colorScheme.background
    val source = backdrop?.takeIf { LocalGlassBlur.current && !solid }
    val blurred = rememberGraphicsLayer()
    val radius = with(LocalDensity.current) { GLASS_BLUR.toPx() }
    val blur = remember(radius) { BlurEffect(radius, radius, TileMode.Clamp) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    Box(
        modifier = modifier
            .then(if (elevation > 0.dp) Modifier.shadow(elevation, shape, ambientColor = chrome.shadow, spotColor = chrome.shadow) else Modifier)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .clip(shape)
            .drawBehind {
                if (source != null) {
                    // This window of the content, blurred, on the screen's own background (a list draws none of its own).
                    drawRect(under)
                    blurred.renderEffect = blur
                    val offset = origin - source.origin
                    blurred.record { translate(-offset.x, -offset.y) { drawLayer(source.layer) } }
                    drawLayer(blurred)
                    drawRect(chrome.glass)
                } else {
                    drawRect(if (solid) chrome.bar else chrome.glassSolid)
                }
            }
            .border(1.dp, chrome.glassEdge, shape),
    ) {
        // Words on the glass take the theme's ink, light or dark, whatever surrounds the surface.
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) { content() }
    }
}

// The dock

/** The dock's four destinations, in order. */
val DOCK_TABS: List<DockTab> = listOf(DockTab.LIBRARY, DockTab.PLAYLISTS, DockTab.SEARCH, DockTab.TOOLS)

@get:StringRes
val DockTab.label: Int
    get() = when (this) {
        DockTab.LIBRARY -> R.string.nav_library
        DockTab.PLAYLISTS -> R.string.nav_playlists
        DockTab.SEARCH -> R.string.nav_search
        DockTab.TOOLS -> R.string.nav_tools
    }

@get:DrawableRes
private val DockTab.icon: Int
    get() = when (this) {
        DockTab.LIBRARY -> R.drawable.ic_library_music
        DockTab.PLAYLISTS -> R.drawable.ic_queue_music
        DockTab.SEARCH -> R.drawable.ic_search
        DockTab.TOOLS -> R.drawable.ic_handyman
    }

@get:DrawableRes
private val DockTab.selectedIcon: Int
    get() = when (this) {
        DockTab.LIBRARY -> R.drawable.ic_library_music_fill
        // Material Symbols' filled queue_music is the same shape as the outlined one.
        DockTab.PLAYLISTS -> R.drawable.ic_queue_music
        DockTab.SEARCH -> R.drawable.ic_search
        DockTab.TOOLS -> R.drawable.ic_handyman_fill
    }

/** The dot on Tools: songs to check, duplicate groups not opened yet, or the AI needing the user. */
fun toolsDot(checks: Int, duplicates: Int, aiAttention: Boolean): Boolean = checks > 0 || duplicates > 0 || aiAttention

/** Which side a new tab comes from: 1 from the end (a tab after the one shown), -1 from the start, 0 for the same tab. */
fun tabSlide(from: DockTab, to: DockTab): Int = DOCK_TABS.indexOf(to).compareTo(DOCK_TABS.indexOf(from)).coerceIn(-1, 1)

/** The dot on a dock item: it pops in and out, never appearing at once. */
@Composable
private fun DockDot(dot: Boolean, modifier: Modifier) {
    val reduced = LocalReducedMotion.current
    AnimatedVisibility(dot, modifier, enter = badgeIn(reduced), exit = badgeOut(reduced)) { DotBadge() }
}

/** A dock item's name for TalkBack: its label, then its dot when it has one. */
fun dockItemName(vararg parts: String?): String = parts.filterNotNull().joinToString(", ")

/**
 * The dock: a glass capsule with Library, Playlists, Search and Tools, each an icon over its label, the selected one
 * marked by the sung line; a dot on Tools when something there needs the user.
 */
@Composable
fun Dock(selected: DockTab, dot: Boolean, onSelect: (DockTab) -> Unit, modifier: Modifier = Modifier) {
    GlassSurface(null, CircleShape, modifier.fillMaxWidth().padding(horizontal = 12.dp), solid = true) {
        DockItems(selected, dot, onSelect)
    }
}

/** The items at equal widths, and the sung line under the selected one; its leading end travels faster, so it stretches on the way. */
@Composable
private fun DockItems(selected: DockTab, dot: Boolean, onSelect: (DockTab) -> Unit) {
    val highlight = LocalBrandColors.current.highlight
    val reduced = LocalReducedMotion.current
    val line = DOCK_TABS.indexOf(selected)
    val start = remember { Animatable(line.toFloat()) }
    val end = remember { Animatable(line.toFloat()) }
    LaunchedEffect(line, reduced) {
        val target = line.toFloat()
        if (reduced) {
            start.snapTo(target)
            end.snapTo(target)
            return@LaunchedEffect
        }
        val forward = target > start.value
        launch { start.animateTo(target, if (forward) LINE_TRAIL else LINE_LEAD) }
        launch { end.animateTo(target, if (forward) LINE_LEAD else LINE_TRAIL) }
    }
    Row(
        Modifier
            .selectableGroup()
            .heightIn(min = 64.dp)
            .padding(horizontal = 6.dp)
            .drawBehind {
                val item = size.width / DOCK_TABS.size
                val width = SUNG_LINE_WIDTH.toPx()
                val height = SUNG_LINE_HEIGHT.toPx()
                val left = min(start.value, end.value) * item + (item - width) / 2
                val right = max(start.value, end.value) * item + (item + width) / 2
                val x = if (layoutDirection == LayoutDirection.Rtl) size.width - right else left
                drawRoundRect(highlight, Offset(x, size.height - LINE_BOTTOM.toPx() - height), Size(right - left, height), CornerRadius(height / 2))
            },
    ) {
        DOCK_TABS.forEach { tab -> DockItem(tab, tab == selected, dot && tab == DockTab.TOOLS, Modifier.weight(1f).tourTarget(dockStop(tab))) { onSelect(tab) } }
    }
}

@Composable
private fun DockItem(tab: DockTab, selected: Boolean, dot: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    // The label and icon take the selected look over the line's own travel time, never at once.
    val lit by animateFloatAsState(if (selected) 1f else 0f, motion(tween(Motion.SHORT_MS, easing = Motion.Standard), LocalReducedMotion.current), label = "dock item")
    val label = stringResource(tab.label)
    val name = dockItemName(label, if (dot) stringResource(R.string.nav_attention) else null)
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .selectable(selected, interactionSource = null, indication = PressIndication(RoundedCornerShape(20.dp), 0.92f), role = Role.Tab, onClick = onClick)
            .semantics { contentDescription = name }
            .heightIn(min = 56.dp)
            .padding(top = 9.dp, bottom = 14.dp),
    ) {
        Box {
            Icon(
                painterResource(if (selected) tab.selectedIcon else tab.icon),
                contentDescription = null,
                tint = lerp(colors.onSurfaceVariant, brand.highlight, lit),
                modifier = Modifier.size(24.dp),
            )
            DockDot(dot, Modifier.align(Alignment.TopEnd).offset(x = 3.dp, y = (-3).dp))
        }
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = lerp(FontWeight.Medium, FontWeight(650), lit),
            color = lerp(colors.onSurfaceVariant, colors.onSurface, lit),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp).clearAndSetSemantics {},
        )
    }
}

/** A count on a row or a dock item: a coral capsule ringed in the glass colour, so it reads as cut out of it. Decorative. */
@Composable
fun CountBadge(count: Int, modifier: Modifier = Modifier) {
    val brand = LocalBrandColors.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clearAndSetSemantics {}
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .padding(2.dp)
            .background(brand.highlight, CircleShape)
            .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp)
            .padding(horizontal = 5.dp),
    ) {
        Text(formatCount(count), style = MaterialTheme.typography.labelSmall.tabular(), fontWeight = FontWeight.Bold, color = brand.onHighlight, maxLines = 1)
    }
}

/** An 8 dp coral dot with the same ring, for "something needs you". Decorative. */
@Composable
fun DotBadge(modifier: Modifier = Modifier) {
    Box(
        modifier
            .clearAndSetSemantics {}
            .background(MaterialTheme.colorScheme.surfaceContainer, CircleShape)
            .padding(2.dp)
            .size(8.dp)
            .background(LocalBrandColors.current.highlight, CircleShape),
    )
}

// Top bars

/**
 * KJB's top bar: the title in Bricolage, its navigation and actions as glass circles. The page's colour at rest, opaque
 * ([TopBand]); once content is [under] it, a glass band and a hairline fade in. No colour flip.
 */
@Composable
fun LyrifyTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    under: Boolean = false,
    backdrop: GlassBackdrop? = null,
    navigation: (@Composable () -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    TopBand(under, backdrop, modifier) {
        Box(Modifier.statusBarsPadding()) { TopBarRow(title, navigation, actions) }
    }
}

/** A top bar's line: maybe Back, the title (one heading, in the title style), and the actions at the end. */
@Composable
private fun TopBarRow(title: @Composable () -> Unit, navigation: (@Composable () -> Unit)?, actions: @Composable RowScope.() -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.heightIn(min = BAR_HEIGHT).padding(start = if (navigation != null) 8.dp else 20.dp, end = 12.dp),
    ) {
        navigation?.let {
            it()
            Spacer(Modifier.width(4.dp))
        }
        Box(Modifier.weight(1f).semantics(mergeDescendants = true) { heading() }) {
            ProvideTextStyle(MaterialTheme.typography.headlineSmall) { title() }
        }
        Row(verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/**
 * The header of the four main pages (Library, Playlists, Search, Tools): the KJB logo at the start, then the page's
 * title in the title style, the page's own actions at the end (user decision 2026-11-11). The logo is decorative, so
 * TalkBack reads one heading, the title alone. [below]: more of the band under the line, as Search's field. Inner pages
 * keep [LyrifyTopBar] with Back and their own title.
 */
@Composable
fun MainTopBar(tab: DockTab, under: Boolean, backdrop: GlassBackdrop?, actions: @Composable RowScope.() -> Unit = {}, below: (@Composable () -> Unit)? = null) {
    val title = stringResource(headerPage(tab))
    TopBand(under, backdrop) {
        Column(Modifier.statusBarsPadding()) {
            TopBarRow(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        HeaderLogo()
                        Spacer(Modifier.width(12.dp))
                        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                },
                navigation = null,
                actions = actions,
            )
            below?.invoke()
        }
    }
}

/** The name a dock destination's header shows after the logo: the dock's own word for it. */
fun headerPage(tab: DockTab): Int = tab.label

/** The header's logo: the launcher's black tile with the Note arm K, as default cover A. */
val HEADER_LOGO = 30.dp

/** The KJB logo at [HEADER_LOGO], its corners rounded as the launcher's; in dark theme a hairline keeps it off the page. Decorative. */
@Composable
private fun HeaderLogo() {
    val shape = RoundedCornerShape(HEADER_LOGO * 10 / 36)
    val edge = if (LocalDarkTheme.current) Modifier.border(1.dp, BrandBlackBorder, shape) else Modifier
    Image(painterResource(R.drawable.kjb_default_cover), contentDescription = null, modifier = Modifier.size(HEADER_LOGO).clip(shape).then(edge))
}

/**
 * The band behind a top bar, from the top of the status bar: the page's own colour at rest, opaque ([ChromeColors.topBar]),
 * so a list scrolls under it and never shows through; glass and a hairline while content is [under] it.
 */
@Composable
fun TopBand(under: Boolean, backdrop: GlassBackdrop?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val band by animateFloatAsState(if (under) 1f else 0f, motion(tween(BAND_FADE_MS), LocalReducedMotion.current), label = "band")
    Box(modifier.fillMaxWidth().background(LocalChromeColors.current.topBar)) {
        if (band > 0f) GlassSurface(backdrop, RectangleShape, Modifier.matchParentSize().graphicsLayer { alpha = band }, elevation = 0.dp) {}
        content()
    }
}

/**
 * Whether the list has scrolled under the top bar. The bar keeps the scroll state's limit, as Material's bar does: it is
 * what lets the state say how much content is under it. Changes only when the answer does, not on every scrolled pixel.
 */
@ExperimentalMaterial3Api
@Composable
fun TopAppBarScrollBehavior.contentUnder(): Boolean {
    val limit = with(LocalDensity.current) { -BAR_HEIGHT.toPx() }
    SideEffect { if (state.heightOffsetLimit != limit) state.heightOffsetLimit = limit }
    val under by remember(state) { derivedStateOf { state.overlappedFraction > 0.01f } }
    return under
}

/**
 * A round icon button: a 40 dp glass circle in top bars (back, close, actions), or with [glass] false a plain one
 * (inside a field, on a row). The tap, and what TalkBack outlines, take a target of at least 48 dp round it; the press
 * shows on the circle.
 */
@Composable
fun ChromeIconButton(
    @DrawableRes icon: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    glass: Boolean = true,
    size: Dp = 40.dp,
    iconSize: Dp = 22.dp,
    tint: Color = LocalContentColor.current,
) {
    val chrome = LocalChromeColors.current
    val source = remember { MutableInteractionSource() }
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .clickable(interactionSource = source, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .minimumInteractiveComponentSize(),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(size)
                .indication(source, PressIndication(CircleShape, 0.9f))
                .then(if (glass) Modifier.background(chrome.glassSolid, CircleShape).border(1.dp, chrome.glassEdge, CircleShape) else Modifier),
        ) {
            Icon(painterResource(icon), contentDescription = null, tint = if (enabled) tint else tint.copy(alpha = DISABLED_ALPHA), modifier = Modifier.size(iconSize))
        }
    }
}

/** The search field of Songs and the Tone picker: a 52 dp capsule with the search icon, a coral caret and focus ring. */
@Composable
fun LyrifySearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focus: FocusRequester? = null,
    onSearch: () -> Unit = {},
    trailing: @Composable RowScope.() -> Unit = {},
) {
    LyrifyField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.then(if (focus != null) Modifier.focusRequester(focus) else Modifier),
        placeholder = placeholder,
        shape = CircleShape,
        ring = 1.5.dp,
        leading = { Icon(painterResource(R.drawable.ic_search), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(22.dp)) },
        trailing = trailing,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
    )
}

// Menus

/** KJB's menu: DropdownMenu (its anchoring, dismiss and focus) as a 20 dp card with a hairline and a soft shadow. */
@Composable
fun LyrifyMenu(expanded: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(20.dp),
        containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 0.dp,
        shadowElevation = 16.dp,
        border = BorderStroke(1.dp, LocalChromeColors.current.glassEdge),
        content = content,
    )
}

/** A menu item: 48 dp, inset from the menu's edge; the selected one on the brand's container with a check, a destructive one in red. */
@Composable
fun MenuRow(text: String, @DrawableRes icon: Int?, onClick: () -> Unit, selected: Boolean = false, destructive: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val content = when {
        destructive -> colors.error
        selected -> brand.onHighlightContainer
        else -> colors.onSurface
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier
            .padding(horizontal = 6.dp)
            .fillMaxWidth()
            .sizeIn(minWidth = 160.dp, maxWidth = 280.dp, minHeight = 48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) brand.highlightContainer else Color.Transparent)
            .clickable(onClick = onClick)
            .then(if (selected) Modifier.semantics { this.selected = true } else Modifier)
            .padding(horizontal = 12.dp),
    ) {
        if (icon != null) Icon(painterResource(icon), contentDescription = null, tint = if (destructive || selected) content else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = content, modifier = Modifier.weight(1f))
        if (selected) Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
    }
}

// Toasts

/** A short message: its text, maybe a status icon on its tone, maybe one action. */
@Immutable
data class Toast(val text: String, @DrawableRes val icon: Int? = null, val tone: Tone? = null, val action: String? = null)

/**
 * KJB's messages, in place of SnackbarHostState: one at a time, and the newest wins. [show] replaces the message on
 * screen at once, as Android's Snackbar does: the replaced one's caller hears "no action" (its Undo goes away, its change
 * stays done), but a message with an action only at its own time ([toastMillis]), so what its Undo needs (a backup, a
 * record) is never let go sooner than had it stayed. [show] waits until its message has gone and says whether its action
 * was tapped. A message floats at the newest [ToastSlot] on screen (the shell's above the Now bar and the dock, else the
 * open sheet's or full screen's), over the content, never moving it. [a11y], the window's [LocalAccessibilityManager],
 * stretches each message's time to the phone's accessibility timeout.
 */
@Stable
class ToastState private constructor(private val live: Boolean, private val a11y: AccessibilityManager?) {
    constructor(a11y: AccessibilityManager? = null) : this(live = true, a11y)

    private val slots = mutableStateListOf<Any>()

    /** How a message left the screen. */
    internal enum class End {
        /** Its action was tapped. */
        ACTED,

        /** Its time ran out, or it was swiped or dismissed away. */
        DISMISSED,

        /** A newer message took its place. */
        REPLACED,
    }

    /** The message on screen, for [millis] ([toastMillis]); [dismiss] takes it off, [acted] when its action was tapped. */
    class Shown internal constructor(val toast: Toast, internal val millis: Long) {
        internal val result = CompletableDeferred<End>()

        fun dismiss(acted: Boolean = false) {
            result.complete(if (acted) End.ACTED else End.DISMISSED)
        }
    }

    var current: Shown? by mutableStateOf(null)
        private set

    suspend fun show(toast: Toast): Boolean {
        if (!live) return false
        val shown = Shown(toast, toastMillis(toast, a11y))
        // The message on screen goes at once, its action with it; a late tap or timer on it changes nothing.
        current?.result?.complete(End.REPLACED)
        current = shown
        return try {
            coroutineScope {
                // Its own time, from now: replaced, a message with an action ends for its caller no sooner.
                val due = if (toast.action != null) launch { delay(shown.millis) } else null
                val end = shown.result.await()
                if (end == End.REPLACED) due?.join() else due?.cancel()
                end == End.ACTED
            }
        } finally {
            if (current === shown) current = null
        }
    }

    suspend fun show(text: String): Boolean = show(Toast(text))

    internal fun register(slot: Any) {
        slots += slot
    }

    internal fun unregister(slot: Any) {
        slots -= slot
    }

    /** Whether [slot] shows the message: the newest slot on screen does, so one raised in an open sheet takes it. */
    fun showsIn(slot: Any): Boolean = slots.lastOrNull() === slot

    internal companion object {
        /** Stands in for the app's state where none was provided (release builds only): drops every message at once. */
        val Detached = ToastState(live = false, a11y = null)
    }
}

/** Shows the string [message] with the icon and tone that fit it ([messageToast]). */
suspend fun ToastState.say(resources: Resources, @StringRes message: Int, vararg args: Any): Boolean =
    show(messageToast(message, resources.getString(message, *args)))

/** The app's one [ToastState]; the activity provides it, so the shell, its sheets and full screens find it. */
val LocalToasts = staticCompositionLocalOf { missingToasts(BuildConfig.DEBUG) }

/**
 * No [ToastState] was provided: a mistake. A debug build fails loud; a release build drops the messages rather than
 * crash, or keep a caller waiting for a message nothing would ever show.
 */
internal fun missingToasts(debug: Boolean): ToastState {
    check(!debug) { "No ToastState: provide LocalToasts above this composable (the activity does)." }
    return ToastState.Detached
}

/**
 * How long a message stays: 3 s, 6 s with an action or two lines, or the phone's accessibility timeout ([a11y], asked
 * for text, and for controls when it has an action) when that is longer. Never shorter, whatever the phone answers.
 */
fun toastMillis(toast: Toast, a11y: AccessibilityManager?): Long {
    val own = if (toast.action != null || toast.text.length > TWO_LINES) LONG_MS else SHORT_MS
    val asked = a11y?.calculateRecommendedTimeoutMillis(own, containsIcons = toast.icon != null, containsText = true, containsControls = toast.action != null) ?: own
    return maxOf(own, asked)
}

/** [text], the words of the string or plural [message], with the icon and tone that fit it: failures in the error tone. */
fun messageToast(message: Int, text: String): Toast = when (message) {
    R.string.check_done_synced -> Toast(text, R.drawable.ic_lyrics, Tone.SYNCED)
    R.string.removal_done, R.string.removal_done_samsung, R.plurals.dupes_moved, R.plurals.dupes_moved_some, R.string.tones_deleted,
    R.plurals.trash_deleted, R.plurals.trash_deleted_some,
    -> Toast(text, R.drawable.ic_delete, Tone.NONE)
    R.string.dupes_restored -> Toast(text, R.drawable.ic_restore_from_trash, Tone.SYNCED)
    R.string.check_not_saved, R.string.check_busy, R.string.dupes_trash_unconfirmed -> Toast(text, R.drawable.ic_sync_problem, Tone.ERROR)
    R.string.removal_failed, R.string.dupes_trash_failed, R.plurals.dupes_moved_none, R.plurals.dupes_trash_refused,
    R.plurals.trash_deleted_none, R.plurals.trash_delete_refused,
    R.string.tones_not_saved, R.string.tones_song_unreadable, R.string.tones_not_deleted, R.string.tones_not_renamed,
    R.string.tones_not_played, R.string.tones_clock_not_opened,
    -> Toast(text, R.drawable.ic_error, Tone.ERROR)
    else -> Toast(text)
}

/** Which way a message floats from its [ToastSlot]: up from it (above a dock or buttons), or down from it (under a top bar). */
enum class ToastFloat { UP, DOWN }

/** A message's pill: one line, this high (the phone test of 2026-11-11 found the old two-line card too big). */
val TOAST_HEIGHT = 40.dp

/** How far the shell's message floats above the Now bar, or above the dock when nothing plays: clear of them, close to them. */
val TOAST_GAP = 10.dp

/**
 * Where a message of [size] sits from its slot, [slotWidth] wide: centred, and up from the slot ([ToastFloat.UP], its
 * bottom edge [gap] px above it) or down from it (its top edge [gap] px below it). Never off the slot's start.
 */
fun toastPlace(float: ToastFloat, slotWidth: Int, size: IntSize, gap: Int): IntOffset =
    IntOffset(((slotWidth - size.width) / 2).coerceAtLeast(0), if (float == ToastFloat.UP) -(size.height + gap) else gap)

/**
 * Where the app's message floats in this window. The slot takes no space: the message, a compact pill as wide as its
 * words, is drawn centred over the content, up from the slot ([ToastFloat.UP], its bottom edge the bottom of [padding]
 * above it) or down from it (the top of [padding] below it), and nothing under it moves. Each slot registers while it is
 * composed and only the newest shows the message, so one raised under an open sheet appears in the sheet. Put the slot
 * after the content it floats over, so it draws on top. A newer message replaces the one shown at once: the old one
 * fades, the new one rises in (the prototype's toastIn).
 */
@Composable
fun ToastSlot(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
    float: ToastFloat = ToastFloat.UP,
    state: ToastState = LocalToasts.current,
) {
    val key = remember { Any() }
    DisposableEffect(state) {
        state.register(key)
        onDispose { state.unregister(key) }
    }
    val shown = state.current?.takeIf { state.showsIn(key) }
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val rise = with(density) { TOAST_RISE.roundToPx() }
    val gap = with(density) { (if (float == ToastFloat.UP) padding.calculateBottomPadding() else padding.calculateTopPadding()).roundToPx() }
    AnimatedContent(
        targetState = shown,
        transitionSpec = {
            val enter = if (reduced) {
                fadeIn(tween(TOAST_IN_MS))
            } else {
                fadeIn(tween(TOAST_IN_MS, easing = ToastEasing)) +
                    scaleIn(tween(TOAST_IN_MS, easing = ToastEasing), initialScale = TOAST_SCALE, transformOrigin = TransformOrigin(0.5f, if (float == ToastFloat.UP) 1f else 0f)) +
                    slideInVertically(tween(TOAST_IN_MS, easing = ToastEasing)) { if (float == ToastFloat.UP) rise else -rise }
            }
            (enter togetherWith fadeOut(tween(TOAST_OUT_MS))).using(SizeTransform(clip = false))
        },
        contentAlignment = if (float == ToastFloat.UP) Alignment.BottomCenter else Alignment.TopCenter,
        label = "toast",
        modifier = modifier.floatOver(float, gap),
    ) { message ->
        if (message != null) ToastPill(message)
    }
}

/**
 * Measures the message at its own width but reports the slot's width and no height, and draws it centred, [gap] px up
 * or down from this point ([toastPlace]): it never pushes the layout. Its touch is where it is drawn: Compose hit-tests
 * a child outside its parent's bounds unless a layer clips it, so no layer between the message and the screen may clip
 * at the slot (hence SizeTransform(clip = false)).
 */
private fun Modifier.floatOver(float: ToastFloat, gap: Int): Modifier = layout { measurable, constraints ->
    val placeable = measurable.measure(constraints.copy(minWidth = 0, minHeight = 0, maxHeight = Constraints.Infinity))
    layout(constraints.maxWidth, 0) { placeable.place(toastPlace(float, constraints.maxWidth, IntSize(placeable.width, placeable.height), gap)) }
}

/**
 * The ink pill, one line high: its icon, its words (ellipsised), its action; hold it to keep it, swipe it away, TalkBack
 * can dismiss it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToastPill(message: ToastState.Shown, modifier: Modifier = Modifier) {
    val toast = message.toast
    val chrome = LocalChromeColors.current
    // Its time stops while a finger is on it and starts again, whole, when the finger lifts, as Snackbar's does.
    var held by remember(message) { mutableStateOf(false) }
    LaunchedEffect(message, held) {
        if (held) return@LaunchedEffect
        delay(message.millis)
        message.dismiss()
    }
    val shape = CircleShape
    SwipeToDismissBox(
        state = rememberSwipeToDismissBoxState(),
        backgroundContent = {},
        modifier = modifier,
        onDismiss = { message.dismiss() },
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .shadow(8.dp, shape, ambientColor = chrome.shadow, spotColor = chrome.shadow)
                .background(chrome.toast, shape)
                .then(if (chrome.toastEdge.alpha > 0f) Modifier.border(1.dp, chrome.toastEdge, shape) else Modifier)
                .pointerInput(message) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        held = true
                        waitForUpOrCancellation()
                        held = false
                    }
                }
                .semantics(mergeDescendants = true) {
                    liveRegion = LiveRegionMode.Polite
                    dismiss {
                        message.dismiss()
                        true
                    }
                }
                .heightIn(min = TOAST_HEIGHT)
                .padding(start = if (toast.icon != null) 8.dp else 16.dp, end = if (toast.action != null) 4.dp else 16.dp),
        ) {
            toast.icon?.let { ToneCircle(it, toast.tone ?: Tone.NONE, size = 24.dp) }
            Text(toast.text, style = MaterialTheme.typography.bodyMedium, color = chrome.onToast, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            // The action keeps to the pill's height; Compose still widens its touch to 48 dp.
            toast.action?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelLarge,
                    color = chrome.toastAction,
                    maxLines = 1,
                    modifier = Modifier.clip(CircleShape).clickable(role = Role.Button) { message.dismiss(acted = true) }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

private val GLASS_BLUR = 24.dp
private val GLASS_SHADOW = 16.dp
private val BAR_HEIGHT = 64.dp
private const val BAND_FADE_MS = 180

/** The sung line sits this far above the capsule's bottom edge, under the labels. */
private val LINE_BOTTOM = 7.dp
private val LINE_LEAD = spring(dampingRatio = 0.8f, stiffness = 700f, visibilityThreshold = 0.001f)
private val LINE_TRAIL = spring(dampingRatio = 0.8f, stiffness = 260f, visibilityThreshold = 0.001f)
private const val DISABLED_ALPHA = 0.38f

private const val SHORT_MS = 3_000L
private const val LONG_MS = 6_000L

/** About two lines of a toast at the default font size. */
private const val TWO_LINES = 48
/** The prototype's toastIn: 0.28 s on its pop curve, from 16 dp below at 92% size and transparent. */
private const val TOAST_IN_MS = Motion.ARRIVE_MS
private val ToastEasing = Motion.Pop
private const val TOAST_SCALE = 0.92f
private val TOAST_RISE = 16.dp
private const val TOAST_OUT_MS = Motion.QUICK_MS
