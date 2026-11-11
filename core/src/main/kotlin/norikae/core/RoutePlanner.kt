package norikae.core

/**
 * 荒井（または山陽明石）→（山陽）→ 新開地 →（神戸電鉄）→ 北鈴蘭台 の1経路。
 * 山陽の時刻表が未入力のときは [sanyo] が null で、[shinkaichiArr] は推定値か null。
 * 終電後の最終手段（鈴蘭台止まり → タクシー）のときは [taxiFromSuzurandai] が true で、[kitaArr] は鈴蘭台着。
 */
data class Route(
    val sanyo: SanyoTrain?,
    val shinkaichiArr: ServiceTime?,
    val shinkaichiEstimated: Boolean,
    val shintetsu: ShintetsuTrain,
    /** 最後に電車を降りる時刻（北鈴蘭台着。鈴蘭台止まりなら鈴蘭台着） */
    val kitaArr: ServiceTime,
    val origin: Origin = Origin.ARAI,
) {
    /** 鈴蘭台止まりに乗り、鈴蘭台からタクシーで帰る経路 */
    val taxiFromSuzurandai: Boolean get() = shintetsu.endsAtSuzurandai

    /** 電車を降りる駅 */
    val alightStation: String get() = if (taxiFromSuzurandai) "鈴蘭台" else "北鈴蘭台"

    /** 乗り過ごすと梅田まで行ってしまう経路か（新開地アラームを強める） */
    val umedaRisk: Boolean get() = sanyo?.goesToUmeda ?: false

    /** 普通だけで帰れる経路か（山陽が未入力のときは普通扱い） */
    val sanyoIsLocal: Boolean get() = sanyo?.isLocalOnly ?: true

    val waitAtShinkaichi: Int? get() = shinkaichiArr?.let { shintetsu.dep - it }

    /** 新開地の乗り換えが2分以内（走れば乗れる） */
    val tightTransfer: Boolean get() = waitAtShinkaichi?.let { it <= TIGHT_TRANSFER_MINUTES } ?: false

    /** 合計の所要時間（乗る駅を出てから、最後に電車を降りるまで）。山陽が未入力なら null */
    val totalMinutes: Int? get() = sanyo?.let { kitaArr - it.dep }

    companion object {
        /** この分数以下の乗り換えは「走れば乗れる」として目立たせる */
        const val TIGHT_TRANSFER_MINUTES = 2
    }
}

/** 所要時間を「1時間12分」「44分」の形に */
fun formatMinutes(minutes: Int): String =
    if (minutes >= 60) "${minutes / 60}時間${minutes % 60}分" else "${minutes}分"

class RoutePlanner(private val timetable: Timetable) {

    /** 新開地に [arrival] に着いたとき、乗れる北鈴蘭台行きの最初の電車 */
    fun connectAtShinkaichi(arrival: ServiceTime): ShintetsuTrain? {
        val earliest = arrival + timetable.transferMinutesAtShinkaichi
        return timetable.shintetsu
            .firstOrNull { it.reachesKitaSuzurandai && it.dep >= earliest }
    }

    /** 北鈴蘭台行きにもう乗れないとき、乗れる鈴蘭台止まり（その後タクシー） */
    fun taxiConnectionAtShinkaichi(arrival: ServiceTime): ShintetsuTrain? {
        val earliest = arrival + timetable.transferMinutesAtShinkaichi
        return timetable.shintetsu.firstOrNull { it.endsAtSuzurandai && it.dep >= earliest }
    }

    /** 北鈴蘭台行き、無ければ鈴蘭台止まり */
    fun connectOrTaxi(arrival: ServiceTime): ShintetsuTrain? =
        connectAtShinkaichi(arrival) ?: taxiConnectionAtShinkaichi(arrival)

    private fun route(origin: Origin, sanyo: SanyoTrain): Route? {
        val arr = sanyo.shinkaichiArr ?: return null
        val train = connectOrTaxi(arr) ?: return null
        return Route(sanyo, arr, false, train, train.finalArr!!, origin)
    }

    /**
     * [from] 以降に [origin] を出る経路の一覧（発車の早い順）。[filter] で種別を絞る（明石の特急/普通タブ）。
     * 北鈴蘭台まで行けない時刻でも、鈴蘭台止まりに乗れればタクシー経路として入れる。
     * 山陽が未入力なら、新開地発の北鈴蘭台行きの一覧を返す（新開地着は推定できる場合だけ入れる）。
     */
    fun plan(
        from: ServiceTime,
        origin: Origin = Origin.ARAI,
        filter: (SanyoTrain) -> Boolean = { true },
    ): List<Route> {
        val sanyoList = timetable.sanyoFrom(origin)
        if (sanyoList.isEmpty()) {
            val run = timetable.sanyoDefaultRunMinutes
            val transfer = timetable.transferMinutesAtShinkaichi
            val earliestShintetsuDep = if (run != null) from + run + transfer else from
            return timetable.shintetsu
                .filter { it.reachesKitaSuzurandai && it.dep >= earliestShintetsuDep }
                .map { train ->
                    Route(
                        sanyo = null,
                        shinkaichiArr = run?.let { train.dep - transfer },
                        shinkaichiEstimated = true,
                        shintetsu = train,
                        kitaArr = train.kitaSuzurandaiArr!!,
                        origin = origin,
                    )
                }
        }
        return sanyoList
            .filter { it.dep >= from && filter(it) }
            .mapNotNull { route(origin, it) }
    }

    /**
     * おすすめ経路（普通優先）。タクシー経路は、ほかに無いときだけ選ぶ。
     * 一番早く北鈴蘭台に着く経路より [toleranceMinutes] 分以内の遅れで済むなら、普通だけの経路を選ぶ。
     */
    fun recommend(routes: List<Route>, toleranceMinutes: Int = 15): Route? {
        val byTrain = routes.filterNot { it.taxiFromSuzurandai }
        if (byTrain.isEmpty()) return routes.firstOrNull()
        // 着く時刻が同じなら、遅く出る（合計の所要時間が短い）方を選ぶ
        val order = compareBy<Route>({ it.kitaArr }, { it.totalMinutes ?: Int.MAX_VALUE })
        val fastest = byTrain.minWith(order)
        val local = byTrain
            .filter { it.sanyoIsLocal && it.kitaArr - fastest.kitaArr <= toleranceMinutes }
            .minWithOrNull(order)
        return local ?: fastest
    }

    /**
     * 実際に [origin] で乗った時刻から経路を決める（遅れや、予定と違う電車に乗った場合に対応）。
     * 山陽の時刻表があれば「乗車時刻 +2分 までに出た最後の列車」に乗ったとみなす。
     */
    fun routeFromBoarding(
        boardedAt: ServiceTime,
        origin: Origin = Origin.ARAI,
        filter: (SanyoTrain) -> Boolean = { true },
    ): Route? {
        val sanyoList = timetable.sanyoFrom(origin).filter(filter)
        if (sanyoList.isEmpty()) {
            val run = timetable.sanyoDefaultRunMinutes ?: return null
            val arr = boardedAt + run
            val train = connectOrTaxi(arr) ?: return null
            return Route(null, arr, true, train, train.finalArr!!, origin)
        }
        val sanyo = sanyoList.lastOrNull { it.dep <= boardedAt + 2 && it.dep >= boardedAt - 15 }
            ?: sanyoList.firstOrNull { it.dep >= boardedAt }
            ?: return null
        return route(origin, sanyo)
    }

    /**
     * 新開地で神戸電鉄に乗った時刻から、実際に乗った電車を推定する。
     * 発車時刻が [boardedAt] の 4分前〜1分後 の電車のうち、一番近いもの。
     */
    fun shintetsuFromBoarding(boardedAt: ServiceTime): ShintetsuTrain? =
        timetable.shintetsu
            .filter { it.dep >= boardedAt - 4 && it.dep <= boardedAt + 1 }
            .minByOrNull { kotlin.math.abs(it.dep - boardedAt) }

    /** 北鈴蘭台に戻れる新開地方面の電車（乗り過ごし時の案内用）。[after] 以降で最初のもの */
    fun returnTrain(after: ServiceTime): UpTrain? =
        timetable.kitaSuzurandaiUp.firstOrNull { it.dep >= after }

    /** 一覧の中で合計の所要時間が一番短い経路（タクシー経路は除く） */
    fun shortest(routes: List<Route>): Route? =
        routes.filter { !it.taxiFromSuzurandai && it.totalMinutes != null }.minByOrNull { it.totalMinutes!! }

    /** その日、北鈴蘭台まで電車で帰れる最後の経路 */
    fun lastRoute(origin: Origin = Origin.ARAI): Route? =
        plan(ServiceTime(0), origin).filterNot { it.taxiFromSuzurandai }.maxByOrNull { it.kitaArr }

    /** [origin] を一番遅く出て、北鈴蘭台まで電車で帰れる経路（山陽が未入力なら null） */
    fun lastDepartureRoute(origin: Origin = Origin.ARAI, filter: (SanyoTrain) -> Boolean = { true }): Route? =
        plan(ServiceTime(0), origin, filter)
            .filter { it.sanyo != null && !it.taxiFromSuzurandai }
            .maxByOrNull { it.sanyo!!.dep }

    /** [origin] を一番遅く出て、鈴蘭台止まり（タクシー）に間に合う経路 */
    fun lastTaxiRoute(origin: Origin = Origin.ARAI, filter: (SanyoTrain) -> Boolean = { true }): Route? =
        plan(ServiceTime(0), origin, filter)
            .filter { it.sanyo != null && it.taxiFromSuzurandai }
            .maxByOrNull { it.sanyo!!.dep }
}
