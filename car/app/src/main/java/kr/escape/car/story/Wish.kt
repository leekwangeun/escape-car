package kr.escape.car.story

/**
 * The family's wish as the setup screen writes it ("공룡 섬에서 펼쳐지는 보물찾기 이야기"), and whether its place can be
 * read at all. A place with no whole Korean syllable and no English letter (ㅎㅇ, ㅋㅋ, !!, 123) is refused in the app
 * and never sent to the story server.
 */
object Wish {
    val FORM = Regex("^(.+)에서 펼쳐지는 (.+) 이야기$")

    /** Why the app refused it, as the parent's notice shows it. */
    const val UNREADABLE = "알아볼 수 없는 말"

    /** The place the family typed, or the whole wish when it is not in the setup screen's form. */
    fun place(wish: String): String = FORM.matchEntire(wish.trim())?.groupValues?.get(1) ?: wish

    /** Whether [text] has a whole Korean syllable (가..힣, so "용" passes and ㅎㅇ does not) or an English letter. */
    fun readable(text: String): Boolean = text.any { it in '가'..'힣' || it in 'a'..'z' || it in 'A'..'Z' }
}
