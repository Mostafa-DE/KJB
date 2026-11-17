package app.lyrify.ui

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.IntSize
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.rememberTransition
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.minimumInteractiveComponentSize
import android.animation.ValueAnimator
import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollDispatcher
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import androidx.compose.ui.unit.Velocity
import kotlinx.coroutines.launch
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.view.WindowCompat
import app.lyrify.R
import app.lyrify.data.LyricsStatus
import app.lyrify.player.OpenedFiles
import app.lyrify.tags.CoverVersions
import coil3.ImageLoader
import coil3.asImage
import coil3.compose.AsyncImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.ImageRequest
import coil3.request.Options
import coil3.request.crossfade
import coil3.size.pxOrElse
import java.io.IOException
import java.text.NumberFormat

/** A count as the phone writes numbers ("1,345"). */
fun formatCount(count: Int): String = NumberFormat.getIntegerInstance().format(count)

/** [this] with tabular figures, so counts line up. */
fun TextStyle.tabular(): TextStyle = copy(fontFeatureSettings = TABULAR)

// The sung line

/**
 * The karaoke line: three lyric lines, the middle one a track whose "sung" part fills from the start, in the scheme's
 * muted colour and the brand highlight. It stands in for a missing cover and, [sweeping], is the "Looking up"
 * signature: the sung part sweeps across and restarts; otherwise, and with the phone's animations off, it rests still
 * at 65%. The app's logo is [BrandMark].
 */
@Composable
fun BrandGlyph(modifier: Modifier = Modifier, width: Dp = 18.dp, sweeping: Boolean = false) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val lines = muted.copy(alpha = 0.4f)
    val track = muted.copy(alpha = 0.22f)
    val sung = LocalBrandColors.current.highlight
    // Animators off (animator scale 0, Remove animations, Battery Saver): the still frame, not a sweep pinned at its end.
    val fill = if (glyphSweeps(sweeping, ValueAnimator.areAnimatorsEnabled())) {
        val sweep by rememberInfiniteTransition(label = "glyph").animateFloat(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(SWEEP_MS, easing = FastOutSlowInEasing), RepeatMode.Restart),
            label = "sung line",
        )
        sweep
    } else {
        RESTING_FILL
    }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // The lines span 48 units across and 38 down: 40, 48 and 30 long, 6, 10 and 6 thick, 8 apart.
    Canvas(modifier.size(width, width * GLYPH_HEIGHT / GLYPH_WIDTH)) {
        val unit = size.width / GLYPH_WIDTH
        fun line(top: Float, length: Float, thickness: Float, color: Color) {
            val w = length * unit
            if (w <= 0f) return
            drawRoundRect(color, Offset(if (rtl) size.width - w else 0f, top * unit), Size(w, thickness * unit), CornerRadius(thickness * unit / 2))
        }
        line(0f, 40f, 6f, lines)
        line(14f, 48f, 10f, track)
        line(14f, 48f * fill, 10f, sung)
        line(32f, 30f, 6f, lines)
    }
}

/** Whether the glyph's sung part moves: only while [sweeping] with the phone's animations on; else it rests at [RESTING_FILL]. */
fun glyphSweeps(sweeping: Boolean, animationsOn: Boolean): Boolean = sweeping && animationsOn

/**
 * The app's logo: the Note arm K (the Blade K whose arm is an eighth note) on its black tile, as on the launcher icon,
 * in both themes ([shape]: the launcher's rounded square unless given). In dark theme a hairline keeps it off the dark
 * background. Decorative.
 */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 36.dp, shape: Shape = RoundedCornerShape(size * 10 / 36)) {
    val border = if (LocalDarkTheme.current) Modifier.border(1.dp, BrandBlackBorder, shape) else Modifier
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size).clip(shape).background(BrandBlack).then(border)) {
        BrandK(height = size * BRAND_K_SHARE)
    }
}

/** The Note arm K alone, [height] tall, in its steel and coral: for a black ground. Decorative. */
@Composable
fun BrandK(height: Dp, modifier: Modifier = Modifier) {
    Image(painterResource(R.drawable.ic_brand_k), contentDescription = null, modifier = modifier.size(height * BRAND_K_WIDTH / BRAND_K_HEIGHT, height))
}

// Statuses

/** A status's icon on its tone: the accent fills the circle, the icon takes the surface colour. */
@Composable
fun ToneCircle(@DrawableRes icon: Int, tone: Tone, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    Box(contentAlignment = Alignment.Center, modifier = modifier.size(size).clip(CircleShape).background(LocalStatusColors.current[tone].accent)) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.surface, modifier = Modifier.size(size / 2))
    }
}

/**
 * The status as a 40 dp pill: icon and label on its tone ([tone] null: neutral, with [leading] in place of an icon),
 * and a chevron when it opens a sheet ([onClick]).
 */
@Composable
fun StatusPill(
    label: String,
    tone: Tone?,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    leading: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    large: Boolean = false,
) {
    val colors = tone?.let { LocalStatusColors.current[it] }
    val container = colors?.container ?: MaterialTheme.colorScheme.surfaceContainerHighest
    val content = colors?.onContainer ?: MaterialTheme.colorScheme.onSurface
    val click = if (onClick != null) {
        Modifier.clickable(interactionSource = null, indication = PressIndication(CircleShape, 0.96f), role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    // The prototype's .pill (24 dp, 12/16) and, [large], the Now screen's .spill (40 dp, 14 semibold).
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (large) 6.dp else 4.dp),
        modifier = modifier
            .then(click)
            .then(if (onClick != null) Modifier.minimumInteractiveComponentSize() else Modifier)
            .background(container, CircleShape)
            .heightIn(min = if (large) 40.dp else 24.dp)
            .padding(start = if (large) 10.dp else 6.dp, end = if (large) 12.dp else if (onClick != null) 4.dp else 9.dp),
    ) {
        CompositionLocalProvider(LocalContentColor provides content) {
            when {
                leading != null -> leading()
                icon != null -> Icon(painterResource(icon), contentDescription = null, modifier = Modifier.size(if (large) 18.dp else 16.dp))
            }
            Text(label, style = if (large) MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold) else MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (onClick != null) Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null, modifier = Modifier.size(16.dp))
        }
    }
}

/** [status]'s pill: its tone, icon and label. */
@Composable
fun StatusPill(status: LyricsStatus, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, large: Boolean = false) {
    val style = status.style
    StatusPill(stringResource(style.label), style.tone, modifier, icon = style.icon, onClick = onClick, large = large)
}

/** The 18 dp badge on a cover's corner: the status's icon on its tone. Decorative: the label is text elsewhere. */
@Composable
fun StatusBadge(status: LyricsStatus, modifier: Modifier = Modifier, size: Dp = 18.dp) {
    val style = status.style
    val colors = LocalStatusColors.current[style.tone]
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(size)
            .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
            .clip(CircleShape)
            .background(colors.container),
    ) {
        Icon(painterResource(style.icon), contentDescription = null, tint = colors.onContainer, modifier = Modifier.size(size * 0.65f))
    }
}

// Messages

/**
 * One line and one button, under the top bar; the button opens a sheet with the sentence. Announced when it appears: the
 * live region merges the title, so TalkBack hears it.
 */
@Composable
fun Banner(@DrawableRes icon: Int, title: String, action: String, container: Color, content: Color, onAction: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }
            .background(container, CircleShape)
            .heightIn(min = 56.dp)
            .padding(start = 16.dp, end = 4.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = content, modifier = Modifier.size(20.dp))
        Text(title, style = MaterialTheme.typography.titleSmall, color = content, modifier = Modifier.weight(1f))
        LyrifyButton(action, onClick = onAction, kind = ButtonKind.SECONDARY, compact = true)
    }
}

/** A sheet with a title, one or more sentences and maybe one button: the help behind a line, a banner or an info icon. */
@Composable
fun HelpSheet(title: String, vararg text: String, action: String? = null, onAction: () -> Unit = {}, onDismiss: () -> Unit) {
    LyrifySheet(
        onDismiss = onDismiss,
        spacing = 12.dp,
        footer = if (action == null) {
            null
        } else {
            {
                LyrifyButton(
                    action,
                    onClick = {
                        onAction()
                        onDismiss()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.semantics { heading() })
        text.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

/**
 * KJB's sheet: a floating card 8 dp from the edges, 32 dp corners all round, its own handle, the scrolling [content],
 * the toast slot, and a pinned [footer] (the action row stays in view). Drag, scrim, back and the window stay
 * ModalBottomSheet's. [padding] is the content's side margin; [scroll] where the content starts (the run log: its end).
 * Inside a [SheetFlow] it is only that flow's current step, in the flow's own window.
 */
@Composable
fun LyrifySheet(
    onDismiss: () -> Unit,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    spacing: Dp = 16.dp,
    padding: Dp = 20.dp,
    scroll: ScrollState = rememberScrollState(),
    content: @Composable ColumnScope.() -> Unit,
) {
    if (LocalSheetFlow.current) {
        Column { SheetBody(footer, spacing, padding, scroll, content) }
    } else {
        SheetWindow(onDismiss) { SheetBody(footer, spacing, padding, scroll, content) }
    }
}

/**
 * Sheets that lead to one another (a song's sheet, then Add to): one window whose card changes [step] as one smooth
 * change, the old step fading out before the new one fades in while the card takes its new height. Each step draws
 * its LyrifySheet as usual; null closes the window. [onDismiss]: the sheet dragged, tapped or backed away.
 */
@Composable
fun <K : Any> SheetFlow(step: K?, onDismiss: () -> Unit, content: @Composable (K) -> Unit) {
    // Closed, the window leaves at once and its exit ([SheetWindowExit]) carries it away.
    step ?: return
    val reduced = LocalReducedMotion.current
    SheetWindow(onDismiss) {
        AnimatedContent(
            targetState = step,
            modifier = Modifier.weight(1f, fill = false),
            transitionSpec = { fadeThrough(reduced) using SizeTransform { _, _ -> spring(dampingRatio = 1f, stiffness = 300f, visibilityThreshold = IntSize.VisibilityThreshold) } },
            label = "sheet step",
        ) { at ->
            // Only the step arriving or resting takes touches: a tap meant for the next step never lands on the old one.
            Box(Modifier.inertUnless { takesInput(headed = transition.targetState == EnterExitState.Visible, settled = true, container = false) }) {
                CompositionLocalProvider(LocalSheetFlow provides true) { content(at) }
            }
        }
    }
}

/** Whether a LyrifySheet is a step of a [SheetFlow], drawn in the flow's window. */
private val LocalSheetFlow = staticCompositionLocalOf { false }

/** A sheet's window and card: the scrim, the card floating 8 dp from the edges with its handle, short of the status bar. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetWindow(onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val chrome = LocalChromeColors.current
    val colors = MaterialTheme.colorScheme
    // A tall sheet stops short of the status bar.
    val top = WindowInsets.statusBars.getTop(LocalDensity.current)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = RectangleShape,
        containerColor = Color.Transparent,
        contentColor = colors.onSurface,
        tonalElevation = 0.dp,
        scrimColor = chrome.scrim,
        dragHandle = null,
        contentWindowInsets = { WindowInsets(0) },
    ) {
        SheetWindowExit()
        Column(
            Modifier
                .layout { measurable, constraints ->
                    val gap = top + SHEET_GAP.roundToPx()
                    val max = if (constraints.hasBoundedHeight) (constraints.maxHeight - gap).coerceAtLeast(constraints.minHeight) else constraints.maxHeight
                    val placeable = measurable.measure(constraints.copy(maxHeight = max))
                    layout(placeable.width, placeable.height) { placeable.place(0, 0) }
                }
                .fillMaxWidth()
                .padding(horizontal = SHEET_GAP)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = SHEET_GAP)
                .shadow(16.dp, SheetShape, ambientColor = chrome.shadow, spotColor = chrome.shadow)
                .background(chrome.sheet, SheetShape),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(vertical = 9.dp)
                    .size(36.dp, 4.dp)
                    .background(colors.outline.copy(alpha = 0.5f), CircleShape),
            )
            content()
        }
    }
}

/**
 * A sheet's scrolling content, its toast slot and its pinned footer. A drag down from anywhere on them closes the sheet
 * once the content is scrolled to its top ([dragDownCloses]), not only from the strip above (phone test 2026-11-11).
 */
@Composable
private fun ColumnScope.SheetBody(footer: (@Composable ColumnScope.() -> Unit)?, spacing: Dp, padding: Dp, scroll: ScrollState, content: @Composable ColumnScope.() -> Unit) {
    val sheet = remember { NestedScrollDispatcher() }
    Column(Modifier.weight(1f, fill = false).nestedScroll(NoNestedScroll, sheet).dragDownCloses(sheet) { scroll.value == 0 }) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(scroll).padding(start = padding, end = padding, top = 2.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        ToastSlot(Modifier.padding(horizontal = 12.dp), padding = PaddingValues(bottom = 8.dp))
        if (footer != null) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = footer)
        }
    }
}

/**
 * A drag down on a sheet's content, its cover, rows and buttons included, while the content is scrolled to its top
 * ([atTop]): taken before anything in the content can claim it ([sheetDrag]), it is handed to the sheet as a scrolling
 * list hands its overscroll ([sheet] dispatches it to the sheet's own nested scroll), so the sheet follows the finger,
 * and the fling at the lift settles it back or closes it. Taps, and drags up or sideways, stay the content's.
 */
private fun Modifier.dragDownCloses(sheet: NestedScrollDispatcher, atTop: () -> Boolean): Modifier = pointerInput(sheet) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        if (!atTop()) return@awaitEachGesture
        // Tracked from each change's own movement, not its place: the sheet, and this content with it, follows the finger.
        val velocity = VelocityTracker().apply { addPointerInputChange(down) }
        val slop = viewConfiguration.touchSlop
        var moved = Offset.Zero
        var taken = false
        while (true) {
            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: return@awaitEachGesture
            velocity.addPointerInputChange(change)
            if (!change.pressed) break
            val delta = change.positionChange()
            if (taken) {
                sheet.dispatchPostScroll(Offset.Zero, Offset(0f, delta.y), NestedScrollSource.UserInput)
            } else {
                if (change.isConsumed) return@awaitEachGesture
                moved += delta
                when (sheetDrag(moved, slop, atTop())) {
                    SheetDrag.WAIT -> continue
                    SheetDrag.LEAVE -> return@awaitEachGesture
                    SheetDrag.TAKE -> {
                        taken = true
                        sheet.dispatchPostScroll(Offset.Zero, Offset(0f, (moved.y - slop).coerceAtLeast(0f)), NestedScrollSource.UserInput)
                    }
                }
            }
            change.consume()
        }
        if (!taken) return@awaitEachGesture
        val fling = Velocity(0f, velocity.calculateVelocity().y)
        sheet.coroutineScope.launch {
            val consumed = sheet.dispatchPreFling(fling)
            sheet.dispatchPostFling(consumed, fling - consumed)
        }
    }
}

/** A nested scroll node that consumes nothing: the sheet's drag only dispatches through it. */
private val NoNestedScroll = object : NestedScrollConnection {}

/**
 * Inside a sheet's content: its window's exit ([R.style.SheetWindow]). A sheet its own button closes leaves the
 * composition at once, so the window slides a little and fades away with its scrim instead of vanishing.
 */
@Composable
fun SheetWindowExit() {
    val view = LocalView.current
    SideEffect { (view.parent as? DialogWindowProvider)?.window?.setWindowAnimations(R.style.SheetWindow) }
}

/**
 * The confirm dialog: a card with an icon tile, the title (a heading), the text, maybe a [body] (a field), and two equal
 * buttons, Cancel at the start and the confirm at the end ([destructive]: red; else ink). The buttons stack when the
 * phone's text is large, Cancel still first ([DIALOG_BUTTONS]). Over a brand-tinted scrim that fades in with it; a tap
 * on it or back cancels. It never leaves at once: the card and the scrim fade away first, and the action runs once they
 * have (decision D5, [DialogExit]).
 */
@Composable
fun ConfirmDialog(
    title: String,
    confirm: String,
    dismiss: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    text: String? = null,
    @DrawableRes icon: Int = R.drawable.ic_delete,
    destructive: Boolean = true,
    confirmEnabled: Boolean = true,
    body: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val chrome = LocalChromeColors.current
    val colors = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    val exit = remember { DialogExit() }
    val shown = remember { MutableTransitionState(false) }.apply { targetState = !exit.leaving }
    // The action runs only once the card and the scrim are gone; the empty window goes with them, so nothing is left
    // over the screen even while the caller has not closed the dialog yet.
    if (exit.leaving && shown.isIdle && !shown.currentState) {
        LaunchedEffect(Unit) { exit.ended() }
        return
    }
    val cancel = { exit.leave(onDismiss) }
    Dialog(onDismissRequest = { cancel() }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        PlainDialogWindow()
        val scrim by rememberTransition(shown, label = "scrim").animateFloat(
            transitionSpec = { tween(if (reduced) Motion.REDUCED_MS else if (targetState) Motion.FADE_IN_MS else Motion.FADE_OUT_MS) },
            label = "scrim",
        ) { if (it) 1f else 0f }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .drawBehind { drawRect(chrome.scrim, alpha = chrome.scrim.alpha * scrim) }
                .pointerInput(Unit) { detectTapGestures { cancel() } }
                .imePadding()
                .padding(24.dp),
        ) {
            AnimatedVisibility(visibleState = shown, enter = dialogEnter(reduced), exit = dialogExit(reduced)) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier
                        .widthIn(max = 340.dp)
                        .fillMaxWidth()
                        // A tap on the card stays on it.
                        .pointerInput(Unit) { detectTapGestures { } }
                        .shadow(24.dp, DialogShape, ambientColor = chrome.shadow, spotColor = chrome.shadow)
                        .background(chrome.sheet, DialogShape)
                        .semantics { paneTitle = title }
                        .padding(24.dp),
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(18.dp))
                            .background(if (destructive) colors.errorContainer else colors.surfaceContainerHigh),
                    ) {
                        Icon(painterResource(icon), contentDescription = null, tint = if (destructive) colors.onErrorContainer else colors.onSurfaceVariant, modifier = Modifier.size(28.dp))
                    }
                    // The card's own ink: the dialog is often opened beside a page, where no Surface hands one down.
                    Text(title, style = MaterialTheme.typography.titleLarge, color = colors.onSurface, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp).semantics { heading() })
                    if (text != null) Text(text, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant, textAlign = TextAlign.Center)
                    body?.let { CompositionLocalProvider(LocalContentColor provides colors.onSurface) { it(this) } }
                    val button: @Composable (DialogButton, Modifier) -> Unit = { which, at ->
                        when (which) {
                            DialogButton.DISMISS -> LyrifyButton(dismiss, { cancel() }, at.height(48.dp), kind = ButtonKind.SECONDARY)
                            DialogButton.CONFIRM ->
                                LyrifyButton(confirm, { exit.leave(onConfirm) }, at.height(48.dp), kind = if (destructive) ButtonKind.DANGER else ButtonKind.PRIMARY, enabled = confirmEnabled)
                        }
                    }
                    if (LocalDensity.current.fontScale > STACK_FONT_SCALE) {
                        Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            DIALOG_BUTTONS.forEach { button(it, Modifier.fillMaxWidth()) }
                        }
                    } else {
                        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            DIALOG_BUTTONS.forEach { button(it, Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }
    }
}

/** A confirm dialog's two buttons. */
enum class DialogButton { DISMISS, CONFIRM }

/**
 * Their order, side by side and stacked alike: Cancel first, so a destructive confirm is never the first, easiest
 * target, and the order does not flip when the phone's text grows.
 */
internal val DIALOG_BUTTONS = listOf(DialogButton.DISMISS, DialogButton.CONFIRM)

/** A dialog window that draws its own scrim: no platform dim; the system bars' icons follow the theme. */
@Composable
private fun PlainDialogWindow() {
    val view = LocalView.current
    val dark = LocalDarkTheme.current
    SideEffect {
        (view.parent as? DialogWindowProvider)?.window?.let { window ->
            window.setDimAmount(0f)
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}

// Cards and rows

/**
 * KJB's card: 24 dp corners on [color], no border, no elevation. With [onClick] it is a button that squashes a
 * little when pressed.
 */
@Composable
fun LyrifyCard(
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.surfaceContainerLow,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val click = if (onClick != null) {
        Modifier.clickable(interactionSource = null, indication = PressIndication(CardShape, 0.98f), onClickLabel = onClickLabel, role = Role.Button, onClick = onClick)
    } else {
        Modifier
    }
    Column(modifier.then(click).background(color, CardShape)) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

/** Rows grouped on one card, 16 dp from the screen's edges: the AI tab's rows, Settings. */
@Composable
fun GroupedRows(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = ScreenPadding).clip(CardShape).background(MaterialTheme.colorScheme.surfaceContainerLow),
        content = content,
    )
}

/**
 * A row of [GroupedRows]: its icon in a tinted squircle ([tile], [onTile]), its title and [supporting] line, and a
 * chevron or a value at the end. A hairline from after the icon separates it from the row above, unless it is [first].
 * One TalkBack stop, as a list item is.
 */
@Composable
fun GroupedRow(
    @DrawableRes icon: Int,
    title: String,
    tile: Color,
    onTile: Color,
    modifier: Modifier = Modifier,
    first: Boolean = false,
    supporting: (@Composable () -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    minHeight: Dp = 64.dp,
    trailing: (@Composable () -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val hairline = colors.outlineVariant.copy(alpha = HAIRLINE_ALPHA)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier.semantics(mergeDescendants = true) {})
            .drawBehind {
                if (!first) {
                    val start = HAIRLINE_START.toPx()
                    drawRect(hairline, Offset(if (layoutDirection == LayoutDirection.Rtl) 0f else start, 0f), Size(size.width - start, 1.dp.toPx()))
                }
            }
            .heightIn(min = minHeight)
            .padding(start = 14.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(tile)) {
            Icon(painterResource(icon), contentDescription = null, tint = onTile, modifier = Modifier.size(20.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = colors.onSurface)
            if (supporting != null) {
                CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant, LocalTextStyle provides RowLine) { supporting() }
            }
        }
        if (trailing != null) CompositionLocalProvider(LocalContentColor provides colors.onSurfaceVariant) { trailing() }
    }
}

/** What an empty state shows in its tile. */
sealed interface EmptyArt {
    /** The app's mark on black: nothing yet. */
    data object Glyph : EmptyArt

    data class Icon(@DrawableRes val icon: Int, val tone: Tone? = null) : EmptyArt
}

/**
 * An empty screen: a fan of three cards, a title, one line, maybe one button. It never appears at once: the cards fade
 * in as they rise 8 dp (motion spec 2.6), the words with them.
 */
@Composable
fun EmptyState(art: EmptyArt, title: String, modifier: Modifier = Modifier, text: String? = null, action: String? = null, onAction: () -> Unit = {}) {
    val reduced = LocalReducedMotion.current
    val shown = remember { Animatable(0f) }
    LaunchedEffect(Unit) { shown.animateTo(1f, tween(if (reduced) Motion.REDUCED_MS else Motion.OPEN_MS, easing = Motion.Rise)) }
    val rise = with(LocalDensity.current) { EMPTY_RISE.toPx() }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .graphicsLayer {
                alpha = shown.value
                translationY = if (reduced) 0f else rise * (1f - shown.value)
            }
            .padding(horizontal = 16.dp, vertical = 48.dp),
    ) {
        CardStack(art)
        // The prototype's .empty: the title 18 dp under the cards, the line 15/22 within about 30 characters.
        Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp).semantics { heading() })
        if (text != null) Text(text, style = EmptyLine, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 270.dp))
        if (action != null) LyrifyButton(action, onClick = onAction, modifier = Modifier.padding(top = 8.dp), compact = true)
    }
}

/** Three rounded cards fanned out: two quiet ones behind, the art in front (the app's mark on black, or the icon on its tone). Decorative. */
@Composable
private fun CardStack(art: EmptyArt) {
    val colors = MaterialTheme.colorScheme
    val tone = (art as? EmptyArt.Icon)?.tone?.let { LocalStatusColors.current[it] }
    val front = when {
        art == EmptyArt.Glyph -> BrandBlack
        tone != null -> tone.container
        else -> colors.surfaceContainerLowest
    }
    val edge = when {
        art == EmptyArt.Glyph && LocalDarkTheme.current -> Modifier.border(1.dp, BrandBlackBorder, StackShape)
        art != EmptyArt.Glyph && tone == null -> Modifier.border(1.dp, colors.outlineVariant, StackShape)
        else -> Modifier
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(112.dp).clearAndSetSemantics {}) {
        StackCard(colors.surfaceContainerHigh, rotation = -8f, shift = -14.dp)
        StackCard(colors.surfaceContainerHighest, rotation = 6f, shift = 14.dp)
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(STACK_WIDTH, STACK_HEIGHT).clip(StackShape).background(front).then(edge)) {
            when (art) {
                EmptyArt.Glyph -> BrandK(height = 40.dp)
                is EmptyArt.Icon -> Icon(
                    painterResource(art.icon),
                    contentDescription = null,
                    tint = tone?.onContainer ?: colors.onSurfaceVariant,
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

@Composable
private fun StackCard(color: Color, rotation: Float, shift: Dp) {
    Box(
        Modifier
            .offset(x = shift)
            .size(STACK_WIDTH, STACK_HEIGHT)
            .graphicsLayer { rotationZ = rotation }
            .background(color, StackShape),
    )
}

/** An empty state's line (.empty .ed): 15/22. */
private val EmptyLine = body(15f, 22)

/** A section's label (.slabel): 13/18 semibold. */
internal val SectionLabel = body(13f, 18, 600)

/** A section's title, a heading for TalkBack. */
@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = SectionLabel, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = modifier.semantics { heading() })
}

/** An icon and a short text: the song sheet's facts, the AI download sheet, the first run's privacy line. */
@Composable
fun Fact(@DrawableRes icon: Int, text: String, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium, middleEllipsis: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = modifier.fillMaxWidth()) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Text(
            text,
            style = style,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (middleEllipsis) 1 else Int.MAX_VALUE,
            overflow = if (middleEllipsis) TextOverflow.MiddleEllipsis else TextOverflow.Clip,
        )
    }
}

// Covers

/**
 * A song file's cover, by its path: MediaStore's thumbnail of the audio file (its embedded art). [mediaStoreId] is the
 * library's id for it, from the same MediaStore read: with it, a row's cover skips the lookup by path. [version] is the
 * cover's version in this process ([CoverVersions]): [SongCover] fills it in.
 */
data class SongArt(val path: String, val mediaStoreId: Long? = null, val version: Int = 0)

/**
 * Loads a [SongArt] through MediaStore, or a file another app opened from KJB's copy of it ([OpenedFiles.cover]); a song
 * without a cover fails, and the default cover under it shows.
 */
class SongArtFetcher(private val art: SongArt, private val options: Options) : Fetcher {
    override suspend fun fetch(): FetchResult? {
        val context = options.context
        val px = options.size.width.pxOrElse { DEFAULT_PX }
        val uri = art.mediaStoreId?.let { ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, it) } ?: audioUri(context, art.path)
            ?: return OpenedFiles.cover(context, art.path, px)?.let { ImageFetchResult(it.asImage(), isSampled = true, dataSource = DataSource.DISK) }
        val bitmap = try {
            context.contentResolver.loadThumbnail(uri, android.util.Size(px, px), null)
        } catch (e: IOException) {
            // No embedded cover: the default cover shows.
            return null
        }
        return ImageFetchResult(bitmap.asImage(), isSampled = true, dataSource = DataSource.DISK)
    }

    class Factory : Fetcher.Factory<SongArt> {
        override fun create(data: SongArt, options: Options, imageLoader: ImageLoader): Fetcher = SongArtFetcher(data, options)
    }

    private companion object {
        const val DEFAULT_PX = 256
    }
}

class SongArtKeyer : Keyer<SongArt> {
    override fun key(data: SongArt, options: Options): String = coverKey(data)
}

/** A cover's key in the image loader: its path, and its version once Covers & tags changed the song (apply or Undo). */
fun coverKey(art: SongArt): String = if (art.version == 0) art.path else "${art.path}#${art.version}"

/** The image loader's key for [model]'s cover when it is a song's ([coverKey]), whatever size it was loaded at; null for anything else. */
fun coverRequestKey(model: Any?): String? = (model as? SongArt)?.let(::coverKey)

/** The image loader of the whole app: song covers from MediaStore, cross-fading in. No network. */
fun songImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context)
        .components {
            add(SongArtKeyer())
            add(SongArtFetcher.Factory())
        }
        .crossfade(true)
        .build()

/** The audio file's MediaStore uri, by its path (readable for all audio with All files access). Blocking. */
private fun audioUri(context: Context, path: String): android.net.Uri? {
    @Suppress("DEPRECATION")
    val data = MediaStore.Audio.Media.DATA
    return try {
        context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Audio.Media._ID), "$data = ?", arrayOf(path), null)
            ?.use { cursor -> if (cursor.moveToFirst()) ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, cursor.getLong(0)) else null }
    } catch (e: SecurityException) {
        null
    }
}

/**
 * A song's cover ([model]: a [SongArt], the player's bitmap, or null) on KJB's default cover ([R.drawable.kjb_default_cover],
 * the one the widget, the notification and Android Auto show too): the default shows until the cover is in, and stays
 * when there is none. A song's cover already in memory at another size (the Now bar's, as the player's cover grows out
 * of it) shows while this size loads, never the default (phone test 2026-11-17). With [status], its badge sits on the
 * bottom-end corner. Decorative: the song's name and status are text next to it.
 */
@Composable
fun SongCover(model: Any?, modifier: Modifier = Modifier, size: Dp = 48.dp, shape: Shape = MaterialTheme.shapes.small, status: LyricsStatus? = null) {
    // A song Covers & tags changed gets its new cover at once: its version is part of the key.
    val versions by CoverVersions.versions.collectAsState()
    val shown = (model as? SongArt)?.let { it.copy(version = versions[it.path] ?: 0) } ?: model
    val context = LocalContext.current
    Box(modifier.size(size)) {
        Box(Modifier.fillMaxSize().clip(shape)) {
            Image(painterResource(R.drawable.kjb_default_cover), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (shown != null) {
                val request = remember(shown, context) { ImageRequest.Builder(context).data(shown).placeholderMemoryCacheKey(coverRequestKey(shown)).build() }
                AsyncImage(model = request, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        if (status != null) StatusBadge(status, Modifier.align(Alignment.BottomEnd).padding(1.dp))
    }
}

/** The karaoke line's extent, in the units of the old launcher icon's 108-unit canvas. */
private const val GLYPH_WIDTH = 48f
private const val GLYPH_HEIGHT = 38f

/** The mark's box (ic_brand_k), and its height on a [BrandMark] tile: a little smaller than on the launcher. */
private const val BRAND_K_WIDTH = 48f
private const val BRAND_K_HEIGHT = 50f
private const val BRAND_K_SHARE = 0.56f

/** The cards of [LyrifyCard], [GroupedRows]; sheets and dialogs. */
private val CardShape = RoundedCornerShape(24.dp)
private val SheetShape = RoundedCornerShape(32.dp)
private val DialogShape = RoundedCornerShape(32.dp)

/** How far an empty state rises as it appears. */
private val EMPTY_RISE = 8.dp

/** A sheet's distance from the screen's sides and bottom. */
private val SHEET_GAP = 8.dp

/** Above this text scale, a dialog's buttons stack. */
private const val STACK_FONT_SCALE = 1.3f
private const val HAIRLINE_ALPHA = 0.7f

/** Where a grouped row's hairline starts: after its 36 dp icon and the 16 dp on each side of it. */
private val HAIRLINE_START = 64.dp

/** A grouped row's second line (.gr .s): 13.5/19. */
private val RowLine = body(13.5f, 19)
private val StackShape = RoundedCornerShape(16.dp)
private val STACK_WIDTH = 64.dp
private val STACK_HEIGHT = 84.dp

/** The sung part of a glyph at rest, and its still frame while the phone's animations are off. */
internal const val RESTING_FILL = 0.65f

/** One sweep of the sung line. */
private const val SWEEP_MS = 1_400

/**
 * The prototype's link (.lnk): a 2 dp [color] underline. CSS puts it 4 dp under the baseline; at the body sizes that is
 * the bottom of the text's line box, where it is drawn.
 */
fun Modifier.linkUnderline(color: Color): Modifier = drawBehind {
    val thickness = LINK_LINE.toPx()
    val y = size.height - thickness / 2
    drawLine(color, Offset(0f, y), Offset(size.width, y), thickness)
}

private val LINK_LINE = 2.dp
