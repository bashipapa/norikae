package norikae.core

/**
 * 荒井 →（山陽）→ 新開地 →（神戸電鉄）→ 北鈴蘭台 の1経路。
 * 山陽の時刻表が未入力のときは [sanyo] が null で、[shinkaichiArr] は推定値か null。
 */
data class Route(
    val sanyo: SanyoTrain?,
    val shinkaichiArr: ServiceTime?,
    val shinkaichiEstimated: Boolean,
    val shintetsu: ShintetsuTrain,
    val kitaArr: ServiceTime,
) {
    /** 乗り過ごすと梅田まで行ってしまう経路か（新開地アラームを強める） */
    val umedaRisk: Boolean get() = sanyo?.goesToUmeda ?: false

    /** 普通だけで帰れる経路か（山陽が未入力のときは普通扱い） */
    val sanyoIsLocal: Boolean get() = sanyo?.isLocalOnly ?: true

    val waitAtShinkaichi: Int? get() = shinkaichiArr?.let { shintetsu.dep - it }
}

class RoutePlanner(private val timetable: Timetable) {

    /** 新開地に [arrival] に着いたとき、乗れる北鈴蘭台行きの最初の電車 */
    fun connectAtShinkaichi(dayType: DayType, arrival: ServiceTime): ShintetsuTrain? {
        val earliest = arrival + timetable.transferMinutesAtShinkaichi
        return timetable.forDay(dayType).shintetsu
            .firstOrNull { it.reachesKitaSuzurandai && it.dep >= earliest }
    }

    /**
     * [from] 以降に荒井を出る経路の一覧（荒井発の早い順）。
     * 山陽が未入力なら、新開地発の北鈴蘭台行きの一覧を返す（新開地着は推定できる場合だけ入れる）。
     */
    fun plan(dayType: DayType, from: ServiceTime): List<Route> {
        val day = timetable.forDay(dayType)
        if (day.sanyo.isEmpty()) {
            val run = timetable.sanyoDefaultRunMinutes
            val transfer = timetable.transferMinutesAtShinkaichi
            // 荒井を from に出たときに間に合う最初の電車以降
            val earliestShintetsuDep = if (run != null) from + run + transfer else from
            return day.shintetsu
                .filter { it.reachesKitaSuzurandai && it.dep >= earliestShintetsuDep }
                .map { train ->
                    Route(
                        sanyo = null,
                        shinkaichiArr = run?.let { train.dep - transfer },
                        shinkaichiEstimated = true,
                        shintetsu = train,
                        kitaArr = train.kitaSuzurandaiArr!!,
                    )
                }
        }
        return day.sanyo
            .filter { it.dep >= from && it.shinkaichiArr != null }
            .mapNotNull { sanyo ->
                val connection = connectAtShinkaichi(dayType, sanyo.shinkaichiArr!!) ?: return@mapNotNull null
                Route(
                    sanyo = sanyo,
                    shinkaichiArr = sanyo.shinkaichiArr,
                    shinkaichiEstimated = false,
                    shintetsu = connection,
                    kitaArr = connection.kitaSuzurandaiArr!!,
                )
            }
    }

    /**
     * おすすめ経路（普通優先）。
     * 一番早く北鈴蘭台に着く経路より [toleranceMinutes] 分以内の遅れで済むなら、普通だけの経路を選ぶ。
     */
    fun recommend(routes: List<Route>, toleranceMinutes: Int = 15): Route? {
        if (routes.isEmpty()) return null
        val fastest = routes.minBy { it.kitaArr }
        val local = routes
            .filter { it.sanyoIsLocal && it.kitaArr - fastest.kitaArr <= toleranceMinutes }
            .minByOrNull { it.kitaArr }
        return local ?: fastest
    }

    /**
     * 実際に荒井で乗った時刻から経路を決める（遅れや、予定と違う電車に乗った場合に対応）。
     * 山陽の時刻表があれば「乗車時刻 +2分 までに出た最後の列車」に乗ったとみなす。
     */
    fun routeFromBoarding(dayType: DayType, boardedAt: ServiceTime): Route? {
        val day = timetable.forDay(dayType)
        if (day.sanyo.isEmpty()) {
            val run = timetable.sanyoDefaultRunMinutes ?: return null
            val arr = boardedAt + run
            val train = connectAtShinkaichi(dayType, arr) ?: return null
            return Route(null, arr, true, train, train.kitaSuzurandaiArr!!)
        }
        val sanyo = day.sanyo.lastOrNull { it.dep <= boardedAt + 2 && it.dep >= boardedAt - 15 }
            ?: day.sanyo.firstOrNull { it.dep >= boardedAt }
            ?: return null
        val arr = sanyo.shinkaichiArr ?: return null
        val train = connectAtShinkaichi(dayType, arr) ?: return null
        return Route(sanyo, arr, false, train, train.kitaSuzurandaiArr!!)
    }

    /**
     * 新開地で神戸電鉄に乗った時刻から、実際に乗った電車を推定する。
     * 発車時刻が [boardedAt] の 4分前〜1分後 の電車のうち、一番近いもの。
     */
    fun shintetsuFromBoarding(dayType: DayType, boardedAt: ServiceTime): ShintetsuTrain? =
        timetable.forDay(dayType).shintetsu
            .filter { it.dep >= boardedAt - 4 && it.dep <= boardedAt + 1 }
            .minByOrNull { kotlin.math.abs(it.dep - boardedAt) }

    /** 北鈴蘭台に戻れる新開地方面の電車（乗り過ごし時の案内用）。[after] 以降で最初のもの */
    fun returnTrain(dayType: DayType, after: ServiceTime): UpTrain? =
        timetable.forDay(dayType).kitaSuzurandaiUp.firstOrNull { it.dep >= after }

    /** その日の終電で帰れる最後の経路（北鈴蘭台着が一番遅いもの） */
    fun lastRoute(dayType: DayType): Route? = plan(dayType, ServiceTime(0)).maxByOrNull { it.kitaArr }

    /** 荒井を一番遅く出て帰れる経路（山陽が未入力なら null） */
    fun lastDepartureRoute(dayType: DayType): Route? =
        plan(dayType, ServiceTime(0)).filter { it.sanyo != null }.maxByOrNull { it.sanyo!!.dep }
}
