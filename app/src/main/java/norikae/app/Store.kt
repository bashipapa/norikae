package norikae.app

import android.content.Context
import norikae.core.DayType
import norikae.core.LatLon
import norikae.core.ServiceTime
import norikae.core.StationId
import norikae.core.StationLocations
import norikae.core.Timetable
import norikae.core.TripState
import norikae.core.dayTypeOf
import java.time.LocalDate

/** 端末内の保存（SharedPreferences） */
object Store {
    private const val PREFS = "norikae"
    private const val KEY_TRIP = "trip_state"
    private const val KEY_RUN_MINUTES = "sanyo_run_minutes"
    private const val KEY_DAY_OVERRIDE = "day_override"
    private const val KEY_LEFT_ARAI = "left_arai_at"
    private const val KEY_LOCAL_TOLERANCE = "local_tolerance"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // ---- 乗車中の状態 ----

    fun trip(ctx: Context): TripState? =
        prefs(ctx).getString(KEY_TRIP, null)?.let { runCatching { TripState.fromJson(it) }.getOrNull() }

    fun saveTrip(ctx: Context, state: TripState?) {
        prefs(ctx).edit().apply {
            if (state == null) remove(KEY_TRIP) else putString(KEY_TRIP, state.toJson())
        }.apply()
    }

    // ---- 駅の位置 ----

    fun stations(ctx: Context): StationLocations {
        val p = prefs(ctx)
        val map = StationId.entries.mapNotNull { id ->
            val text = p.getString("station_${id.name}", null) ?: return@mapNotNull null
            val (lat, lon) = text.split(",").map { it.toDouble() }
            id to LatLon(lat, lon)
        }.toMap()
        return StationLocations(map)
    }

    fun saveStation(ctx: Context, id: StationId, location: LatLon) {
        prefs(ctx).edit().putString("station_${id.name}", "${location.lat},${location.lon}").apply()
    }

    // ---- 設定 ----

    /** 荒井→新開地の所要分（山陽の時刻表が未入力のときに使う）。未設定は null */
    fun sanyoRunMinutes(ctx: Context): Int? =
        prefs(ctx).getInt(KEY_RUN_MINUTES, -1).takeIf { it > 0 }

    fun saveSanyoRunMinutes(ctx: Context, minutes: Int?) {
        prefs(ctx).edit().putInt(KEY_RUN_MINUTES, minutes ?: -1).apply()
    }

    /** 普通優先の許容差（分） */
    fun localTolerance(ctx: Context): Int = prefs(ctx).getInt(KEY_LOCAL_TOLERANCE, 15)

    /** ダイヤ種別の手動切り替え（祝日用）。"日付|WEEKDAY" の形で、その営業日だけ有効 */
    fun dayType(ctx: Context, serviceDate: LocalDate): DayType {
        val override = prefs(ctx).getString(KEY_DAY_OVERRIDE, null)?.split("|")
        if (override != null && override.size == 2 && override[0] == serviceDate.toString()) {
            return DayType.valueOf(override[1])
        }
        return dayTypeOf(serviceDate)
    }

    fun overrideDayType(ctx: Context, serviceDate: LocalDate, dayType: DayType) {
        prefs(ctx).edit().putString(KEY_DAY_OVERRIDE, "$serviceDate|${dayType.name}").apply()
    }

    // ---- 荒井を出た時刻（自動開始用） ----

    fun leftAraiAt(ctx: Context, serviceDate: LocalDate): ServiceTime? {
        val parts = prefs(ctx).getString(KEY_LEFT_ARAI, null)?.split("|") ?: return null
        if (parts.size != 2 || parts[0] != serviceDate.toString()) return null
        return ServiceTime(parts[1].toInt())
    }

    fun saveLeftAraiAt(ctx: Context, serviceDate: LocalDate, time: ServiceTime) {
        prefs(ctx).edit().putString(KEY_LEFT_ARAI, "$serviceDate|${time.minutes}").apply()
    }

    // ---- 時刻表 ----

    @Volatile private var cachedTimetable: Timetable? = null

    /** assets/timetable.json を読み、設定の所要分で上書きしたもの */
    fun timetable(ctx: Context): Timetable {
        val base = cachedTimetable ?: run {
            val text = ctx.assets.open("timetable.json").bufferedReader().use { it.readText() }
            // 山陽の時刻表（data/local/sanyo.json）。個人用のため git には入れていない。無ければ null
            val sanyo = runCatching {
                ctx.assets.open("local/sanyo.json").bufferedReader().use { it.readText() }
            }.getOrNull()
            Timetable.parse(text, sanyo)
        }.also { cachedTimetable = it }
        val runMinutes = sanyoRunMinutes(ctx) ?: return base
        return base.copy(sanyoDefaultRunMinutes = runMinutes)
    }
}
