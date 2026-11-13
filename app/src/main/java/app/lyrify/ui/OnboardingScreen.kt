package app.lyrify.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.lyrify.R

/**
 * The first run (onboarding-research.md 4.2, the approved mock onboarding-design/onboarding.html): Welcome, Find your
 * music (All files access, required: no Not now), Other music apps (notification access, optional), and You're ready,
 * which takes the tour or skips it ([onFinish]). A phone that set up before and lost All files access gets Find your music
 * alone ([setupScreens]). Each screen's text scrolls over a pinned bar, so its button is always in reach; Back goes one
 * screen back, and leaves KJB from the first. The grants are read again on every return ([access]).
 */
@Composable
fun OnboardingScreen(
    access: Access,
    setupDone: Boolean,
    onOpenAllFilesAccess: () -> Unit,
    onOpenNotificationAccess: () -> Unit,
    onOpenAppInfo: () -> Unit,
    onFinish: (declinesTour: Boolean) -> Unit,
) {
    val screens = remember(setupDone) { setupScreens(setupDone) }
    var picked by rememberSaveable { mutableStateOf(screens.first()) }
    val at = picked.takeIf { it in screens } ?: screens.first()
    // A screen reached by a move (not the first one shown) takes the focus on its title, so TalkBack reads it first.
    var moved by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    val activity = LocalActivity.current
    val back = setupBack(screens, at)
    BackHandler(enabled = back != null) {
        moved = true
        back?.let { picked = it }
    }
    val go: (SetupMove) -> Unit = { move ->
        when (move) {
            is SetupMove.To -> {
                moved = true
                picked = move.screen
            }
            SetupMove.OpenFiles -> onOpenAllFilesAccess()
            SetupMove.OpenNotifications -> onOpenNotificationAccess()
            is SetupMove.Finish -> onFinish(move.declinesTour)
        }
    }
    val reduced = LocalReducedMotion.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val slide = with(LocalDensity.current) { SLIDE.roundToPx() }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        SetupTop(screens, at, onBack = { if (back != null) go(SetupMove.To(back)) else activity?.finish() })
        AnimatedContent(
            targetState = at,
            modifier = Modifier.weight(1f),
            transitionSpec = { setupMove(screens, slide, rtl, reduced) },
            label = "setup screen",
        ) { screen ->
            val scroll = rememberScrollState()
            Column(Modifier.fillMaxSize()) {
                Column(
                    Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 28.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    val title = remember { FocusRequester() }
                    LaunchedEffect(Unit) { if (moved) title.requestFocus() }
                    when (screen) {
                        SetupScreen.WELCOME -> Welcome(title)
                        SetupScreen.FILES -> GrantScreen(
                            icon = R.drawable.ic_folder_open,
                            on = access.allFiles,
                            name = stringResource(R.string.ob_files_title),
                            text = stringResource(R.string.setup_files_detail),
                            hint = { FilesHint() },
                            link = stringResource(R.string.ob_files_blocked),
                            linkIcon = null,
                            onLink = onOpenAppInfo,
                            title = title,
                        )
                        SetupScreen.OTHER_APPS -> GrantScreen(
                            icon = R.drawable.ic_library_music,
                            on = access.notifications,
                            name = stringResource(R.string.ob_apps_title),
                            text = stringResource(R.string.ob_apps_text),
                            hint = { Text(stringResource(R.string.ob_apps_hint)) },
                            link = stringResource(R.string.setup_restricted_title),
                            linkIcon = R.drawable.ic_help,
                            onLink = { help = true },
                            title = title,
                        )
                        SetupScreen.READY -> Ready(title)
                    }
                }
                SetupBottom(setupBar(screen, access), edge = scroll.canScrollForward, onPrimary = { go(setupPrimary(screens, screen, access)) }) {
                    setupSecondary(screens, screen)?.let(go)
                }
            }
        }
    }
    if (help) {
        HelpSheet(
            stringResource(R.string.setup_restricted_title),
            stringResource(R.string.setup_restricted_detail),
            action = stringResource(R.string.open_app_info),
            onAction = onOpenAppInfo,
        ) { help = false }
    }
}

/** A screen moving on slides [slide] px in from the end and the old one out to the start, back the other way; with reduced motion, a fade. */
private fun AnimatedContentTransitionScope<SetupScreen>.setupMove(screens: List<SetupScreen>, slide: Int, rtl: Boolean, reduced: Boolean): ContentTransform {
    if (reduced) return fadeIn(tween(Motion.REDUCED_MS)) togetherWith fadeOut(tween(Motion.REDUCED_MS))
    val forward = screens.indexOf(targetState) > screens.indexOf(initialState)
    val way = if (forward != rtl) 1 else -1
    return (slideInHorizontally(tween(Motion.OPEN_MS, easing = Motion.Rise)) { way * slide } + fadeIn(tween(Motion.OPEN_MS, easing = Motion.Rise))) togetherWith
        (slideOutHorizontally(tween(Motion.SETTLE_MS, easing = Motion.EmphasizedOut)) { -way * slide } + fadeOut(tween(Motion.SETTLE_MS)))
}

/** Back, then the four segments and "2 of 4" (read when it changes), which go to a second line before they would squeeze. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupTop(screens: List<SetupScreen>, at: SetupScreen, onBack: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val action = LocalChromeColors.current.action
    val reduced = LocalReducedMotion.current
    val number = screens.indexOf(at) + 1
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 20.dp, top = 4.dp, bottom = 4.dp)) {
        ChromeIconButton(R.drawable.ic_arrow_back, stringResource(R.string.check_back), onBack, glass = false)
        if (screens.size > 1) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.Start),
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
                itemVerticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f).padding(start = 8.dp),
            ) {
                Row(Modifier.weight(1f).widthIn(min = 96.dp, max = 140.dp).clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    screens.indices.forEach { k ->
                        val lit by animateColorAsState(if (k < number) action else colors.outlineVariant, motion(tween(Motion.OPEN_MS), reduced), label = "segment")
                        Box(Modifier.weight(1f).height(4.dp).background(lit, CircleShape))
                    }
                }
                Text(
                    stringResource(R.string.step_count, number, screens.size),
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold).tabular(),
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            }
        }
    }
}

/**
 * The pinned bar: [bar]'s primary button, its secondary one, and the note under them. A hairline shows over it only while
 * text runs under it ([edge]).
 */
@Composable
private fun SetupBottom(bar: SetupBar, edge: Boolean, onPrimary: () -> Unit, onSecondary: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val line by animateColorAsState(if (edge) colors.outlineVariant else Color.Transparent, tween(Motion.FADE_IN_MS), label = "bar edge")
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind { drawRect(line, size = size.copy(height = 1.dp.toPx())) }
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp),
    ) {
        LyrifyButton(stringResource(bar.primary), onClick = onPrimary, modifier = Modifier.fillMaxWidth())
        bar.secondary?.let { secondary ->
            LyrifyButton(stringResource(secondary), onClick = onSecondary, modifier = Modifier.fillMaxWidth(), kind = ButtonKind.GHOST, contentColor = colors.onSurface)
        }
        if (bar.note) {
            Text(
                stringResource(R.string.ob_files_note),
                style = NoteText,
                color = colors.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
    }
}

// The screens

/** A screen's title: a heading that takes the focus ([focus]) when the screen is reached by a move. */
private fun Modifier.screenTitle(focus: FocusRequester): Modifier = focusRequester(focus).focusable().semantics { heading() }

/** The hero (the mark and a sung line), KJB, what it does, and what leaves the phone. */
@Composable
private fun Welcome(title: FocusRequester) {
    val colors = MaterialTheme.colorScheme
    val large = LocalDensity.current.fontScale > LARGE_TEXT
    // With large text the hero gives its room to the words: the lines go and the mark shrinks.
    val shrink = if (large) LARGE_HERO else 1f
    Hero(large, shrink)
    // The name keeps its size whatever the phone's font scale: it is the brand, and the words under it grow instead.
    val name = with(LocalDensity.current) { (NAME_SIZE * shrink).toSp() }
    Text(stringResource(R.string.app_name), style = NameText.copy(fontSize = name, lineHeight = name * 1.05f), modifier = Modifier.screenTitle(title).padding(top = 4.dp))
    Text(stringResource(R.string.setup_intro), style = display(18, 24, weight = 600), color = colors.onSurfaceVariant, modifier = Modifier.offset(y = (-8).dp))
    Text(stringResource(R.string.ob_welcome_text), style = BodyText)
    SmallFact(R.drawable.ic_lock, stringResource(R.string.setup_privacy))
}

/** The hero card: a warm glow, the app's mark, and three lines with the middle one sung once. Decorative. */
@Composable
private fun Hero(large: Boolean, shrink: Float) {
    val colors = MaterialTheme.colorScheme
    val surface = colors.surface
    val reduced = LocalReducedMotion.current
    // The glow from the theme: the brand's ink through its primary to its coral, lit by the warm fixed tones.
    val glow = listOf(BrandInk, colors.primary, colors.tertiary)
    val lights = listOf(Triple(0.3f, 0.36f, colors.tertiaryFixedDim), Triple(0.68f, 0.28f, LauncherCoral), Triple(0.56f, 0.66f, colors.tertiaryFixed))
    val sung = remember { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(reduced) { if (!reduced) sung.animateTo(1f, tween(SING_MS, delayMillis = SING_DELAY_MS, easing = LinearEasing)) else sung.snapTo(1f) }
    Column(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clearAndSetSemantics {}
            .clip(RoundedCornerShape(28.dp))
            .background(colors.surfaceContainerLow)
            .drawBehind {
                drawRect(Brush.linearGradient(glow, start = Offset.Zero, end = Offset(size.width, size.height)), alpha = HERO_GLOW)
                lights.forEach { (x, y, color) ->
                    val at = Offset(size.width * x, size.height * y)
                    drawCircle(Brush.radialGradient(listOf(color, color.copy(alpha = 0f)), center = at, radius = size.minDimension * 0.55f), radius = size.minDimension * 0.55f, center = at, alpha = HERO_GLOW)
                }
                drawRect(Brush.verticalGradient(0f to surface.copy(alpha = 0.22f), 0.58f to surface.copy(alpha = 0.8f)))
            }
            .padding(if (large) 14.dp else 20.dp),
    ) {
        BrandMark(size = 64.dp * shrink, shape = RoundedCornerShape(18.dp * shrink))
        if (!large) {
            val highlight = LocalBrandColors.current.highlight
            // Only the mask's alpha counts: the lines fade out at their end.
            val mask = colors.onSurface
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        drawRect(Brush.horizontalGradient(0.9f to mask, 1f to mask.copy(alpha = 0f)), blendMode = BlendMode.DstIn)
                    },
            ) {
                HeroLine(stringResource(R.string.ob_line_past), HeroLineText.copy(color = colors.outline))
                val p = sung.value
                HeroLine(stringResource(R.string.ob_line_now), HeroNowText.copy(brush = Brush.horizontalGradient(0f to highlight, p to highlight, p to colors.onSurface, 1f to colors.onSurface)))
                HeroLine(stringResource(R.string.ob_line_next), HeroLineText.copy(color = colors.onSurfaceVariant))
            }
        }
    }
}

@Composable
private fun HeroLine(text: String, style: TextStyle) {
    Text(text, style = style, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
}

/**
 * Find your music and Other music apps: the grant's tile (a check and "On" once it is on), its [name] as the title, why,
 * and while it is off, what the phone will show ([hint]) and the help [link].
 */
@Composable
private fun GrantScreen(
    @DrawableRes icon: Int,
    on: Boolean,
    name: String,
    text: String,
    hint: @Composable () -> Unit,
    link: String,
    @DrawableRes linkIcon: Int?,
    onLink: () -> Unit,
    title: FocusRequester,
) {
    val colors = MaterialTheme.colorScheme
    val reduced = LocalReducedMotion.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
        GrantTile(icon, on)
        AnimatedVisibility(on, enter = fadeIn(tween(if (reduced) Motion.REDUCED_MS else Motion.OPEN_MS)), exit = fadeOut(tween(Motion.FADE_OUT_MS))) { OnChip() }
    }
    Text(name, style = TitleText, modifier = Modifier.screenTitle(title))
    Text(text, style = BodyText)
    if (!on) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth().background(colors.surfaceContainerLow, RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
        ) {
            Icon(painterResource(R.drawable.ic_smartphone), contentDescription = null, tint = colors.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(20.dp))
            CompositionLocalProvider(LocalTextStyle provides HintText.copy(color = colors.onSurfaceVariant)) { hint() }
        }
        // The link's words line up with the text above it: the ghost button's own side padding hangs outside.
        LyrifyButton(link, onClick = onLink, modifier = Modifier.offset(x = (-12).dp).offset(y = (-6).dp), kind = ButtonKind.GHOST, icon = linkIcon, compact = true)
    }
}

/** "Your phone opens a page. Find KJB, then turn on [the switch's own name, in bold]." */
@Composable
private fun FilesHint() {
    val switch = stringResource(R.string.ob_files_switch)
    val sentence = stringResource(R.string.ob_files_hint, switch)
    val ink = MaterialTheme.colorScheme.onSurface
    val text = buildAnnotatedString {
        val at = sentence.indexOf(switch)
        if (at < 0) {
            append(sentence)
        } else {
            append(sentence, 0, at)
            withStyle(SpanStyle(color = ink, fontWeight = FontWeight.SemiBold)) { append(switch) }
            append(sentence, at + switch.length, sentence.length)
        }
    }
    Text(text)
}

/** The grant's tile: its icon on the neutral tile, turning over to a check on green once it is on. */
@Composable
private fun GrantTile(@DrawableRes icon: Int, on: Boolean) {
    val colors = MaterialTheme.colorScheme
    val done = LocalStatusColors.current[Tone.SYNCED]
    val reduced = LocalReducedMotion.current
    val turn = remember { Animatable(0f) }
    var was by remember { mutableStateOf(on) }
    LaunchedEffect(on) {
        // Only a grant coming on while the screen shows turns the tile; one already on is simply there.
        if (on && !was && !reduced) {
            turn.snapTo(90f)
            turn.animateTo(0f, tween(Motion.OPEN_MS, easing = Motion.Rise))
        }
        was = on
    }
    val density = LocalDensity.current.density
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(56.dp)
            .graphicsLayer {
                rotationY = turn.value
                cameraDistance = TILE_CAMERA * density
            }
            .clip(RoundedCornerShape(18.dp))
            .background(if (on) done.container else colors.surfaceContainerHighest),
    ) {
        Icon(painterResource(if (on) R.drawable.ic_check else icon), contentDescription = null, tint = if (on) done.onContainer else colors.onSurfaceVariant, modifier = Modifier.size(28.dp))
    }
}

/** "On", with a check, beside a grant's tile once it is on. */
@Composable
private fun OnChip() {
    val done = LocalStatusColors.current[Tone.SYNCED]
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.heightIn(min = 32.dp).background(done.container, CircleShape).padding(start = 8.dp, end = 14.dp, top = 4.dp, bottom = 4.dp),
    ) {
        Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = done.onContainer, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.setup_done), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = done.onContainer)
    }
}

/** A fan of covers filling in while the library is read, the title, and where the rest lives. */
@Composable
private fun Ready(title: FocusRequester) {
    val large = LocalDensity.current.fontScale > LARGE_TEXT
    CoverFan(if (large) LARGE_HERO else 1f)
    Text(stringResource(R.string.ob_ready_title), style = TitleText, modifier = Modifier.screenTitle(title))
    Text(stringResource(R.string.ob_ready_text), style = BodyText)
    SmallFact(R.drawable.ic_info, stringResource(R.string.ob_ready_more))
}

/** Five covers popping in one after another and a sixth still shimmering, with a check: the scan has started. Decorative. */
@Composable
private fun CoverFan(shrink: Float) {
    val reduced = LocalReducedMotion.current
    val done = LocalStatusColors.current[Tone.SYNCED]
    val surface = MaterialTheme.colorScheme.surface
    val wait = MaterialTheme.colorScheme.surfaceContainerHighest
    val covers = fanCovers()
    val shimmer = rememberInfiniteTransition(label = "waiting cover")
    val sweep by shimmer.animateFloat(-1f, 1f, infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart), label = "sweep")
    Box(Modifier.padding(top = 8.dp, bottom = 4.dp).widthIn(max = FAN_WIDTH * shrink).clearAndSetSemantics {}) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            covers.chunked(FAN_COLUMNS).forEachIndexed { row, line ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    line.forEachIndexed { column, cover ->
                        val k = row * FAN_COLUMNS + column
                        val shown = remember { Animatable(if (reduced) 1f else 0f) }
                        LaunchedEffect(reduced) { if (!reduced) shown.animateTo(1f, tween(POP_MS, delayMillis = POP_DELAY_MS + k * POP_STEP_MS, easing = Motion.Rise)) }
                        Box(
                            Modifier
                                .weight(1f)
                                .aspectRatio(1f)
                                .graphicsLayer {
                                    alpha = shown.value
                                    val scale = 0.82f + 0.18f * shown.value
                                    scaleX = scale
                                    scaleY = scale
                                    translationY = (1f - shown.value) * 8.dp.toPx()
                                }
                                .clip(RoundedCornerShape(16.dp))
                                .drawBehind {
                                    if (cover == null) {
                                        drawRect(wait)
                                        if (!reduced) {
                                            val x = sweep * size.width
                                            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, surface.copy(alpha = 0.7f), Color.Transparent), startX = x, endX = x + size.width))
                                        }
                                    } else {
                                        drawRect(Brush.linearGradient(cover, start = Offset.Zero, end = Offset(size.width, size.height)))
                                    }
                                },
                        )
                    }
                }
            }
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(10.dp, 10.dp)
                .size(40.dp)
                .border(4.dp, surface, CircleShape)
                .padding(4.dp)
                .background(done.container, CircleShape),
        ) {
            Icon(painterResource(R.drawable.ic_check), contentDescription = null, tint = done.onContainer, modifier = Modifier.size(20.dp))
        }
    }
}

/** A small icon and a sentence in the secondary ink: what leaves the phone, where the rest lives. */
@Composable
private fun SmallFact(@DrawableRes icon: Int, text: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(painterResource(icon), contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 1.dp).size(18.dp))
        Text(text, style = SmallText, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// The mock's type and art

private val NameText = display(44, 46, tracking = -0.03, weight = 700)
private val TitleText = display(28, 33, tracking = -0.02, weight = 650)
private val BodyText = body(16f, 24)
private val HintText = body(14f, 20)
private val SmallText = body(13f, 19)
private val NoteText = body(13f, 18)
private val HeroLineText = display(16, 21, tracking = -0.01, weight = 600)
private val HeroNowText = display(19, 25, tracking = -0.01, weight = 600)

private const val HERO_GLOW = 0.6f

/** Five made-up covers in the theme's tones, and one still being read (null). */
@Composable
private fun fanCovers(): List<List<Color>?> {
    val colors = MaterialTheme.colorScheme
    val brand = LocalBrandColors.current
    val tones = LocalStatusColors.current
    return listOf(
        listOf(brand.highlight, colors.tertiaryFixedDim),
        listOf(tones[Tone.AI_SYNCED].accent, tones[Tone.PLAIN].accent),
        listOf(BrandInk, colors.primary),
        listOf(tones[Tone.SYNCED].accent, tones[Tone.SYNCED].container),
        listOf(tones[Tone.CHECK].accent, tones[Tone.CHECK].container),
        null,
    )
}
private const val FAN_COLUMNS = 3
private val FAN_WIDTH: Dp = 204.dp

/** With large text the hero's mark, the name and the fan shrink to this share. */
private const val LARGE_HERO = 0.78f
private val NAME_SIZE: Dp = 44.dp

private const val SING_MS = 2600
private const val SING_DELAY_MS = 350
private const val SHIMMER_MS = 1600
private const val POP_MS = 450
private const val POP_DELAY_MS = 120
private const val POP_STEP_MS = 70
private val SLIDE: Dp = 48.dp
private const val TILE_CAMERA = 12f

// The first run's rules, pure so they are tested on the JVM

/** The first run's screens, in order. */
enum class SetupScreen { WELCOME, FILES, OTHER_APPS, READY }

/**
 * The screens a phone gets: all four until setup was finished once ([setupDone]); after that, when All files access is
 * off again, only Find your music.
 */
fun setupScreens(setupDone: Boolean): List<SetupScreen> = if (setupDone) listOf(SetupScreen.FILES) else SetupScreen.entries

/** The pinned bar under a screen: its [primary] button, maybe a [secondary] one, and the [note] under them. */
@Immutable
data class SetupBar(@StringRes val primary: Int, @StringRes val secondary: Int? = null, val note: Boolean = false)

fun setupBar(screen: SetupScreen, access: Access): SetupBar = when (screen) {
    SetupScreen.WELCOME -> SetupBar(R.string.ob_get_started)
    SetupScreen.FILES -> if (access.allFiles) SetupBar(R.string.setup_continue) else SetupBar(R.string.ob_open_settings, note = true)
    SetupScreen.OTHER_APPS -> if (access.notifications) SetupBar(R.string.setup_continue) else SetupBar(R.string.ob_open_settings, R.string.setup_not_now)
    SetupScreen.READY -> SetupBar(R.string.ob_take_tour, R.string.ob_skip_tour)
}

/** What a tap in the bar does: another screen, a page of the phone's settings, or the end of the first run. */
sealed interface SetupMove {
    data class To(val screen: SetupScreen) : SetupMove

    data object OpenFiles : SetupMove

    data object OpenNotifications : SetupMove

    /** Into the app. [declinesTour]: "Skip tour", so the tour does not open by itself. */
    data class Finish(val declinesTour: Boolean) : SetupMove
}

/** The primary button on [at], one of [screens]. */
fun setupPrimary(screens: List<SetupScreen>, at: SetupScreen, access: Access): SetupMove = when {
    at == SetupScreen.FILES && !access.allFiles -> SetupMove.OpenFiles
    at == SetupScreen.OTHER_APPS && !access.notifications -> SetupMove.OpenNotifications
    else -> onward(screens, at)
}

/** The secondary button on [at], if it has one. */
fun setupSecondary(screens: List<SetupScreen>, at: SetupScreen): SetupMove? = when (at) {
    SetupScreen.OTHER_APPS -> onward(screens, at)
    SetupScreen.READY -> SetupMove.Finish(declinesTour = true)
    else -> null
}

/** The screen after [at], or into the app from the last one. */
private fun onward(screens: List<SetupScreen>, at: SetupScreen): SetupMove =
    screens.getOrNull(screens.indexOf(at) + 1)?.let(SetupMove::To) ?: SetupMove.Finish(declinesTour = false)

/** Back from [at]: the screen before it, or null on the first, where Back leaves KJB. */
fun setupBack(screens: List<SetupScreen>, at: SetupScreen): SetupScreen? = screens.getOrNull(screens.indexOf(at) - 1)
