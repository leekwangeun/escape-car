package kr.escape.car.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class EngineTest {
    private val allDevices = Device.entries.toSet()
    private val parked = CarState(gear = Gear.PARK, driverSeated = true, devices = allDevices)

    /** Every climate value known: the fan at 3, 21 degrees, everything else off. */
    private val known = parked.copy(
        fanSpeed = 3, tempLeft = 21f, acOn = false, recirculation = false, defrostFront = false, seatHeat = 0,
        readingLights = Seat.entries.associateWith { false },
    )
    private val setup = Setup()
    private val little = Setup(ages = listOf(Age.LITTLE))

    private fun reason(dial: Dial, value: Int = 1) = Puzzle.Quiz(QuizKind.REASON, dial, value)

    /** Every situation the server knows (server quiz.REASON_ACTIONS). */
    private val everySituation = Dial.entries.filter { QuizKind.REASON in it.kinds }
        .flatMap { d -> d.values(QuizKind.REASON).map { reason(d, it) } }

    @Test
    fun startNeedsParkClosedDoorsAndDriver() {
        assertEquals(emptyList<StopReason>(), Safety.startBlockers(parked))
        assertEquals(listOf(StopReason.DRIVING), Safety.startBlockers(parked.copy(gear = Gear.DRIVE)))
        assertEquals(listOf(StopReason.DRIVING), Safety.startBlockers(parked.copy(uxRestricted = true)))
        assertEquals(listOf(StopReason.NO_DRIVER), Safety.startBlockers(parked.copy(driverSeated = null)))
        assertEquals(listOf(StopReason.DOOR_OPEN), Safety.startBlockers(parked.copy(doorOpen = true)))
        assertEquals(listOf(StopReason.HVAC_OFF), Safety.startBlockers(parked.copy(hvacPower = false)))
        assertEquals(emptyList<StopReason>(), Safety.startBlockers(parked.copy(hvacPower = true)))
    }

    @Test
    fun pausePrefersDrivingThenDoorThenTime() {
        assertNull(Safety.pauseReason(parked, 0))
        assertEquals(StopReason.DRIVING, Safety.pauseReason(parked.copy(gear = Gear.DRIVE, doorOpen = true), 0))
        assertEquals(StopReason.DOOR_OPEN, Safety.pauseReason(parked.copy(doorOpen = true), 0))
        assertEquals(StopReason.TIME_UP, Safety.pauseReason(parked, Safety.MAX_PLAY_MS))
        // a driver getting out is a door first; the seat alone does not stop a running game
        assertNull(Safety.pauseReason(parked.copy(driverSeated = false), 0))
    }

    @Test
    fun recirculationWarningAfterThreeMinutes() {
        val on = parked.copy(recirculation = true)
        assertFalse(Safety.recirculationTooLong(on, 0, Safety.RECIRCULATION_LIMIT_MS - 1))
        assertTrue(Safety.recirculationTooLong(on, 0, Safety.RECIRCULATION_LIMIT_MS))
        assertFalse(Safety.recirculationTooLong(on.copy(recirculation = false), 0, Safety.RECIRCULATION_LIMIT_MS))
    }

    @Test
    fun aLongGapIsARestartAndOnlyASecondCounts() {
        assertEquals(200L, Safety.countedMs(200))
        // a car asleep, or a screen being rebuilt, does not play
        assertEquals(1_000L, Safety.countedMs(5_000))
        assertEquals(0L, Safety.countedMs(-5))
        assertFalse(Safety.restarted(59_999))
        assertTrue(Safety.restarted(60_000))
    }

    // ------------------------------------------------------------ the dials match the server

    @Test
    fun dialsAskTheKindsAndValuesTheServerKnows() {
        val number = listOf(QuizKind.MATH, QuizKind.ENGLISH, QuizKind.PATTERN)
        assertEquals(number, Dial.FAN.kinds)
        assertEquals(number, Dial.LIGHTS.kinds)
        assertEquals(listOf(QuizKind.MATH, QuizKind.ENGLISH), Dial.SEAT_HEAT.kinds)
        assertEquals(listOf(QuizKind.MATH, QuizKind.ENGLISH, QuizKind.REASON), Dial.TEMP_UP.kinds)
        assertEquals(listOf(QuizKind.MATH, QuizKind.ENGLISH, QuizKind.REASON), Dial.TEMP_DOWN.kinds)
        for (d in listOf(Dial.AC, Dial.RECIRC, Dial.DEFROST, Dial.FAN_UP, Dial.FAN_DOWN, Dial.SEAT_HEAT_ON, Dial.SEAT_HEAT_OFF, Dial.LIGHT_CHILD, Dial.LIGHTS_OFF)) {
            assertEquals(d.name, listOf(QuizKind.REASON), d.kinds)
        }
        // a situation on the temperature only says which way
        assertEquals(1..3, Dial.TEMP_UP.values(QuizKind.MATH))
        assertEquals(1..1, Dial.TEMP_UP.values(QuizKind.REASON))
        assertEquals(1..1, Dial.TEMP_DOWN.values(QuizKind.REASON))
        assertEquals(0..1, Dial.AC.values(QuizKind.REASON))
        assertEquals(0..1, Dial.RECIRC.values(QuizKind.REASON))
        assertEquals(0..0, Dial.SEAT_HEAT_OFF.values(QuizKind.REASON))
        assertEquals(0..0, Dial.LIGHTS_OFF.values(QuizKind.REASON))
        assertEquals(13, everySituation.size)
    }

    @Test
    fun aSituationsTitleSaysWhatToDo() {
        assertEquals("추론: 에어컨 켜기", reason(Dial.AC, 1).title)
        assertEquals("추론: 에어컨 끄기", reason(Dial.AC, 0).title)
        assertEquals("추론: 운전석 열선 켜기", reason(Dial.SEAT_HEAT_ON).title)
        assertEquals("추론: 운전석 열선 끄기", reason(Dial.SEAT_HEAT_OFF, 0).title)
        assertEquals("추론: 온도 올리기", reason(Dial.TEMP_UP).title)
        assertEquals("바람 세기 계산 문제", Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 5).title)
        for (q in everySituation) assertFalse(q.title, q.title.contains('·'))
    }

    // ------------------------------------------------------------ what solves a quiz

    @Test
    fun fanSeatHeatAndAcAreReadStraightFromTheCar() {
        val fan = Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 5)
        assertTrue(fan.solvedBy(parked.copy(fanSpeed = 5), parked))
        assertFalse(fan.solvedBy(parked.copy(fanSpeed = 4), parked))
        assertFalse(fan.solvedBy(parked.copy(fanSpeed = null), parked))

        val heat = Puzzle.Quiz(QuizKind.ENGLISH, Dial.SEAT_HEAT, 2)
        assertTrue(heat.solvedBy(parked.copy(seatHeat = 2), parked))
        assertFalse(heat.solvedBy(parked.copy(seatHeat = 3), parked))
        assertFalse(heat.solvedBy(parked.copy(seatHeat = null), parked))

        val acOn = reason(Dial.AC, 1)
        val acOff = reason(Dial.AC, 0)
        assertTrue(acOn.solvedBy(parked.copy(acOn = true), parked))
        assertFalse(acOn.solvedBy(parked.copy(acOn = false), parked))
        assertTrue(acOff.solvedBy(parked.copy(acOn = false), parked))
        assertFalse(acOff.solvedBy(parked.copy(acOn = true), parked))
        // not known yet is never an answer
        assertFalse(acOn.solvedBy(parked, parked))
        assertFalse(acOff.solvedBy(parked, parked))
    }

    @Test
    fun everySituationIsJudgedOnTheCar() {
        val recircOn = reason(Dial.RECIRC, 1)
        val recircOff = reason(Dial.RECIRC, 0)
        assertTrue(recircOn.solvedBy(known.copy(recirculation = true), known))
        assertFalse(recircOn.solvedBy(known, known))
        assertFalse(recircOn.solvedBy(known.copy(recirculation = null), known))
        assertTrue(recircOff.solvedBy(known, known.copy(recirculation = true)))
        assertFalse(recircOff.solvedBy(known.copy(recirculation = true), known))

        val defrost = reason(Dial.DEFROST)
        assertTrue(defrost.solvedBy(known.copy(defrostFront = true), known))
        assertFalse(defrost.solvedBy(known, known))
        assertFalse(defrost.solvedBy(known.copy(defrostFront = null), known))

        // the temperature only has to go the right way, by any amount, from where the puzzle began
        val warmer = reason(Dial.TEMP_UP)
        val at20 = known.copy(tempLeft = 20f)
        assertTrue(warmer.solvedBy(known.copy(tempLeft = 20.5f), at20))
        assertTrue(warmer.solvedBy(known.copy(tempLeft = 24f), at20))
        assertFalse(warmer.solvedBy(at20, at20))
        assertFalse(warmer.solvedBy(known.copy(tempLeft = 19f), at20))
        assertFalse(warmer.solvedBy(known.copy(tempLeft = null), at20))
        val cooler = reason(Dial.TEMP_DOWN)
        val at24 = known.copy(tempLeft = 24f, acOn = true)
        assertTrue(cooler.solvedBy(at24.copy(tempLeft = 23.5f), at24))
        assertTrue(cooler.solvedBy(at24.copy(tempLeft = 18f), at24))
        assertFalse(cooler.solvedBy(at24, at24))
        assertFalse(cooler.solvedBy(at24.copy(tempLeft = 25f), at24))
        assertFalse(cooler.solvedBy(at24, known.copy(tempLeft = null)))

        val stronger = reason(Dial.FAN_UP)
        val fan2 = known.copy(fanSpeed = 2)
        assertTrue(stronger.solvedBy(known.copy(fanSpeed = 3), fan2))
        assertTrue(stronger.solvedBy(known.copy(fanSpeed = 8), fan2))
        assertFalse(stronger.solvedBy(fan2, fan2))
        assertFalse(stronger.solvedBy(known.copy(fanSpeed = 1), fan2))
        assertFalse(stronger.solvedBy(known.copy(fanSpeed = null), fan2))
        // a fan switched off is not a weaker wind
        val weaker = reason(Dial.FAN_DOWN)
        val fan7 = known.copy(fanSpeed = 7)
        assertTrue(weaker.solvedBy(known.copy(fanSpeed = 6), fan7))
        assertTrue(weaker.solvedBy(known.copy(fanSpeed = 1), fan7))
        assertFalse(weaker.solvedBy(known.copy(fanSpeed = 0), fan7))
        assertFalse(weaker.solvedBy(fan7, fan7))
        assertFalse(weaker.solvedBy(known.copy(fanSpeed = 8), fan7))

        val heatOn = reason(Dial.SEAT_HEAT_ON)
        for (step in 1..3) assertTrue(heatOn.solvedBy(known.copy(seatHeat = step), known))
        assertFalse(heatOn.solvedBy(known, known))
        assertFalse(heatOn.solvedBy(known.copy(seatHeat = null), known))
        val heatOff = reason(Dial.SEAT_HEAT_OFF, 0)
        assertTrue(heatOff.solvedBy(known, known.copy(seatHeat = 2)))
        assertFalse(heatOff.solvedBy(known.copy(seatHeat = 1), known.copy(seatHeat = 2)))
        assertFalse(heatOff.solvedBy(known.copy(seatHeat = null), known.copy(seatHeat = 2)))

        // either back-seat light solves it; front-seat lights do not
        val childLight = reason(Dial.LIGHT_CHILD)
        val off = Seat.entries.associateWith { false }
        assertTrue(childLight.solvedBy(known.copy(readingLights = off + (Seat.REAR_LEFT to true)), known))
        assertTrue(childLight.solvedBy(known.copy(readingLights = off + (Seat.REAR_RIGHT to true)), known))
        assertTrue(childLight.solvedBy(known.copy(readingLights = Seat.entries.associateWith { true }), known))
        assertFalse(childLight.solvedBy(known.copy(readingLights = off + (Seat.DRIVER to true)), known))
        assertFalse(childLight.solvedBy(known.copy(readingLights = emptyMap()), known))

        val allOff = reason(Dial.LIGHTS_OFF, 0)
        val one = known.copy(readingLights = off + (Seat.DRIVER to true))
        assertTrue(allOff.solvedBy(known, one))
        assertFalse(allOff.solvedBy(one, one))
        // no light known is not "every light off"
        assertFalse(allOff.solvedBy(known.copy(readingLights = emptyMap()), one))
    }

    @Test
    fun lightsCountHowManyAreOnWhereverTheyAre() {
        val two = Puzzle.Quiz(QuizKind.PATTERN, Dial.LIGHTS, 2)
        assertTrue(two.solvedBy(parked.copy(readingLights = mapOf(Seat.DRIVER to true, Seat.REAR_LEFT to true, Seat.REAR_RIGHT to false)), parked))
        assertTrue(two.solvedBy(parked.copy(readingLights = mapOf(Seat.PASSENGER to true, Seat.REAR_RIGHT to true)), parked))
        assertFalse(two.solvedBy(parked.copy(readingLights = mapOf(Seat.DRIVER to true, Seat.REAR_LEFT to false)), parked))
        assertFalse(two.solvedBy(parked.copy(readingLights = Seat.entries.associateWith { true }), parked))
        assertTrue(Puzzle.Quiz(QuizKind.MATH, Dial.LIGHTS, 4).solvedBy(parked.copy(readingLights = Seat.entries.associateWith { true }), parked))
    }

    @Test
    fun aTemperatureAnswerCountsFromThePuzzlesStart() {
        val up = Puzzle.Quiz(QuizKind.MATH, Dial.TEMP_UP, 2)
        val start = parked.copy(tempLeft = 20f)
        assertEquals(22f, up.tempTarget(20f))
        assertTrue(up.solvedBy(parked.copy(tempLeft = 22f), start))
        assertFalse(up.solvedBy(parked.copy(tempLeft = 21.5f), start))
        assertFalse(up.solvedBy(parked.copy(tempLeft = 20f), start))
        // the same degrees the wrong way do not count while the target can be reached
        assertFalse(up.solvedBy(parked.copy(tempLeft = 18f), start))
        // another start, another target
        assertFalse(up.solvedBy(parked.copy(tempLeft = 22f), parked.copy(tempLeft = 21f)))
        assertTrue(up.solvedBy(parked.copy(tempLeft = 23f), parked.copy(tempLeft = 21f)))

        val down = Puzzle.Quiz(QuizKind.ENGLISH, Dial.TEMP_DOWN, 3)
        assertEquals(21f, down.tempTarget(24f))
        assertTrue(down.solvedBy(parked.copy(tempLeft = 21f), parked.copy(tempLeft = 24f)))
        assertFalse(down.solvedBy(parked.copy(tempLeft = 27f), parked.copy(tempLeft = 24f)))

        // nothing known, nothing solved
        assertFalse(up.solvedBy(parked.copy(tempLeft = 22f), parked))
        assertFalse(up.solvedBy(parked, start))
    }

    @Test
    fun anUnreachableTemperatureCountsEitherWay() {
        // 29 + 3 is past the car's highest setting, so three degrees down counts instead
        val up = Puzzle.Quiz(QuizKind.MATH, Dial.TEMP_UP, 3)
        val hot = parked.copy(tempLeft = 29f)
        assertTrue(up.tempTarget(29f) > Puzzle.TEMP_MAX)
        assertTrue(up.solvedBy(parked.copy(tempLeft = 26f), hot))
        assertFalse(up.solvedBy(parked.copy(tempLeft = 30f), hot))
        assertFalse(up.solvedBy(hot, hot))

        val down = Puzzle.Quiz(QuizKind.ENGLISH, Dial.TEMP_DOWN, 2)
        val cold = parked.copy(tempLeft = 17f)
        assertTrue(down.tempTarget(17f) < Puzzle.TEMP_MIN)
        assertTrue(down.solvedBy(parked.copy(tempLeft = 19f), cold))
        assertTrue(down.solvedBy(parked.copy(tempLeft = 15f), cold))
        assertFalse(down.solvedBy(parked.copy(tempLeft = 16f), cold))
    }

    // ------------------------------------------------------------ holding the answer

    private val fan5 = Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 5)
    private val at3 = parked.copy(fanSpeed = 3)
    private val at5 = parked.copy(fanSpeed = 5)
    private fun fanJudge() = HoldJudge({ s -> fan5.solvedBy(s, parked) })

    @Test
    fun holdJudgeWaitsForTheHold() {
        val hold = HoldJudge.HOLD_MS
        val judge = fanJudge()
        assertFalse(judge.update(at3, 0))
        assertFalse(judge.update(at5, 100))
        assertFalse(judge.update(at5, 100 + hold - 1))
        assertTrue(judge.update(at5, 100 + hold))
        // leaving the answer starts the wait again
        val back = 200 + hold
        assertFalse(judge.update(at3, back))
        assertFalse(judge.update(at5, back + 100))
        assertFalse(judge.update(at5, back + 100 + hold - 1))
        assertTrue(judge.update(at5, back + 100 + hold))
    }

    @Test
    fun aLightCountPassedOnTheWayDoesNotCount() {
        val two = Puzzle.Quiz(QuizKind.MATH, Dial.LIGHTS, 2)
        val judge = HoldJudge({ s -> two.solvedBy(s, parked) })
        val lit = { n: Int -> parked.copy(readingLights = Seat.entries.mapIndexed { i, seat -> seat to (i < n) }.toMap()) }
        assertFalse(judge.update(lit(0), 0))
        assertFalse(judge.update(lit(1), 800))
        // the parent reaches for the next switch: two lights for a second and a half, then four
        assertFalse(judge.update(lit(2), 1600))
        assertFalse(judge.update(lit(2), 3100))
        assertFalse(judge.update(lit(3), 3200))
        assertFalse(judge.update(lit(4), 3800))
        assertFalse(judge.update(lit(4), 9000))
    }

    @Test
    fun holdJudgeIgnoresAValuePassedThroughOnTheWay() {
        val judge = fanJudge()
        judge.update(at3, 0)
        assertFalse(judge.update(at5, 100))
        assertFalse(judge.update(parked.copy(fanSpeed = 6), 600))
        assertFalse(judge.update(parked.copy(fanSpeed = 6), 3000))
    }

    @Test
    fun holdJudgeIgnoresAnAnswerAlreadyShowing() {
        val judge = fanJudge()
        assertFalse(judge.update(at5, 0))
        assertFalse(judge.update(at5, 5000))
    }

    @Test
    fun aTemperatureQuizIsJudgedFromTheCarAtItsStart() {
        val down = Puzzle.Quiz(QuizKind.MATH, Dial.TEMP_DOWN, 2)
        val start = parked.copy(tempLeft = 24f)
        val judge = HoldJudge({ s -> down.solvedBy(s, start) })
        assertFalse(judge.update(start, 0))
        assertFalse(judge.update(parked.copy(tempLeft = 23f), 300))
        assertFalse(judge.update(parked.copy(tempLeft = 22f), 600))
        assertTrue(judge.update(parked.copy(tempLeft = 22f), 600 + HoldJudge.HOLD_MS))
    }

    @Test
    fun aSituationHoldsLikeAnyAnswer() {
        val weaker = reason(Dial.FAN_DOWN)
        val start = known.copy(fanSpeed = 7)
        val judge = HoldJudge({ s -> weaker.solvedBy(s, start) })
        assertFalse(judge.update(start, 0))
        assertFalse(judge.update(known.copy(fanSpeed = 4), 300))
        assertTrue(judge.update(known.copy(fanSpeed = 4), 300 + HoldJudge.HOLD_MS))
    }

    // ------------------------------------------------------------ when a situation may be told

    @Test
    fun theAirConditionerAndTheTemperatureFollowTheCabin() {
        val ac1 = reason(Dial.AC, 1)
        assertTrue(Planner.fits(ac1, known, setup))
        assertTrue(Planner.fits(ac1, known.copy(tempLeft = 26f), setup))
        assertFalse(Planner.fits(ac1, known.copy(tempLeft = 26.5f), setup))
        assertFalse(Planner.fits(ac1, known.copy(acOn = true), setup))
        assertFalse(Planner.fits(ac1, known.copy(acOn = null), setup))
        assertFalse(Planner.fits(ac1, known.copy(fanSpeed = 0), setup))
        assertFalse(Planner.fits(ac1, known.copy(fanSpeed = null), setup))
        assertFalse(Planner.fits(ac1, known.copy(tempLeft = null), setup))
        assertFalse(Planner.fits(ac1, known.copy(devices = allDevices - Device.AC), setup))

        val ac0 = reason(Dial.AC, 0)
        val cool = known.copy(acOn = true, tempLeft = 21.5f)
        assertTrue(Planner.fits(ac0, cool, setup))
        assertFalse(Planner.fits(ac0, cool.copy(tempLeft = 22f), setup))
        assertFalse(Planner.fits(ac0, cool.copy(acOn = false), setup))
        assertFalse(Planner.fits(ac0, cool.copy(fanSpeed = 0), setup))

        val warmer = reason(Dial.TEMP_UP)
        assertTrue(Planner.fits(warmer, known, setup))
        assertFalse(Planner.fits(warmer, known.copy(tempLeft = 22f), setup))
        assertFalse(Planner.fits(warmer, known.copy(acOn = true), setup))
        assertFalse(Planner.fits(warmer, known.copy(fanSpeed = 0), setup))
        assertFalse(Planner.fits(warmer, known.copy(tempLeft = null), setup))

        val cooler = reason(Dial.TEMP_DOWN)
        val warm = known.copy(acOn = true, tempLeft = 22f)
        assertTrue(Planner.fits(cooler, warm, setup))
        assertTrue(Planner.fits(cooler, warm.copy(tempLeft = 28f), setup))
        assertFalse(Planner.fits(cooler, warm.copy(tempLeft = 21.5f), setup))
        assertFalse(Planner.fits(cooler, warm.copy(acOn = false), setup))
        assertFalse(Planner.fits(cooler, warm.copy(fanSpeed = 0), setup))
    }

    @Test
    fun theOtherSituationsFollowTheirDevice() {
        val recircOn = reason(Dial.RECIRC, 1)
        val recircOff = reason(Dial.RECIRC, 0)
        assertTrue(Planner.fits(recircOn, known, setup))
        assertFalse(Planner.fits(recircOn, known.copy(recirculation = true), setup))
        assertFalse(Planner.fits(recircOn, known.copy(recirculation = null), setup))
        assertFalse(Planner.fits(recircOn, known.copy(fanSpeed = 0), setup))
        assertTrue(Planner.fits(recircOff, known.copy(recirculation = true), setup))
        assertFalse(Planner.fits(recircOff, known, setup))
        assertFalse(Planner.fits(recircOff, known.copy(recirculation = true, fanSpeed = 0), setup))
        assertFalse(Planner.fits(recircOn, known.copy(devices = allDevices - Device.RECIRCULATION), setup))

        val defrost = reason(Dial.DEFROST)
        assertTrue(Planner.fits(defrost, known, setup))
        assertFalse(Planner.fits(defrost, known.copy(defrostFront = true), setup))
        assertFalse(Planner.fits(defrost, known.copy(defrostFront = null), setup))
        assertFalse(Planner.fits(defrost, known.copy(devices = allDevices - Device.DEFROST), setup))

        val stronger = reason(Dial.FAN_UP)
        for (fan in 1..3) assertTrue(Planner.fits(stronger, known.copy(fanSpeed = fan), setup))
        for (fan in listOf(0, 4, 8)) assertFalse(Planner.fits(stronger, known.copy(fanSpeed = fan), setup))
        assertFalse(Planner.fits(stronger, known.copy(fanSpeed = null), setup))
        // nowhere to go up on a fan whose top is 3
        assertFalse(Planner.fits(stronger, known.copy(fanSpeed = 3, fanSpeedRange = 1..3), setup))
        val weaker = reason(Dial.FAN_DOWN)
        for (fan in 6..8) assertTrue(Planner.fits(weaker, known.copy(fanSpeed = fan), setup))
        for (fan in listOf(1, 5)) assertFalse(Planner.fits(weaker, known.copy(fanSpeed = fan), setup))

        val heatOn = reason(Dial.SEAT_HEAT_ON)
        val heatOff = reason(Dial.SEAT_HEAT_OFF, 0)
        assertTrue(Planner.fits(heatOn, known, setup))
        assertFalse(Planner.fits(heatOn, known.copy(seatHeat = 1), setup))
        assertFalse(Planner.fits(heatOn, known.copy(seatHeat = null), setup))
        for (step in 1..3) assertTrue(Planner.fits(heatOff, known.copy(seatHeat = step), setup))
        assertFalse(Planner.fits(heatOff, known, setup))
        assertFalse(Planner.fits(heatOff, known.copy(seatHeat = null), setup))

        // both back-seat lights must be off before asking; front lights do not matter
        val childLight = reason(Dial.LIGHT_CHILD)
        val off = Seat.entries.associateWith { false }
        assertTrue(Planner.fits(childLight, known, setup))
        assertTrue(Planner.fits(childLight, known.copy(readingLights = off + (Seat.DRIVER to true)), setup))
        assertFalse(Planner.fits(childLight, known.copy(readingLights = off + (Seat.REAR_RIGHT to true)), setup))
        assertFalse(Planner.fits(childLight, known.copy(readingLights = off + (Seat.REAR_LEFT to true)), setup))
        assertFalse(Planner.fits(childLight, known.copy(readingLights = mapOf(Seat.DRIVER to false)), setup))

        val allOff = reason(Dial.LIGHTS_OFF, 0)
        assertTrue(Planner.fits(allOff, known.copy(readingLights = off + (Seat.PASSENGER to true)), setup))
        assertFalse(Planner.fits(allOff, known, setup))
        assertFalse(Planner.fits(allOff, known.copy(readingLights = emptyMap()), setup))
    }

    @Test
    fun theYoungestAreNeverToldAboutRecirculationOrCooling() {
        val recircOn = reason(Dial.RECIRC, 1)
        val recircOff = reason(Dial.RECIRC, 0)
        val cooler = reason(Dial.TEMP_DOWN)
        val warm = known.copy(acOn = true, tempLeft = 24f, recirculation = true)
        for (s in listOf(setup, Setup(ages = listOf(Age.DETECTIVE)))) {
            assertTrue(Planner.fits(recircOn, known, s))
            assertTrue(Planner.fits(recircOff, warm, s))
            assertTrue(Planner.fits(cooler, warm, s))
        }
        // the youngest child on board decides, whoever else is there
        for (s in listOf(little, Setup(ages = listOf(Age.DETECTIVE, Age.LITTLE)))) {
            assertFalse(Planner.fits(recircOn, known, s))
            assertFalse(Planner.fits(recircOff, warm, s))
            assertFalse(Planner.fits(cooler, warm, s))
            // the other situations are fine, and so is a number question on the temperature
            assertTrue(Planner.fits(reason(Dial.AC, 1), known, s))
            assertTrue(Planner.fits(Puzzle.Quiz(QuizKind.MATH, Dial.TEMP_DOWN, 2), warm, s))
        }
        // nor the ones a second thought settles: warming the air, the windscreen, every reading light off
        for (q in listOf(reason(Dial.TEMP_UP), reason(Dial.DEFROST), reason(Dial.LIGHTS_OFF, 0))) {
            assertFalse(q.toString(), Planner.allowed(q, Age.LITTLE))
            assertTrue(q.toString(), Planner.allowed(q, Age.CAPTAIN))
        }
        for (q in listOf(reason(Dial.FAN_DOWN), reason(Dial.SEAT_HEAT_OFF, 0), reason(Dial.LIGHT_CHILD))) {
            assertTrue(q.toString(), Planner.allowed(q, Age.LITTLE))
        }
        val never = listOf(Dial.RECIRC, Dial.TEMP_DOWN, Dial.TEMP_UP, Dial.DEFROST, Dial.LIGHTS_OFF)
        val car = warm.copy(devices = setOf(Device.RECIRCULATION, Device.TEMPERATURE, Device.AC))
        repeat(100) { seed ->
            for (q in Planner.plan(little.copy(minutes = 15), car, Random(seed)).filterIsInstance<Puzzle.Quiz>()) {
                assertFalse(q.toString(), q.kind == QuizKind.REASON && q.dial in never)
            }
        }
    }

    // ------------------------------------------------------------ planning an episode

    @Test
    fun planUsesOnlyDevicesTheCarHasWithDistinctDevices() {
        val cars = listOf(
            parked,
            known,
            known.copy(devices = setOf(Device.FAN_SPEED, Device.READING_LIGHT, Device.AC)),
            known.copy(devices = allDevices - Device.TEMPERATURE),
            known.copy(devices = setOf(Device.TEMPERATURE, Device.SEAT_HEAT), tempLeft = 18f),
        )
        for (car in cars) repeat(50) { seed ->
            val quizzes = Planner.plan(Setup(minutes = 15), car, Random(seed)).filterIsInstance<Puzzle.Quiz>()
            assertTrue(quizzes.size in 1..4)
            assertTrue(quizzes.all { it.dial.device in car.devices })
            // one puzzle per device at most
            assertEquals(quizzes.size, quizzes.map { it.dial.device }.toSet().size)
            assertTrue(quizzes.all { it.kind in it.dial.kinds && it.value in it.dial.values(it.kind) })
        }
        // a car whose every value is known has a puzzle for each of four devices
        repeat(50) { seed -> assertEquals(4, Planner.plan(Setup(minutes = 15), known, Random(seed)).filterIsInstance<Puzzle.Quiz>().size) }
        assertTrue(Planner.plan(Setup(), parked.copy(devices = emptySet()), Random(1)).isEmpty())
        // a device the car has not reported yet tells no situation
        assertTrue(Planner.plan(Setup(), parked.copy(devices = setOf(Device.AC, Device.DEFROST, Device.RECIRCULATION)), Random(1)).isEmpty())
    }

    @Test
    fun theTemperaturePuzzleGoesDownWhenWarmAndUpWhenCool() {
        assertEquals(Dial.TEMP_DOWN, Planner.tempDial(parked.copy(tempLeft = 22f)))
        assertEquals(Dial.TEMP_DOWN, Planner.tempDial(parked.copy(tempLeft = 27f)))
        assertEquals(Dial.TEMP_UP, Planner.tempDial(parked.copy(tempLeft = 21.5f)))
        // not known yet is taken as 22 degrees
        assertEquals(Dial.TEMP_DOWN, Planner.tempDial(parked))
        val onlyTemp = known.copy(devices = setOf(Device.TEMPERATURE))
        repeat(20) { seed ->
            assertEquals(Dial.TEMP_UP, (Planner.plan(Setup(), onlyTemp.copy(tempLeft = 18f), Random(seed)).single() as Puzzle.Quiz).dial)
            assertEquals(Dial.TEMP_DOWN, (Planner.plan(Setup(), onlyTemp.copy(tempLeft = 25f), Random(seed)).single() as Puzzle.Quiz).dial)
            assertEquals(Dial.TEMP_DOWN, (Planner.plan(Setup(), onlyTemp.copy(tempLeft = 25f, acOn = true), Random(seed)).single() as Puzzle.Quiz).dial)
        }
    }

    @Test
    fun situationsAndNumbersAreMixedAndNumberKindsSpread() {
        var situations = 0
        var quizzes = 0
        repeat(200) { seed ->
            val plan = Planner.plan(Setup(minutes = 15), known, Random(seed)).filterIsInstance<Puzzle.Quiz>()
            val told = plan.count { it.kind == QuizKind.REASON }
            // about half: never all of one sort when the devices allow both
            assertTrue(plan.toString(), told in 1..3)
            situations += told
            quizzes += plan.size
            val numbers = plan.filter { it.kind != QuizKind.REASON }.map { it.kind }
            assertEquals(numbers.size, numbers.toSet().size)
            // the air conditioner, recirculation and defroster only ever tell a situation
            for (q in plan) if (q.dial.device in setOf(Device.AC, Device.RECIRCULATION, Device.DEFROST)) assertEquals(QuizKind.REASON, q.kind)
        }
        assertTrue("$situations of $quizzes", situations * 10 in quizzes * 4..quizzes * 6)
        val onlyAc = known.copy(devices = setOf(Device.AC))
        repeat(20) { seed ->
            assertEquals(reason(Dial.AC, 1), Planner.plan(Setup(), onlyAc, Random(seed)).single())
            assertEquals(reason(Dial.AC, 0), Planner.plan(Setup(), onlyAc.copy(acOn = true), Random(seed)).single())
        }
    }

    @Test
    fun theBeltMissionSitsAfterTheSecondPuzzle() {
        repeat(50) { seed ->
            val long = Planner.plan(Setup(minutes = 15), known, Random(seed))
            assertEquals(5, long.size)
            assertEquals(Puzzle.BeltMission, long[2])
            assertEquals(1, long.count { it == Puzzle.BeltMission })
            val mid = Planner.plan(Setup(minutes = 10), known, Random(seed))
            assertEquals(4, mid.size)
            assertEquals(Puzzle.BeltMission, mid[2])
            val short = Planner.plan(Setup(minutes = 5), known, Random(seed))
            assertEquals(3, short.size)
            assertEquals(Puzzle.BeltMission, short[2])
        }
        // the count on the setup screen is the belt mission included
        for (m in listOf(5, 10, 15)) assertEquals(Planner.puzzleCount(m), Planner.plan(Setup(minutes = m), known, Random(1)).size)
        // a car with two devices still gets its break, one with a single device does not
        val two = known.copy(devices = setOf(Device.FAN_SPEED, Device.AC))
        assertEquals(Puzzle.BeltMission, Planner.plan(Setup(minutes = 15), two, Random(1)).last())
        val one = known.copy(devices = setOf(Device.AC))
        assertTrue(Planner.plan(Setup(minutes = 15), one, Random(1)).none { it == Puzzle.BeltMission })
    }

    @Test
    fun aNumberAnswerIsNeverWhatTheCarAlreadyShows() {
        val car = parked.copy(fanSpeed = 3, readingLights = mapOf(Seat.DRIVER to true, Seat.REAR_LEFT to true), seatHeat = 1, tempLeft = 22f)
        assertEquals((1..8) - 3, Planner.answers(Dial.FAN, car))
        assertEquals(listOf(1), Planner.answers(Dial.LIGHTS, car))
        assertEquals(listOf(2, 3), Planner.answers(Dial.SEAT_HEAT, car))
        assertEquals((1..3).toList(), Planner.answers(Dial.TEMP_DOWN, car))
        // a car that can only show its current value has no answer left
        assertEquals(emptyList<Int>(), Planner.answers(Dial.SEAT_HEAT, car.copy(seatHeatMax = 1)))
        assertFalse(Planner.fits(Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 3), car, setup))
        assertTrue(Planner.fits(Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 4), car, setup))
        // a temperature number goes the way the cabin needs
        assertTrue(Planner.fits(Puzzle.Quiz(QuizKind.MATH, Dial.TEMP_DOWN, 2), car, setup))
        assertFalse(Planner.fits(Puzzle.Quiz(QuizKind.MATH, Dial.TEMP_UP, 2), car, setup))
    }

    @Test
    fun theAnswerStaysWithinWhatThisCarCanSet() {
        val small = parked.copy(fanSpeedRange = 1..4, fanSpeed = 4, seatHeatMax = 2, seatHeat = 2)
        assertEquals(listOf(1, 2, 3), Planner.answers(Dial.FAN, small))
        assertEquals(listOf(1), Planner.answers(Dial.SEAT_HEAT, small))
        repeat(50) { seed ->
            for (q in Planner.plan(Setup(minutes = 15), small, Random(seed)).filterIsInstance<Puzzle.Quiz>()) {
                if (q.dial == Dial.FAN) assertTrue(q.value in 1..3)
                if (q.dial == Dial.SEAT_HEAT) assertEquals(1, q.value)
            }
        }
    }

    /** A car with every value at random, some not known yet. */
    private fun randomCar(r: Random): CarState {
        fun <T> maybe(v: T): T? = if (r.nextInt(6) == 0) null else v
        return parked.copy(
            fanSpeed = maybe(r.nextInt(0, 9)),
            tempLeft = maybe(16f + r.nextInt(0, 29) * 0.5f),
            acOn = maybe(r.nextBoolean()),
            recirculation = maybe(r.nextBoolean()),
            defrostFront = maybe(r.nextBoolean()),
            seatHeat = maybe(r.nextInt(0, 4)),
            readingLights = if (r.nextInt(6) == 0) emptyMap() else Seat.entries.associateWith { r.nextBoolean() },
        )
    }

    @Test
    fun noQuizIsPlannedWhoseAnswerTheCarAlreadyShows() {
        val r = Random(7)
        repeat(2000) { seed ->
            val car = randomCar(r)
            val s = Setup(ages = listOf(Age.entries.random(r)), minutes = 15)
            val quizzes = Planner.plan(s, car, Random(seed)).filterIsInstance<Puzzle.Quiz>()
            for (q in quizzes) {
                assertFalse("$q on $car", q.solvedBy(car, car))
                assertTrue("$q on $car", Planner.fits(q, car, s))
            }
            // in an order where an earlier puzzle never spoils a later one, whenever such an order exists
            if (!holds(quizzes, car, s)) assertFalse("$quizzes on $car", permutations(quizzes).any { holds(it, car, s) })
        }
    }

    /** Each quiz still fits once the ones before it are solved. */
    private fun holds(order: List<Puzzle.Quiz>, car: CarState, s: Setup): Boolean {
        var now = car
        for (q in order) {
            if (!Planner.fits(q, now, s)) return false
            now = Planner.after(q, now)
        }
        return true
    }

    private fun <T> permutations(list: List<T>): List<List<T>> =
        if (list.size <= 1) listOf(list)
        else list.indices.flatMap { i -> permutations(list.take(i) + list.drop(i + 1)).map { listOf(list[i]) + it } }

    @Test
    fun anEarlierPuzzleNeverSpoilsALaterOne() {
        // too hot a story after warming the cabin would still fit; warming after switching the air conditioner on would not
        val car = known.copy(devices = setOf(Device.AC, Device.TEMPERATURE))
        val warmer = reason(Dial.TEMP_UP)
        val cooler = reason(Dial.AC, 1)
        assertEquals(listOf(warmer, cooler), Planner.order(listOf(cooler, warmer), car, setup))
        assertEquals(listOf(warmer, cooler), Planner.order(listOf(warmer, cooler), car, setup))
        repeat(50) { seed ->
            val plan = Planner.plan(Setup(minutes = 5), car, Random(seed)).filterIsInstance<Puzzle.Quiz>()
            assertTrue(plan.toString(), holds(plan, car, setup))
        }
    }

    // ------------------------------------------------------------ a puzzle the car no longer allows as it starts

    @Test
    fun aSituationTurnsTheOtherWayWhenTheCarHasChanged() {
        val off = Seat.entries.associateWith { false }
        val r = Random(1)
        val none = emptySet<Device>()
        // the child's light was switched on during an earlier puzzle: every light off instead
        val lit = known.copy(readingLights = off + (Seat.REAR_RIGHT to true))
        assertEquals(reason(Dial.LIGHTS_OFF, 0), Planner.replacement(reason(Dial.LIGHT_CHILD), lit, setup, none, none, r))
        assertEquals(reason(Dial.LIGHT_CHILD), Planner.replacement(reason(Dial.LIGHTS_OFF, 0), known, setup, none, none, r))
        assertEquals(reason(Dial.AC, 0), Planner.replacement(reason(Dial.AC, 1), known.copy(acOn = true), setup, none, none, r))
        assertEquals(reason(Dial.AC, 1), Planner.replacement(reason(Dial.AC, 0), known, setup, none, none, r))
        assertEquals(reason(Dial.SEAT_HEAT_OFF, 0), Planner.replacement(reason(Dial.SEAT_HEAT_ON), known.copy(seatHeat = 2), setup, none, none, r))
        assertEquals(reason(Dial.SEAT_HEAT_ON), Planner.replacement(reason(Dial.SEAT_HEAT_OFF, 0), known, setup, none, none, r))
        assertEquals(reason(Dial.FAN_UP), Planner.replacement(reason(Dial.FAN_DOWN), known.copy(fanSpeed = 2), setup, none, none, r))
        assertEquals(reason(Dial.FAN_DOWN), Planner.replacement(reason(Dial.FAN_UP), known.copy(fanSpeed = 7), setup, none, none, r))
        assertEquals(reason(Dial.RECIRC, 0), Planner.replacement(reason(Dial.RECIRC, 1), known.copy(recirculation = true), setup, none, none, r))
        assertEquals(reason(Dial.RECIRC, 1), Planner.replacement(reason(Dial.RECIRC, 0), known, setup, none, none, r))
        // a temperature number whose way no longer suits the cabin turns round with the same question kind and amount
        val cool = Puzzle.Quiz(QuizKind.ENGLISH, Dial.TEMP_DOWN, 2)
        assertEquals(cool.copy(dial = Dial.TEMP_UP), Planner.replacement(cool, known, setup, none, none, r))
        // a number the car already shows becomes another number of the same kind on the same device
        repeat(20) { seed ->
            val fan = Puzzle.Quiz(QuizKind.PATTERN, Dial.FAN, 3)
            val swap = Planner.replacement(fan, known, setup, none, none, Random(seed))!!
            assertEquals(QuizKind.PATTERN, swap.kind)
            assertEquals(Dial.FAN, swap.dial)
            assertNotEquals(3, swap.value)
        }
    }

    @Test
    fun withNoOtherWayAnotherDeviceTakesThePuzzle() {
        // the air conditioner was switched on in a warm cabin: neither way of it fits any more
        val warm = known.copy(acOn = true, tempLeft = 24f, defrostFront = null)
        val later = setOf(Device.FAN_SPEED, Device.READING_LIGHT)
        val earlier = setOf(Device.SEAT_HEAT)
        repeat(50) { seed ->
            val swap = Planner.replacement(reason(Dial.AC, 1), warm, setup, later, earlier, Random(seed))
            assertNotNull(swap)
            // never the device of a puzzle still to come, nor one already played while another is free
            assertTrue(swap.toString(), swap!!.dial.device in setOf(Device.TEMPERATURE, Device.RECIRCULATION))
            assertTrue(Planner.fits(swap, warm, setup))
            assertFalse(swap.solvedBy(warm, warm))
            // a situation for a situation where one fits
            assertEquals(QuizKind.REASON, swap.kind)
        }
        // only a device already played is left: it is taken again rather than losing the puzzle
        val onlyPlayed = warm.copy(devices = setOf(Device.AC, Device.SEAT_HEAT))
        assertEquals(Device.SEAT_HEAT, Planner.replacement(reason(Dial.AC, 1), onlyPlayed, setup, emptySet(), earlier, Random(1))?.dial?.device)
        // nothing at all fits
        assertNull(Planner.replacement(reason(Dial.AC, 1), warm.copy(devices = setOf(Device.AC)), setup, emptySet(), emptySet(), Random(1)))
    }

    @Test
    fun aPuzzleIsSwappedOrDroppedAsItStarts() {
        val childLight = reason(Dial.LIGHT_CHILD)
        val ac = reason(Dial.AC, 1)
        val episode = listOf(Puzzle.Quiz(QuizKind.MATH, Dial.FAN, 5), ac, Puzzle.BeltMission, childLight)
        // as planned: nothing changes
        assertEquals(episode, Planner.fitted(episode, 1, known, setup, Random(1)))
        assertEquals(episode, Planner.fitted(episode, 2, known, setup, Random(1)))
        // the parent switched the child's light on during the first puzzle
        val lit = known.copy(readingLights = known.readingLights + (Seat.REAR_RIGHT to true))
        assertEquals(episode.dropLast(1) + reason(Dial.LIGHTS_OFF, 0), Planner.fitted(episode, 3, lit, setup, Random(1)))
        // the air conditioner is on in a warm cabin and the only other device is a later puzzle's: the puzzle is left out
        val bare = known.copy(acOn = true, tempLeft = 24f, devices = setOf(Device.AC, Device.READING_LIGHT))
        assertEquals(listOf(episode[0], Puzzle.BeltMission, childLight), Planner.fitted(episode, 1, bare, setup, Random(1)))
        // with a device played before, that one asks again rather than losing the puzzle
        val withFan = bare.copy(devices = bare.devices + Device.FAN_SPEED)
        assertEquals(reason(Dial.FAN_UP), Planner.fitted(episode, 1, withFan, setup, Random(1))[1])
        // whatever the car did, the puzzle that starts never shows its answer already
        val r = Random(3)
        repeat(1000) { seed ->
            val car = randomCar(r)
            val s = Setup(ages = listOf(Age.entries.random(r)), minutes = 15)
            val planned = Planner.plan(s, randomCar(r), Random(seed))
            for (i in planned.indices) {
                val now = Planner.fitted(planned, i, car, s, Random(seed))
                val q = now.getOrNull(i) as? Puzzle.Quiz ?: continue
                if (now.size < planned.size) continue
                assertTrue("$q on $car", Planner.fits(q, car, s))
                assertFalse("$q on $car", q.solvedBy(car, car))
            }
        }
    }

    @Test
    fun snapshotListsWhatChanged() {
        val before = parked.copy(
            recirculation = false, fanSpeed = 3, readingLights = mapOf(Seat.DRIVER to false), acOn = false, seatHeat = 0,
        )
        val snap = Snapshot.of(before)
        assertEquals(emptyList<String>(), snap.changes(before))
        val after = before.copy(recirculation = true, fanSpeed = 1, readingLights = mapOf(Seat.DRIVER to true), acOn = true, seatHeat = 2)
        assertEquals(listOf("외기 유입", "풍량 3단", "운전석 독서등 끄기", "에어컨 끄기", "운전석 열선 끄기"), snap.changes(after))
        assertEquals(listOf("에어컨 켜기", "운전석 열선 2단"), Snapshot.of(before.copy(acOn = true, seatHeat = 2)).changes(before))
        // what was not known before is left alone
        assertEquals(emptyList<String>(), Snapshot.of(parked).changes(after))
    }

    private fun math(dial: Dial, v: Int) = Puzzle.Quiz(QuizKind.MATH, dial, v)

    /** A parked car with two reading lights, the air conditioner on, 20 degrees and the fan at 2. */
    private val shelfCar = known.copy(
        acOn = true, tempLeft = 20f, fanSpeed = 2,
        readingLights = mapOf(Seat.DRIVER to false, Seat.REAR_RIGHT to false),
    )
    private val ten = Setup(minutes = 10)
    private val belt = listOf<Puzzle>(Puzzle.BeltMission)

    @Test
    fun playableHidesWhatTheCarShowsNow() {
        val keys = Planner.playable(setup, known.copy(fanSpeed = 3, tempLeft = 23f))
        assertTrue(math(Dial.FAN, 3) in keys)
        assertTrue(math(Dial.TEMP_UP, 1) in keys)
        assertTrue(math(Dial.TEMP_DOWN, 1) in keys)
        assertTrue(keys.none { it.kind == QuizKind.REASON && it.dial == Dial.TEMP_UP })
    }

    @Test
    fun playableHoldsOnlySituationsThatFitNow() {
        val keys = Planner.playable(setup, shelfCar)
        assertTrue(reason(Dial.AC, 0) in keys)
        assertFalse(reason(Dial.AC, 1) in keys)
        val noAc = Planner.playable(setup, shelfCar.copy(devices = allDevices - Device.AC))
        assertTrue(noAc.none { it.dial == Dial.AC })
        assertTrue(noAc.isNotEmpty())
    }

    @Test
    fun playableLeavesOutValuesTheCarCannotSet() {
        val lights = Planner.playable(setup, shelfCar)
        assertTrue(math(Dial.LIGHTS, 1) in lights && math(Dial.LIGHTS, 2) in lights)
        assertTrue(lights.none { it.dial == Dial.LIGHTS && it.value > 2 })
        val capped = Planner.playable(setup, shelfCar.copy(fanSpeedRange = 1..6, seatHeatMax = 2))
        assertTrue(capped.none { it.dial == Dial.FAN && it.value > 6 })
        assertTrue(capped.none { it.dial == Dial.SEAT_HEAT && it.value > 2 })
        assertTrue(math(Dial.FAN, 5) in capped)
        // what the car shows now stays listed, so the list does not tell it
        val showing = Planner.playable(setup, shelfCar.copy(fanSpeedRange = 1..6, fanSpeed = 8))
        assertTrue(math(Dial.FAN, 8) in showing && math(Dial.FAN, 7) !in showing)
    }

    @Test
    fun playableLeavesOutWhatTheYoungestAreNeverToldAndWhatHasNoQuestion() {
        assertTrue(Planner.playable(little, shelfCar).none { it.kind == QuizKind.REASON && it.dial in listOf(Dial.RECIRC, Dial.TEMP_DOWN) })
        val none = Planner.playable(setup, shelfCar, Questions { q, _ -> q.dial != Dial.FAN })
        assertTrue(none.none { it.dial == Dial.FAN })
        assertTrue(Planner.playable(setup, shelfCar).none { it == Puzzle.BeltMission })
    }

    @Test
    fun aShelvedStoryPlaysTheFirstFittingAnswerOfEachSlot() {
        val slots = listOf(listOf(reason(Dial.AC, 1), reason(Dial.AC, 0)), listOf(math(Dial.FAN, 5)), belt, listOf(math(Dial.LIGHTS, 2)))
        val got = Planner.accepts(slots, ten, shelfCar)
        assertEquals(listOf(reason(Dial.AC, 0), math(Dial.FAN, 5), Puzzle.BeltMission, math(Dial.LIGHTS, 2)), got)
    }

    @Test
    fun aShelvedStoryNeedingAMissingDeviceIsRefused() {
        val slots = listOf(listOf(reason(Dial.AC, 0)), listOf(math(Dial.FAN, 5)), belt, listOf(math(Dial.LIGHTS, 2)))
        assertNotNull(Planner.accepts(slots, ten, shelfCar))
        assertNull(Planner.accepts(slots, ten, shelfCar.copy(devices = allDevices - Device.AC)))
    }

    @Test
    fun aShelvedNumberTheCarAlreadyShowsIsRefused() {
        assertNull(Planner.accepts(listOf(listOf(math(Dial.FAN, 5))), Setup(minutes = 5), shelfCar.copy(fanSpeed = 5, devices = setOf(Device.FAN_SPEED))))
    }

    @Test
    fun aShelvedOrderThatNoLongerWorksIsRefused() {
        val car = shelfCar.copy(acOn = false)
        val five = Setup(minutes = 5)
        assertNull(Planner.accepts(listOf(listOf(reason(Dial.AC, 1)), listOf(reason(Dial.TEMP_UP, 1)), belt), five, car))
        assertNotNull(Planner.accepts(listOf(listOf(reason(Dial.TEMP_UP, 1)), listOf(reason(Dial.AC, 1)), belt), five, car))
    }

    @Test
    fun aShelvedStoryWithTheBeltElsewhereIsRefused() {
        val ok = listOf(listOf(reason(Dial.AC, 0)), listOf(math(Dial.FAN, 5)), belt, listOf(math(Dial.LIGHTS, 2)))
        assertNotNull(Planner.accepts(ok, ten, shelfCar))
        val first = listOf(belt, ok[0], ok[1], ok[3])
        val last = listOf(ok[0], ok[1], ok[3], belt)
        val none = listOf(ok[0], ok[1], ok[3])
        assertNull(Planner.accepts(first, ten, shelfCar))
        assertNull(Planner.accepts(last, ten, shelfCar))
        assertNull(Planner.accepts(none, ten, shelfCar))
    }

    @Test
    fun aShelvedStoryOfAnotherLengthIsRefused() {
        val two = listOf(listOf(reason(Dial.AC, 0)), listOf(math(Dial.FAN, 5)), belt)
        assertNull(Planner.accepts(two, ten, shelfCar))
        // a car with only two devices that can ask plays two quizzes whatever the time
        val small = shelfCar.copy(devices = setOf(Device.FAN_SPEED, Device.READING_LIGHT))
        assertNotNull(Planner.accepts(listOf(listOf(math(Dial.FAN, 5)), listOf(math(Dial.LIGHTS, 2)), belt), ten, small))
    }

    @Test
    fun twoPuzzlesOnOneDeviceAreRefused() {
        val slots = listOf(listOf(math(Dial.FAN, 5)), listOf(math(Dial.FAN, 6)), belt, listOf(math(Dial.LIGHTS, 2)))
        assertNull(Planner.accepts(slots, ten, shelfCar))
    }

    @Test
    fun acceptsChangesNothing() {
        val slots = listOf(listOf(reason(Dial.AC, 1), reason(Dial.AC, 0)), listOf(math(Dial.FAN, 5)), belt, listOf(math(Dial.LIGHTS, 2)))
        val copy = slots.map { it.toList() }
        val car = shelfCar.copy()
        assertEquals(Planner.accepts(slots, ten, car), Planner.accepts(slots, ten, car))
        assertEquals(copy, slots)
        assertEquals(shelfCar, car)
    }
}
