package kr.escape.car.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WishTest {
    @Test
    fun theseAreWords() {
        for (place in listOf("용", "북극 얼음 마을", "공룡 섬2", "Paris", "dog", "Tokyo", "ㅋㅋ용", "3번 우주 정거장", "별!!", "Zoo", "LONDON")) {
            assertTrue(place, Wish.readable(place))
        }
    }

    @Test
    fun theseAreNot() {
        for (place in listOf("ghd", "rkt", "dkssud", "123", "ㅎㅇ", "ㅋㅋ", "!!", "ㅏㅑ", "  ", "", "ㅎㅇ!! 123", "😀")) {
            assertFalse(place, Wish.readable(place))
        }
    }

    @Test
    fun englishWithNoVowelIsNoWord() {
        // y counts as a vowel
        assertFalse(Wish.readable("ghd"))
        assertFalse(Wish.readable("BCD 123"))
        assertTrue(Wish.readable("sky"))
    }

    @Test
    fun koreanTypedWithTheKeyboardInEnglishIsNoWord() {
        // dkssud = 안녕, rkqtk = 가방 (Shift makes ㄲ ㄸ ㅃ ㅆ ㅉ ㅒ ㅖ), gksrmf = 한글
        assertEquals(2, Wish.dubeolsikSyllables("dkssud"))
        assertEquals(2, Wish.dubeolsikSyllables("gksrmf"))
        assertFalse(Wish.readable("gksrmf"))
        assertFalse(Wish.readable("Tkfkd"))
        // a double vowel or a double final consonant is still one syllable: rhk (과), dlfr (읽)
        assertEquals(1, Wish.dubeolsikSyllables("rhk"))
        assertEquals(1, Wish.dubeolsikSyllables("dlfr"))
        // a space breaks the run
        assertEquals(2, Wish.dubeolsikSyllables("rk rk"))
        // English words that make one syllable at most stay words
        assertEquals(1, Wish.dubeolsikSyllables("Paris"))
        assertEquals(1, Wish.dubeolsikSyllables("dog"))
        assertEquals(1, Wish.dubeolsikSyllables("Tokyo"))
    }

    @Test
    fun thePlaceIsTakenFromTheWishAsTheSetupScreenWritesIt() {
        assertEquals("ㅎㅇ", Wish.place("ㅎㅇ에서 펼쳐지는 보물찾기 이야기"))
        assertEquals("용", Wish.place("용에서 펼쳐지는 탐정 놀이 이야기"))
        // the words the screen adds around the place never make it readable
        assertFalse(Wish.readable(Wish.place("123에서 펼쳐지는 구조대 이야기")))
        assertFalse(Wish.readable(Wish.place("ghd에서 펼쳐지는 구조대 이야기")))
        // a wish in another form is checked whole
        assertEquals("!!", Wish.place("!!"))
    }
}
