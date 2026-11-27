package kr.escape.car.story

/**
 * The family's wish as the setup screen writes it ("공룡 섬에서 펼쳐지는 보물찾기 이야기"), and whether its place can be
 * read at all. A place that is no word is refused in the app and never sent to the story server.
 */
object Wish {
    val FORM = Regex("^(.+)에서 펼쳐지는 (.+) 이야기$")

    /** Why the app refused it, as the parent's notice shows it. */
    const val UNREADABLE = "알아볼 수 없는 말"

    /** The place the family typed, or the whole wish when it is not in the setup screen's form. */
    fun place(wish: String): String = FORM.matchEntire(wish.trim())?.groupValues?.get(1) ?: wish

    /**
     * Whether [text] reads as a word:
     * - any whole Korean syllable (가..힣) is enough ("용", "공룡 섬2");
     * - with neither Korean nor English letters it is not (ㅎㅇ, 123, !!);
     * - English letters only: not without an English vowel (a e i o u y: "ghd", "rkt"), nor when the letters typed on
     *   the 두벌식 keyboard make two or more Korean syllables, Korean typed with the keyboard left in English
     *   ("dkssud" is 안녕). Any other English word is ("Paris", "dog", "Tokyo").
     */
    fun readable(text: String): Boolean {
        if (text.any { it in '가'..'힣' }) return true
        if (text.none(::isLatin)) return false
        if (text.none { it.lowercaseChar() in "aeiouy" }) return false
        return dubeolsikSyllables(text) < 2
    }

    /**
     * How many whole Korean syllables [text] makes when typed on the 두벌식 keyboard. Each syllable begins where a
     * consonant is followed by a vowel (a double vowel or a double final consonant adds none), so they are counted
     * without putting the syllables together. Anything but a letter key breaks the run.
     */
    fun dubeolsikSyllables(text: String): Int {
        val jamo = text.map { DUBEOLSIK[it] ?: DUBEOLSIK[it.lowercaseChar()] }
        return jamo.zipWithNext().count { (a, b) -> a != null && b != null && a in CONSONANTS && b !in CONSONANTS }
    }

    private fun isLatin(c: Char) = c in 'a'..'z' || c in 'A'..'Z'

    /** The 두벌식 keys: with Shift only these five consonants and two vowels differ (other capitals are as lower case). */
    private val DUBEOLSIK: Map<Char, Char> = mapOf(
        'q' to 'ㅂ', 'w' to 'ㅈ', 'e' to 'ㄷ', 'r' to 'ㄱ', 't' to 'ㅅ', 'y' to 'ㅛ', 'u' to 'ㅕ', 'i' to 'ㅑ', 'o' to 'ㅐ',
        'p' to 'ㅔ', 'a' to 'ㅁ', 's' to 'ㄴ', 'd' to 'ㅇ', 'f' to 'ㄹ', 'g' to 'ㅎ', 'h' to 'ㅗ', 'j' to 'ㅓ', 'k' to 'ㅏ',
        'l' to 'ㅣ', 'z' to 'ㅋ', 'x' to 'ㅌ', 'c' to 'ㅊ', 'v' to 'ㅍ', 'b' to 'ㅠ', 'n' to 'ㅜ', 'm' to 'ㅡ',
        'Q' to 'ㅃ', 'W' to 'ㅉ', 'E' to 'ㄸ', 'R' to 'ㄲ', 'T' to 'ㅆ', 'O' to 'ㅒ', 'P' to 'ㅖ',
    )

    private const val CONSONANTS = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"
}
