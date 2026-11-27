package kr.escape.car.story

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WishTest {
    @Test
    fun aPlaceWithNoWholeSyllableAndNoEnglishLetterCannotBeRead() {
        for (place in listOf("ㅎㅇ", "ㅋㅋ", "!!", "123", "ㅏㅑ", "  ", "", "ㅎㅇ!! 123", "😀")) {
            assertFalse(place, Wish.readable(place))
        }
    }

    @Test
    fun anyWholeSyllableOrEnglishLetterIsEnough() {
        for (place in listOf("용", "공룡 섬", "ㅋㅋ용", "Zoo", "x", "3번 우주 정거장", "별!!")) {
            assertTrue(place, Wish.readable(place))
        }
    }

    @Test
    fun thePlaceIsTakenFromTheWishAsTheSetupScreenWritesIt() {
        assertEquals("ㅎㅇ", Wish.place("ㅎㅇ에서 펼쳐지는 보물찾기 이야기"))
        assertEquals("용", Wish.place("용에서 펼쳐지는 탐정 놀이 이야기"))
        // the words the screen adds around the place never make it readable
        assertFalse(Wish.readable(Wish.place("123에서 펼쳐지는 구조대 이야기")))
        // a wish in another form is checked whole
        assertEquals("!!", Wish.place("!!"))
    }
}
