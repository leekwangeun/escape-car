package kr.escape.car.story

import kr.escape.car.engine.Dial
import kr.escape.car.engine.Friend
import kr.escape.car.engine.Puzzle
import kr.escape.car.engine.QuizKind
import kr.escape.car.engine.WrongJudge
import kr.escape.car.voice.Subtitles
import kotlin.random.Random

/**
 * What one puzzle looks and sounds like: Lumi's line, the hint, the stage picture (assets/img), the parent's buttons
 * and the answer, and for a quiz the question the child's screen shows. [result] is the story's line for when the
 * puzzle is solved; the app's own lines have none and say [Lines.SUCCESS].
 */
data class Scene(
    val bubble: String,
    val speech: String,
    val hint: String,
    val pictures: List<String>,
    val buttons: List<String>,
    val answer: String,
    val question: Question? = null,
    val result: String = "",
)

/**
 * The reviewed lines the app ships with. The AI story server writes a new question around the same answer; when it
 * fails or a check rejects its question, the app asks its own simple one made here.
 */
object Lines {
    // said in any story when its own result line is missing, so it names no place of the default star train story
    const val SUCCESS = "해냈다! 목표에 한 걸음 더 가까워졌어. 우리 탐험대장 최고야!"
    const val TREASURE_BADGE = "기차가 다 고쳐졌어! 고마워, 탐험대장. 반짝이는 탐험 배지를 줄게!"
    const val PAUSED = "잠깐 쉬어 갈게. 다시 준비되면 이어서 하자!"
    /** 이어 하기: before the puzzle the family comes back to, after the car was switched off. */
    const val WELCOME_BACK = "다시 만났네! 하던 탐험을 이어서 해 보자."

    /** While the next puzzle's picture is still being drawn. */
    const val DRAWING = "잠깐만! 다음 장면을 그리고 있어."

    /**
     * The story server refused the family's wish (any rule, any word): said once as the setup screen comes back, next to
     * the parent's notice that tells why.
     */
    const val WISH_REJECTED = "다른 소원을 빌어 주세요."

    /**
     * At the start of play: the climate's AUTO mode changes the fan, the air conditioner and the recirculation by
     * itself, which the app cannot read. Lumi says it in Korean letters so the speech engine does not spell it out.
     */
    const val AUTO_OFF = "안전 지킴이, 공조 오토를 꺼 주세요."
    const val AUTO_OFF_PARENT = "공조 AUTO를 꺼 주세요.\nAUTO가 바람, 에어컨, 내기 순환을 저절로 바꿔요"

    fun intro(f: Friend) = "안녕, 탐험대장! 나는 ${isName(f.label)}. 오늘 밤 별 축제에 가야 하는데 별빛 기차 칸 곳곳이 고장 났대. " +
        "나랑 같이 문제를 풀어서 기차를 고쳐 보자! 답을 찾으면, 그 답으로 버튼을 어떻게 바꿀지 안전 지킴이에게 알려 줘."

    /**
     * A wrong answer (engine WrongJudge), gentle and in words that fit every quiz and every story: they never say the
     * answer, only whether the button, the amount or the way is off. [n] takes turns through each kind's lines.
     */
    fun wrong(kind: WrongJudge.Kind, n: Int): String {
        val lines = when (kind) {
            WrongJudge.Kind.BUTTON -> WRONG_BUTTON
            WrongJudge.Kind.AMOUNT -> WRONG_AMOUNT
            WrongJudge.Kind.WAY -> WRONG_WAY
        }
        return lines[Math.floorMod(n, lines.size)]
    }

    private val WRONG_BUTTON = listOf(
        "음, 그 버튼은 아닌 것 같아. 다시 생각해 볼까?",
        "앗, 다른 버튼이었나 봐. 문제를 다시 떠올려 보자!",
        "아깝다! 천천히 다시 찾아볼까?",
    )
    private val WRONG_AMOUNT = listOf(
        "버튼은 맞았어! 그런데 답이 조금 다른 것 같아. 다시 풀어 볼까?",
        "아깝다, 거의 다 왔어! 답을 한 번 더 확인해 볼까?",
    )
    private val WRONG_WAY = listOf(
        "버튼은 맞았는데, 거꾸로 된 것 같아. 다시 생각해 볼까?",
        "앗, 반대쪽인가 봐. 이야기를 다시 떠올려 볼까?",
    )

    /** After the treasure, in the friend's own words. */
    const val CLOSER = "우리 한층 더 친해졌어!"

    fun invited(rare: Friend) = "새로운 친구 ${subject(rare.label)} 탐험단에 들어왔어!"

    /** [word] with 과 or 와, whichever its last syllable takes ("코냥과", "반테와"). */
    fun withAnd(word: String): String = word + if (closed(word)) "과" else "와"

    /** [word] with 이 or 가 ("코냥이", "반테가"). */
    fun subject(word: String): String = word + if (closed(word)) "이" else "가"

    /** [word] with 을 or 를 ("투싼을", "아반떼를"). */
    fun objectOf(word: String): String = word + if (closed(word)) "을" else "를"

    /** [word] with 의 ("반테의"). */
    fun of(word: String): String = word + "의"

    /** "반테야", "코냥이야". */
    fun isName(word: String): String = word + if (closed(word)) "이야" else "야"

    /** "토끼면", "곰이면": 이면 after a closed syllable. */
    fun ifIs(word: String): String = word.trimEnd() + if (closed(word)) "이면" else "면"

    private fun closed(word: String): Boolean {
        val last = word.trimEnd().lastOrNull() ?: return false
        return last in '가'..'힣' && (last - '가') % 28 != 0
    }

    /**
     * [own] is a question the app drew from its copy of the question bank, with its hint; without one, a built-in one.
     * The friend the child picked says every line as "나".
     */
    fun scene(p: Puzzle, own: Pair<Question, String>? = null): Scene = when (p) {
        is Puzzle.Quiz -> (own?.first ?: question(p)).let { q -> quizScene(p, bubble(p), lead(p), own?.second ?: hint(p, q), q) }
        Puzzle.BeltMission -> Scene(
            bubble = "탐험대장, 벨트를 딸깍!",
            speech = "다음 곳으로 가기 전에 준비! 탐험대장, 벨트를 딸깍! 이미 매고 있다면 버클을 짚고 딸깍 확인!",
            hint = "벨트를 버클에 끝까지 넣으면 딸깍 소리가 나!",
            pictures = listOf("scene_belt"),
            buttons = emptyList(),
            answer = "아이가 스스로 매면 확인 버튼",
        )
    }

    /**
     * One quiz scene from its words ([lead] is the story before the question) and its checked question. A letter
     * count's word is heard, never seen, so a bubble or a hint (shown as subtitles) that writes it is the app's own.
     */
    fun quizScene(p: Puzzle.Quiz, bubble: String, lead: String, hint: String, q: Question): Scene {
        val word = q.word.trim()
        val writes = { s: String ->
            countsLetters(q) && word.isNotEmpty() &&
                Regex("(?<![A-Za-z])${Regex.escape(word)}(?![A-Za-z])", RegexOption.IGNORE_CASE).containsMatchIn(s)
        }
        return Scene(
            bubble = if (writes(bubble)) Lines.bubble(p) else bubble,
            speech = spoken(lead, q, p),
            hint = if (writes(hint)) Lines.hint(p, q) else hint,
            pictures = listOf(picture(p)),
            buttons = listOf(button(p.dial)),
            answer = answer(p, q),
            question = q,
        )
    }

    /** Lumi's words in order: the story, the question, then what to do with the answer (always the app's own words). */
    fun spoken(lead: String, q: Question, p: Puzzle.Quiz): String =
        listOf(lead.trim(), sayable(q.text.trim(), q.word), ask(p, q)).filter { it.isNotEmpty() }.joinToString(" ")

    /** The speech engine may read a word in capitals letter by letter, so Lumi says the English word in small letters. */
    private fun sayable(text: String, word: String) =
        if (word.isBlank()) text else text.replace(word, word.lowercase(), ignoreCase = true)

    /**
     * What to do with the answer. The app says this itself, so a written question can never send the child wrong. A
     * situation already ends by asking for help with a car button ("알맞은 차량 버튼을 찾아 나를 도와줘!"), so nothing
     * follows it.
     */
    fun ask(p: Puzzle.Quiz, q: Question): String {
        if (p.kind == QuizKind.REASON) return ""
        // the question ends in the instruction ("one은 숫자로 몇일까? 그 수만큼 온도를 내려 줘!"): the child works the
        // amount out and says it
        return when (p.dial) {
            Dial.LIGHTS -> "손이 닿는 불은 직접 켜고, 나머지는 몇 개 켤지 안전 지킴이에게 부탁해!"
            else -> "어떻게 하면 되는지 안전 지킴이에게 말해 줘!"
        }
    }

    /** The parent's 정답 보기: what to set, and how the question works out to it. */
    fun answer(p: Puzzle.Quiz, q: Question): String {
        val reason = p.kind == QuizKind.REASON
        val how = when (p.dial) {
            Dial.FAN -> "풍량 ${p.value}단"
            Dial.TEMP_UP -> if (reason) "운전석 온도를 퍼즐 시작 때보다 올리기" else "운전석 온도를 퍼즐 시작 때보다 ${p.value}도 올리기"
            Dial.TEMP_DOWN -> if (reason) "운전석 온도를 퍼즐 시작 때보다 내리기" else "운전석 온도를 퍼즐 시작 때보다 ${p.value}도 내리기"
            Dial.LIGHTS -> "독서등 모두 ${p.value}개 켜기"
            Dial.SEAT_HEAT -> "운전석 열선 ${p.value}단"
            Dial.AC -> if (p.value == 1) "에어컨 켜기" else "에어컨 끄기"
            Dial.RECIRC -> if (p.value == 1) "내기 순환 켜기" else "내기 순환 끄기 (외기 유입)"
            Dial.DEFROST -> "앞 유리 성에 제거 켜기"
            Dial.FAN_UP -> "풍량을 퍼즐 시작 때보다 세게"
            Dial.FAN_DOWN -> "풍량을 퍼즐 시작 때보다 약하게 (끄지 않기)"
            Dial.SEAT_HEAT_ON -> "운전석 열선 켜기 (몇 단이든)"
            Dial.SEAT_HEAT_OFF -> "운전석 열선 끄기"
            Dial.LIGHT_CHILD -> "뒷자리 독서등 켜기"
            Dial.LIGHTS_OFF -> "독서등 모두 끄기"
        }
        val why = when (p.kind) {
            QuizKind.MATH -> q.expr.takeIf { it.isNotBlank() }?.let { "${pretty(it)} = ${p.value}" }
            // the parent may see the word; only the child's screen never does
            QuizKind.ENGLISH -> englishSum(q.expr)?.let { "$it = ${p.value}" }
                ?: q.word.takeIf { it.isNotBlank() }?.trim()?.uppercase()?.let {
                    englishSum(it)?.let { sum -> "$sum = ${p.value}" }
                        ?: if (it in NUMBER_WORDS) "$it = ${p.value}" else "$it, ${it.length}글자"
                }
            QuizKind.PATTERN -> q.terms.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")} 다음은 ${p.value}" }
            // nothing to work out: the child finds the function from the trouble
            QuizKind.REASON -> null
        }
        return if (why == null) how else "$how ($why)"
    }

    /**
     * The big line on the child's screen ("3 + 2 = ?", "TWO = ?"), or null when the question has none (a situation is
     * heard). A letter count shows only the word's Korean meaning ("사과 → 영어 몇 글자?"): the child spells it in
     * their head.
     */
    fun card(q: Question): String? = when (q.kind) {
        QuizKind.MATH -> q.expr.takeIf { it.isNotBlank() }?.let { "${pretty(it)} = ?" }
        QuizKind.ENGLISH -> when {
            // a sum of number words ("EIGHT-FIVE") is written out for the child to work on
            englishSum(q.expr) != null -> "${englishSum(q.expr)} = ?"
            q.word.isBlank() -> null
            countsLetters(q) -> Subtitles.spelled(q.text).firstOrNull()?.let { (_, meaning) -> "$meaning → 영어 몇 글자?" } ?: "영어로 몇 글자?"
            else -> q.word.trim().uppercase().let { englishSum(it) ?: it }.let { "$it = ?" }
        }
        QuizKind.PATTERN -> q.terms.takeIf { it.isNotEmpty() }?.let { "${it.joinToString(", ")}, ?" }
        QuizKind.REASON -> null
    }

    /** An English question that counts its word's letters (any word but a number word or a sum of them). */
    fun countsLetters(q: Question): Boolean {
        if (q.kind != QuizKind.ENGLISH) return false
        val w = q.word.trim().uppercase()
        return w.isNotEmpty() && w !in NUMBER_WORDS && englishSum(w) == null && englishSum(q.expr) == null
    }

    /**
     * A sum of number words ("TWO*FOUR-FIVE", "seven 빼기 five") as the card writes it ("TWO × FOUR - FIVE"); null
     * otherwise.
     */
    private fun englishSum(word: String): String? {
        val w = word.uppercase().replace("빼기", "-").replace("더하기", "+").replace("곱하기", "*").replace("나누기", "/")
        val parts = w.split('+', '-', '*', '/').map { it.trim().removePrefix("(").removeSuffix(")").trim() }
        if (parts.size < 2 || parts.any { it !in NUMBER_WORDS }) return null
        return pretty(w)
    }

    /** "(4*3)-7" as a child reads it: "(4 × 3) - 7". */
    fun pretty(expr: String): String = expr
        .replace("*", " × ").replace("/", " ÷ ").replace("+", " + ").replace("-", " - ")
        .replace(Regex("\\s+"), " ").replace("( ", "(").replace(" )", ")").trim()

    /** The same scene with its friend helped, after the puzzle is solved. */
    fun successPicture(p: Puzzle?): String = when (p) {
        is Puzzle.Quiz -> when (picture(p)) {
            "scene_fog" -> "scene_fog_ok"
            "scene_wind_face" -> "scene_wind_ok"
            "scene_rooms_left" -> "scene_rooms_ok"
            "scene_together" -> "scene_together_ok"
            "scene_ac_on", "scene_ac_off" -> "scene_ac_ok"
            "scene_recirc_on", "scene_recirc_off" -> "scene_recirc_ok"
            // every reading light off: the friend resting is the trouble, so the solved picture is the cosy room
            "scene_dust_ok" -> "scene_rooms_ok"
            else -> "scene_dust_ok"
        }
        Puzzle.BeltMission -> "scene_belt_ok"
        null -> "ending_bante"
    }

    // ------------------------------------------------------------ the app's own questions

    val NUMBER_WORDS = listOf(
        "ONE", "TWO", "THREE", "FOUR", "FIVE", "SIX", "SEVEN", "EIGHT", "NINE", "TEN",
        "ELEVEN", "TWELVE", "THIRTEEN", "FOURTEEN", "FIFTEEN", "SIXTEEN", "SEVENTEEN", "EIGHTEEN", "NINETEEN", "TWENTY",
    )

    /**
     * A simple question whose answer is the puzzle's value, the same every time for the same puzzle (the parent's
     * answer and Lumi's words must agree), for when even the app's copy of the question bank has none.
     */
    fun question(p: Puzzle.Quiz): Question {
        val rnd = Random(StoryKeys.key(p).hashCode())
        val v = p.value
        return when (p.kind) {
            // a sum is asked first and the instruction then says "그 수", so no number in it sounds like the answer
            QuizKind.MATH -> if (v >= 2) {
                val a = rnd.nextInt(1, v)
                val b = v - a
                Question(QuizKind.MATH, "$a 더하기 $b${sinoTopic(b)} 얼마일까? ${thatMany(p.dial)}", expr = "$a+$b")
            } else {
                Question(QuizKind.MATH, "3 빼기 2${sinoTopic(2)} 얼마일까? ${thatMany(p.dial)}", expr = "3-2")
            }
            QuizKind.ENGLISH -> NUMBER_WORDS[v - 1].let {
                Question(QuizKind.ENGLISH, "${it.lowercase()}${englishTopic(it)} 숫자로 몇일까? ${thatMany(p.dial)}", word = it)
            }
            QuizKind.PATTERN -> {
                val terms = if (v >= 3) listOf(v - 3, v - 2, v - 1) else listOf(v + 3, v + 2, v + 1)
                Question(QuizKind.PATTERN, instruction(p.dial, "${terms.joinToString(", ")} 다음 수"), terms = terms)
            }
            QuizKind.REASON -> Question(QuizKind.REASON, situation(p))
        }
    }

    /**
     * A situation that only one function solves, in the bank's form: the trouble as "나", then a request for help with
     * a car button. It never names the button itself. Each is the first of its function in the question bank.
     */
    private fun situation(p: Puzzle.Quiz): String = when (p.dial) {
        Dial.AC -> if (p.value == 1) "나 땀이 뻘뻘 나, 바람은 나오는데 안 시원해. 창문은 닫아 둔 채로 알맞은 차량 버튼을 찾아 나를 도와줘!"
            else "나 오들오들 추운데 더울 때 켜는 시원한 바람이 켜져 있어. 온도 숫자는 그대로 두고 알맞은 차량 버튼을 찾아 나를 도와줘!"
        Dial.RECIRC -> if (p.value == 1) "바람 구멍 앞에만 가면 이상한 냄새에 콜록콜록 기침이 나와. 냄새는 차 밖에서 오는 것 같으니 바람은 계속 나오게 두고 알맞은 차량 버튼을 찾아 나를 도와줘!"
            else "같은 공기만 빙글빙글 돌아서 나는 숨이 답답해. 창문은 닫아 둔 채로, 알맞은 차량 버튼을 찾아 나를 도와줘!"
        Dial.DEFROST -> "앞 유리 안쪽이 뿌예지려고 해, 와이퍼는 바깥만 닦아. 알맞은 차량 버튼을 찾아 나를 도와줘!"
        Dial.TEMP_UP -> "내 몸이 으슬으슬 떨려. 의자 말고 차 안 공기를 따뜻하게, 알맞은 차량 버튼을 찾아 나를 도와줘!"
        Dial.TEMP_DOWN -> "에어컨은 켜져 있는데 나는 아직 조금 더워. 바람이 세지면 퍼즐 조각이 흩어지니까 알맞은 차량 버튼을 찾아 나를 도와줘!"
        Dial.FAN_UP -> "내 바람개비가 느릿느릿 돌아. 덥지도 춥지도 않은데, 빙글빙글 빨리 돌게 알맞은 차량 버튼을 찾아 나를 도와줘!"
        Dial.FAN_DOWN -> "바람이 쌩쌩 불어서 내 그림 종이가 날아가. 바람은 조금 나와야 하니까 알맞은 차량 버튼을 찾아 나를 도와줘!"
        Dial.SEAT_HEAT_ON -> "안전 지킴이 의자가 차가워졌어. 차 안 공기 말고 의자를 데우게 알맞은 차량 버튼을 찾아 안전 지킴이를 도와줘!"
        Dial.SEAT_HEAT_OFF -> "안전 지킴이 의자가 찐빵처럼 계속 뜨끈해. 의자가 그만 데워지게 알맞은 차량 버튼을 찾아 안전 지킴이를 도와줘!"
        Dial.LIGHT_CHILD -> "그림 지도가 개미만큼 작아서 네 자리에 빛이 더 있어야 잘 보여. 창문은 깨끗해, 알맞은 차량 버튼을 찾아 나를 도와줘!"
        Dial.LIGHTS_OFF -> "나 반짝 별 스티커를 보고 싶어. 하나만 말고 작은 불이 하나도 안 켜져 있게 알맞은 차량 버튼을 찾아 나를 도와줘!"
        Dial.FAN, Dial.LIGHTS, Dial.SEAT_HEAT -> "알맞은 차량 버튼을 찾아 나를 도와줘!"
    }

    /** The car instruction after a question asked first, the amount as "그 수": "그 수만큼 온도를 내려 줘!". */
    fun thatMany(d: Dial): String = when (d) {
        Dial.FAN -> "그 수만큼 바람 세기를 맞춰 줘!"
        Dial.TEMP_UP -> "그 수만큼 온도를 올려 줘!"
        Dial.TEMP_DOWN -> "그 수만큼 온도를 내려 줘!"
        Dial.LIGHTS -> "켜진 독서등이 모두 그 수만큼 되게 해 줘!"
        Dial.SEAT_HEAT -> "의자 열선을 그 수와 같은 단으로 맞춰 줘!"
        // a situation has no amount
        else -> ""
    }

    /** The topic particle after a number read the Sino-Korean way (일, 삼, 육, 칠, 팔, 십 are closed): "8은", "2는". */
    fun sinoTopic(n: Int): String = if (n % 10 in setOf(0, 1, 3, 6, 7, 8)) "은" else "는"

    /** The topic particle after a number word as it sounds in Korean: one(원)은, two(투)는, seven(세븐)은. */
    fun englishTopic(word: String): String =
        if (word.uppercase() in setOf("TWO", "THREE", "FOUR", "FIVE", "SIX", "TWELVE", "TWENTY")) "는" else "은"

    /** The car instruction with the amount as a little puzzle: "3, 4, 5 다음 수만큼 온도를 내려 줘!". */
    fun instruction(d: Dial, amount: String): String = when (d) {
        Dial.FAN -> "바람 세기를 ${amount}단으로 맞춰 줘!"
        Dial.TEMP_UP -> "${amount}만큼 온도를 올려 줘!"
        Dial.TEMP_DOWN -> "${amount}만큼 온도를 내려 줘!"
        Dial.LIGHTS -> "켜진 독서등이 모두 ${amount}개가 되게 해 줘!"
        Dial.SEAT_HEAT -> "의자 열선을 ${amount}단으로 맞춰 줘!"
        // a situation has no amount
        else -> amount
    }

    private fun lead(p: Puzzle.Quiz): String {
        // the situation itself tells the trouble; the story before it never gives the function away
        // also played inside an AI story whose scene was left out, so it names no place: "여기" is wherever the story is
        if (p.kind == QuizKind.REASON) return "어, 곤란한 일이 생겼어. 잘 듣고 어떤 차 버튼이 나를 도와줄 수 있을지 생각해 봐!"
        return when (p.dial) {
            Dial.FAN -> "내가 키우는 새싹이 목말라서 축 늘어졌어. 문제를 풀면 바람을 보내 줄 수 있어!"
            Dial.TEMP_DOWN -> "여기가 너무 더워서 내가 땀을 뻘뻘 흘려! 문제를 풀면 시원하게 할 수 있어."
            Dial.TEMP_UP -> "여기가 너무 추워서 내가 덜덜 떨고 있어! 문제를 풀면 조금 따뜻하게 할 수 있어."
            Dial.LIGHTS -> "여기가 깜깜해서 내가 길을 못 찾고 있어! 문제를 풀면 작은 불을 밝힐 수 있어."
            // the seat heat is the driver's seat
            else -> "안전 지킴이 의자가 얼음처럼 차가워! 문제를 풀면 의자를 따끈하게 데울 수 있어."
        }
    }

    private fun bubble(p: Puzzle.Quiz): String = if (p.kind == QuizKind.REASON) {
        when (p.dial) {
            Dial.AC -> if (p.value == 1) "너무 더워!" else "너무 추워!"
            Dial.RECIRC -> if (p.value == 1) "콜록콜록, 에취!" else "숨이 답답해!"
            Dial.DEFROST -> "앞이 뿌예져!"
            Dial.TEMP_UP -> "너무 추워!"
            Dial.TEMP_DOWN -> "조금 더워!"
            Dial.FAN_UP -> "바람이 약해!"
            Dial.FAN_DOWN -> "바람이 너무 세!"
            Dial.SEAT_HEAT_ON -> "의자가 차가워!"
            Dial.SEAT_HEAT_OFF -> "의자가 뜨끈뜨끈!"
            Dial.LIGHT_CHILD -> "그림이 너무 작아!"
            Dial.LIGHTS_OFF -> "잠이 안 와!"
            Dial.FAN, Dial.LIGHTS, Dial.SEAT_HEAT -> "친구를 도와줘!"
        }
    } else {
        when (p.dial) {
            Dial.FAN -> "새싹이 목말라!"
            Dial.TEMP_DOWN -> "땀이 뻘뻘!"
            Dial.TEMP_UP -> "방이 너무 추워!"
            Dial.LIGHTS -> "깜깜해서 안 보여!"
            else -> "의자가 차가워!"
        }
    }

    private fun hint(p: Puzzle.Quiz, q: Question): String = when (p.kind) {
        QuizKind.MATH -> "손가락을 하나씩 펴면서 세어 봐!"
        QuizKind.ENGLISH -> if (countsLetters(q)) "영어 철자를 머릿속으로 떠올리며 알파벳마다 손가락을 하나씩 펴 봐!"
            else "원, 투, 쓰리, 손가락으로 하나씩 세면서 영어로 말해 봐!"
        QuizKind.PATTERN -> if (q.terms.size >= 2 && q.terms[1] < q.terms[0]) "수가 얼마씩 작아지는지 손가락으로 세어 봐!"
            else "수가 얼마씩 커지는지 손가락으로 세어 봐!"
        QuizKind.REASON -> "어떤 차 버튼이 나를 도울 수 있을지 하나씩 떠올려 봐!"
    }

    /** The stage picture with the scene's friend in it, by the device the puzzle is about. */
    private fun picture(p: Puzzle.Quiz): String = when (p.dial) {
        Dial.FAN, Dial.FAN_UP -> "scene_wind_face"
        // turning something down or off never shows the picture of a friend who needs more of it: too much wind
        // swirling, a friend sweating, a friend who wants to rest
        Dial.FAN_DOWN -> "scene_recirc_off"
        Dial.TEMP_UP, Dial.TEMP_DOWN -> "scene_rooms_left"
        Dial.LIGHTS, Dial.LIGHT_CHILD -> "scene_together"
        Dial.LIGHTS_OFF -> "scene_dust_ok"
        Dial.AC -> if (p.value == 1) "scene_ac_on" else "scene_ac_off"
        Dial.RECIRC -> if (p.value == 1) "scene_recirc_on" else "scene_recirc_off"
        Dial.DEFROST -> "scene_fog"
        Dial.SEAT_HEAT, Dial.SEAT_HEAT_ON -> "scene_dust_in"
        Dial.SEAT_HEAT_OFF -> "scene_ac_on"
    }

    private fun button(d: Dial): String = when (d) {
        Dial.FAN, Dial.FAN_UP, Dial.FAN_DOWN -> "풍량"
        Dial.TEMP_UP, Dial.TEMP_DOWN -> "운전석 온도"
        Dial.LIGHTS, Dial.LIGHT_CHILD, Dial.LIGHTS_OFF -> "독서등"
        Dial.SEAT_HEAT, Dial.SEAT_HEAT_ON, Dial.SEAT_HEAT_OFF -> "시트 열선"
        Dial.AC -> "에어컨"
        Dial.RECIRC -> "내기 순환"
        Dial.DEFROST -> "앞 유리 성에 제거"
    }
}
