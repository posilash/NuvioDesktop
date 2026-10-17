package com.nuvio.app.features.calendar

import com.nuvio.app.core.time.isoEpochDay
import com.nuvio.app.features.watchprogress.CurrentDateProvider

internal data class YearMonth(val year: Int, val month: Int) {
    fun plus(months: Int): YearMonth {
        val index = year * 12 + (month - 1) + months
        return YearMonth(index.floorDiv(12), index.mod(12) + 1)
    }

    val firstDay: Long get() = isoEpochDay(iso(year, month, 1))
    val length: Int get() = when (month) {
        2 -> if (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) 29 else 28
        4, 6, 9, 11 -> 30
        else -> 31
    }
    val label: String get() = "${MonthNames[month - 1]} $year"

    companion object {
        fun of(isoDate: String) = YearMonth(isoDate.substring(0, 4).toInt(), isoDate.substring(5, 7).toInt())
    }
}

internal val MonthNames = listOf(
    "January", "February", "March", "April", "May", "June",
    "July", "August", "September", "October", "November", "December",
)
internal val WeekdayNames = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

private fun iso(year: Int, month: Int, day: Int): String =
    "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

/** yyyy-MM-dd for a day count since 1970-01-01 (Howard Hinnant's civil_from_days). */
internal fun epochDayToIso(epochDay: Long): String {
    val z = epochDay + 719_468L
    val era = z.floorDiv(146_097L)
    val doe = z - era * 146_097L
    val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val day = (doy - (153 * mp + 2) / 5 + 1).toInt()
    val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
    val year = (yoe + era * 400 + if (month <= 2) 1 else 0).toInt()
    return iso(year, month, day)
}

/** 0 = Monday. 1970-01-01 was a Thursday. */
internal fun weekdayMondayFirst(epochDay: Long): Int = (epochDay + 3).mod(7L).toInt()

internal fun dayOfMonth(isoDate: String): Int = isoDate.substring(8, 10).toInt()

/** "21:00" in local time, or null for a date-only release. */
internal fun localAirTime(episode: CalendarEpisode): String? {
    val at = episode.airEpochMs ?: return null
    val dayStart = CurrentDateProvider.localStartOfDayEpochMs(episode.date) ?: return null
    val minutes = ((at - dayStart) / 60_000L).toInt().takeIf { it in 0 until 24 * 60 } ?: return null
    return "${(minutes / 60).toString().padStart(2, '0')}:${(minutes % 60).toString().padStart(2, '0')}"
}

internal fun dayHeading(isoDate: String, todayIso: String): String {
    val day = isoEpochDay(isoDate)
    val today = isoEpochDay(todayIso)
    val name = "${WeekdayNames[weekdayMondayFirst(day)]} ${dayOfMonth(isoDate)} ${MonthNames[YearMonth.of(isoDate).month - 1]}"
    return when (day - today) {
        0L -> "Today · $name"
        1L -> "Tomorrow · $name"
        else -> name
    }
}

internal fun episodeCode(episode: CalendarEpisode): String = when {
    episode.season != null && episode.episode != null -> "S${episode.season} E${episode.episode}"
    episode.episode != null -> "E${episode.episode}"
    else -> ""
}
