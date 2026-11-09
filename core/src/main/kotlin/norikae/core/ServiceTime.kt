package norikae.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * 「営業日」基準の時刻。0時を過ぎても前日の続きとして 24:03 のように扱う。
 * 内部では営業日の 0:00 からの経過分で持つ。
 */
@JvmInline
value class ServiceTime(val minutes: Int) : Comparable<ServiceTime> {
    override fun compareTo(other: ServiceTime): Int = minutes.compareTo(other.minutes)

    operator fun plus(deltaMinutes: Int) = ServiceTime(minutes + deltaMinutes)
    operator fun minus(deltaMinutes: Int) = ServiceTime(minutes - deltaMinutes)
    operator fun minus(other: ServiceTime): Int = minutes - other.minutes

    override fun toString(): String = "%d:%02d".format(minutes / 60, minutes % 60)

    /** 営業日 [serviceDate] の実際の日時に変換する（24:03 → 翌日 0:03） */
    fun toLocalDateTime(serviceDate: LocalDate): LocalDateTime =
        serviceDate.atStartOfDay().plusMinutes(minutes.toLong())

    companion object {
        /** "21:05" や "24:03" を読む */
        fun parse(text: String): ServiceTime {
            val parts = text.trim().split(":")
            require(parts.size == 2) { "時刻の形式が不正です: $text" }
            val h = parts[0].toInt()
            val m = parts[1].toInt()
            require(h in 0..29 && m in 0..59) { "時刻の範囲が不正です: $text" }
            return ServiceTime(h * 60 + m)
        }
    }
}

/** 何時までを前日の営業日とみなすか（4時より前は前日扱い） */
const val SERVICE_DAY_BOUNDARY_HOUR = 4

/** 実際の日時 → (営業日, 営業日基準の時刻) */
fun LocalDateTime.toService(): Pair<LocalDate, ServiceTime> {
    val minutesOfDay = hour * 60 + minute
    return if (hour < SERVICE_DAY_BOUNDARY_HOUR) {
        toLocalDate().minusDays(1) to ServiceTime(minutesOfDay + 24 * 60)
    } else {
        toLocalDate() to ServiceTime(minutesOfDay)
    }
}

/**
 * 平日（アプリが動く日）か。土日は動かない。
 * 祝日は自動判定しないので、[holidays] に入れるか、アプリで「今日は休み」にする。
 */
fun isWeekday(serviceDate: LocalDate, holidays: Set<LocalDate> = emptySet()): Boolean =
    serviceDate.dayOfWeek != DayOfWeek.SATURDAY &&
        serviceDate.dayOfWeek != DayOfWeek.SUNDAY &&
        serviceDate !in holidays

/** 飲み会モードの判定: 21時以降（営業日基準で 21:00〜28:00 未満）に乗車したら ON */
data class DrinkingModeRule(val startHour: Int = 21) {
    fun isDrinkingTime(time: ServiceTime): Boolean = time.minutes >= startHour * 60
    fun isDrinkingTime(now: LocalDateTime): Boolean = isDrinkingTime(now.toService().second)
}
