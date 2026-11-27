package kr.escape.car

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kr.escape.car.engine.CarRestorer
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Dial
import kr.escape.car.engine.Friend
import kr.escape.car.engine.FriendBook
import kr.escape.car.engine.HoldJudge
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.Questions
import kr.escape.car.engine.QuizKind
import kr.escape.car.engine.Safety
import kr.escape.car.engine.Setup
import kr.escape.car.engine.Snapshot
import kr.escape.car.engine.StopReason
import kr.escape.car.engine.WrongJudge
import kr.escape.car.story.FriendIntro
import kr.escape.car.story.Lines
import kr.escape.car.story.Progress
import kr.escape.car.story.ProgressStore
import kr.escape.car.story.Question
import kr.escape.car.story.QuestionBank
import kr.escape.car.story.StoryKeys
import kr.escape.car.story.Scene
import kr.escape.car.story.Story
import kr.escape.car.story.StoryRepo
import kr.escape.car.story.Wish
import kr.escape.car.voice.Voice
import kotlin.random.Random

enum class Phase { SETUP, INTRO, PLAYING, SOLVED, TREASURE, RESTORE }

data class GameUi(
    val phase: Phase = Phase.SETUP,
    val setup: Setup = Setup(),
    val puzzles: List<Puzzle> = emptyList(),
    val index: Int = 0,
    val stars: Int = 0,
    val playedMs: Long = 0,
    /** Stopped by the car (driving, a door, time). The game cannot go on until it clears. */
    val stopped: StopReason? = null,
    /** Stopped with the parent's 멈춤 button. */
    val held: Boolean = false,
    val hintHeard: Boolean = false,
    val recirculationWarning: Boolean = false,
    /**
     * Next to the parent's 정답 보기: where a temperature or fan answer counts from (and for a number, where it should
     * end), or whose reading light it is.
     */
    val answerNote: String? = null,
    /**
     * The app's own question for each quiz (answer key), drawn from its copy of the bank when the game began, or
     * when a puzzle was swapped as it started.
     */
    val own: Map<String, Pair<Question, String>> = emptyMap(),
    val snapshot: Snapshot? = null,
    /** Words from the story server for this episode; null means the app's own [Lines]. */
    val story: Story? = null,
    /** The friend's hearts after this episode, set when the treasure is found. */
    val heartsNow: Int? = null,
    /** A rare friend opened from the friend book just now, shown until the next touch. */
    val invited: Friend? = null,
    /** 처음 설정으로: the app is putting the car back right now. */
    val restoring: Boolean = false,
    /** The app has tried to put the car back at least once (what is still different is left to the parent). */
    val restoreTried: Boolean = false,
    /**
     * What differed from the climate as the parent left it when 처음 설정으로 was pressed, as the screen lists it: the
     * restore screen checks off each one as the car reports it back.
     */
    val restoreItems: List<String> = emptyList(),
    /** Why the app did not put the car back (not parked); the parent can try again. */
    val restoreNote: String? = null,
    /** Wall-clock ms before which this episode cannot go on after its 20 minutes (kept with the save). */
    val restUntil: Long? = null,
    /** Set by 저장하고 쉬기: putting the car back and the next 처음 화면으로 leave the save for after the rest. */
    val keepSave: Boolean = false,
    /** Held after the app did not run for a while; 이어 하기 then takes the climate and the 20 minutes from now. */
    val rebase: Boolean = false,
    /** 소원 빌기 asked the story server for this episode and it is still writing: 탐험 시작 waits for it. */
    val writing: Boolean = false,
    /** The puzzle (index) waiting for its picture, still being drawn; it starts once the picture is in or has failed. */
    val drawing: Int? = null,
    /** What the friend says before that puzzle once it starts (이어 하기's welcome back). */
    val drawingIntro: String? = null,
) {
    val puzzle: Puzzle? get() = puzzles.getOrNull(index)
    val paused: Boolean get() = stopped != null || held

    /** The friend on screen: on the setup screen, while an episode waits to go on, the friend it was played with. */
    fun friendShown(saved: Progress?): Friend = if (phase == Phase.SETUP && saved != null) saved.setup.friend else setup.friend

    fun scene(p: Puzzle): Scene =
        own[StoryKeys.key(p)].let { mine -> story?.scene(p, mine) ?: Lines.scene(p, mine) }

    /**
     * What Lumi says once the puzzle being played is solved: the story's line for that very puzzle (a quiz swapped as
     * it started has its own), or the app's own when the scene is played from [Lines] or the line was dropped.
     */
    val solvedLine: String
        get() = puzzle?.let { scene(it).result }?.takeIf { it.isNotBlank() } ?: Lines.SUCCESS
}

/**
 * One episode from the parent's setup to "처음 설정으로". The car state comes in through [tick]; the rule engine
 * decides every answer and Lumi only speaks.
 */
class Game(
    private val car: StateFlow<CarState>,
    private val lumi: Voice,
    private val stories: StoryRepo? = null,
    private val book: FriendBook? = null,
    private val restorer: CarRestorer? = null,
    private val bank: QuestionBank? = null,
    /** Where the episode in progress is kept, so the family can go on after the car was switched off (이어 하기). */
    private val progress: ProgressStore? = null,
    private val rnd: Random = Random.Default,
) {
    private val _ui = MutableStateFlow(GameUi())
    val ui: StateFlow<GameUi> = _ui

    /** The episode kept on disk: while it is not null and the game is at its setup, the parent is offered 이어 하기. */
    private val _saved = MutableStateFlow(progress?.read())
    val saved: StateFlow<Progress?> = _saved

    init {
        // the next story the setup screen prepares must not take this one's pictures
        stories?.keepPlaying(_saved.value?.story?.storyId)
    }

    private var hold: HoldJudge? = null
    private var wrong: WrongJudge? = null
    private var wrongTold = 0
    private var lastTickMs: Long? = null
    private var recirculationSince: Long? = null

    fun setup(change: (Setup) -> Setup) = _ui.update { it.copy(setup = change(it.setup)) }

    /** A friend picked in the book goes through the next episode; a rare one only once it is open. */
    fun pick(f: Friend) {
        if (book?.book?.value?.isOpen(f) == false) return
        _ui.update { it.copy(setup = it.setup.copy(friend = f), invited = null) }
    }

    /** "새로운 친구를 만나러 가자": [by] at full hearts opens the next rare friend. */
    fun invite(by: Friend) {
        val rare = book?.invite(by) ?: return
        _ui.update { it.copy(invited = rare) }
        lumi.say(Lines.invited(rare))
    }

    fun closeInvite() = _ui.update { it.copy(invited = null) }

    /** What the planner may ask: the app's copy of the question bank, or without one its own built-in questions. */
    private val questions: Questions = bank?.questions ?: Questions.ANY

    /** Whether this car can play an episode for the parent's choices right now (the setup screen's start button). */
    fun playable(s: CarState): Boolean = Planner.plan(_ui.value.setup, s, Random(0), questions).isNotEmpty()

    /**
     * 소원 빌기, the setup screen's one button: plans the episode for the parent's choices, remembers the climate as the
     * parent left it and goes to the episode's first screen at once. With a story server the story is asked for now and
     * written while the parent turns AUTO off and checks the car there ([storyDone] goes on when it is back); the server
     * is never asked at any other time. Without one, the app's own lines play.
     */
    fun begin() {
        val s = car.value
        val setup = _ui.value.setup
        // a place that is no word at all (ㅎㅇ, !!, 123) is refused here and never sent: the setup screen stays, with
        // the parent's notice and the friend asking for another wish, as for a wish the server refused
        if (setup.wish.isNotBlank() && !Wish.readable(Wish.place(setup.wish))) {
            stories?.refuse(listOf(Wish.UNREADABLE))
            lumi.say(Lines.WISH_REJECTED)
            return
        }
        var ui = GameUi(
            phase = Phase.INTRO, setup = setup, puzzles = Planner.plan(setup, s, rnd, questions),
            snapshot = Snapshot.of(s), writing = stories != null,
        )
        // never a quiz the car already answers
        var i = 0
        while (i < ui.puzzles.size) {
            val size = ui.puzzles.size
            ui = fitted(ui, i, s)
            if (ui.puzzles.size == size) i++
        }
        if (ui.puzzles.isEmpty()) return
        // the youngest child on board sets how hard the app's own questions are
        val youngest = Planner.youngest(setup)
        val own = ui.puzzles.filterIsInstance<Puzzle.Quiz>().mapNotNull { q -> bank?.pick(q, youngest, rnd)?.let { StoryKeys.key(q) to it } }.toMap()
        _ui.value = ui.copy(own = own)
        save()
        lastTickMs = null
        if (stories == null) {
            lumi.say("${Lines.intro(setup.friend)} ${Lines.AUTO_OFF}")
            // every line this episode will say, fetched now so each plays without a wait
            lumi.prefetch(lines(_ui.value))
            return
        }
        // the parent turns AUTO off while the story is written; the friend says hello once it is here
        lumi.say(Lines.AUTO_OFF)
        // a refused wish is told at once too
        lumi.prefetch(FriendIntro.ALL + Lines.WISH_REJECTED)
        stories.prepare(setup, s, _ui.value.puzzles)
    }

    /**
     * A line from the waiting screen's cards (the friend telling about itself), said only while the story is still
     * being written, so it never talks over the friend's hello once the story is here.
     */
    fun whileWriting(line: String) {
        val ui = _ui.value
        if (ui.phase == Phase.INTRO && ui.writing) lumi.say(line)
    }

    /** The waiting screen turned to another card: a line left up to be read (no recording) goes with its card. */
    fun hushWhileWriting() {
        val ui = _ui.value
        if (ui.phase == Phase.INTRO && ui.writing) lumi.stop()
    }

    /**
     * The story server's answer to the episode [begin] asked for: its story, the app's own lines when it could not
     * write one, or back to the setup screen when it refused the family's wish (the reason is shown there, and the
     * friend asks for another wish). Each answer is taken once: after it the game is no longer writing.
     */
    fun storyDone(status: StoryRepo.Status) {
        val ui = _ui.value
        if (ui.phase != Phase.INTRO || !ui.writing) return
        when (status) {
            StoryRepo.Status.READY -> introduce(stories?.takeWritten())
            StoryRepo.Status.OFFLINE -> introduce(null)
            StoryRepo.Status.REJECTED -> {
                clearSave()
                _ui.value = GameUi(setup = ui.setup)
                lumi.say(Lines.WISH_REJECTED)
            }
            StoryRepo.Status.NONE, StoryRepo.Status.WRITING -> Unit
        }
    }

    /**
     * The friend's hello once the story is here (the app's own without one), and every line fetched for later. A story
     * from the shelf brings its own puzzles in place of the planned ones, and the app's own questions follow them.
     */
    private fun introduce(ready: StoryRepo.Ready?) {
        val story = ready?.story
        _ui.update { ui ->
            if (ready == null || ready.puzzles == ui.puzzles) return@update ui.copy(story = story, writing = false)
            val youngest = Planner.youngest(ui.setup)
            val own = ready.puzzles.filterIsInstance<Puzzle.Quiz>()
                .mapNotNull { q -> bank?.pick(q, youngest, rnd)?.let { StoryKeys.key(q) to it } }.toMap()
            ui.copy(story = story, writing = false, puzzles = ready.puzzles, own = own)
        }
        save()
        // the pictures still being drawn are fetched now, and the ones already in are taken in
        story?.let { stories?.follow(it) }
        stories?.let { picturesSettled(it.settled.value) }
        val ui = _ui.value
        lumi.say(story?.intro ?: Lines.intro(ui.setup.friend))
        lumi.prefetch(lines(ui))
    }

    /**
     * Pictures the story server finished (or could not draw) since the story came, taken into it; a puzzle waiting for
     * its picture starts once it is settled (not while the game is paused: 이어 하기 starts it then).
     */
    fun picturesSettled(settled: Map<String, String?>) {
        val story = _ui.value.story ?: return
        val now = story.settledWith(settled)
        if (now != story) {
            _ui.update { it.copy(story = now) }
            save()
        }
        val ui = _ui.value
        val waiting = ui.drawing ?: return
        val p = ui.puzzles.getOrNull(waiting) ?: return
        if (!ui.paused && !pictureComing(ui, p)) startPuzzle(waiting, ui.drawingIntro)
    }

    /** Whether puzzle [p]'s picture is still being drawn by the story server. */
    private fun pictureComing(ui: GameUi, p: Puzzle): Boolean {
        val story = ui.story ?: return false
        val key = StoryKeys.key(p)
        val name = story.scenes[key]?.picture ?: story.scenePictures[key] ?: return false
        return story.drawing(name)
    }

    /** [intro] is said before the puzzle's own words (이어 하기's welcome back). */
    fun startPuzzle(index: Int, intro: String? = null) {
        if (_ui.value.puzzles.getOrNull(index) == null) return
        // a temperature or fan answer counts from the car as it is now
        val start = car.value
        // the parent may have changed the car during an earlier puzzle: never start one whose answer already shows
        var ui = _ui.value
        while (true) {
            val size = ui.puzzles.size
            ui = fitted(ui, index, start)
            if (ui.puzzles.size == size) break
        }
        val p = ui.puzzles.getOrNull(index)
        if (p == null) {
            // the last puzzle had nothing the car allows now: the episode ends here
            _ui.value = ui
            treasure()
            return
        }
        if (pictureComing(ui, p)) {
            // its picture is still being drawn: the friend waits with the child (the app's own picture is used only
            // when the server could not draw it at all)
            if (_ui.value.drawing != index) lumi.say(Lines.DRAWING)
            _ui.value = ui.copy(drawing = index, drawingIntro = intro)
            return
        }
        val quiz = p as? Puzzle.Quiz
        hold = quiz?.let { q -> HoldJudge({ s -> q.solvedBy(s, start) }) }
        // the belt mission reads no car value, so it has no wrong answer
        wrong = quiz?.let { WrongJudge(it, start) }
        _ui.value = ui.copy(
            phase = Phase.PLAYING, index = index, hintHeard = false, answerNote = quiz?.let { note(it, start) },
            drawing = null, drawingIntro = null,
        )
        save()
        lumi.say(listOfNotNull(intro, _ui.value.scene(p).speech).joinToString(" "))
        // a quiz swapped just now has new words for after the answer
        lumi.prefetch(listOf(_ui.value.solvedLine))
    }

    /**
     * What Lumi will say in [ui] from [from] onwards, exactly as she will say it: each puzzle's story and question,
     * the line after the answer, the gentle wrong-answer lines, the last words and the pause line. Hints are left to
     * when they are asked for, so a line nobody hears is never paid for.
     */
    private fun lines(ui: GameUi, from: Int = 0): List<String> = buildList {
        for (i in from until ui.puzzles.size) {
            val p = ui.puzzles[i]
            add(ui.scene(p).speech)
            add(ui.copy(index = i).solvedLine)
        }
        // the few fixed lines for a wrong answer, so "다시 생각해 볼까?" comes at once
        WrongJudge.Kind.values().forEach { kind -> (0 until 3).forEach { n -> add(Lines.wrong(kind, n)) } }
        // said only when a picture is late, so it plays at once then
        if (ui.story?.images?.values?.any(Story::drawing) == true) add(Lines.DRAWING)
        val treasure = ui.story?.treasureBadge ?: Lines.TREASURE_BADGE
        add(treasure)
        add("$treasure ${Lines.CLOSER}")
        add(Lines.PAUSED)
    }

    /**
     * [ui] with the quiz at [index] fitting the car [s]: one whose answer the car already shows, or whose story no
     * longer fits the car, is swapped for one that does (its question from the app's own bank, its words the app's
     * own lines), or dropped when the car allows nothing.
     */
    private fun fitted(ui: GameUi, index: Int, s: CarState): GameUi {
        val puzzles = Planner.fitted(ui.puzzles, index, s, ui.setup, rnd, questions)
        if (puzzles == ui.puzzles) return ui
        val swap = if (puzzles.size == ui.puzzles.size) puzzles[index] as? Puzzle.Quiz else null
        val own = swap?.let { bank?.pick(it, Planner.youngest(ui.setup), rnd) }
        return ui.copy(puzzles = puzzles, own = if (swap == null || own == null) ui.own else ui.own + (StoryKeys.key(swap) to own))
    }

    /** Where a temperature or fan answer counts from, or which reading lights solve it. */
    private fun note(q: Puzzle.Quiz, start: CarState): String? = when (q.dial) {
        Dial.TEMP_UP, Dial.TEMP_DOWN -> start.tempLeft?.let { from ->
            if (q.kind == QuizKind.REASON) "지금 ${degrees(from)}도보다 ${if (q.dial == Dial.TEMP_UP) "높게" else "낮게"}"
            else "지금 ${degrees(from)}도에서 ${degrees(q.tempTarget(from))}도로"
        }
        Dial.FAN_UP, Dial.FAN_DOWN -> start.fanSpeed?.let { "지금 ${it}단에서" }
        Dial.LIGHT_CHILD -> "뒷자리 독서등 아무거나"
        else -> null
    }

    private fun degrees(c: Float) = if (c % 1f == 0f) c.toInt().toString() else c.toString()

    fun next() {
        val ui = _ui.value
        if (ui.index + 1 < ui.puzzles.size) startPuzzle(ui.index + 1) else treasure()
    }

    /** The end of the episode: a whole episode with this friend is one heart more. */
    private fun treasure() {
        val ui = _ui.value
        val friend = ui.setup.friend
        val hearts = book?.played(friend)
        _ui.update { it.copy(phase = Phase.TREASURE, heartsNow = hearts) }
        // the heart is given: an episode that reached its treasure is not played again
        clearSave()
        val treasure = ui.story?.treasureBadge ?: Lines.TREASURE_BADGE
        lumi.say(if (hearts == null) treasure else "$treasure ${Lines.CLOSER}")
    }

    fun hint() {
        val p = _ui.value.puzzle ?: return
        _ui.update { it.copy(hintHeard = true) }
        lumi.say(_ui.value.scene(p).hint)
    }

    /** 벨트 딸깍 미션: the parent saw the child buckle up (no car signal is read). */
    fun confirmBelt() {
        if (_ui.value.puzzle == Puzzle.BeltMission && !_ui.value.paused) solved()
    }

    fun hold() {
        _ui.update { it.copy(held = true) }
        lumi.stop()
    }

    /** 이어 하기: only once the car is safe again. */
    fun resume() {
        val ui = _ui.value
        if (ui.stopped != null) return
        if (!ui.rebase) {
            _ui.update { it.copy(held = false) }
            // a puzzle that was waiting for its picture starts now if the picture came during the pause
            val waiting = ui.drawing
            if (waiting != null) startPuzzle(waiting, ui.drawingIntro) else lumi.repeat()
            return
        }
        // back after the car was off: the climate to put back and the 20 minutes start again from now
        _ui.update { it.copy(held = false, rebase = false, snapshot = Snapshot.of(car.value), playedMs = 0) }
        // the puzzle starts again like 이어 하기 from the card: one the car now already answers is swapped, and its
        // answer counts from the car as it is now
        if (ui.phase == Phase.PLAYING && ui.puzzle != null) startPuzzle(ui.index, intro = Lines.WELCOME_BACK)
        else lumi.say(Lines.WELCOME_BACK)
    }

    /**
     * 20분 멈춤's 바로 이어 하기: the rest is the family's choice, not forced. The 20 minutes start again from now and
     * the kept rest is dropped, so the pause goes as soon as the car is safe.
     */
    fun keepGoing() {
        val ui = _ui.value
        if (ui.stopped != StopReason.TIME_UP && ui.restUntil == null) return
        _ui.update { it.copy(playedMs = 0, restUntil = null, held = false) }
        save()
        if (ui.phase == Phase.PLAYING) lumi.repeat()
    }

    /** 20분 멈춤's 저장하고 쉬기: the car is put back like 끝내기, but the episode is kept for after the rest. */
    fun restAndSave() {
        _ui.update { it.copy(keepSave = true) }
        finish()
    }

    /** 끝내기 or the treasure's 처음 설정으로: the app puts back what the game changed. */
    fun finish() {
        // the friend stops at once: the game is over, so the puzzle being read is not read to its end
        lumi.stop()
        // after its 20 minutes the episode is kept for after the rest, whichever reason the pause shows by then
        _ui.update {
            it.copy(
                phase = Phase.RESTORE, held = false, keepSave = it.keepSave || it.restUntil != null,
                restoreItems = it.snapshot?.changes(car.value).orEmpty(), drawing = null, drawingIntro = null,
            )
        }
        if (!_ui.value.keepSave) clearSave()
        restore()
    }

    /** Puts the car back to the snapshot taken at the start; only while parked (the parent can try again after P). */
    fun restore() {
        val target = _ui.value.snapshot ?: return
        val r = restorer ?: return
        if (_ui.value.restoring) return
        if (StopReason.DRIVING in Safety.startBlockers(car.value)) {
            _ui.update { it.copy(restoreNote = "주차(P)한 뒤 다시 되돌려 주세요") }
            return
        }
        _ui.update { it.copy(restoring = true, restoreNote = null) }
        r.restore(target) { _ -> _ui.update { it.copy(restoring = false, restoreTried = true) } }
    }

    fun reset() {
        lumi.stop()
        if (!_ui.value.keepSave) clearSave()
        _ui.update { GameUi(setup = it.setup) }
    }

    private fun solved() {
        hold = null
        wrong = null
        val ui = _ui.value
        _ui.update { it.copy(phase = Phase.SOLVED, stars = it.stars + 1) }
        save()
        lumi.say(ui.solvedLine)
    }

    // ------------------------------------------------------------ 이어 하기

    /**
     * Whether the card's 이어 하기 may go on now: the car as safe as for a new start, and every device the puzzles
     * still to come need reported (right after the car is switched on it has not yet). The rest after 20 minutes is
     * only suggested: the family may go on before it is over.
     */
    fun canContinue(s: CarState): Boolean {
        val p = _saved.value ?: return false
        return Safety.startBlockers(s).isEmpty() && p.puzzles.drop(p.next).all { s.devices.containsAll(it.devices) }
    }

    /** How much of the suggested rest after 20 minutes is left (the card shows it; it does not hold the family back). */
    fun restLeftMs(): Long = _saved.value?.let { p -> progress?.restLeftMs(p) } ?: 0L

    /**
     * The card's 이어 하기: the saved episode from its next puzzle, with its stars and story. The climate to put back
     * and the 20 minutes start again from now.
     */
    fun continueSaved() {
        val p = _saved.value ?: return
        val s = car.value
        if (!canContinue(s)) return
        _ui.value = GameUi(
            phase = Phase.INTRO, setup = p.setup, puzzles = p.puzzles, stars = p.stars, own = p.own, story = p.story,
            snapshot = Snapshot.of(s),
        )
        lastTickMs = null
        // pictures the server was still drawing when the app was switched off are fetched again
        p.story?.let { stories?.follow(it) }
        stories?.let { picturesSettled(it.settled.value) }
        // the car may have put the climate's AUTO back on while it was off, as at a new start Lumi asks for it off
        if (p.next < p.puzzles.size) {
            startPuzzle(p.next, intro = "${Lines.WELCOME_BACK} ${Lines.AUTO_OFF}")
            lumi.prefetch(lines(_ui.value, from = p.next))
        } else {
            treasure()
        }
        stories?.prepare(p.setup, s)
    }

    /** The card's 새로 시작: the saved episode and its pictures go. */
    fun startOver() {
        _saved.value?.story?.storyId?.let { stories?.letGo(it) }
        clearSave()
    }

    /** Keeps the episode as it is now: the puzzle to come back to follows from where the game stands. */
    private fun save() {
        val store = progress ?: return
        val ui = _ui.value
        val next = when (ui.phase) {
            Phase.INTRO -> 0
            Phase.SOLVED -> ui.index + 1
            else -> ui.index
        }
        val p = Progress(ui.setup, ui.puzzles, next, ui.stars, ui.own, ui.story, ui.restUntil)
        store.write(p)
        _saved.value = p
    }

    private fun clearSave() {
        progress?.clear()
        _saved.value = null
    }

    /** 이야기 신고 from the parent's screen. */
    fun report(reason: String) {
        val id = _ui.value.story?.storyId ?: return
        stories?.report(id, reason)
    }

    /** Called a few times a second with the clock: safety first, then the answer. */
    fun tick(nowMs: Long) {
        val ui = _ui.value
        val s = car.value
        if (ui.phase !in listOf(Phase.INTRO, Phase.PLAYING, Phase.SOLVED)) {
            lastTickMs = nowMs
            return
        }
        val gap = lastTickMs?.let { nowMs - it } ?: 0
        lastTickMs = nowMs
        // the app did not run for a while (the car asleep with the app in memory): as if switched off and on again,
        // the parent goes on with 이어 하기. An episode already at its 20 minutes keeps its rest instead.
        if (Safety.restarted(gap) && ui.stopped != StopReason.TIME_UP) {
            _ui.update { it.copy(held = true, rebase = true) }
            lumi.stop()
            wrong?.pause()
            return
        }
        val dt = Safety.countedMs(gap)

        val stop = Safety.pauseReason(s, ui.playedMs)
        if (stop != ui.stopped) {
            _ui.update { it.copy(stopped = stop) }
            // said aside, so 다시 듣기 after the car is ready again repeats the puzzle, not this line
            if (stop != null) lumi.aside(Lines.PAUSED)
            // 20 minutes: kept with a rest at once, so switching the car off on this screen still rests
            val store = progress
            if (stop == StopReason.TIME_UP && ui.restUntil == null && store != null) {
                _ui.update { it.copy(restUntil = store.now() + ProgressStore.REST_MS) }
                save()
            }
        }
        if (stop != null || ui.held) {
            wrong?.pause()
            return
        }

        if (s.recirculation == true) {
            if (recirculationSince == null) recirculationSince = nowMs
        } else {
            recirculationSince = null
        }
        val warn = Safety.recirculationTooLong(s, recirculationSince, nowMs)
        _ui.update { it.copy(playedMs = it.playedMs + dt, recirculationWarning = warn) }

        if (ui.phase != Phase.PLAYING) return
        if (hold?.update(s, nowMs) == true) {
            solved()
            return
        }
        // a wrong answer is told once it has stayed, never over the friend's words; 다시 듣기 still repeats the puzzle
        wrong?.update(s, nowMs, quiet = lumi.speaking.value)?.let { lumi.aside(Lines.wrong(it, wrongTold++)) }
    }
}
