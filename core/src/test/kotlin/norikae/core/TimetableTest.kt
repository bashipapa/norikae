package norikae.core

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

fun loadRealTimetable(): Timetable =
    Timetable.parse(File(System.getProperty("timetable.path")).readText())

fun t(text: String) = ServiceTime.parse(text)

class ServiceTimeTest {
    @Test
    fun parsesAfterMidnight() {
        assertEquals(24 * 60 + 3, t("24:03").minutes)
        assertEquals("24:03", t("24:03").toString())
        assertEquals("21:05", t("21:05").toString())
    }

    @Test
    fun convertsRealTimeToServiceDay() {
        val (date, time) = LocalDateTime.of(2026, 11, 10, 0, 30).toService()
        assertEquals(LocalDate.of(2026, 11, 9), date)
        assertEquals("24:30", time.toString())
        assertEquals(LocalDateTime.of(2026, 11, 10, 0, 30), time.toLocalDateTime(date))
    }

    @Test
    fun drinkingModeStartsAt21() {
        val rule = DrinkingModeRule()
        assertFalse(rule.isDrinkingTime(LocalDateTime.of(2026, 11, 9, 20, 59)))
        assertTrue(rule.isDrinkingTime(LocalDateTime.of(2026, 11, 9, 21, 0)))
        assertTrue(rule.isDrinkingTime(LocalDateTime.of(2026, 11, 10, 0, 20)))
        assertFalse(rule.isDrinkingTime(LocalDateTime.of(2026, 11, 10, 8, 0)))
    }

    @Test
    fun weekdaysOnly() {
        assertTrue(isWeekday(LocalDate.of(2026, 11, 9)))  // 月
        assertFalse(isWeekday(LocalDate.of(2026, 11, 8))) // 日
        assertFalse(isWeekday(LocalDate.of(2026, 11, 7))) // 土
        val holiday = LocalDate.of(2026, 11, 23)
        assertFalse(isWeekday(holiday, setOf(holiday)))
    }
}

class TimetableTest {
    private val tt = loadRealTimetable()

    @Test
    fun loadsShintetsuFromJson() {
        val weekday = tt.shintetsu
        assertEquals("21:11", weekday.first().dep.toString())
        assertEquals("24:03", weekday.last().dep.toString())
        assertNull(weekday.last().kitaSuzurandaiArr, "24:03 は鈴蘭台止まり")
        assertEquals("2025-03-15", tt.shintetsuRevision)
    }

    @Test
    fun awaLineTrainsDoNotReachKitaSuzurandai() {
        val shimizu = tt.shintetsu.first { it.dep == t("21:17") }
        assertEquals("志染", shimizu.dest)
        assertFalse(shimizu.reachesKitaSuzurandai)
    }

    @Test
    fun lastTrainIsDojoMinamiguchi() {
        val planner = RoutePlanner(tt)
        val last = assertNotNull(planner.lastRoute())
        assertEquals("23:50", last.shintetsu.dep.toString())
        assertEquals("道場南口", last.shintetsu.dest)
        assertEquals("24:08", last.kitaArr.toString())
    }

}

class RetrievedAtTest {
    @Test
    fun retrievedAtIsParsed() {
        assertEquals("2026-11-09", loadRealTimetable().retrievedAt)
    }
}
