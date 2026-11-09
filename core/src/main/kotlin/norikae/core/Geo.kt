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
    SUZURANDAI("鈴蘭台"),
    KITA_SUZURANDAI("北鈴蘭台"),
    YAMANOMACHI("山の街"),
    SUZURANDAI_NISHIGUCHI("鈴蘭台西口"),
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
