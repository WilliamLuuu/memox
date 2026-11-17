package cool.hin.memox.presentation.activity.main

import cool.hin.memox.data.model.Reminder
import cool.hin.memox.data.model.Repetition
import cool.hin.memox.data.model.RepetitionTimeUnit
import cool.hin.memox.data.model.nextRepetition
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class MainActivityDateRangeTest {

    private val timeZone = TimeZone.getTimeZone("Asia/Shanghai")

    private fun utcMidnight(year: Int, month: Int, day: Int): Long =
        Calendar.getInstance(TimeZone.getTimeZone("UTC"))
            .apply {
                clear()
                set(year, month, day)
            }
            .timeInMillis

    private fun localMillis(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        Calendar.getInstance(timeZone)
            .apply {
                clear()
                set(year, month, day, hour, minute, 0)
            }
            .timeInMillis

    @Test
    fun `date range uses local start and exclusive next-day upper bound`() {
        val from = MainActivity.localStartOfDay(utcMidnight(2026, Calendar.MARCH, 10), timeZone)
        val to = MainActivity.exclusiveEndOfDay(utcMidnight(2026, Calendar.MARCH, 12), timeZone)

        assertEquals(localMillis(2026, Calendar.MARCH, 10), from)
        assertEquals(localMillis(2026, Calendar.MARCH, 13), to)
        // 起始日零点与结束日 23:59 都落在范围内，结束日次日零点被排除。
        assertTrue(localMillis(2026, Calendar.MARCH, 10, 0, 0) >= from)
        assertTrue(localMillis(2026, Calendar.MARCH, 12, 23, 59) < to)
        assertTrue(localMillis(2026, Calendar.MARCH, 13, 0, 0) >= to)
        // 同一天选择也应覆盖全天，而不是只匹配 UTC 零点那一瞬。
        val sameDayEnd = MainActivity.exclusiveEndOfDay(utcMidnight(2026, Calendar.MARCH, 10), timeZone)
        assertTrue(localMillis(2026, Calendar.MARCH, 10, 23, 0) < sameDayEnd)
    }

    @Test
    fun `exclusive upper bound follows daylight saving transitions`() {
        val dstZone = TimeZone.getTimeZone("America/New_York")
        // 2026-03-08 is the DST start in New York: the next local midnight is only 23 hours away.
        val end = MainActivity.exclusiveEndOfDay(utcMidnight(2026, Calendar.MARCH, 8), dstZone)
        val expected =
            Calendar.getInstance(dstZone)
                .apply {
                    clear()
                    set(2026, Calendar.MARCH, 9)
                }
                .timeInMillis
        assertEquals(expected, end)
    }

    @Test
    fun `repeating reminder exports next future trigger instead of original start`() {
        val original = localMillis(2026, Calendar.MARCH, 1, 9, 0)
        val now = localMillis(2026, Calendar.MARCH, 10, 10, 0)
        val reminder = Reminder(1, Date(original), Repetition(1, RepetitionTimeUnit.DAYS))

        val exportStart = reminder.nextRepetition(Date(now)) ?: reminder.dateTime

        assertEquals(Date(localMillis(2026, Calendar.MARCH, 11, 9, 0)), exportStart)
    }
}
