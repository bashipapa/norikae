package norikae.core

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double) {
    /** 2点間の距離（メートル） */
    fun distanceTo(other: LatLon): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(other.lat - lat)
        val dLon = Math.toRadians(other.lon - lon)
        val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat)) * cos(Math.toRadians(other.lat)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(a))
    }
}

enum class StationId(val label: String) {
    ARAI("荒井"),
    AKASHI("山陽明石"),
    SHINKAICHI("新開地"),
    /** 新開地の次の駅（山陽・阪神方面）。ここに近づいたら新開地の乗り過ごし */
    KOSOKU_KOBE("高速神戸"),
    SUZURANDAI("鈴蘭台"),
    KITA_SUZURANDAI("北鈴蘭台"),
    YAMANOMACHI("山の街"),
    SUZURANDAI_NISHIGUCHI("鈴蘭台西口"),

    // ---- どこからでもモード（大阪などから、三宮か新開地で乗り換えて帰る）----
    /** 三宮（阪急・阪神・地下鉄・JRの三宮駅のほぼ中央） */
    SANNOMIYA("三宮"),
    /** 地下鉄北神線の終点。神戸電鉄有馬線に乗り換えて北鈴蘭台へ */
    TANIGAMI("谷上"),
    /** 三宮の次（谷上方面）。ここに近づいたら谷上方面に向かっている */
    SHIN_KOBE("新神戸"),
    /** 新開地の次（神戸電鉄）。こちらに向かっていれば神戸電鉄に乗っている */
    MINATOGAWA("湊川"),
    /** 新開地の次（山陽・阪神の西代方面） */
    DAIKAI("大開"),
}

/**
 * 駅の位置。アプリの「今いる場所をこの駅として登録」で設定する。
 * 未登録の駅は位置判定を使わず、時刻と乗り物/歩行の判定だけで動く。
 */
data class StationLocations(val map: Map<StationId, LatLon> = emptyMap()) {
    /** [overrides] を優先して重ねる（アプリで登録した位置 > 時刻表の初期値） */
    fun withOverrides(overrides: Map<StationId, LatLon>) = StationLocations(map + overrides)

    operator fun get(id: StationId): LatLon? = map[id]

    /** [here] が駅 [id] から [radiusMeters] 以内か。駅が未登録なら null（判定できない） */
    fun isNear(id: StationId, here: LatLon?, radiusMeters: Double): Boolean? {
        val station = map[id] ?: return null
        if (here == null) return null
        return here.distanceTo(station) <= radiusMeters
    }
}
