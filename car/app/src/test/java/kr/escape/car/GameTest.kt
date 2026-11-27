package kr.escape.car

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kr.escape.car.engine.CarState
import kr.escape.car.engine.Device
import kr.escape.car.engine.Dial
import kr.escape.car.engine.FriendBook
import kr.escape.car.engine.Gear
import kr.escape.car.engine.Planner
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.QuizKind
import kr.escape.car.engine.Seat
import kr.escape.car.engine.Setup
import kr.escape.car.engine.Snapshot
import kr.escape.car.engine.StopReason
import kr.escape.car.story.Fetched
import kr.escape.car.story.Lines
import kr.escape.car.story.Progress
import kr.escape.car.story.ProgressStore
import kr.escape.car.story.Story
import kr.escape.car.story.StoryClient
import kr.escape.car.story.StoryKeys
import kr.escape.car.story.StoryRepo
import kr.escape.car.story.Wish
import kr.escape.car.story.WishRejected
import kr.escape.car.voice.Voice
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.random.Random

class GameTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /** Lumi without the car's speakers: what she said, in order. */
    private class FakeVoice : Voice {
        override val speaking: StateFlow<Boolean> = MutableStateFlow(false)
        val said = mutableListOf<String>()
        val prefetched = mutableListOf<String>()
        private var last: String? = null
        override fun say(text: String) {
            said += text
            last = text
        }
        override fun aside(text: String) {
            said += text
        }
        override fun repeat() {
            last?.let { said += it }
        }
        var stops = 0
        override fun stop() {
            stops++
        }
        override fun prefetch(lines: Collection<String>) {
            prefetched += lines
        }
    }

    /** Parked with a parent in the driver seat, every climate value known: the fan at 3, 21 degrees, everything else off. */
    private val known = CarState(
        gear = Gear.PARK, driverSeated = true, devices = Device.entries.toSet(),
        fanSpeed = 3, tempLeft = 21f, acOn = false, recirculation = false, defrostFront = false, seatHeat = 0,
        readingLights = Seat.entries.associateWith { false },
    )
    private val car = MutableStateFlow(known)
    private val voice = FakeVoice()
    private var now = 1_000_000_000L
    private val store by lazy { ProgressStore(tmp.root.resolve("playing_episode.json")) { now } }

    private fun game() = Game(car, voice, book = FriendBook(tmp.root.resolve("book")), progress = store, rnd = Random(0))

    private val quiz = Planner.plan(Setup(), known, Random(0)).first { it is Puzzle.Quiz }
    /** The front defroster switched on; the car above has it off, so it can be asked. */
    private val defrost = Puzzle.Quiz(QuizKind.REASON, Dial.DEFROST, 1)

    private fun saved(puzzles: List<Puzzle>, next: Int, stars: Int = next) =
        store.write(Progress(Setup(), puzzles, next, stars, own = emptyMap(), story = null))

    @Test
    fun beginSavesTheEpisodeFromItsFirstPuzzle() {
        val g = game()
        g.begin()
        val s = store.read()!!
        assertEquals(0, s.next)
        assertEquals(0, s.stars)
        assertEquals(g.ui.value.puzzles, s.puzzles)
        assertEquals(g.ui.value.setup, s.setup)
    }

    @Test
    fun startingAPuzzleSavesWhereToComeBack() {
        val g = game()
        g.begin()
        g.startPuzzle(1)
        assertEquals(1, store.read()!!.next)
    }

    @Test
    fun aSavedEpisodeContinuesAtItsNextPuzzleWithItsStars() {
        saved(listOf(quiz, Puzzle.BeltMission), next = 1, stars = 1)
        car.value = known.copy(fanSpeed = 6)
        val g = game()
        assertNotNull(g.saved.value)
        assertTrue(g.canContinue(car.value))
        g.continueSaved()
        val ui = g.ui.value
        assertEquals(Phase.PLAYING, ui.phase)
        assertEquals(Puzzle.BeltMission, ui.puzzle)
        assertEquals(1, ui.stars)
        assertEquals(0L, ui.playedMs)
        // the climate is put back as it was when they went on, not as it was before the car went off
        assertEquals(Snapshot.of(car.value), ui.snapshot)
        assertTrue(voice.said.last().startsWith(Lines.WELCOME_BACK))
        assertTrue(ui.scene(Puzzle.BeltMission).speech in voice.prefetched)
    }

    @Test
    fun solvingAContinuedPuzzleSavesTheNextOneAndTheStar() {
        saved(listOf(Puzzle.BeltMission, quiz), next = 0)
        val g = game()
        g.continueSaved()
        g.confirmBelt()
        val s = store.read()!!
        assertEquals(1, s.next)
        assertEquals(1, s.stars)
    }

    @Test
    fun aSavedEpisodeWaitsForASafeCarAndItsDevices() {
        saved(listOf(quiz, Puzzle.BeltMission), next = 0)
        val g = game()
        assertFalse(g.canContinue(known.copy(gear = Gear.DRIVE)))
        // right after the car is switched on it has not reported anything yet
        assertFalse(g.canContinue(known.copy(gear = Gear.UNKNOWN)))
        assertFalse(g.canContinue(known.copy(devices = emptySet())))

        saved(listOf(quiz, Puzzle.BeltMission), next = 1)
        // only the belt is left, which reads no device
        assertTrue(game().canContinue(known.copy(devices = emptySet())))
    }

    @Test
    fun aContinuedPuzzleTheCarAlreadyAnswersIsSwappedAndSaved() {
        saved(listOf(defrost, Puzzle.BeltMission), next = 0)
        // the parent switched the defroster on before the car went off
        car.value = known.copy(defrostFront = true)
        val g = game()
        g.continueSaved()
        assertFalse(defrost in g.ui.value.puzzles)
        assertEquals(g.ui.value.puzzles, store.read()!!.puzzles)
    }

    @Test
    fun aSaveWithOnlyTheTreasureLeftOpensItAndIsCleared() {
        saved(listOf(Puzzle.BeltMission), next = 1)
        val g = game()
        g.continueSaved()
        assertEquals(Phase.TREASURE, g.ui.value.phase)
        assertNull(store.read())
        assertNull(g.saved.value)
    }

    @Test
    fun endingTheGameForgetsTheSave() {
        saved(listOf(quiz, Puzzle.BeltMission), next = 0)
        val g = game()
        g.continueSaved()
        g.hold()
        g.finish()
        assertNull(store.read())
        assertNull(g.saved.value)
    }

    @Test
    fun endingTheGameKeepsWhatDifferedForTheChecklist() {
        val g = playing()
        car.value = car.value.copy(fanSpeed = (car.value.fanSpeed ?: 0) + 2)
        g.finish()
        val ui = g.ui.value
        assertEquals(Phase.RESTORE, ui.phase)
        assertEquals(ui.snapshot!!.changes(car.value), ui.restoreItems)
        assertTrue(ui.restoreItems.single().startsWith("풍량"))
    }

    @Test
    fun endingTheGameStopsTheFriendAtOnce() {
        val g = playing()
        val before = voice.stops
        g.finish()
        assertEquals(before + 1, voice.stops)
    }

    // ------------------------------------------------------------ 20 minutes, and the time the car was off

    /** A saved episode the family went on with, at its quiz. */
    private fun playing(): Game {
        saved(listOf(quiz, Puzzle.BeltMission), next = 0)
        return game().also { it.continueSaved() }
    }

    /** Ticks a second at a time from [from] until the 20 minutes stop the game; returns the last tick's time. */
    private fun Game.playTwentyMinutes(from: Long = 0): Long {
        var t = from
        tick(t)
        repeat(1_210) {
            if (ui.value.stopped == StopReason.TIME_UP) return t
            t += 1_000
            tick(t)
        }
        throw AssertionError("20 minutes never stopped the game (played ${ui.value.playedMs} ms)")
    }

    @Test
    fun twentyMinutesSaveARestAndRestAndSaveKeepsIt() {
        val g = playing()
        g.playTwentyMinutes()
        assertEquals(now + ProgressStore.REST_MS, store.read()!!.restUntil)
        g.restAndSave()
        assertEquals(Phase.RESTORE, g.ui.value.phase)
        g.reset()
        assertEquals(Phase.SETUP, g.ui.value.phase)
        assertNotNull(g.saved.value)
        // the rest is suggested on the card, but the family may go on before it is over
        assertEquals(ProgressStore.REST_MS, g.restLeftMs())
        assertTrue(g.canContinue(car.value))
    }

    @Test
    fun twentyMinutesLeaveARestEvenWithoutTheButton() {
        playing().playTwentyMinutes()
        // the car is switched off on the pause screen and on again: the app starts anew, the episode kept
        val again = game()
        assertNotNull(again.saved.value)
        assertTrue(again.restLeftMs() > 0)
        assertTrue(again.canContinue(car.value))
    }

    @Test
    fun goingOnAtOnceAfterTwentyMinutesCountsThemAgainFromNow() {
        val g = playing()
        val t = g.playTwentyMinutes()
        g.keepGoing()
        g.tick(t + 1_000)
        // the pause is gone, the 20 minutes start again and no rest is kept
        assertNull(g.ui.value.stopped)
        assertEquals(Phase.PLAYING, g.ui.value.phase)
        assertTrue(g.ui.value.playedMs < 60_000)
        assertNull(store.read()!!.restUntil)
    }

    @Test
    fun aShortStallIsNotCountedWhole() {
        val g = playing()
        g.tick(0)
        g.tick(5_000)
        assertEquals(1_000L, g.ui.value.playedMs)
    }

    @Test
    fun aLongGapPausesAndTheParentRestartsTheClockAndTheBaseline() {
        val g = playing()
        g.tick(0)
        g.tick(1_000)
        assertEquals(1_000L, g.ui.value.playedMs)
        // the car slept for a minute with the app still in memory
        g.tick(62_000)
        assertTrue(g.ui.value.held)
        assertTrue(g.ui.value.rebase)
        assertEquals(1_000L, g.ui.value.playedMs)
        car.value = known.copy(fanSpeed = 6)
        g.resume()
        val ui = g.ui.value
        assertFalse(ui.held)
        assertFalse(ui.rebase)
        assertEquals(0L, ui.playedMs)
        assertEquals(6, ui.snapshot!!.fanSpeed)
        assertTrue(voice.said.last().startsWith(Lines.WELCOME_BACK))
    }

    @Test
    fun aLongGapResumeRefitsAPuzzleTheCarNowAnswers() {
        saved(listOf(defrost, Puzzle.BeltMission), next = 0)
        val g = game()
        g.continueSaved()
        assertEquals(defrost, g.ui.value.puzzle)
        g.tick(0)
        g.tick(1_000)
        g.tick(62_000)
        // while the car was off the defroster was switched on: the child did nothing
        car.value = known.copy(defrostFront = true)
        g.resume()
        var t = 62_000L
        repeat(25) {
            t += 200
            g.tick(t)
        }
        assertEquals(Phase.PLAYING, g.ui.value.phase)
        assertEquals(0, g.ui.value.stars)
        assertFalse(defrost in g.ui.value.puzzles)
    }

    @Test
    fun continuingAsksToSwitchTheClimateAutoOff() {
        saved(listOf(quiz, Puzzle.BeltMission), next = 0)
        val g = game()
        g.continueSaved()
        assertTrue(Lines.AUTO_OFF in voice.said.last())
    }

    @Test
    fun endingAfterTwentyMinutesStillKeepsTheRest() {
        val g = playing()
        val t = g.playTwentyMinutes()
        // the family gets out for the rest: the door's reason shows now, and with it 끝내기
        car.value = known.copy(doorOpen = true)
        g.tick(t + 200)
        assertEquals(StopReason.DOOR_OPEN, g.ui.value.stopped)
        g.finish()
        g.reset()
        assertNotNull(g.saved.value)
        assertNotNull(store.read()!!.restUntil)
    }

    @Test
    fun startingOverForgetsTheSave() {
        saved(listOf(quiz, Puzzle.BeltMission), next = 0)
        val g = game()
        g.startOver()
        assertNull(store.read())
        assertNull(g.saved.value)
    }

    // ------------------------------------------------------------ 소원 빌기: the story is written on the first screen

    /** A story server that answers every request the same way and counts them. */
    private class Server(private val answer: () -> Story) : StoryClient("http://127.0.0.1:9") {
        val asked = mutableListOf<JSONObject>()
        override fun episode(request: JSONObject): Story {
            asked += request
            return answer()
        }
        /** What every picture of a story is when the game asks for it. */
        var picture = Fetched.SAVED

        override fun fetch(path: String, to: java.io.File): Fetched {
            if (picture == Fetched.SAVED) {
                to.parentFile?.mkdirs()
                to.writeText(path)
            }
            return picture
        }
    }

    private val written = Story.parse(JSONObject(
        """{"storyId":"${"c".repeat(32)}","theme":"STAR_TRAIN","title":"구름 위 보물찾기","intro":"안녕, 나는 반테야!",
           "treasureBadge":"배지 받아!","scenes":{}}""",
    ))

    private fun TestScope.gameWith(server: Server): Pair<Game, StoryRepo> {
        val d = StandardTestDispatcher(testScheduler)
        val repo = StoryRepo(tmp.root.resolve("next_episode.json"), "0".repeat(16), server, CoroutineScope(d), io = d)
        return Game(car, voice, stories = repo, book = FriendBook(tmp.root.resolve("book")), progress = store, rnd = Random(0)) to repo
    }

    @Test
    fun aWishGoesToTheFirstScreenAtOnceAndTheFriendSaysHelloWhenTheStoryIsHere() = runTest {
        val server = Server { written }
        val (g, repo) = gameWith(server)
        g.setup { it.copy(wish = "구름 위 놀이공원에서 펼쳐지는 보물찾기 이야기") }
        g.begin()
        // the parent turns AUTO off while the story is written; 탐험 시작 waits
        assertEquals(Phase.INTRO, g.ui.value.phase)
        assertTrue(g.ui.value.writing)
        assertEquals(listOf(Lines.AUTO_OFF), voice.said)
        advanceUntilIdle()
        g.storyDone(repo.status.value)
        assertFalse(g.ui.value.writing)
        assertEquals(written, g.ui.value.story)
        assertEquals("안녕, 나는 반테야!", voice.said.last())
        // asked once, for exactly the puzzles the game plays, and never again for a next episode
        assertEquals(1, server.asked.size)
        val slots = server.asked.single().getJSONArray("puzzles")
        assertEquals(g.ui.value.puzzles.map(StoryKeys::key), List(slots.length()) { slots.getJSONArray(it).getString(0) })
        assertEquals(written, store.read()!!.story)
    }

    /** A story whose first puzzle's picture (p1) the server is still drawing when it arrives. */
    private fun drawingServer(): Server {
        lateinit var server: Server
        server = Server {
            val slots = server.asked.last().getJSONArray("puzzles")
            written.copy(images = mapOf("p1" to "/v1/images/c/p1.webp"), scenePictures = mapOf(slots.getJSONArray(0).getString(0) to "p1"))
        }
        server.picture = Fetched.DRAWING
        return server
    }

    private fun TestScope.storyWithItsFirstPictureComing(server: Server): Pair<Game, StoryRepo> {
        val (g, repo) = gameWith(server)
        g.setup { it.copy(wish = "구름 위 놀이공원에서 펼쳐지는 보물찾기 이야기") }
        g.begin()
        runCurrent()
        g.storyDone(repo.status.value)
        assertTrue(g.ui.value.story!!.drawing("p1"))
        return g to repo
    }

    @Test
    fun aPuzzleWhosePictureIsStillDrawnWaitsForItAndStartsOnceItIsIn() = runTest {
        val server = drawingServer()
        val (g, repo) = storyWithItsFirstPictureComing(server)
        g.startPuzzle(0)
        // the friend waits with the child instead of showing the app's own picture
        assertEquals(0, g.ui.value.drawing)
        assertEquals(Phase.INTRO, g.ui.value.phase)
        assertEquals(Lines.DRAWING, voice.said.last())
        g.startPuzzle(0)  // 탐험 시작 pressed again: still waiting, said once
        assertEquals(1, voice.said.count { it == Lines.DRAWING })
        server.picture = Fetched.SAVED
        advanceTimeBy(StoryRepo.FOLLOW_EVERY_MS + 1)
        runCurrent()
        g.picturesSettled(repo.settled.value)
        assertEquals(Phase.PLAYING, g.ui.value.phase)
        assertNull(g.ui.value.drawing)
        assertFalse(g.ui.value.story!!.drawing("p1"))
        // the picture is kept with the episode, for 이어 하기
        assertFalse(store.read()!!.story!!.drawing("p1"))
    }

    @Test
    fun aPictureThatCouldNotBeDrawnStartsThePuzzleWithTheAppsOwnPicture() = runTest {
        val server = drawingServer()
        val (g, repo) = storyWithItsFirstPictureComing(server)
        g.startPuzzle(0)
        server.picture = Fetched.MISSING
        advanceTimeBy(StoryRepo.FOLLOW_EVERY_MS + 1)
        runCurrent()
        g.picturesSettled(repo.settled.value)
        assertEquals(Phase.PLAYING, g.ui.value.phase)
        assertFalse("p1" in g.ui.value.story!!.images)
        assertEquals(Lines.scene(g.ui.value.puzzle!!).pictures, g.ui.value.scene(g.ui.value.puzzle!!).pictures)
    }

    @Test
    fun aPictureThatComesWhileTheGameIsHeldStartsItsPuzzleOnResume() = runTest {
        val server = drawingServer()
        val (g, repo) = storyWithItsFirstPictureComing(server)
        g.startPuzzle(0)
        g.hold()
        server.picture = Fetched.SAVED
        advanceTimeBy(StoryRepo.FOLLOW_EVERY_MS + 1)
        runCurrent()
        g.picturesSettled(repo.settled.value)
        assertEquals(Phase.INTRO, g.ui.value.phase)
        g.resume()
        assertEquals(Phase.PLAYING, g.ui.value.phase)
    }

    @Test
    fun endingWhileAPictureIsComingNeverStartsThePuzzleLater() = runTest {
        val server = drawingServer()
        val (g, repo) = storyWithItsFirstPictureComing(server)
        g.startPuzzle(0)
        g.finish()
        server.picture = Fetched.SAVED
        advanceTimeBy(StoryRepo.FOLLOW_EVERY_MS + 1)
        runCurrent()
        g.picturesSettled(repo.settled.value)
        assertEquals(Phase.RESTORE, g.ui.value.phase)
    }

    @Test
    fun withoutTheServerTheFirstScreenGoesOnWithTheAppsOwnLines() = runTest {
        val (g, repo) = gameWith(Server { throw java.io.IOException("server down") })
        g.setup { it.copy(wish = "공룡 섬에서 펼쳐지는 보물찾기 이야기") }
        g.begin()
        advanceUntilIdle()
        g.storyDone(repo.status.value)
        assertEquals(Phase.INTRO, g.ui.value.phase)
        assertFalse(g.ui.value.writing)
        assertNull(g.ui.value.story)
        assertEquals(Lines.intro(g.ui.value.setup.friend), voice.said.last())
    }

    @Test
    fun aRefusedWishGoesBackToTheSetupToBeChanged() = runTest {
        val (g, repo) = gameWith(Server { throw WishRejected(listOf("무서운 말: 귀신")) })
        val wish = "귀신의 집에서 펼쳐지는 탐정 놀이 이야기"
        g.setup { it.copy(wish = wish) }
        g.begin()
        advanceUntilIdle()
        g.storyDone(repo.status.value)
        assertEquals(Phase.SETUP, g.ui.value.phase)
        // the wish is still in the box, and no episode is kept to go on with
        assertEquals(wish, g.ui.value.setup.wish)
        assertNull(store.read())
        assertEquals(listOf("무서운 말: 귀신"), repo.rejection.value)
    }

    @Test
    fun theFriendAsksForAnotherWishOnceWhenItIsRefused() = runTest {
        val (g, repo) = gameWith(Server { throw WishRejected(listOf("무서운 말: 귀신")) })
        g.setup { it.copy(wish = "귀신의 집에서 펼쳐지는 탐정 놀이 이야기") }
        g.begin()
        // fetched while the story is written, so it plays at once
        assertTrue(Lines.WISH_REJECTED in voice.prefetched)
        advanceUntilIdle()
        g.storyDone(repo.status.value)
        assertEquals(Lines.WISH_REJECTED, voice.said.last())
        // the screen telling the game the same answer again (it is shown again) is not a second refusal
        g.storyDone(repo.status.value)
        g.storyDone(StoryRepo.Status.REJECTED)
        assertEquals(1, voice.said.count { it == Lines.WISH_REJECTED })
    }

    @Test
    fun everyRefusalIsToldWhateverTheRule() = runTest {
        // a word of any kind, several at once, or none named at all
        for (reasons in listOf(listOf("무서운 말: 귀신"), listOf("다치는 말: 칼", "나쁜 말: 바보"), emptyList())) {
            voice.said.clear()
            val (g, repo) = gameWith(Server { throw WishRejected(reasons) })
            g.setup { it.copy(wish = "어딘가에서 펼쳐지는 보물찾기 이야기") }
            g.begin()
            advanceUntilIdle()
            g.storyDone(repo.status.value)
            assertEquals(reasons.toString(), listOf(Lines.WISH_REJECTED), voice.said.filter { it == Lines.WISH_REJECTED })
            assertEquals(Phase.SETUP, g.ui.value.phase)
        }
    }

    @Test
    fun aWishRefusedAgainAfterItWasChangedIsToldAgain() = runTest {
        val (g, repo) = gameWith(Server { throw WishRejected(listOf("무서운 말: 귀신")) })
        g.setup { it.copy(wish = "귀신의 집에서 펼쳐지는 탐정 놀이 이야기") }
        g.begin()
        advanceUntilIdle()
        g.storyDone(repo.status.value)
        // the parent writes another wish and it is refused too: a new refusal, told again
        g.setup { it.copy(wish = "귀신 성에서 펼쳐지는 탐정 놀이 이야기") }
        g.begin()
        advanceUntilIdle()
        g.storyDone(repo.status.value)
        assertEquals(2, voice.said.count { it == Lines.WISH_REJECTED })
    }

    @Test
    fun aPlaceThatIsNoWordIsRefusedInTheAppAndNeverSent() = runTest {
        for (place in listOf("ㅎㅇ", "ㅋㅋ", "!!", "123", "ghd", "rkt", "dkssud")) {
            voice.said.clear()
            val server = Server { written }
            val (g, repo) = gameWith(server)
            g.setup { it.copy(wish = "${place}에서 펼쳐지는 보물찾기 이야기") }
            g.begin()
            advanceUntilIdle()
            // never asked, the setup screen stays with the parent's notice, and the friend asks for another wish once
            assertEquals(place, 0, server.asked.size)
            assertEquals(place, Phase.SETUP, g.ui.value.phase)
            assertEquals(place, StoryRepo.Status.REJECTED, repo.status.value)
            assertEquals(place, listOf(Wish.UNREADABLE), repo.rejection.value)
            assertEquals(place, listOf(Lines.WISH_REJECTED), voice.said)
            assertNull(place, store.read())
            // the screen passing the refusal on is not a second one
            g.storyDone(repo.status.value)
            assertEquals(place, 1, voice.said.count { it == Lines.WISH_REJECTED })
        }
    }

    @Test
    fun aOneSyllableOrEnglishPlaceIsAWishLikeAnyOther() = runTest {
        for (place in listOf("용", "북극 얼음 마을", "공룡 섬2", "Paris", "dog", "Tokyo")) {
            voice.said.clear()
            val server = Server { written }
            val (g, repo) = gameWith(server)
            g.setup { it.copy(wish = "${place}에서 펼쳐지는 보물찾기 이야기") }
            g.begin()
            advanceUntilIdle()
            assertEquals(place, 1, server.asked.size)
            g.storyDone(repo.status.value)
            assertEquals(place, written, g.ui.value.story)
            assertFalse(place, Lines.WISH_REJECTED in voice.said)
        }
    }

    @Test
    fun aWishFixedAfterTheAppRefusedItGoesToTheServer() = runTest {
        val server = Server { written }
        val (g, repo) = gameWith(server)
        g.setup { it.copy(wish = "ㅎㅇ에서 펼쳐지는 보물찾기 이야기") }
        g.begin()
        g.setup { it.copy(wish = "공룡 섬에서 펼쳐지는 보물찾기 이야기") }
        g.begin()
        // the refusal is cleared while the new wish is written
        assertEquals(StoryRepo.Status.WRITING, repo.status.value)
        assertTrue(repo.rejection.value.isEmpty())
        advanceUntilIdle()
        assertEquals(1, server.asked.size)
    }

    @Test
    fun aStoryThatComesIsNeverToldAsRefused() = runTest {
        val (g, repo) = gameWith(Server { written })
        g.setup { it.copy(wish = "구름 위 놀이공원에서 펼쳐지는 보물찾기 이야기") }
        g.begin()
        advanceUntilIdle()
        g.storyDone(repo.status.value)
        assertFalse(Lines.WISH_REJECTED in voice.said)
    }
}
