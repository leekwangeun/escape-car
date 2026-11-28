package kr.escape.car.engine

import kotlin.math.abs
import kotlin.random.Random

enum class Age(val label: String, val years: String) {
    LITTLE("꼬마 탐험대", "4~6세"), CAPTAIN("탐험대장", "7~9세"), DETECTIVE("수석 탐정", "10~12세")
}

/**
 * How Lumi asks: the story server writes the question in this form, and checks its answer (server app/quiz.py). The
 * number kinds work out to an amount for a device; a situation (REASON) is a trouble the child solves by working out
 * which car function helps.
 */
enum class QuizKind(val label: String) {
    MATH("계산 문제"), ENGLISH("영어 문제"), PATTERN("규칙 찾기"), REASON("추론")
}

/**
 * Where the child puts the answer: one car device, and the values an answer can take there (server quiz.DIALS and
 * quiz.KINDS_FOR). [label] starts a number puzzle's title, which has to fit one line on the parent's panel.
 */
enum class Dial(val label: String, val device: Device, val range: IntRange, val kinds: List<QuizKind>) {
    FAN("바람 세기", Device.FAN_SPEED, 1..8, listOf(QuizKind.MATH, QuizKind.ENGLISH, QuizKind.PATTERN)),
    // answers of 1~3 are too small for a number sequence that reads naturally
    TEMP_UP("온도", Device.TEMPERATURE, 1..3, listOf(QuizKind.MATH, QuizKind.ENGLISH, QuizKind.REASON)),
    TEMP_DOWN("온도", Device.TEMPERATURE, 1..3, listOf(QuizKind.MATH, QuizKind.ENGLISH, QuizKind.REASON)),
    LIGHTS("독서등", Device.READING_LIGHT, 1..4, listOf(QuizKind.MATH, QuizKind.ENGLISH, QuizKind.PATTERN)),
    SEAT_HEAT("열선", Device.SEAT_HEAT, 1..3, listOf(QuizKind.MATH, QuizKind.ENGLISH)),

    // the situation devices: their answer is which way a function goes, never an amount

    /** 1 is on, 0 is off. */
    AC("에어컨", Device.AC, 0..1, listOf(QuizKind.REASON)),
    /** 1 keeps the outside air out (내기 순환 on), 0 lets it in. */
    RECIRC("내기 순환", Device.RECIRCULATION, 0..1, listOf(QuizKind.REASON)),
    /** The front window defroster, switched on. */
    DEFROST("성에 제거", Device.DEFROST, 1..1, listOf(QuizKind.REASON)),
    /** The fan stronger, or weaker but still blowing, than when the puzzle began. */
    FAN_UP("바람 세기", Device.FAN_SPEED, 1..1, listOf(QuizKind.REASON)),
    FAN_DOWN("바람 세기", Device.FAN_SPEED, 1..1, listOf(QuizKind.REASON)),
    /** The driver seat's heat on (any step) or off. */
    SEAT_HEAT_ON("열선", Device.SEAT_HEAT, 1..1, listOf(QuizKind.REASON)),
    SEAT_HEAT_OFF("열선", Device.SEAT_HEAT, 0..0, listOf(QuizKind.REASON)),
    /** The reading light over the child's seat on, or every reading light off. */
    LIGHT_CHILD("독서등", Device.READING_LIGHT, 1..1, listOf(QuizKind.REASON)),
    LIGHTS_OFF("독서등", Device.READING_LIGHT, 0..0, listOf(QuizKind.REASON));

    /** The answers a quiz of [kind] can carry here: a situation on the temperature only says which way, so it is 1. */
    fun values(kind: QuizKind): IntRange =
        if (kind == QuizKind.REASON && (this == TEMP_UP || this == TEMP_DOWN)) 1..1 else range

    /** What the child does to solve a situation with this answer, as the parent reads it (server quiz.REASON_ACTIONS). */
    fun action(value: Int): String = when (this) {
        AC -> if (value == 1) "에어컨 켜기" else "에어컨 끄기"
        RECIRC -> if (value == 1) "내기 순환 켜기" else "내기 순환 끄기"
        DEFROST -> "앞 유리 성에 제거 켜기"
        TEMP_UP -> "온도 올리기"
        TEMP_DOWN -> "온도 내리기"
        FAN_UP -> "바람 세게 하기"
        FAN_DOWN -> "바람 약하게 하기"
        SEAT_HEAT_ON -> "운전석 열선 켜기"
        SEAT_HEAT_OFF -> "운전석 열선 끄기"
        LIGHT_CHILD -> "뒷자리 독서등 켜기"
        LIGHTS_OFF -> "독서등 모두 끄기"
        FAN, LIGHTS, SEAT_HEAT -> label
    }
}

/**
 * A puzzle with its answer already chosen by the rule engine. The story server only writes a question whose answer
 * is that value; it can never move the answer. A quiz is solved by the car state and nothing else.
 */
sealed class Puzzle(val title: String, val devices: Set<Device>) {
    /**
     * What kind of puzzle it is, without its answer ("추론하기", not "추론: 내기 순환 켜기"): the name the screens show,
     * since the child may see the parent's side too.
     */
    val category: String
        get() = when (this) {
            is Quiz -> when (kind) {
                QuizKind.REASON -> "추론하기"
                QuizKind.MATH -> "수리 문제"
                QuizKind.PATTERN -> "규칙 맞추기"
                QuizKind.ENGLISH -> "언어 문제"
            }
            BeltMission -> title
        }

    data class Quiz(val kind: QuizKind, val dial: Dial, val value: Int) :
        Puzzle(if (kind == QuizKind.REASON) "${kind.label}: ${dial.action(value)}" else "${dial.label} ${kind.label}", setOf(dial.device)) {

        /**
         * [start] is the car when the puzzle began: a temperature or a fan that has to go up or down counts from
         * there.
         */
        fun solvedBy(s: CarState, start: CarState): Boolean = when (dial) {
            Dial.FAN -> s.fanSpeed == value
            Dial.LIGHTS -> s.readingLights.count { it.value } == value
            Dial.SEAT_HEAT -> s.seatHeat == value
            Dial.AC -> s.acOn == (value == 1)
            Dial.TEMP_UP, Dial.TEMP_DOWN -> {
                val from = start.tempLeft
                val now = s.tempLeft
                when {
                    from == null || now == null -> false
                    // a situation only asks for the right way, by any amount
                    kind == QuizKind.REASON -> if (dial == Dial.TEMP_UP) now - from > TEMP_EPS else from - now > TEMP_EPS
                    else -> {
                        val target = tempTarget(from)
                        // a target the car cannot reach counts the other way round, so the puzzle never gets stuck
                        if (target in TEMP_MIN..TEMP_MAX) abs(now - target) < TEMP_EPS else abs(abs(now - from) - value) < TEMP_EPS
                    }
                }
            }
            Dial.RECIRC -> s.recirculation == (value == 1)
            Dial.DEFROST -> s.defrostFront == true
            Dial.FAN_UP -> {
                val from = start.fanSpeed
                val now = s.fanSpeed
                from != null && now != null && now > from
            }
            // a fan switched off is not a weaker wind
            Dial.FAN_DOWN -> {
                val from = start.fanSpeed
                val now = s.fanSpeed
                from != null && now != null && now >= 1 && now < from
            }
            Dial.SEAT_HEAT_ON -> (s.seatHeat ?: 0) >= 1
            Dial.SEAT_HEAT_OFF -> s.seatHeat == 0
            // either back seat's light, wherever the child sits; the front lights do not matter
            Dial.LIGHT_CHILD -> s.readingLights.any { (seat, on) -> seat.rear && on }
            Dial.LIGHTS_OFF -> s.readingLights.isNotEmpty() && s.readingLights.values.none { it }
        }

        /** The driver's temperature a number answer asks for, counted from [from]. */
        fun tempTarget(from: Float): Float = from + if (dial == Dial.TEMP_UP) value else -value
    }

    /** 벨트 딸깍 미션: no car signal at all, the parent confirms with a button. */
    data object BeltMission : Puzzle("벨트 딸깍 미션", emptySet())

    companion object {
        const val TEMP_MIN = 16f
        const val TEMP_MAX = 30f
        private const val TEMP_EPS = 0.25f
    }
}

/** What the parent chose on E1. */
data class Setup(
    val ages: List<Age> = listOf(Age.CAPTAIN),
    val minutes: Int = 10,
    /** The friend going through every puzzle of the episode, picked in the friend book. */
    val friend: Friend = Friend.BANTE,
    /** The story the family asked for ("공룡 섬에서 보물찾기"); blank lets the story server choose. Puzzles ignore it. */
    val wish: String = "",
)

/** Whether there is a question for a quiz at an age (story QuestionBank); the planner never plans a quiz without one. */
fun interface Questions {
    fun has(q: Puzzle.Quiz, age: Age): Boolean

    companion object {
        /** Every quiz has one: the app's own built-in questions (story Lines.question), for when there is no bank. */
        val ANY = Questions { _, _ -> true }
    }
}

/**
 * Picks this episode's quizzes, their devices and answers at random, only from devices this car has, and never one
 * whose answer the car already shows: the child always has to change something.
 */
object Planner {
    /** Puzzles in one episode, the belt mission included. */
    fun puzzleCount(minutes: Int) = when {
        minutes <= 5 -> 3
        minutes <= 10 -> 4
        else -> 5
    }

    /** The youngest child on board sets how hard the questions are and which situations are told. */
    fun youngest(setup: Setup): Age = setup.ages.minByOrNull { it.ordinal } ?: Age.CAPTAIN

    /**
     * Situations the youngest (4~6) are never told: they do not know the recirculation button, and hear "에어컨" in a
     * cooling story as the answer. Warming the air (they reach for the seat), the inside of the windscreen, and every
     * reading light off at once take a second thought: they get only situations one clue settles (server
     * quiz.REASON_NOT_LITTLE).
     */
    private val NOT_LITTLE = setOf(Dial.RECIRC, Dial.TEMP_DOWN, Dial.TEMP_UP, Dial.DEFROST, Dial.LIGHTS_OFF)

    /** The youngest (4~6) know the English number words up to five (one..five), as the question bank does. */
    const val LITTLE_ENGLISH_MAX = 5

    /**
     * Whether a quiz of this kind is ever asked at [age]. Checked for every plan, also without the question bank, when
     * the app asks its own questions (story Lines.question).
     */
    fun allowed(q: Puzzle.Quiz, age: Age): Boolean = when {
        age != Age.LITTLE -> true
        q.kind == QuizKind.REASON -> q.dial !in NOT_LITTLE
        q.kind == QuizKind.ENGLISH -> q.value <= LITTLE_ENGLISH_MAX
        else -> true
    }

    fun plan(setup: Setup, s: CarState, rnd: Random = Random.Default, questions: Questions = Questions.ANY): List<Puzzle> {
        // every quiz each device of this car could ask right now; a device with none sits this episode out
        val byDevice = Device.entries.filter { it in s.devices }
            .associateWith { options(it, s, setup, questions) }
            .filterValues { it.isNotEmpty() }
        // one puzzle per device at most
        val devices = byDevice.keys.shuffled(rnd).take(puzzleCount(setup.minutes) - 1)
        fun situations(d: Device) = byDevice.getValue(d).filter { it.kind == QuizKind.REASON }
        fun numbers(d: Device) = byDevice.getValue(d).filter { it.kind != QuizKind.REASON }

        // about half the quizzes are situations, as far as the devices allow: a device that can only tell a
        // situation tells one, the others take turns at random
        val only = devices.filter { numbers(it).isEmpty() }
        val either = devices.filter { situations(it).isNotEmpty() && numbers(it).isNotEmpty() }.shuffled(rnd)
        val want = ((devices.size + rnd.nextInt(2)) / 2).coerceAtLeast(only.size)
        val told = (only + either.take(want - only.size)).toSet()

        // a different number kind for each puzzle where the devices allow it; the most limited device chooses first
        val used = mutableSetOf<QuizKind>()
        val counted = devices.filter { it !in told }.sortedBy { d -> numbers(d).map { it.kind }.toSet().size }.associateWith { d ->
            val kinds = numbers(d).map { it.kind }.distinct().shuffled(rnd)
            val kind = (kinds.firstOrNull { it !in used } ?: kinds.first()).also { used += it }
            numbers(d).filter { it.kind == kind }.random(rnd)
        }
        val quizzes = devices.map { d -> if (d in told) situations(d).random(rnd) else counted.getValue(d) }
        val picks = order(quizzes, s, setup).toMutableList<Puzzle>()
        // the belt mission sits after the second puzzle, as a short break between car puzzles
        if (picks.size >= 2) picks.add(2, Puzzle.BeltMission)
        return picks
    }

    /**
     * The quiz keys the story library may offer this car: every number quiz of a device the car has, whatever it shows
     * now (so the request does not tell it what the cabin looks like), and the situations that fit now. Only quizzes
     * [questions] can ask for the youngest child; the belt mission is always playable and is never listed.
     */
    fun playable(setup: Setup, s: CarState, questions: Questions = Questions.ANY): List<Puzzle.Quiz> {
        val age = youngest(setup)
        return Dial.entries.filter { it.device in s.devices }.flatMap { dial ->
            dial.kinds.flatMap { kind -> dial.values(kind).map { Puzzle.Quiz(kind, dial, it) } }
        }.filter { q ->
            allowed(q, age) && questions.has(q, age) && (q.kind != QuizKind.REASON || fits(q, s, setup)) &&
                (q.kind == QuizKind.REASON || settable(q, s))
        }.distinct()
    }

    /**
     * The puzzles a shelved story plays on this car now, or null when it cannot be played. Slot by slot in the
     * story's order, a slot takes its first answer that fits once the slots before it are solved and that [questions]
     * can ask (this is not the choice [plan] would make). The story is accepted only when its quizzes are on distinct
     * devices, its belt mission sits where [plan] puts it (third, when there are two quizzes or more), and it has as
     * many quizzes as [plan] would ask of this car.
     */
    fun accepts(slots: List<List<Puzzle>>, setup: Setup, s: CarState, questions: Questions = Questions.ANY): List<Puzzle>? {
        val age = youngest(setup)
        val picked = mutableListOf<Puzzle>()
        var now = s
        for (slot in slots) {
            if (slot.isNotEmpty() && slot.all { it is Puzzle.BeltMission }) {
                picked += Puzzle.BeltMission
                continue
            }
            val quizzes = slot.map { it as? Puzzle.Quiz ?: return null }
            val q = quizzes.firstOrNull { fits(it, now, setup) && questions.has(it, age) } ?: return null
            picked += q
            now = after(q, now)
        }
        val quizzes = picked.filterIsInstance<Puzzle.Quiz>()
        if (quizzes.map { it.dial.device }.toSet().size != quizzes.size) return null
        val belt = picked.indices.filter { picked[it] is Puzzle.BeltMission }
        if (belt != if (quizzes.size >= 2) listOf(2) else emptyList<Int>()) return null
        if (quizzes.size != minOf(puzzleCount(setup.minutes) - 1, devicesWithOptions(s, setup, questions))) return null
        return picked
    }

    /** How many devices of the car have a quiz to ask right now. */
    private fun devicesWithOptions(s: CarState, setup: Setup, questions: Questions): Int =
        Device.entries.count { it in s.devices && options(it, s, setup, questions).isNotEmpty() }

    /** Every quiz on device [d] the car allows now with a question for the youngest. */
    private fun options(d: Device, s: CarState, setup: Setup, questions: Questions): List<Puzzle.Quiz> {
        val age = youngest(setup)
        return Dial.entries.filter { it.device == d }
            .flatMap { dial -> dial.kinds.flatMap { kind -> dial.values(kind).map { Puzzle.Quiz(kind, dial, it) } } }
            .filter { fits(it, s, setup) && questions.has(it, age) }
    }

    /**
     * Whether [q] may be asked with the car as [s] is: the car has the device, does not already show the answer, and
     * for a situation the story fits the car (a "too hot" story only while the air conditioner is off, and so on).
     * Checked when the episode is planned and again when the puzzle starts.
     */
    fun fits(q: Puzzle.Quiz, s: CarState, setup: Setup): Boolean {
        if (q.dial.device !in s.devices || q.kind !in q.dial.kinds || q.value !in q.dial.values(q.kind)) return false
        if (!allowed(q, youngest(setup))) return false
        if (q.kind != QuizKind.REASON) return when (q.dial) {
            // which way depends on where the cabin is now
            Dial.TEMP_UP, Dial.TEMP_DOWN -> q.dial == tempDial(s)
            else -> q.value in answers(q.dial, s)
        }
        val fan = s.fanSpeed
        val blowing = fan != null && fan >= 1
        val temp = s.tempLeft
        return when (q.dial) {
            Dial.AC -> blowing && temp != null &&
                if (q.value == 1) s.acOn == false && temp <= 26f else s.acOn == true && temp < 22f
            Dial.TEMP_UP -> blowing && s.acOn == false && temp != null && temp < 22f && temp < Puzzle.TEMP_MAX
            Dial.TEMP_DOWN -> blowing && s.acOn == true && temp != null && temp >= 22f && temp > Puzzle.TEMP_MIN
            Dial.RECIRC -> blowing && s.recirculation == (q.value == 0)
            Dial.DEFROST -> s.defrostFront == false
            Dial.FAN_UP -> fan != null && fan in 1..3 && fan < s.fanSpeedRange.last
            Dial.FAN_DOWN -> fan != null && fan >= 6
            Dial.SEAT_HEAT_ON -> s.seatHeat == 0
            Dial.SEAT_HEAT_OFF -> (s.seatHeat ?: 0) >= 1
            Dial.LIGHT_CHILD -> s.readingLights.filterKeys { it.rear }.let { it.isNotEmpty() && it.values.none { on -> on } }
            Dial.LIGHTS_OFF -> s.readingLights.values.any { it }
            Dial.FAN, Dial.LIGHTS, Dial.SEAT_HEAT -> false
        }
    }

    /** Down when the cabin is warm, up when it is cool, so the answer stays a comfortable temperature. */
    fun tempDial(s: CarState): Dial = if ((s.tempLeft ?: 22f) >= 22f) Dial.TEMP_DOWN else Dial.TEMP_UP

    /** The values a number dial can be set to on this car (the other dials take their whole range). */
    private fun capability(dial: Dial, s: CarState): IntRange = when (dial) {
        Dial.FAN -> maxOf(dial.range.first, s.fanSpeedRange.first)..minOf(dial.range.last, s.fanSpeedRange.last)
        Dial.SEAT_HEAT -> dial.range.first..minOf(dial.range.last, s.seatHeatMax)
        // only as many lights as the car told us about
        Dial.LIGHTS -> dial.range.first..minOf(dial.range.last, maxOf(1, s.readingLights.size))
        else -> dial.range
    }.takeUnless { it.isEmpty() } ?: dial.range

    /** What a number dial shows now, or null when the car does not tell (or the dial has no such reading). */
    private fun showing(dial: Dial, s: CarState): Int? = when (dial) {
        Dial.FAN -> s.fanSpeed
        Dial.LIGHTS -> s.readingLights.count { it.value }
        Dial.SEAT_HEAT -> s.seatHeat
        else -> null
    }

    /** The answers a number dial can take on this car: within what it can set, and not what it already shows. */
    fun answers(dial: Dial, s: CarState): List<Int> = capability(dial, s).filter { it != showing(dial, s) }

    /**
     * Whether the car can be set to [q]'s value. What the dial shows now is kept even when it lies outside what the
     * car can set, so the list does not tell what the cabin looks like.
     */
    private fun settable(q: Puzzle.Quiz, s: CarState): Boolean = q.value in capability(q.dial, s) || q.value == showing(q.dial, s)

    /**
     * The car once [q] is solved, as far as another puzzle's condition can tell: a temperature or a fan moved the
     * least it can, which lights a light count turns on left as they are.
     */
    fun after(q: Puzzle.Quiz, s: CarState): CarState = when (q.dial) {
        Dial.FAN -> s.copy(fanSpeed = q.value)
        Dial.FAN_UP -> s.copy(fanSpeed = s.fanSpeed?.plus(1))
        Dial.FAN_DOWN -> s.copy(fanSpeed = s.fanSpeed?.minus(1))
        Dial.TEMP_UP -> s.copy(tempLeft = s.tempLeft?.plus(q.value))
        Dial.TEMP_DOWN -> s.copy(tempLeft = s.tempLeft?.minus(q.value))
        Dial.AC -> s.copy(acOn = q.value == 1)
        Dial.RECIRC -> s.copy(recirculation = q.value == 1)
        Dial.DEFROST -> s.copy(defrostFront = true)
        Dial.SEAT_HEAT -> s.copy(seatHeat = q.value)
        Dial.SEAT_HEAT_ON -> s.copy(seatHeat = maxOf(1, s.seatHeat ?: 0))
        Dial.SEAT_HEAT_OFF -> s.copy(seatHeat = 0)
        Dial.LIGHTS -> s
        // one back light on is enough; which one cannot be told, so the first the car reports
        Dial.LIGHT_CHILD -> s.copy(readingLights = s.readingLights + ((s.readingLights.keys.firstOrNull { it.rear } ?: Seat.REAR_RIGHT) to true))
        Dial.LIGHTS_OFF -> s.copy(readingLights = s.readingLights.mapValues { false })
    }

    /**
     * The quizzes in an order where each one still fits once the ones before it are solved (a "too hot" story before
     * a "warm the cabin" one would no longer fit); [quizzes] as they are when they already do, or when no order does.
     */
    fun order(quizzes: List<Puzzle.Quiz>, s: CarState, setup: Setup): List<Puzzle.Quiz> {
        fun works(order: List<Puzzle.Quiz>): Boolean {
            var now = s
            for (q in order) {
                if (!fits(q, now, setup)) return false
                now = after(q, now)
            }
            return true
        }
        if (works(quizzes)) return quizzes
        return permutations(quizzes).firstOrNull(::works) ?: quizzes
    }

    private fun <T> permutations(list: List<T>): List<List<T>> =
        if (list.size <= 1) listOf(list)
        else list.indices.flatMap { i -> permutations(list.take(i) + list.drop(i + 1)).map { listOf(list[i]) + it } }

    /**
     * [puzzles] with the one at [index] fitting the car [s] as it starts: a quiz the car no longer allows is swapped
     * for its [replacement], or dropped when nothing fits. The other puzzles stay as they are.
     */
    fun fitted(
        puzzles: List<Puzzle>, index: Int, s: CarState, setup: Setup, rnd: Random, questions: Questions = Questions.ANY,
    ): List<Puzzle> {
        val q = puzzles.getOrNull(index) as? Puzzle.Quiz ?: return puzzles
        if (fits(q, s, setup)) return puzzles
        val later = puzzles.drop(index + 1).flatMap { it.devices }.toSet()
        val earlier = puzzles.take(index).flatMap { it.devices }.toSet()
        val swap = replacement(q, s, setup, later, earlier, rnd, questions)
            ?: return puzzles.filterIndexed { i, _ -> i != index }
        return puzzles.mapIndexed { i, p -> if (i == index) swap else p }
    }

    /**
     * Another quiz for [q] when the car no longer allows it as its puzzle starts (the parent changed something during
     * an earlier one): the same function the other way first ("독서등 모두 끄기" for "뒷자리 독서등 켜기"), then
     * anything else on the same device, then a device no other puzzle of the episode uses. [later] are the devices of
     * the puzzles still to come, never taken; [earlier] those of the puzzles already played, taken only when nothing
     * else fits. Null when the car allows nothing at all.
     */
    fun replacement(
        q: Puzzle.Quiz, s: CarState, setup: Setup, later: Set<Device>, earlier: Set<Device>, rnd: Random,
        questions: Questions = Questions.ANY,
    ): Puzzle.Quiz? {
        opposite(q)?.takeIf { fits(it, s, setup) && questions.has(it, youngest(setup)) }?.let { return it }
        // the same kind of question where the device has one
        fun pick(d: Device) = options(d, s, setup, questions).filter { it != q }
            .let { all -> all.filter { it.kind == q.kind }.ifEmpty { all } }.randomOrNull(rnd)
        pick(q.dial.device)?.let { return it }
        val others = Device.entries.filter { it in s.devices && it != q.dial.device && it !in later }.shuffled(rnd)
        return others.filter { it !in earlier }.firstNotNullOfOrNull(::pick)
            ?: others.filter { it in earlier }.firstNotNullOfOrNull(::pick)
    }

    /** The same function the other way (on and off, up and down), or null for one that only goes one way. */
    fun opposite(q: Puzzle.Quiz): Puzzle.Quiz? = when (q.dial) {
        Dial.AC, Dial.RECIRC -> q.copy(value = 1 - q.value)
        Dial.TEMP_UP -> q.copy(dial = Dial.TEMP_DOWN)
        Dial.TEMP_DOWN -> q.copy(dial = Dial.TEMP_UP)
        Dial.FAN_UP -> Puzzle.Quiz(q.kind, Dial.FAN_DOWN, 1)
        Dial.FAN_DOWN -> Puzzle.Quiz(q.kind, Dial.FAN_UP, 1)
        Dial.SEAT_HEAT_ON -> Puzzle.Quiz(q.kind, Dial.SEAT_HEAT_OFF, 0)
        Dial.SEAT_HEAT_OFF -> Puzzle.Quiz(q.kind, Dial.SEAT_HEAT_ON, 1)
        Dial.LIGHT_CHILD -> Puzzle.Quiz(q.kind, Dial.LIGHTS_OFF, 0)
        Dial.LIGHTS_OFF -> Puzzle.Quiz(q.kind, Dial.LIGHT_CHILD, 1)
        Dial.FAN, Dial.LIGHTS, Dial.SEAT_HEAT, Dial.DEFROST -> null
    }
}

/**
 * Solved only when the parent has changed the car into the answer and it has held for [holdMs]: a value passed
 * through on the way to another one does not count. A puzzle never starts with its answer showing (Planner.fits);
 * should the car still show it at the first look, it counts only once it has been changed away and back.
 */
class HoldJudge(private val solved: (CarState) -> Boolean, private val holdMs: Long = HOLD_MS) {
    private var since: Long? = null
    private var armed = false

    fun update(s: CarState, nowMs: Long): Boolean {
        if (!solved(s)) {
            armed = true
            since = null
            return false
        }
        if (!armed) return false
        val start = since ?: nowMs.also { since = it }
        return nowMs - start >= holdMs
    }

    companion object {
        /**
         * Long enough that a value passed on the way somewhere else does not count (a light switched on one seat at a
         * time stops at every count for a moment), short enough that the child is not left waiting.
         */
        const val HOLD_MS = 2500L
    }
}
