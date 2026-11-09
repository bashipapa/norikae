package norikae.app

import android.content.Context
import norikae.core.LatLon
import norikae.core.Origin
import norikae.core.ServiceTime
import norikae.core.StationId
import norikae.core.StationLocations
import norikae.core.Timetable
import norikae.core.TripState
import norikae.core.isWeekday
import java.time.LocalDate

/** 端末内の保存（SharedPreferences） */
object Store {
    private const val PREFS = "norikae"
    private const val KEY_TRIP = "trip_state"
    private const val KEY_RUN_MINUTES = "sanyo_run_minutes"
    private const val KEY_DAY_OFF = "day_off"
    private const val KEY_LEFT_ORIGIN = "left_origin_at"
    private const val KEY_LAST_TAB = "last_tab"
    private const val KEY_CHECKED_AT = "timetable_checked_at"
    private const val KEY_FITBIT = "fitbit_helper"
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

    /** 判定に使う駅の位置：時刻表の初期値（Yahoo!路線情報の駅の位置）に、自分で登録した位置を重ねたもの */
    fun stations(ctx: Context): StationLocations =
        StationLocations(timetable(ctx).stations).withOverrides(savedStations(ctx))

    /** 自分で「今いる場所を登録」した駅の位置 */
    fun savedStations(ctx: Context): Map<StationId, LatLon> {
        val p = prefs(ctx)
        return StationId.entries.mapNotNull { id ->
            val text = p.getString("station_${id.name}", null) ?: return@mapNotNull null
            val (lat, lon) = text.split(",").map { it.toDouble() }
            id to LatLon(lat, lon)
        }.toMap()
    }

    fun clearStation(ctx: Context, id: StationId) {
        prefs(ctx).edit().remove("station_${id.name}").apply()
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

    /** 「今日は休み」（祝日など）にした営業日。この日は自動開始しない */
    fun isDayOff(ctx: Context, serviceDate: LocalDate): Boolean =
        prefs(ctx).getString(KEY_DAY_OFF, null) == serviceDate.toString()

    fun setDayOff(ctx: Context, serviceDate: LocalDate, off: Boolean) {
        prefs(ctx).edit().apply {
            if (off) putString(KEY_DAY_OFF, serviceDate.toString()) else remove(KEY_DAY_OFF)
        }.apply()
    }

    /** アプリが動く日か（平日で、「今日は休み」にしていない） */
    fun isServiceDay(ctx: Context, serviceDate: LocalDate): Boolean =
        isWeekday(serviceDate) && !isDayOff(ctx, serviceDate)

    // ---- 乗る駅（荒井・山陽明石）の範囲を出た時刻（自動開始用） ----

    /** その営業日に最後に出た乗る駅と時刻 */
    fun leftOrigin(ctx: Context, serviceDate: LocalDate): Pair<Origin, ServiceTime>? {
        val parts = prefs(ctx).getString(KEY_LEFT_ORIGIN, null)?.split("|") ?: return null
        if (parts.size != 3 || parts[0] != serviceDate.toString()) return null
        val origin = Origin.entries.firstOrNull { it.name == parts[1] } ?: return null
        return origin to ServiceTime(parts[2].toInt())
    }

    fun saveLeftOrigin(ctx: Context, serviceDate: LocalDate, origin: Origin, time: ServiceTime) {
        prefs(ctx).edit().putString(KEY_LEFT_ORIGIN, "$serviceDate|${origin.name}|${time.minutes}").apply()
    }

    /** 最後に開いていたタブ */
    fun lastTab(ctx: Context): Int = prefs(ctx).getInt(KEY_LAST_TAB, 0)
    fun saveLastTab(ctx: Context, index: Int) { prefs(ctx).edit().putInt(KEY_LAST_TAB, index).apply() }

    // ---- 時刻表の自動更新 ----

    /** ダウンロードした時刻表を保存する。戻り値: 中身が変わったか */
    fun saveDownloadedTimetable(ctx: Context, text: String): Boolean {
        val file = downloadedFile(ctx)
        val before = timetable(ctx).let { "${it.retrievedAt}|${it.sanyo}|${it.sanyoAkashi}|${it.shintetsu}" }
        if (file.exists() && file.readText() == text) return false
        file.writeText(text)
        cachedTimetable = null
        val after = timetable(ctx).let { "${it.retrievedAt}|${it.sanyo}|${it.sanyoAkashi}|${it.shintetsu}" }
        return before != after
    }

    /** 最後に時刻表を確認（ダウンロード）した日 */
    fun checkedAt(ctx: Context): String? = prefs(ctx).getString(KEY_CHECKED_AT, null)
    fun saveCheckedAt(ctx: Context, date: LocalDate) { prefs(ctx).edit().putString(KEY_CHECKED_AT, date.toString()).apply() }

    // ---- Fitbit Air の補助 ----

    fun fitbitHelperEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FITBIT, true)
    fun setFitbitHelperEnabled(ctx: Context, on: Boolean) { prefs(ctx).edit().putBoolean(KEY_FITBIT, on).apply() }

    // ---- 時刻表 ----

    @Volatile private var cachedTimetable: Timetable? = null

    private fun downloadedFile(ctx: Context) = java.io.File(ctx.filesDir, "timetable.json")

    /**
     * 使う時刻表：APK に同梱した assets/timetable.json と、週1回ダウンロードした時刻表のうち新しい方。
     * 設定の所要分で上書きしたものを返す。
     */
    fun timetable(ctx: Context): Timetable {
        val base = cachedTimetable ?: run {
            val bundled = ctx.assets.open("timetable.json").bufferedReader().use { Timetable.parse(it.readText()) }
            val downloaded = runCatching { Timetable.parse(downloadedFile(ctx).readText()) }.getOrNull()
            if (downloaded != null && (downloaded.retrievedAt ?: "") >= (bundled.retrievedAt ?: "")) downloaded else bundled
        }.also { cachedTimetable = it }
        val runMinutes = sanyoRunMinutes(ctx) ?: return base
        return base.copy(sanyoDefaultRunMinutes = runMinutes)
    }
}
