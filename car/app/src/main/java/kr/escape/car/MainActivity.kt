package kr.escape.car

import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.LocalContext
import androidx.activity.viewModels
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import kr.escape.car.ui.rememberAsset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kr.escape.car.car.VehicleSource
import kr.escape.car.engine.Age
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Friend
import kr.escape.car.engine.FriendBook
import kr.escape.car.engine.Book
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Safety
import kr.escape.car.engine.StopReason
import kr.escape.car.story.FriendIntro
import kr.escape.car.story.Lines
import kr.escape.car.story.Places
import kr.escape.car.story.Progress
import kr.escape.car.story.Question
import kr.escape.car.story.Story
import kr.escape.car.story.StoryRepo
import kr.escape.car.story.Wish
import kr.escape.car.ui.AssetImage
import kr.escape.car.ui.BodyText
import kr.escape.car.ui.CheckLabel
import kr.escape.car.ui.ChoiceTile
import kr.escape.car.ui.Clay
import kr.escape.car.ui.ClayButton
import kr.escape.car.ui.ClayGlyph
import kr.escape.car.ui.ClayIcon
import kr.escape.car.ui.ClayStyle
import kr.escape.car.ui.Fonts
import kr.escape.car.ui.keepWords
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.unit.Constraints
import kr.escape.car.ui.FriendFace
import kr.escape.car.ui.Hearts
import kr.escape.car.ui.KidText
import kr.escape.car.ui.Portrait
import kr.escape.car.ui.StatusChip
import kr.escape.car.ui.starPath
import kr.escape.car.voice.Lumi
import kr.escape.car.voice.Said
import kr.escape.car.voice.Subtitles

class MainActivity : ComponentActivity() {
    /** The car, Lumi and the game live here, so a rebuilt screen goes on with the same episode. */
    private val model: GameModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Fonts.load(assets)
        val m = model
        val prefs = getSharedPreferences("screen", MODE_PRIVATE)
        val askTerms = !prefs.getBoolean(Terms.AGREED, false) || intent.getBooleanExtra("terms", false)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = Clay.Ink, background = Clay.NightBottom, surface = Clay.Cream)) {
                LaunchedEffect(Unit) {
                    while (true) {
                        m.game.tick(SystemClock.elapsedRealtime())
                        delay(200)
                    }
                }
                // the logo first, with the terms the first time; the first-run guide comes after it, on the app itself
                var opening by rememberSaveable { mutableStateOf(true) }
                if (opening) {
                    Splash(askTerms) {
                        if (askTerms) prefs.edit().putBoolean(Terms.AGREED, true).apply()
                        opening = false
                    }
                } else {
                    App(m.game, m.source, m.lumi, m.stories, m.book)
                }
            }
        }
    }
}

@Composable
private fun App(game: Game, source: VehicleSource, lumi: Lumi, stories: StoryRepo, book: FriendBook) {
    val ui by game.ui.collectAsState()
    val friends by book.book.collectAsState()
    val car by source.state.collectAsState()
    val speaking by lumi.speaking.collectAsState()
    val subtitle by lumi.subtitle.collectAsState()
    val noSound by lumi.noSound.collectAsState()
    // 지난 자막: what the friend has said this episode; back on the setup screen it starts empty for the next one
    val said by lumi.said.collectAsState()
    LaunchedEffect(ui.phase) { if (ui.phase == Phase.SETUP) lumi.forget() }
    // 아이 화면 자막: on unless the parent turned it off, remembered across drives
    val prefs = LocalContext.current.getSharedPreferences("screen", android.content.Context.MODE_PRIVATE)
    var subtitlesOn by remember { mutableStateOf(prefs.getBoolean("subtitles", true)) }
    val subs = Subs(subtitlesOn) { on -> subtitlesOn = on; prefs.edit().putBoolean("subtitles", on).apply() }
    // a line with no sound is always shown, whatever the switch says; nothing is shown while the car is not parked
    val shown = subtitle?.takeIf { (subtitlesOn || noSound) && ui.stopped != StopReason.DRIVING }
    val storyStatus by stories.status.collectAsState()
    val preview by stories.preview.collectAsState()
    val rejection by stories.rejection.collectAsState()
    val waited = rememberElapsedSeconds(storyStatus == StoryRepo.Status.WRITING, ui.setup.wish)
    // the wish box as the parent fills it in: the setup card edits it, the child's bubble follows it as it is typed.
    // It starts from the episode's wish, so a refused one comes back into the box
    val draft = remember(ui.setup.wish) { wishParts(ui.setup.wish).let { (place, kind) -> WishDraft(place, kind) } }
    val view = StoryView(storyStatus, preview, rejection, waited)
    // the story server is asked only by 소원 빌기 (game.begin); its answer moves the episode's first screen on
    LaunchedEffect(storyStatus) { game.storyDone(storyStatus) }
    // pictures the server finished drawing after the story came
    val settled by stories.settled.collectAsState()
    LaunchedEffect(settled) { game.picturesSettled(settled) }
    // 처음 사용 가이드: once, the first time the app is opened with nothing played yet, split between the setup screen
    // and the episode's first screen ("--ez guide true" shows both again)
    val saved by game.saved.collectAsState()
    val activity = LocalContext.current as? android.app.Activity
    val forced = activity?.intent?.getBooleanExtra("guide", false) == true
    var setupGuide by rememberSaveable {
        mutableStateOf(forced || (Guide.fresh(friends, saved) && !prefs.getBoolean(Guide.SEEN, false)))
    }
    var introGuide by rememberSaveable {
        mutableStateOf(forced || (Guide.fresh(friends, saved) && !prefs.getBoolean(Guide.SEEN_INTRO, false)))
    }
    val guide = when {
        setupGuide && ui.phase == Phase.SETUP && saved == null -> Guide.SETUP
        introGuide && ui.phase == Phase.INTRO && !ui.paused -> Guide.INTRO
        else -> null
    }
    val spots = remember { GuideSpots() }
    // the parts of the screen the guide points at report where they are only while it is open
    CompositionLocalProvider(LocalGuideSpots provides spots.takeIf { guide != null }) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Clay.NightTop, Clay.NightBottom)))) {
            val portrait = maxWidth < maxHeight
            // why the episode cannot start yet, on the setup screen (the 이어 하기 card tells its own rest and readings)
            val waits = if (ui.phase != Phase.SETUP) emptyList() else Safety.startBlockers(car).let { blockers ->
                blockers.map { it.message } + listOfNotNull(
                    "차량 장치(공조, 독서등)를 아직 읽지 못했어요".takeIf { blockers.isEmpty() && saved == null && !game.playable(car) },
                )
            }
            Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, top = 28.dp, bottom = if (waits.isEmpty()) 36.dp else 20.dp)) {
                Header(ui, car, ui.friendShown(saved))
                Spacer(Modifier.height(24.dp))
                if (portrait) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                        KidStage(game, ui, friends, speaking, shown, said, view, draft, Modifier.weight(1f).fillMaxWidth())
                        ParentPanel(game, ui, car, speaking, lumi, view, subs, draft, Modifier.fillMaxWidth())
                    }
                } else {
                    Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        val panelWidth = if (ui.phase == Phase.SETUP) 680.dp else 500.dp
                        ParentPanel(game, ui, car, speaking, lumi, view, subs, draft, Modifier.width(panelWidth).fillMaxHeight())
                        KidStage(game, ui, friends, speaking, shown, said, view, draft, Modifier.weight(1f).fillMaxHeight())
                    }
                }
                if (waits.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    WaitBanner(waits)
                }
            }
            if (ui.paused && ui.phase in listOf(Phase.INTRO, Phase.PLAYING, Phase.SOLVED)) PauseOverlay(game, ui)
            if (guide != null) GuideOverlay(guide, spots) { skipped ->
                // 건너뛰기 on the setup screen skips the part on the episode's first screen too
                if (guide == Guide.SETUP) {
                    setupGuide = false
                    if (skipped) introGuide = false
                } else {
                    introGuide = false
                }
                prefs.edit().putBoolean(Guide.SEEN, !setupGuide).putBoolean(Guide.SEEN_INTRO, !introGuide).apply()
            }
        }
    }
}

// ---------------------------------------------------------------- header

@Composable
private fun Header(ui: GameUi, car: CarState, friend: Friend) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        FriendFace(friend.picture, friend.label, 64.dp, ringWidth = 0.dp)
        KidText("차근차근 탐험단", 42.sp, color = Clay.Cream)
        Row(Modifier.guideSpot(Spot.STATUS), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            StatusChip("주차 중", car.gear == Gear.PARK && !car.uxRestricted)
            StatusChip("운전석 보호자", car.driverSeated == true)
        }
        Spacer(Modifier.weight(1f))
        if (ui.puzzles.isNotEmpty() && ui.phase !in listOf(Phase.SETUP, Phase.RESTORE)) ProgressBeads(ui)
    }
}

/** What still keeps the episode from starting: a big coral band across the bottom of the screen, under both sides. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WaitBanner(lines: List<String>) {
    val shape = RoundedCornerShape(32.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Clay.Coral).padding(horizontal = 24.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
    ) {
        Box(Modifier.size(38.dp).clip(CircleShape).background(Color.White), contentAlignment = Alignment.Center) {
            ClayGlyph(ClayIcon.ALERT, Clay.Coral, Modifier.size(24.dp))
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(36.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            lines.forEach { KidText(it, 26.sp, color = Color.White) }
        }
    }
}

/** One bead per puzzle (mint star solved, sun ring now, dashed next) and the treasure at the end. */
@Composable
private fun ProgressBeads(ui: GameUi) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f)).padding(horizontal = 18.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (i in ui.puzzles.indices) {
            val done = i < ui.stars
            val now = !done && i == ui.stars && ui.phase != Phase.TREASURE
            Canvas(Modifier.size(52.dp)) {
                val r = size.minDimension / 2
                when {
                    done -> {
                        drawCircle(Clay.Mint, r)
                        drawPath(starPath(r, r, r * 0.55f, r * 0.24f), Color.White)
                    }
                    now -> {
                        drawCircle(Clay.Sun.copy(alpha = 0.2f), r)
                        drawCircle(Clay.Sun, r - 2.dp.toPx(), style = Stroke(3.dp.toPx()))
                        drawCircle(Clay.Sun, r * 0.3f)
                    }
                    else -> drawCircle(
                        Color.White.copy(alpha = 0.35f), r - 2.dp.toPx(),
                        style = Stroke(3.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
                    )
                }
            }
        }
        val treasure = ui.phase == Phase.TREASURE
        Box(
            Modifier.size(52.dp).clip(CircleShape).background(if (treasure) Clay.Sun else Color.White.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center,
        ) { ClayGlyph(ClayIcon.GIFT, if (treasure) Clay.Ink else Clay.Sun, Modifier.size(30.dp)) }
    }
}

// ---------------------------------------------------------------- parent panel

@Composable
private fun PanelCard(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(44.dp)
    Column(
        modifier.shadow(24.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).clip(shape).background(Clay.Cream).padding(36.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
        content = content,
    )
}

@Composable
private fun PanelTitle(text: String, size: Int = 52) = KidText(text, size.sp)

@Composable
private fun FieldLabel(text: String) = BodyText(text, size = 20.sp, weight = FontWeight.Medium)

/**
 * The part of a panel between its title and its bottom buttons. It takes the room that is left and scrolls when
 * there is more (a long restore list, a notice plus three car buttons), so 멈춤 and the main button never get squeezed.
 */
@Composable
private fun ColumnScope.Middle(gap: Dp = 18.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(gap),
        content = content,
    )
}

/**
 * Like [Middle] for the setup card: its groups [gap] apart, and the room left over shared out evenly between them, up
 * to [extra] more each, so the card neither ends in a gap above 소원 빌기 nor spreads apart; it still scrolls when
 * there is too little room.
 */
@Composable
private fun ColumnScope.EvenMiddle(gap: Dp, extra: Dp, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).heightIn(min = maxHeight),
            verticalArrangement = object : Arrangement.Vertical {
                override val spacing = gap

                override fun Density.arrange(totalSize: Int, sizes: IntArray, outPositions: IntArray) {
                    val more = if (sizes.size > 1) ((totalSize - sizes.sum()) / (sizes.size - 1) - gap.roundToPx()) else 0
                    val each = gap.roundToPx() + more.coerceIn(0, extra.roundToPx())
                    var y = 0
                    sizes.forEachIndexed { i, s ->
                        outPositions[i] = y
                        y += s + each
                    }
                }
            },
            content = content,
        )
    }
}

/** One setup choice on a single row: its numbered name on the left, the choices filling the rest. */
@Composable
private fun SetupRow(label: String, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        SetupLabel(label, Modifier.width(150.dp))
        content()
    }
}

/** A step's name on the setup card, bold and dark. Set in the plain font: the round one spaces "함께" apart. */
@Composable
private fun SetupLabel(text: String, modifier: Modifier = Modifier) =
    BodyText(text, modifier, size = 22.sp, color = Clay.Ink, weight = FontWeight.Bold)

@Composable
private fun ParentPanel(
    game: Game,
    ui: GameUi,
    car: CarState,
    speaking: Boolean,
    lumi: Lumi,
    view: StoryView,
    subs: Subs,
    draft: WishDraft,
    modifier: Modifier,
) {
    when (ui.phase) {
        Phase.SETUP -> {
            // the episode the car was switched off in comes first: the parent goes on with it or starts anew
            val saved by game.saved.collectAsState()
            val resume = saved
            if (resume != null) ResumePanel(game, resume, car, modifier) else SetupPanel(game, ui, car, view, subs, draft, modifier)
        }
        Phase.INTRO -> PanelCard(modifier) {
            PanelTitle(ui.story?.title ?: "오늘의 이야기")
            BodyText(if (ui.story != null) "이야기는 AI가 만들었어요. 정답은 규칙 엔진이 정해요" else "정답은 규칙 엔진이 정해요")
            Notice(Lines.AUTO_OFF_PARENT)
            Middle {
                Column(Modifier.guideSpot(Spot.PUZZLES), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    FieldLabel("오늘의 퍼즐")
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        // only what kind each puzzle is: its title would give the answer away
                        ui.puzzles.forEachIndexed { i, p -> PuzzleRow(i + 1, p.category, first = i == 0) }
                    }
                }
            }
            ReportRow(game, ui)
            SafetyLine(ui, car, Modifier.guideSpot(Spot.SAFETY))
            // the story is written while the parent gets the car ready here; 탐험 시작 waits for it
            ClayButton(
                if (ui.writing) "이야기를 쓰고 있어요 · ${view.waited}초" else "탐험 시작", ClayStyle.SUN, Modifier.fillMaxWidth().guideSpot(Spot.START),
                height = 112.dp, enabled = !ui.writing,
            ) { game.startPuzzle(0) }
        }
        Phase.PLAYING -> PlayingPanel(game, ui, car, speaking, lumi, subs, modifier)
        Phase.SOLVED -> PanelCard(modifier) {
            PanelTitle("정답이에요!")
            BodyText("아이를 듬뿍 칭찬해 주세요. 별 하나를 모았어요")
            Middle { }
            SafetyLine(ui, car)
            ClayButton(
                if (ui.index + 1 < ui.puzzles.size) "다음 퍼즐" else "보물 열기", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 104.dp,
            ) { game.next() }
            ClayButton("멈춤", ClayStyle.CORAL, Modifier.fillMaxWidth(), height = 104.dp, icon = ClayIcon.PAUSE) { game.hold() }
        }
        Phase.TREASURE -> PanelCard(modifier) {
            PanelTitle("탐험 배지를 받았어요")
            BodyText("끝까지 해낸 아이를 꼭 안아 칭찬해 주세요")
            Middle {
                ui.heartsNow?.let { CloserCard(ui.setup.friend, it) }
            }
            ClayButton("처음 설정으로", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 112.dp) { game.finish() }
        }
        Phase.RESTORE -> PanelCard(modifier) {
            PanelTitle("처음 설정으로")
            // live: what still differs from the climate as the parent left it
            val changes = ui.snapshot?.changes(car).orEmpty()
            // every item that differed when the game ended, checked off as the car reports it back, and anything the
            // car changed since
            val items = ui.restoreItems + changes.filter { it !in ui.restoreItems }
            Middle {
                when {
                    ui.restoring -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = Clay.Mint, strokeWidth = 4.dp, trackColor = Clay.MintSoft, strokeCap = StrokeCap.Round)
                        BodyText("게임 전에 저장해 둔 설정으로 차를 되돌리는 중이에요", size = 21.sp, color = Clay.MintDeep, weight = FontWeight.Medium)
                    }
                    items.isEmpty() -> CheckLabelText("모두 처음 설정과 같아요")
                    changes.isEmpty() -> CheckLabelText("게임 전 설정으로 모두 되돌렸어요")
                    else -> BodyText(
                        if (ui.restoreTried) "체크되지 않은 곳은 되돌리지 못했어요. 직접 돌려 주세요"
                        else "게임 전에 저장해 둔 설정과 달라진 곳이에요",
                    )
                }
                if (items.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { items.forEach { RestoreRow(it, done = it !in changes) } }
                }
                ui.restoreNote?.let { Notice(it) }
                if (changes.isNotEmpty() && !ui.restoring) {
                    ClayButton("다시 되돌리기", ClayStyle.GHOST, Modifier.fillMaxWidth()) { game.restore() }
                }
            }
            ReportRow(game, ui)
            ClayButton(if (changes.isEmpty()) "처음 화면으로" else "다 돌렸어요", ClayStyle.SUN, Modifier.fillMaxWidth(), height = 112.dp, enabled = !ui.restoring) {
                game.reset()
            }
        }
    }
}

@Composable
private fun SetupPanel(game: Game, ui: GameUi, car: CarState, view: StoryView, subs: Subs, draft: WishDraft, modifier: Modifier) {
    val blockers = Safety.startBlockers(car)
    // with no puzzle this car can play, game.begin() would return without a word
    val playable = game.playable(car)
    // what is in the wish box; it becomes the episode's wish when 소원 빌기 is pressed (a refused one comes back here)
    val wish = draft.kind?.takeIf { draft.place.isNotBlank() }?.let { wishOf(draft.place, it) }
    PanelCard(modifier) {
        Column {
            PanelTitle("시작하기 전에 골라 주세요", 38)
            BodyText("아이가 버튼을 찾아 가리키면, 안전 지킴이가 확인하고 눌러 줘요", size = 18.sp, color = Clay.Sub, weight = FontWeight.Medium)
        }
        // everything on one screen with no scrolling, in the order the parent goes: the wish comes last, so
        // everything the story depends on is chosen when 소원 빌기 asks for it. Fixed gaps, no outlined boxes: the
        // choices are big enough to fill the card, and 소원 빌기 stays at its bottom
        EvenMiddle(gap = 28.dp, extra = 26.dp) {
            SetupRow("1. 함께할 친구") {
                ChosenFriend(ui.setup.friend, Modifier.weight(1f))
                SubtitleSwitch(subs)
            }
            SetupRow("2. 아이 나이", Modifier.guideSpot(Spot.AGE)) {
                for (age in Age.entries) {
                    ChoiceTile(age.years, age.label, ui.setup.ages.firstOrNull() == age, Modifier.weight(1f)) {
                        game.setup { it.copy(ages = listOf(age)) }
                    }
                }
            }
            SetupRow("3. 기다릴 시간", Modifier.guideSpot(Spot.TIME)) {
                for (m in listOf(5, 10, 15)) {
                    ChoiceTile("${m}분", "퍼즐 ${Planner.puzzleCount(m)}개", ui.setup.minutes == m, Modifier.weight(1f)) {
                        game.setup { it.copy(minutes = m) }
                    }
                }
            }
            WishBox(draft.place, draft.kind, view, onPlace = { draft.place = it.take(PLACE_MAX) }, onKind = { draft.kind = it })
        }
        // why 소원 빌기 cannot be pressed yet is told big across the bottom of the screen (WaitBanner), not here
        // the one button: it asks the story server for this episode and goes on to the episode's first screen, where
        // the story is written while the parent gets the car ready
        ClayButton("소원 빌기", ClayStyle.SUN, Modifier.fillMaxWidth().guideSpot(Spot.WISH_BUTTON), height = 88.dp, enabled = wish != null && blockers.isEmpty() && playable) {
            if (wish != null) {
                game.setup { it.copy(wish = wish) }
                game.begin()
            }
        }
    }
}

/** 이어 하기 카드: the episode the car was switched off in (or rested after 20 minutes), offered before a new one. */
@Composable
private fun ResumePanel(game: Game, saved: Progress, car: CarState, modifier: Modifier) {
    val blockers = Safety.startBlockers(car)
    // the rest after 20 minutes counts down while the card is open
    val restLeft by produceState(game.restLeftMs(), saved) {
        while (true) {
            value = game.restLeftMs()
            delay(1_000)
        }
    }
    // the rest is only suggested: 이어 하기 waits for the car, not for the rest to be over
    val ready = game.canContinue(car)
    val with = Lines.withAnd(saved.setup.friend.label)
    val where = if (saved.next < saved.puzzles.size) "$with ${saved.next + 1}번째 퍼즐부터 이어서 해요." else "$with 보물만 열면 돼요."
    val stars = if (saved.stars > 0) " 지금까지 별 ${saved.stars}개를 모았어요." else ""
    PanelCard(modifier) {
        PanelTitle("하다 만 탐험이 있어요", 48)
        Middle {
            FieldLabel("이어 할 이야기")
            BodyText("‘${saved.story?.title ?: "기본 이야기"}’", size = 28.sp, weight = FontWeight.Bold)
            BodyText(where + stars, size = 22.sp)
            Spacer(Modifier.height(4.dp))
            // the car may have put AUTO back on while it was off
            Notice(Lines.AUTO_OFF_PARENT)
            // what the car still needs (gear, doors, seat, 공조) is told across the bottom of the screen
            if (restLeft > 0) BodyText("${(restLeft + 59_999) / 60_000}분쯤 쉬었다 하면 좋아요", size = 20.sp, color = Clay.Sub, weight = FontWeight.Medium)
            if (blockers.isEmpty() && !ready) Notice("차량 장치(공조, 독서등)를 아직 읽지 못했어요. 잠시 기다려 주세요")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ClayButton("이어 하기", ClayStyle.SUN, Modifier.weight(1f), height = 112.dp, enabled = ready) { game.continueSaved() }
            ClayButton("새로 시작", ClayStyle.GHOST, Modifier.width(220.dp), height = 112.dp) { game.startOver() }
        }
    }
}

/** What the story server is doing for the setup screen: its status, the story it has ready, why it refused a wish. */
private data class StoryView(val status: StoryRepo.Status, val preview: Story?, val rejection: List<String>, val waited: Int = 0)

/** Whole seconds since [running] turned on (and since [key] last changed while it was on); 0 while it is off. */
@Composable
private fun rememberElapsedSeconds(running: Boolean, key: Any?): Int {
    var seconds by remember { mutableStateOf(0) }
    LaunchedEffect(running, key) {
        seconds = 0
        if (!running) return@LaunchedEffect
        val start = SystemClock.elapsedRealtime()
        while (true) {
            delay(1000)
            seconds = ((SystemClock.elapsedRealtime() - start) / 1000).toInt()
        }
    }
    return seconds
}

/**
 * What the friend is doing while a wish is made. The server does not report its steps, so this follows the measured
 * times with Claude Opus: the words take about 50 s, the pictures, all drawn at once, about 25 s more.
 */
private fun wishStep(waited: Int) = when {
    waited < 50 -> "이야기를 쓰고 있어요"
    waited < 75 -> "친구들 그림을 그리고 있어요"
    else -> "거의 다 됐어요!"
}

// kinds that suit any place the family types and need no character beyond the friend book, and carry any lesson
private val STORY_KINDS = listOf("보물찾기", "구조대", "탐정 놀이", "수리하러 가기", "축제 준비", "발명 대회")
private const val PLACE_MAX = 20

private fun wishOf(place: String, kind: String) = "${place.trim()}에서 펼쳐지는 $kind 이야기"

/** What is in the wish box while the parent fills it in, shared with the child's stage so its bubble follows along. */
private class WishDraft(place: String, kind: String?) {
    var place by mutableStateOf(place)
    var kind by mutableStateOf(kind)

    /** The child's bubble for it, or null while nothing is filled in. */
    val line: String? get() = wishLine(place, kind)
}

// what the friend says for each kind, so a kind that is a team (구조대) still reads as something to do
private val KIND_LINES = mapOf(
    "보물찾기" to "보물찾기 하자!",
    "구조대" to "구조대 출동!",
    "탐정 놀이" to "탐정 놀이 하자!",
    "수리하러 가기" to "수리하러 가자!",
    "축제 준비" to "축제 준비하자!",
    "발명 대회" to "발명 대회 열자!",
)

private val TOWARD_KINDS = setOf("구조대", "수리하러 가기")

/** The child's bubble while the wish is filled in ("우주 정거장으로 구조대 출동!"), or null while nothing is. */
internal fun wishLine(place: String, kind: String?): String? {
    val where = place.trim()
    val what = kind?.let { KIND_LINES[it] ?: "$it 하자!" }
    return when {
        what == null -> if (where.isEmpty()) null else "$where 좋아!"
        where.isEmpty() -> what
        // a rescue team or a repair trip sets out for the place; every other kind happens there
        kind in TOWARD_KINDS -> "$where${toward(where)} $what"
        else -> "${where}에서 $what"
    }
}

/** 으로 or 로 after [word], as its last letter asks ("정거장으로", "마을로", "별로"). */
internal fun toward(word: String): String {
    val last = word.lastOrNull()
    if (last == null || last !in '가'..'힣') return "로"
    val tail = (last - '가') % 28
    return if (tail == 0 || tail == 8) "로" else "으로"
}

/** The place and the kind of story a wish was made from, so the box shows them again. */
private fun wishParts(wish: String): Pair<String, String?> =
    Wish.FORM.matchEntire(wish)?.let { it.groupValues[1] to it.groupValues[2].takeIf { k -> k in STORY_KINDS } } ?: ("" to null)

/**
 * 4. 오늘의 이야기 소원: the family types a place (or lets the dice pick one) and picks a kind of story; 소원 빌기 below
 * the box has the story server write it around today's puzzles and draw its world. The puzzles and their answers stay
 * the rule engine's.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WishBox(place: String, kind: String?, view: StoryView, onPlace: (String) -> Unit, onKind: (String) -> Unit) {
    // on the card itself, like the rows above: a label, the place field with its dice, the kinds of story
    Column(Modifier.fillMaxWidth().guideSpot(Spot.WISH)) {
        SetupLabel("4. 오늘의 이야기 소원")
        Spacer(Modifier.height(14.dp))
        val field = RoundedCornerShape(22.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            // the one outlined thing on the card: a field to type in
            BasicTextField(
                value = place,
                onValueChange = onPlace,
                singleLine = true,
                textStyle = TextStyle(fontSize = 24.sp, color = Clay.Ink),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                modifier = Modifier.weight(1f).height(72.dp).clip(field).background(Color.White).border(2.dp, Clay.Line, field),
                decorationBox = { inner ->
                    Box(Modifier.fillMaxSize().padding(horizontal = 22.dp), contentAlignment = Alignment.CenterStart) {
                        if (place.isEmpty()) BodyText("장소 (예: 공룡 섬)", size = 22.sp, color = Clay.Muted)
                        inner()
                    }
                },
            )
            // typing on the car screen is the slowest part of the setup: the dice fills in a place, and pressing again
            // always gives another one
            Box(
                Modifier.size(72.dp).clip(field).background(Clay.CreamDeep)
                    .semantics { contentDescription = "장소 아무거나 고르기" }
                    .clickable { onPlace(Places.next(place)) },
                contentAlignment = Alignment.Center,
            ) { ClayGlyph(ClayIcon.DICE, Clay.Ink, Modifier.size(36.dp)) }
        }
        Spacer(Modifier.height(18.dp))
        Column(Modifier.selectableGroup(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            STORY_KINDS.chunked(3).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { k -> WishChip(k, k == kind, Modifier.weight(1f)) { onKind(k) } }
                }
            }
        }
        // a wish the server refused comes back here with why
        if (view.status == StoryRepo.Status.REJECTED) {
            Spacer(Modifier.height(12.dp))
            Notice("이 소원은 다르게 적어 주세요 (${view.rejection.joinToString(", ").ifEmpty { "안전 규칙" }})")
        }
    }
}

/** A kind of story: deep cream, sun when picked, as tall as the other choices on the card are easy to press. */
@Composable
private fun WishChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.height(72.dp).clip(RoundedCornerShape(22.dp)).background(if (selected) Clay.Sun else Clay.CreamDeep)
            .selectable(selected, role = Role.RadioButton, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { KidText(text, 23.sp, maxLines = 1, textAlign = TextAlign.Center) }
}

@Composable
private fun CheckLabelText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(Clay.Mint), contentAlignment = Alignment.Center) {
            ClayGlyph(ClayIcon.CHECK, Color.White, Modifier.size(14.dp))
        }
        BodyText(text, size = 19.sp, color = Clay.MintDeep, weight = FontWeight.Medium)
    }
}

@Composable
private fun Notice(text: String) = BodyText("• $text", size = 20.sp, color = Clay.CoralLip, weight = FontWeight.Medium, balanced = true)

@Composable
private fun PuzzleRow(number: Int, title: String, first: Boolean) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.White).border(2.dp, Clay.LineSoft, shape)
            .padding(start = 14.dp, end = 20.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(if (first) Clay.Sun else Clay.CreamDeep), contentAlignment = Alignment.Center) {
            KidText("$number", 26.sp)
        }
        KidText(title, 30.sp)
    }
}

@Composable
private fun RestoreRow(text: String, done: Boolean) {
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.White).border(2.dp, Clay.LineSoft, shape)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        val box = RoundedCornerShape(9.dp)
        // put back: a filled box with a check; still different: an empty box for the parent
        if (done) {
            Box(Modifier.size(30.dp).clip(box).background(Clay.Mint), contentAlignment = Alignment.Center) {
                ClayGlyph(ClayIcon.CHECK, Color.White, Modifier.size(20.dp))
            }
        } else {
            Box(Modifier.size(30.dp).clip(box).background(Clay.Cream).border(3.dp, Color(0xFFD9CBB1), box))
        }
        KidText(text, 30.sp)
    }
}

/** The friend picked in the child's friend book, on the parent's setup panel: its face and name, nothing around them. */
@Composable
private fun ChosenFriend(friend: Friend, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        FriendFace(friend.picture, friend.label, 60.dp, ring = Clay.Sun, ringWidth = 3.dp)
        KidText(friend.label, 30.sp)
    }
}

/** 아이 화면 자막 on the friend's row of the setup panel: its name and the switch, the whole of it pressable. */
@Composable
private fun SubtitleSwitch(subs: Subs) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).toggleable(value = subs.on, role = Role.Switch, onValueChange = subs.set)
            .padding(start = 14.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        BodyText("아이 화면 자막", size = 20.sp, color = Clay.Ink, weight = FontWeight.Medium)
        SlideSwitch(subs.on, compact = true)
    }
}

/** The episode is over: the friend that went through it is one heart closer. */
@Composable
private fun CloserCard(friend: Friend, hearts: Int) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Clay.CreamDeep).padding(20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        FriendFace(friend.picture, friend.label, 88.dp, ring = Clay.Coral, ringWidth = 4.dp)
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            BodyText("${Lines.withAnd(friend.label)} 더 친해졌어요", size = 19.sp, color = Clay.Sub, weight = FontWeight.Medium)
            Hearts(hearts, Friend.MAX_HEARTS, 28.dp)
            if (hearts >= Friend.MAX_HEARTS && !friend.rare) {
                BodyText("하트를 다 채웠어요! 친구 도감에서 새 친구를 만날 수 있어요", size = 17.sp, color = Clay.MintDeep, weight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun SafetyLine(ui: GameUi, car: CarState, modifier: Modifier = Modifier) {
    val sec = ui.playedMs / 1000
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CheckLabel("주차", car.gear == Gear.PARK)
            CheckLabel("문 닫힘", !car.doorOpen)
            CheckLabel("운전석", car.driverSeated == true)
            Spacer(Modifier.weight(1f))
            Text(
                "%02d:%02d / %02d:00".format(sec / 60, sec % 60, Safety.MAX_PLAY_MS / 60000),
                fontSize = 19.sp, color = Clay.Sub, fontWeight = FontWeight.Medium,
            )
        }
        if (ui.recirculationWarning) Notice("내기 순환 3분이 지났어요. 외기로 돌려 주세요")
    }
}

/** 이야기 신고: the parent flags a story; it never stops the game. */
@Composable
private fun ReportRow(game: Game, ui: GameUi) {
    val story = ui.story ?: return
    var open by remember(story.storyId) { mutableStateOf(false) }
    var sent by remember(story.storyId) { mutableStateOf(false) }
    when {
        sent -> BodyText("신고를 받았어요. 이 이야기를 다시 살펴볼게요", size = 18.sp)
        !open -> Text(
            "이야기 신고", fontSize = 19.sp, color = Clay.Muted, fontWeight = FontWeight.Medium, textDecoration = TextDecoration.Underline,
            modifier = Modifier.clickable { open = true }.padding(vertical = 6.dp),
        )
        else -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            for ((reason, label) in listOf("SCARY" to "무서워요", "WRONG_ANSWER" to "정답과 달라요", "HARD_WORDS" to "말이 어려워요")) {
                Text(
                    label, fontSize = 19.sp, color = Clay.Ink, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(Color.White)
                        .border(2.dp, Clay.Line, RoundedCornerShape(50))
                        .clickable { game.report(reason); sent = true }.padding(vertical = 14.dp),
                )
            }
        }
    }
}

@Composable
private fun PlayingPanel(game: Game, ui: GameUi, car: CarState, speaking: Boolean, lumi: Lumi, subs: Subs, modifier: Modifier) {
    val p = ui.puzzle ?: return
    val scene = ui.scene(p)
    val belt = p == Puzzle.BeltMission
    var reveal by remember(ui.index) { mutableStateOf(false) }
    PanelCard(modifier) {
        // the kind of puzzle, not its answer: the answer is behind 정답 보기's long press
        PanelTitle(p.category)
        BodyText(if (belt) "다음 곳으로 가기 전 준비 시간이에요.\n아이가 스스로 매도록 기다려 주세요" else "아이가 말한 답과 버튼을 확인하고 눌러 주세요")
        Middle {
            if (belt) {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Clay.CreamDeep).padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    KidText("이미 매고 있다면", 30.sp)
                    BodyText("버클을 손으로 짚고\n\"딸깍 확인!\"을 외치게 해 주세요", size = 21.sp)
                }
            } else {
                VoiceBox(speaking, ui.setup.friend) { lumi.repeat() }
                // compact like the setup card's, so the card fits its height without scrolling
                SubtitleToggle(subs, compact = true)
                if (scene.buttons.isNotEmpty()) {
                    FieldLabel("이 퍼즐에서 쓰는 버튼")
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { scene.buttons.take(3).forEach { CarButton(it) } }
                }
                val answer = "정답: ${scene.answer}" + (ui.answerNote?.let { ", $it" } ?: "")
                AnswerReveal(if (reveal) answer else null, Modifier.pointerInput(ui.index) {
                    detectTapGestures(onLongPress = { reveal = true })
                })
            }
        }
        SafetyLine(ui, car)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            if (belt) ClayButton("확인했어요", ClayStyle.MINT, Modifier.weight(1f)) { game.confirmBelt() }
            else ClayButton("힌트 듣기", ClayStyle.SUN, Modifier.weight(1f), icon = ClayIcon.HINT) { game.hint() }
            ClayButton("끝내기", ClayStyle.GHOST, Modifier.width(170.dp)) { game.finish() }
        }
        ClayButton("멈춤", ClayStyle.CORAL, Modifier.fillMaxWidth(), height = 104.dp, icon = ClayIcon.PAUSE) { game.hold() }
    }
}

@Composable
private fun VoiceBox(speaking: Boolean, friend: Friend, onRepeat: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Clay.CreamDeep).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ClayGlyph(ClayIcon.WAVE, if (speaking) Clay.Mint else Clay.Muted, Modifier.size(30.dp))
            BodyText(
                if (speaking) "${Lines.subject(friend.label)} 문제를 말하고 있어요" else "${Lines.of(friend.label)} 말을 다시 들을 수 있어요",
                size = 20.sp, color = Clay.Ink, weight = FontWeight.Medium,
            )
        }
        ClayButton("다시 듣기", ClayStyle.GHOST, Modifier.fillMaxWidth(), height = 76.dp, icon = ClayIcon.SPEAKER, onClick = onRepeat)
    }
}

/** The car's own button, drawn like the real one, so the parent knows what the child is looking for. */
@Composable
private fun CarButton(label: String) {
    Column(Modifier.width(100.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier.size(88.dp).clip(RoundedCornerShape(22.dp)).background(Color.White).border(3.dp, Clay.Line, RoundedCornerShape(22.dp)),
            contentAlignment = Alignment.Center,
        ) {
            // a button without a drawn glyph shows its name large instead of a picture that is not its own
            glyphFor(label)?.let { AssetImage(it, Modifier.size(68.dp), scale = ContentScale.Fit) }
                ?: ButtonName(label)
        }
        BodyText(label, size = 18.sp, weight = FontWeight.Medium, textAlign = TextAlign.Center)
    }
}

/**
 * A button's name in place of its picture: one word a line ("시트" / "열선", never "시트 열" / "선"), as large as the
 * longest word allows in the 88 dp square.
 */
@Composable
private fun ButtonName(label: String) {
    val words = label.trim().split(Regex("\\s+"))
    val size = minOf(24f, 60f / words.maxOf { it.length }).sp
    KidText(words.joinToString("\n"), size, maxLines = words.size, textAlign = TextAlign.Center)
}

private fun glyphFor(label: String) = when {
    "풍량" in label -> "hvac_fan"
    "온도" in label -> "hvac_temp"
    "독서등" in label -> "hvac_light"
    "성에" in label -> "hvac_defrost"
    "내기 순환" in label -> "hvac_recirc"
    "열선" in label -> "hvac_seat_heat"
    "에어컨" in label -> "hvac_ac"
    else -> null
}

/** 정답 보기 (길게 누르기): a dashed pill that shows the answer only after a long press. */
@Composable
private fun AnswerReveal(answer: String?, modifier: Modifier) {
    Row(
        modifier.drawBehind {
            drawRoundRect(
                Color(0xFFD9CBB1), cornerRadius = CornerRadius(size.height / 2),
                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
            )
        }.background(if (answer != null) Color.White else Color.Transparent, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ClayGlyph(ClayIcon.EYE, if (answer != null) Clay.Ink else Clay.Sub, Modifier.size(26.dp))
        BodyText(
            answer ?: "정답 보기 (길게 누르기)", size = 20.sp, weight = FontWeight.Medium,
            color = if (answer != null) Clay.Ink else Clay.Sub,
        )
    }
}

// ---------------------------------------------------------------- kid stage

@Composable
private fun KidStage(
    game: Game, ui: GameUi, book: Book, speaking: Boolean, subtitle: String?, said: List<Said>, view: StoryView, draft: WishDraft,
    modifier: Modifier,
) {
    val preview = view.preview
    val saved by game.saved.collectAsState()
    val resume = saved
    // an episode to go on with keeps the friend it was played with, whoever is picked on the setup panel
    val buddy = ui.friendShown(resume)
    val p = ui.puzzle
    val story = ui.story
    val theme = story?.picture("cover") ?: story?.themePicture ?: "theme_star_train"
    val (bubble, picture) = when (ui.phase) {
        Phase.SETUP -> when {
            // the episode to go on with, while the parent's card offers it
            // with no cover on the device the friend's own picture, never another friend's on the default cover
            resume != null -> (resume.story?.title ?: "하던 탐험을 이어 할까?") to
                (resume.story?.picture("cover") ?: "home_${buddy.name.lowercase()}")
            preview != null -> preview.title to (preview.picture("cover") ?: preview.themePicture)
            // the friend picked waits in the explorer team's clubhouse
            // the wish box as it is being filled in, word by word
            draft.line != null -> draft.line!! to "home_${buddy.name.lowercase()}"
            else -> "어디로 탐험 갈까?" to "home_${buddy.name.lowercase()}"
        }
        // while the story is written there is no cover yet: the friend picked waits in its own clubhouse picture
        Phase.INTRO -> (ui.story?.title ?: if (ui.writing) "오늘의 이야기" else "별빛 기차가 고장 났대!") to
            (if (ui.writing && story == null) "home_${buddy.name.lowercase()}" else theme)
        Phase.PLAYING -> p?.let { ui.scene(it).let { s -> s.bubble to (s.pictures.firstOrNull() ?: theme) } } ?: ("" to theme)
        Phase.SOLVED -> "정답! 별 하나 획득!" to (p?.let { story?.donePicture(it) } ?: story?.picture("cover") ?: successPicture(p))
        Phase.TREASURE -> "탐험 배지 획득!" to "ending_${buddy.name.lowercase()}"
        Phase.RESTORE -> when {
            ui.restoring -> "차를 처음처럼 정리하는 중!"
            ui.restoreTried -> "정리 끝! 다음에 또 만나"
            else -> "처음 설정으로 돌려 놓자"
        } to theme
    }
    val shape = RoundedCornerShape(48.dp)
    Box(modifier.clip(shape).background(Clay.NightBottom)) {
        StagePicture(picture, buddy, Modifier.fillMaxSize())
        // a soft shade at the top keeps the bubble readable over any picture
        Box(
            Modifier.fillMaxWidth().height(420.dp)
                .background(Brush.verticalGradient(listOf(Clay.NightBottom.copy(alpha = 0.55f), Color.Transparent))),
        )
        if (ui.phase == Phase.RESTORE) Box(Modifier.fillMaxSize().background(Clay.NightBottom.copy(alpha = 0.35f)))
        // 지난 자막 is offered once the friend has said something in this episode
        val canLookBack = said.isNotEmpty() && ui.phase in listOf(Phase.INTRO, Phase.PLAYING, Phase.SOLVED, Phase.TREASURE)
        var lookingBack by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth().padding(start = 36.dp, top = 30.dp, end = 36.dp)) {
            // the friend going through the episode, with its name under it
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.shadow(16.dp, CircleShape)) { FriendFace(buddy.picture, buddy.label, 156.dp, ringWidth = 6.dp) }
                KidText(buddy.label, 24.sp, color = Clay.Ink, maxLines = 1,
                    modifier = Modifier.shadow(8.dp, RoundedCornerShape(50)).clip(RoundedCornerShape(50))
                        .background(Clay.Cream).padding(horizontal = 18.dp, vertical = 6.dp))
            }
            Spacer(Modifier.width(22.dp))
            // the bubble takes the room up to the button, so a long title never runs under it
            Column(Modifier.weight(1f).padding(top = 22.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // under the title the bubble also says who today's friend is (first screen) or how close it got (the
                // treasure), so the bottom of the stage stays free for what the friend is saying
                val hearts = ui.heartsNow
                SpeechBubble(bubble) {
                    when {
                        ui.phase == Phase.INTRO -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            KidText("오늘 함께할 친구", 26.sp, color = Clay.SunLip, maxLines = 1)
                            KidText(buddy.label, 26.sp, maxLines = 1)
                        }
                        ui.phase == Phase.TREASURE && hearts != null -> Row(
                            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            KidText("${Lines.withAnd(buddy.label)} 더 친해졌어!", 28.sp, color = Clay.CoralLip, maxLines = 1)
                            Hearts(hearts, Friend.MAX_HEARTS, 30.dp)
                        }
                    }
                }
                if (speaking) SpeakingTag()
            }
            if (canLookBack) {
                Spacer(Modifier.width(16.dp))
                // 지난 자막: a clock turned back
                Box(
                    Modifier.padding(top = 22.dp).size(76.dp).shadow(10.dp, CircleShape).clip(CircleShape)
                        .background(Clay.Cream).clickable { lookingBack = true }.semantics { contentDescription = "지난 자막" },
                    contentAlignment = Alignment.Center,
                ) { ClayGlyph(ClayIcon.HISTORY, Clay.Ink, Modifier.size(46.dp)) }
            }
        }
        when (ui.phase) {
            // an episode to go on with keeps the friend it was played with, so the book is shown only for a new one
            Phase.SETUP -> if (resume == null) FriendShelf(book, buddy, onPick = game::pick, onInvite = game::invite)
            Phase.INTRO, Phase.PLAYING, Phase.SOLVED, Phase.TREASURE, Phase.RESTORE -> Unit
        }
        val quiz = p as? Puzzle.Quiz
        val card = if (ui.phase == Phase.PLAYING && quiz != null) ui.scene(quiz).question?.takeIf { Lines.card(it) != null } else null
        card?.let { QuestionCard(it) }
        // what the friend is saying, a piece at a time, above the question card when there is one
        subtitle?.let { SubtitleBar(it, aboveCard = card != null) }
        // the friend waits with the child on the episode's first screen while its story is written
        if (ui.phase == Phase.INTRO && ui.writing) WishingOverlay(buddy, book, view.waited, speaking, game::whileWriting, game::hushWhileWriting)
        val drawnFor = rememberElapsedSeconds(ui.drawing != null, ui.drawing)
        if (ui.drawing != null) DrawingOverlay(buddy, drawnFor)
        ui.invited?.let { InvitePop(it, game::closeInvite) }
        if (lookingBack && canLookBack) LookBack(said) { lookingBack = false }
    }
}

/** 아이 화면 자막 switch, and its setter (remembered on the device). */
private data class Subs(val on: Boolean, val set: (Boolean) -> Unit)

/**
 * The child's subtitles: what the friend is saying now, one or two lines, in the round kid font, dark on a light see-
 * through cream at the bottom of the stage, clear of the question card.
 */
@Composable
private fun BoxScope.SubtitleBar(text: String, aboveCard: Boolean) {
    val shape = RoundedCornerShape(36.dp)
    Box(
        Modifier.align(Alignment.BottomCenter)
            .padding(start = 40.dp, end = 40.dp, bottom = if (aboveCard) 232.dp else 40.dp)
            .widthIn(max = 1240.dp)
            .shadow(12.dp, shape).clip(shape).background(Clay.Cream.copy(alpha = 0.9f))
            .padding(horizontal = 36.dp, vertical = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        // each piece replaces the last at once: a cross-fade left the old words showing under the new ones. The line
        // break goes where the phrase allows (Subtitles.lines), never in the middle of a word
        KidText(Subtitles.lines(text), 46.sp, color = Clay.Ink, textAlign = TextAlign.Center, maxLines = 2, balanced = true)
    }
}

/** The parent's switch for the child's subtitles. A line with no sound is shown even when it is off. */
@Composable
private fun SubtitleToggle(subs: Subs, compact: Boolean = false) {
    val shape = RoundedCornerShape(if (compact) 22.dp else 28.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Color.White).border(3.dp, Clay.Line, shape)
            .toggleable(value = subs.on, role = Role.Switch, onValueChange = subs.set)
            .padding(horizontal = if (compact) 16.dp else 22.dp, vertical = if (compact) 8.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        KidText("아이 화면 자막", if (compact) 22.sp else 28.sp, modifier = Modifier.weight(1f))
        SlideSwitch(subs.on, compact)
    }
}

/** An on/off switch: a white knob that slides right onto mint when on, left onto grey when off. */
@Composable
private fun SlideSwitch(on: Boolean, compact: Boolean) {
    val width = if (compact) 68.dp else 80.dp
    val height = if (compact) 38.dp else 44.dp
    val knob = height - 8.dp
    val x by animateDpAsState(if (on) width - knob - 4.dp else 4.dp, tween(160), label = "switch knob")
    Box(Modifier.size(width, height).clip(RoundedCornerShape(50)).background(if (on) Clay.Mint else Clay.Line)) {
        Box(
            Modifier.align(Alignment.CenterStart).offset(x = x).size(knob).shadow(3.dp, CircleShape).clip(CircleShape)
                .background(Color.White),
        )
    }
}

/** While the next puzzle's picture is still being drawn: the friend waits inside a spinning ring, as for a wish. */
@Composable
private fun BoxScope.DrawingOverlay(buddy: Friend, waited: Int) {
    Box(Modifier.matchParentSize().background(Clay.NightBottom.copy(alpha = 0.72f)))
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Box(Modifier.size(220.dp), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(
                Modifier.fillMaxSize(), color = Clay.Sun, strokeWidth = 10.dp,
                trackColor = Color.White.copy(alpha = 0.15f), strokeCap = StrokeCap.Round,
            )
            FriendFace(buddy.picture, buddy.label, 176.dp, ringWidth = 0.dp)
        }
        KidText("다음 장면을 그리고 있어!", 48.sp, color = Clay.Cream, textAlign = TextAlign.Center)
        BodyText("${waited}초째, 다 그리면 바로 시작해요", size = 22.sp, color = Clay.Cream.copy(alpha = 0.8f), textAlign = TextAlign.Center)
    }
}

/**
 * A stage picture: the app's own scene picture in the friend's version when there is one ("scene_fog_konyang"),
 * otherwise as it is ("scene_fog"); a drawn picture or any other picture as it is.
 */
@Composable
private fun StagePicture(picture: String, buddy: Friend, modifier: Modifier) {
    val own = if (picture.startsWith("scene_")) rememberAsset("${picture}_${buddy.name.lowercase()}") else null
    if (own != null) Image(own, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    else AssetImage(picture, modifier)
}

/**
 * While a wish is being made (about a minute): the stage dims and the friend introduces itself on cards that take
 * turns (itself, its hearts, the rare friends still to meet), each said once in its voice; what is happening and the
 * seconds stay small underneath.
 */
@Composable
private fun BoxScope.WishingOverlay(
    buddy: Friend, book: Book, waited: Int, speaking: Boolean, say: (String) -> Unit, hush: () -> Unit,
) {
    val cards = remember(buddy, book) { FriendIntro.cards(buddy, book) }
    var index by remember(cards) { mutableStateOf(0) }
    val talking by rememberUpdatedState(speaking)
    LaunchedEffect(cards) {
        val said = mutableSetOf<Int>()
        while (true) {
            val line = cards[index].line
            if (line != null && index !in said) {
                // the line before (the ask to turn AUTO off) is let finish first, read even when it has no sound
                if (said.isEmpty()) delay(3_000)
                withTimeoutOrNull(15_000) { snapshotFlow { talking }.first { !it } }
                said += index
                say(line)
                delay(1_500)
                if (talking) {
                    withTimeoutOrNull(20_000) { snapshotFlow { talking }.first { !it } }
                    delay(4_000)
                } else {
                    // no recording: the card stays while its subtitles are read
                    delay(Subtitles.readingPlan(Subtitles.pieces(line)).second + 3_000)
                }
            } else {
                delay(9_000)
            }
            if (!talking) hush()
            index = (index + 1) % cards.size
        }
    }
    Box(Modifier.matchParentSize().background(Clay.NightBottom.copy(alpha = 0.72f)))
    Column(
        Modifier.align(Alignment.Center).padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(28.dp),
    ) {
        IntroCard(cards[index])
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            cards.indices.forEach { i ->
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (i == index) Clay.Sun else Color.White.copy(alpha = 0.3f)))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            CircularProgressIndicator(
                Modifier.size(40.dp), color = Clay.Sun, strokeWidth = 5.dp,
                trackColor = Color.White.copy(alpha = 0.15f), strokeCap = StrokeCap.Round,
            )
            KidText(wishStep(waited), 34.sp, color = Clay.Cream)
            BodyText("${waited}초째, 1분 안팎으로 걸려요", size = 22.sp, color = Clay.Cream.copy(alpha = 0.8f))
        }
    }
}

/** One card on the waiting screen: the friend, its hearts, or the rare friends still to meet. */
@Composable
private fun IntroCard(card: FriendIntro.Card) {
    val shape = RoundedCornerShape(48.dp)
    Box(
        Modifier.widthIn(min = 1000.dp, max = 1240.dp).heightIn(min = 420.dp).shadow(28.dp, shape).clip(shape).background(Clay.Cream)
            .padding(horizontal = 56.dp, vertical = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        when (card) {
            is FriendIntro.Card.Me -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                FriendFace(card.friend.picture, card.friend.label, 300.dp, ring = Clay.Sun, ringWidth = 8.dp)
                Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    KidText(card.friend.label, 76.sp)
                    BodyText(FriendIntro.looksLike(card.friend), size = 32.sp, weight = FontWeight.Medium)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        BodyText("잘하는 것", size = 26.sp)
                        KidText(
                            card.friend.symbol, 34.sp, color = Clay.Ink,
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(Clay.Sun).padding(horizontal = 22.dp, vertical = 6.dp),
                        )
                    }
                }
            }
            is FriendIntro.Card.Hearts -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
                FriendFace(card.friend.picture, card.friend.label, 150.dp, ring = Clay.Coral, ringWidth = 6.dp)
                Hearts(card.hearts, Friend.MAX_HEARTS, 64.dp)
                KidText(
                    if (card.canMeet) "하트 5개를 모으면 새로운 친구를 만날 수 있어요" else "${Lines.withAnd(card.friend.label)} 모은 하트예요",
                    40.sp, textAlign = TextAlign.Center,
                )
            }
            is FriendIntro.Card.Mystery -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(26.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(36.dp)) {
                    card.friends.forEach { f -> FriendFace("${f.picture}_silhouette", "?", 170.dp, ring = Clay.Line, ringWidth = 6.dp) }
                }
                KidText("누구일까?", 56.sp)
                BodyText("하트 5개를 채우면 한 명씩 만날 수 있어요", size = 28.sp, weight = FontWeight.Medium)
            }
        }
    }
}

/**
 * 지난 자막: everything the friend has said this episode, oldest first, scrolled to the newest, over the stage, with a
 * small title before each part (시작, 퍼즐 1, …, 마무리). A touch outside the card or 닫기 closes it.
 */
@Composable
private fun BoxScope.LookBack(said: List<Said>, onClose: () -> Unit) {
    val rows = remember(said) { LookBackParts.rows(said) }
    Box(Modifier.matchParentSize().background(Clay.NightBottom.copy(alpha = 0.72f)).pointerInput(Unit) { detectTapGestures { onClose() } })
    val shape = RoundedCornerShape(44.dp)
    Column(
        Modifier.align(Alignment.Center).fillMaxWidth(0.86f).fillMaxHeight(0.84f).shadow(32.dp, shape).clip(shape)
            .background(Clay.Cream).pointerInput(Unit) { detectTapGestures { } }
            .padding(start = 44.dp, end = 32.dp, top = 28.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            KidText("지난 자막", 44.sp, modifier = Modifier.weight(1f))
            ClayButton("닫기", ClayStyle.GHOST, Modifier.width(180.dp), height = 72.dp, onClick = onClose)
        }
        val list = rememberLazyListState()
        LaunchedEffect(rows.size) { if (rows.isNotEmpty()) list.scrollToItem(rows.lastIndex) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(18.dp)) {
            itemsIndexed(rows) { i, row ->
                when (row) {
                    is LookBackParts.Row.Line -> KidText(row.text, 32.sp, balanced = true)
                    // a part's title: small and colored, with a rule after it, a little apart from the part before
                    is LookBackParts.Row.Title -> Row(
                        Modifier.padding(top = if (i == 0) 0.dp else 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                    ) {
                        KidText(row.text, 28.sp, color = Clay.SunLip, maxLines = 1)
                        Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(50)).background(Clay.Line))
                    }
                }
            }
        }
    }
}

/** The friend's bubble at the top of the stage: its line, and [below] it a smaller line when the screen has one. */
@Composable
private fun SpeechBubble(text: String, below: @Composable () -> Unit = {}) {
    if (text.isBlank()) return
    val shape = RoundedCornerShape(44.dp)
    BoxWithConstraints {
        // a line that wraps takes all the width it may have and leaves an empty strip on the bubble's right: the
        // line is measured first, and the bubble made as wide as its widest wrapped line
        val density = LocalDensity.current
        val room = with(density) { (minOf(maxWidth, 760.dp) - 88.dp).roundToPx().coerceAtLeast(1) }
        val style = LocalTextStyle.current.merge(
            TextStyle(fontFamily = Fonts.jua, fontSize = 56.sp, lineHeight = 56.sp * 1.2f, lineBreak = LineBreak.Heading),
        )
        val measurer = rememberTextMeasurer()
        val widest = remember(text, room) {
            val laid = measurer.measure(AnnotatedString(keepWords(text)), style, maxLines = 2, constraints = Constraints(maxWidth = room))
            (0 until laid.lineCount).maxOf { laid.getLineRight(it) - laid.getLineLeft(it) }
        }
        Box {
            Box(Modifier.matchParentSize().padding(top = 8.dp).clip(shape).background(Clay.Line))
            Column(
                Modifier.padding(bottom = 8.dp).widthIn(max = 760.dp).clip(shape).background(Clay.Cream)
                    .padding(start = 44.dp, end = 44.dp, top = 24.dp, bottom = 26.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                KidText(text, 56.sp, maxLines = 2, balanced = true, modifier = Modifier.width(with(density) { (widest + 4f).toDp() }))
                below()
            }
        }
    }
}

@Composable
private fun SpeakingTag() {
    Row(
        Modifier.clip(RoundedCornerShape(50)).background(Clay.NightBottom.copy(alpha = 0.55f)).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ClayGlyph(ClayIcon.WAVE, Clay.Mint, Modifier.size(26.dp))
        Text("말하는 중", color = Clay.Cream, fontSize = 20.sp, fontWeight = FontWeight.Medium)
    }
}

/**
 * 친구 도감: the nine friends swiped sideways. A tap on an open friend picks it for the next episode; a rare friend
 * not open yet is a grey silhouette. A base friend at full hearts asks to go and meet a new friend.
 */
@Composable
private fun BoxScope.FriendShelf(book: Book, picked: Friend, onPick: (Friend) -> Unit, onInvite: (Friend) -> Unit) {
    Column(
        Modifier.align(Alignment.BottomCenter).padding(horizontal = 28.dp, vertical = 32.dp).fillMaxWidth().guideSpot(Spot.FRIENDS)
            .shadow(24.dp, RoundedCornerShape(36.dp)).clip(RoundedCornerShape(36.dp)).background(Clay.Cream.copy(alpha = 0.95f))
            .padding(top = 20.dp, bottom = 22.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.padding(horizontal = 28.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            KidText("친구 도감 ${book.open.size}명", 34.sp)
            BodyText("함께할 친구를 골라 줘. 한 판을 끝내면 하트가 하나 생겨", size = 19.sp, color = Clay.Muted, weight = FontWeight.Medium)
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 28.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // the bubble's room is kept on every friend while one has it, so the faces stay in one line
            val asking = Friend.entries.any(book::canInvite)
            items(Friend.entries) { f ->
                val open = book.isOpen(f)
                PalItem(
                    // a friend not met yet shows only its outline, so the child can guess who is coming
                    name = f.label, picture = if (open) f.picture else "${f.picture}_silhouette",
                    ring = when { f == picked -> Clay.Sun; open -> Clay.Mint; else -> Color(0xFFD9CBB1) },
                    dim = !open, hidden = !open, hearts = book.hearts(f).takeIf { open },
                    label = if (open) null else "누구일까?",
                    picked = f == picked,
                    invite = book.canInvite(f), bubbleRoom = asking,
                    onInvite = { onInvite(f) },
                    onClick = if (open) ({ onPick(f) }) else null,
                )
            }
        }
    }
}

@Composable
private fun PalItem(
    name: String, picture: String?, ring: Color, dim: Boolean = false, hidden: Boolean = false,
    hearts: Int? = null, label: String? = null, picked: Boolean = false, invite: Boolean = false, bubbleRoom: Boolean = false,
    onInvite: () -> Unit = {}, onClick: (() -> Unit)? = null,
) {
    // the friend picked for the next episode stands on a sun tile, its ring white against it. Every friend keeps the
    // same padding, so the faces stay in one line
    Column(
        Modifier.width(148.dp)
            .then(if (picked) Modifier.clip(RoundedCornerShape(30.dp)).background(Clay.Sun) else Modifier)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (bubbleRoom) Box(Modifier.height(64.dp), contentAlignment = Alignment.BottomCenter) { if (invite) InviteBubble(onInvite) }
        // a silhouette is shown as it is; only a friend with no picture at all gets the grey "?"
        val outline = hidden && picture?.endsWith("_silhouette") == true
        FriendFace(
            picture, name, 112.dp, ring = if (picked) Color.White else ring, ringWidth = if (dim) 3.dp else 5.dp,
            dim = dim && !outline, hidden = hidden && !outline,
        )
        if (hidden) BodyText(label ?: "", size = 18.sp, color = Clay.Muted, weight = FontWeight.Medium, textAlign = TextAlign.Center)
        else KidText(name, if (picked) 24.sp else 22.sp, textAlign = TextAlign.Center, maxLines = 1)
        // big enough to count at a glance; the empty ones darker than the card, white on the picked friend's sun tile
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            if (hearts != null) Hearts(hearts, Friend.MAX_HEARTS, 22.dp, empty = if (picked) Color.White else Color(0xFFD9CBB1))
        }
    }
}

/** "새로운 친구를 만나러 가자": over a base friend at full hearts; tapping it opens the next rare friend. */
@Composable
private fun InviteBubble(onClick: () -> Unit) {
    KidText(
        "새로운 친구를\n만나러 가자!", 17.sp, color = Clay.Ink, textAlign = TextAlign.Center, maxLines = 2,
        modifier = Modifier.shadow(8.dp, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp)).background(Clay.Sun)
            .clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** A rare friend just opened: big over the stage until a touch. */
@Composable
private fun BoxScope.InvitePop(friend: Friend, onClose: () -> Unit) {
    Box(
        Modifier.matchParentSize().background(Clay.NightBottom.copy(alpha = 0.6f)).pointerInput(Unit) { detectTapGestures { onClose() } },
    ) {
        Column(
            Modifier.align(Alignment.Center).widthIn(max = 640.dp).shadow(32.dp, RoundedCornerShape(44.dp))
                .clip(RoundedCornerShape(44.dp)).background(Clay.Cream).padding(horizontal = 44.dp, vertical = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            KidText("새 친구!", 40.sp, color = Clay.MintLip)
            FriendFace(friend.picture, friend.label, 260.dp, ring = Clay.Sun, ringWidth = 8.dp)
            KidText(friend.label, 56.sp, textAlign = TextAlign.Center)
            BodyText("${friend.animal} · ${friend.symbol}", size = 24.sp, color = Clay.Ink, textAlign = TextAlign.Center)
            BodyText("아무 곳이나 누르면 닫혀요", size = 17.sp, color = Clay.Muted)
        }
    }
}

/** The episode is over: the friend that went through it is one heart closer. */
@Composable
private fun BoxScope.CloserPop(friend: Friend, hearts: Int) {
    val shape = RoundedCornerShape(40.dp)
    Box(Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 40.dp)) {
        Box(Modifier.matchParentSize().padding(top = 10.dp).clip(shape).background(Clay.Coral))
        Row(
            Modifier.padding(bottom = 10.dp).shadow(24.dp, shape).clip(shape).background(Clay.Cream)
                .padding(start = 24.dp, end = 40.dp, top = 20.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            FriendFace(friend.picture, friend.label, 140.dp, ring = Clay.Coral, ringWidth = 6.dp)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KidText("${Lines.withAnd(friend.label)} 더 친해졌어!", 40.sp)
                Hearts(hearts, Friend.MAX_HEARTS, 36.dp)
            }
        }
    }
}

/** The question in big type over the stage ("3 + 2 = ?"); a situation has none, the child hears it. */
@Composable
private fun BoxScope.QuestionCard(q: Question) {
    val line = Lines.card(q) ?: return
    val shape = RoundedCornerShape(40.dp)
    Column(
        Modifier.align(Alignment.BottomStart).padding(start = 40.dp, bottom = 40.dp, end = 240.dp)
            .shadow(16.dp, shape).clip(shape).background(Clay.Cream).padding(horizontal = 40.dp, vertical = 26.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        KidText(line, 72.sp, maxLines = 1)
    }
}

private fun successPicture(p: Puzzle?) = Lines.successPicture(p)

// ---------------------------------------------------------------- pause

@Composable
private fun PauseOverlay(game: Game, ui: GameUi) {
    // the scrim swallows every touch: without it 다음 퍼즐 or 힌트 behind the card could still be pressed while paused
    Box(
        Modifier.fillMaxSize().background(Clay.NightBottom.copy(alpha = 0.82f)).pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        val shape = RoundedCornerShape(48.dp)
        Column(
            Modifier.width(816.dp).shadow(32.dp, shape).clip(shape).background(Clay.Cream)
                .padding(start = 48.dp, end = 48.dp, top = 40.dp, bottom = 44.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            val reason = ui.stopped
            val picture = when (reason) {
                StopReason.DOOR_OPEN -> "pause_door"
                StopReason.DRIVING -> "pause_parking"
                else -> null
            }
            if (picture != null) AssetImage(picture, Modifier.fillMaxWidth().aspectRatio(720f / 420f).clip(RoundedCornerShape(32.dp)))
            else FriendFace(ui.setup.friend.picture, ui.setup.friend.label, 260.dp, ring = Clay.Line, ringWidth = 6.dp)
            KidText(if (reason == null) "잠깐 멈췄어요" else "잠깐 쉬어요!", 60.sp)
            BodyText(reason?.message ?: "준비되면 이어 하기를 눌러 주세요", size = 26.sp, weight = FontWeight.Medium, textAlign = TextAlign.Center)
            if (reason == StopReason.TIME_UP || ui.restUntil != null) {
                // after 20 minutes a rest is suggested, not forced: 저장하고 쉬기 keeps the episode for later (the setup
                // screen offers 이어 하기), 바로 이어 하기 goes on at once with the 20 minutes counted from now
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ClayButton("저장하고 쉬기", ClayStyle.SUN, Modifier.weight(1f)) { game.restAndSave() }
                    ClayButton("바로 이어 하기", ClayStyle.GHOST, Modifier.width(300.dp)) { game.keepGoing() }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    ClayButton("이어 하기", ClayStyle.SUN, Modifier.weight(1f), enabled = reason == null) { game.resume() }
                    ClayButton("끝내기", ClayStyle.GHOST, Modifier.width(300.dp)) { game.finish() }
                }
            }
        }
    }
}
