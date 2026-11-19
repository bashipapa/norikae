package norikae.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.min

/**
 * どこからでもモード（大阪などから帰るとき）。時刻表は使わず、位置だけで起こす。
 *
 * 帰り道は2通り：
 *  - 三宮で地下鉄に乗り換え → 谷上（終点）で神戸電鉄に乗り換え → 北鈴蘭台
 *  - 新開地で神戸電鉄に乗り換え → 北鈴蘭台
 *
 * 乗り換える駅（三宮・新開地）に近づいたら起こし、その先は 谷上 → 北鈴蘭台、または 北鈴蘭台 で起こす。
 */
enum class FreePhase {
    /** 三宮か新開地に向かっている（近づいた方で起こす） */
    HEADING,
    /** 三宮に近づいている */
    TO_SANNOMIYA,
    /** 三宮に着いた。谷上方面（地下鉄）か新開地方面（阪神・阪急）かを位置で見ている */
    AT_SANNOMIYA,
    /** 新開地に近づいている（新開地で神戸電鉄に乗り換え） */
    TO_SHINKAICHI,
    /** 谷上に近づいている（地下鉄北神線。谷上が終点） */
    TO_TANIGAMI,
    /** 神戸電鉄で北鈴蘭台に近づいている */
    TO_KITA,
    /** 乗り換える駅（降りる駅）を過ぎた */
    OVERSHOT,
    DONE,
}

@Serializable
data class FreeState(
    val serviceDate: String,
    /** 開始した時刻（営業日基準の分） */
    val startedAt: Int,
    val phase: FreePhase = FreePhase.HEADING,
    /** 乗り換える駅の指定：TANIGAMI＝三宮で地下鉄へ、SHINKAICHI＝新開地で神戸電鉄へ、null＝近づいた方 */
    val via: StationId? = null,
    /** 今向かっている駅（起こす駅） */
    val target: StationId? = null,
    /** 北鈴蘭台へ向かうとき、どちらから来たか（TANIGAMI か SHINKAICHI）。乗り過ごしの判定に使う */
    val kitaFrom: StationId? = null,
    /** 三宮から新開地に向かっている（時刻での目安に使う） */
    val viaSannomiya: Boolean = false,
    val preRung: Boolean = false,
    val mainRung: Boolean = false,
    /** 予告を鳴らした時刻 */
    val preAt: Int? = null,
    /** 向かっている駅に一番近づいた距離（m） */
    val minDist: Double? = null,
    /** 駅を過ぎた（離れていく）位置が続いた回数。誤判定を避けるため2回続いたら乗り過ごしにする */
    val beyondCount: Int = 0,
    /** 着く見込みの時刻。地下・トンネルで位置が取れないときは、この時刻で起こす */
    val expectedArr: Int? = null,
    /** 乗り換えで歩いたあと、乗り物に乗った時刻 */
    val boardedAt: Int? = null,
    val awakeAt: Int? = null,
    val lastRingAt: Int? = null,
    /** 乗り過ごし警報を鳴らした回数 */
    val rings: Int = 0,
    val lastMotion: Motion? = null,
    val walkedAt: Int? = null,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val lastLocAt: Int? = null,
    val prevLat: Double? = null,
    val prevLon: Double? = null,
    val prevLocAt: Int? = null,
    val stayLat: Double? = null,
    val stayLon: Double? = null,
    val stayAt: Int? = null,
) {
    val lastLocation: LatLon? get() = if (lastLat != null && lastLon != null) LatLon(lastLat, lastLon) else null
    val prevLocation: LatLon? get() = if (prevLat != null && prevLon != null) LatLon(prevLat, prevLon) else null

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String): FreeState = json.decodeFromString(serializer(), text)
    }
}

data class FreeTransition(val state: FreeState, val actions: List<TripAction>)

class FreeTripEngine(
    private val stations: StationLocations,
    private val settings: AlarmSettings = AlarmSettings(),
) {
    companion object {
        /** 予告：駅まで約3km（電車で3〜5分前）。近づいているときだけ */
        const val PRE_M = 3000.0
        /** 本アラーム：駅まで約1km（1〜2分前） */
        const val MAIN_M = 1000.0
        /** 北鈴蘭台の予告（谷上〜北鈴蘭台は約3.1kmなので少し短め） */
        const val KITA_PRE_M = 2500.0
        /** 北鈴蘭台の本アラーム（山の街〜北鈴蘭台は約800m。山の街の手前で鳴らないよう短め） */
        const val KITA_MAIN_M = 700.0
        /** 前の位置よりこれだけ近づいていたら「近づいている」 */
        const val APPROACH_M = 50.0
        /** この距離以内なら「駅にいる」（地下駅は位置がずれるので広め） */
        const val AT_M = 400.0
        /** 開始したときにこの距離以内なら、その駅にもういるとみなす */
        const val START_NEAR_M = 1000.0
        /** 一番近づいた距離からこれだけ離れたら「駅を離れた」 */
        const val LEAVING_M = 500.0
        const val NEAR_SHIN_KOBE_M = 600.0
        /** これより古い位置は使わない（分） */
        const val FRESH_MIN = 2
        /** 開始から4時間で自動終了（止め忘れ対策） */
        const val MAX_TRIP_MIN = 240
        /** 乗り換えで歩いてからこの分数以内に乗り物に乗ったら、そこから時刻で着く時刻を見込む */
        const val BOARD_AFTER_WALK_MIN = 20

        // 所要時間（Yahoo!路線情報で確認、2026-11-19）
        const val SANNOMIYA_TO_TANIGAMI_MIN = 10
        const val SHIN_KOBE_TO_TANIGAMI_MIN = 7
        const val SANNOMIYA_TO_SHINKAICHI_MIN = 7
        const val TANIGAMI_TO_KITA_MIN = 8

        /** 時刻での起こし：着く見込みの何分前に予告・本アラーム */
        const val TIME_PRE_MIN = 3
        const val TIME_MAIN_MIN = 1

        const val VIBRATE_ONLY = 0
        const val SIREN_AFTER_RINGS = 10
        const val SIREN_LEVEL = 3
        const val STAY_RADIUS_M = 150.0
    }

    fun start(serviceDate: String, at: ServiceTime, via: StationId? = null): FreeTransition {
        val state = FreeState(serviceDate = serviceDate, startedAt = at.minutes, via = via)
        return FreeTransition(state, listOf(TripAction.Notify("どこからでもモード：${viaLabel(via)}。近づいたら起こします")))
    }

    fun viaLabel(via: StationId?) = when (via) {
        StationId.TANIGAMI -> "三宮で地下鉄に乗り換え → 谷上 → 北鈴蘭台"
        StationId.SHINKAICHI -> "新開地で神戸電鉄に乗り換え → 北鈴蘭台"
        else -> "三宮か新開地（近づいた方）→ 北鈴蘭台"
    }

    fun reduce(state: FreeState, event: TripEvent): FreeTransition {
        if (state.phase == FreePhase.DONE) return FreeTransition(state, emptyList())
        val t = event.at
        if (t.minutes - state.startedAt > MAX_TRIP_MIN) {
            return done(state, "開始から4時間たったので、どこからでもモードを終了しました")
        }
        return when (event) {
            is TripEvent.UserAwake -> FreeTransition(state.copy(awakeAt = t.minutes), emptyList())
            is TripEvent.UserFinished -> done(state, "終了しました")
            is TripEvent.UserChoseVia -> chooseVia(state, event.via, t)
            is TripEvent.UserTransferring -> FreeTransition(state, emptyList())
            is TripEvent.MotionChanged -> onMotion(state, event.motion, t)
            is TripEvent.LocationChanged -> evaluate(withLocation(state, event), t, newFix = true)
            is TripEvent.AlarmFired ->
                if (event.kind == AlarmKind.FREE_TICK) evaluate(state, t, newFix = false) else FreeTransition(state, emptyList())
        }
    }

    /** 向かっている駅までの距離（位置が新しいときだけ） */
    fun distanceToTarget(state: FreeState, t: ServiceTime): Double? {
        val target = state.target ?: return null
        val here = fresh(state, t) ?: return null
        return dist(target, here)
    }

    // ---- ユーザーの操作・動き ----

    private fun chooseVia(state: FreeState, via: StationId?, t: ServiceTime): FreeTransition {
        val acts = mutableListOf<TripAction>()
        var s = state.copy(via = via)
        when (state.phase) {
            FreePhase.HEADING -> Unit
            FreePhase.TO_SANNOMIYA ->
                if (via == StationId.SHINKAICHI) s = newLeg(s, StationId.SHINKAICHI, FreePhase.TO_SHINKAICHI, t)
            FreePhase.AT_SANNOMIYA -> Unit // evaluate で谷上・新開地へ進める
            else -> return FreeTransition(state, emptyList())
        }
        acts += TripAction.Notify("乗り換え：${viaLabel(via)}")
        val next = evaluate(s, t, newFix = false)
        return FreeTransition(next.state, acts + next.actions)
    }

    private fun onMotion(state: FreeState, motion: Motion, t: ServiceTime): FreeTransition {
        var s = state.copy(lastMotion = motion, walkedAt = if (motion == Motion.WALKING) t.minutes else state.walkedAt)
        // 乗り換えで歩いたあとに乗り物に乗った → そこから時刻で着く時刻を見込む（地下・トンネル用）
        if (motion == Motion.IN_VEHICLE && state.walkedAt != null && t.minutes - state.walkedAt <= BOARD_AFTER_WALK_MIN) {
            s = s.copy(boardedAt = t.minutes)
            if (s.expectedArr == null && !s.mainRung) {
                legMinutes(s)?.let { s = s.copy(expectedArr = t.minutes + it) }
            }
        }
        return evaluate(s, t, newFix = false)
    }

    /** 乗り換えてから着くまでの分（時刻での目安。位置が取れないときだけ使う） */
    private fun legMinutes(s: FreeState): Int? = when {
        s.phase == FreePhase.TO_TANIGAMI -> SANNOMIYA_TO_TANIGAMI_MIN
        s.phase == FreePhase.TO_SHINKAICHI && s.viaSannomiya -> SANNOMIYA_TO_SHINKAICHI_MIN
        s.phase == FreePhase.TO_KITA && s.kitaFrom == StationId.TANIGAMI -> TANIGAMI_TO_KITA_MIN
        else -> null
    }

    private fun withLocation(state: FreeState, e: TripEvent.LocationChanged): FreeState {
        val stay = if (state.stayLat != null && state.stayLon != null) LatLon(state.stayLat, state.stayLon) else null
        val moved = stay == null || state.stayAt == null || stay.distanceTo(e.location) > STAY_RADIUS_M
        return state.copy(
            prevLat = state.lastLat, prevLon = state.lastLon, prevLocAt = state.lastLocAt,
            lastLat = e.location.lat, lastLon = e.location.lon, lastLocAt = e.at.minutes,
            stayLat = if (moved) e.location.lat else state.stayLat,
            stayLon = if (moved) e.location.lon else state.stayLon,
            stayAt = if (moved) e.at.minutes else state.stayAt,
        )
    }

    // ---- 判定の本体 ----

    private fun evaluate(state: FreeState, t: ServiceTime, newFix: Boolean): FreeTransition {
        val acts = mutableListOf<TripAction>()
        val here = fresh(state, t)
        // 近づいているかは、新しい位置が届いたときに、3分以内の1つ前の位置と比べる
        val prev = if (newFix && state.prevLocAt != null && state.lastLocAt != null &&
            state.lastLocAt - state.prevLocAt <= 3
        ) state.prevLocation else null
        val firstFix = newFix && state.prevLocAt == null
        var s = state
        // 駅を過ぎて次の区間に進んだら、同じ位置でもう一度見る（最大4回）
        repeat(4) {
            val before = s.phase to s.target
            s = step(s, here, prev, t, firstFix && it == 0, acts)
            if (s.phase == FreePhase.DONE || (s.phase to s.target) == before) return finish(s, t, acts)
        }
        return finish(s, t, acts)
    }

    private fun finish(s: FreeState, t: ServiceTime, acts: MutableList<TripAction>): FreeTransition {
        if (s.phase == FreePhase.DONE) return FreeTransition(s, acts)
        // 1分後にもう一度見る必要があるとき（起こし直し・乗り過ごし警報・時刻での起こし）は予約する
        val waking = s.preRung && !s.mainRung && !awakeSincePre(s)
        val timed = s.expectedArr != null && !s.mainRung
        if (s.phase == FreePhase.OVERSHOT || waking || timed) {
            acts += TripAction.Schedule(listOf(PlannedAlarm(AlarmKind.FREE_TICK, t + 1, "")))
        }
        return FreeTransition(s, acts)
    }

    private fun step(
        s: FreeState, here: LatLon?, prev: LatLon?, t: ServiceTime, firstFix: Boolean, acts: MutableList<TripAction>,
    ): FreeState = when (s.phase) {
        FreePhase.HEADING -> heading(s, here, prev, t, firstFix, acts)
        FreePhase.AT_SANNOMIYA -> atSannomiya(s, here, t, acts)
        FreePhase.TO_SANNOMIYA, FreePhase.TO_SHINKAICHI, FreePhase.TO_TANIGAMI, FreePhase.TO_KITA ->
            leg(s, here, prev, t, acts)
        FreePhase.OVERSHOT -> overshot(s, here, t, acts)
        FreePhase.DONE -> s
    }

    private fun heading(
        s: FreeState, here: LatLon?, prev: LatLon?, t: ServiceTime, firstFix: Boolean, acts: MutableList<TripAction>,
    ): FreeState {
        here ?: return s
        val dSannomiya = dist(StationId.SANNOMIYA, here)
        val dShinkaichi = dist(StationId.SHINKAICHI, here)
        if (firstFix) {
            // 開始したときにもう駅の近くにいる → その駅は起こさず、その先を見る
            if (s.via != StationId.SHINKAICHI && dSannomiya != null && dSannomiya <= START_NEAR_M) {
                acts += TripAction.Notify("三宮の近くです。谷上方面か新開地方面かは、位置で判断します")
                return s.copy(phase = FreePhase.AT_SANNOMIYA, target = null)
            }
            if (dShinkaichi != null && dShinkaichi <= START_NEAR_M) {
                return newLeg(s, StationId.SHINKAICHI, FreePhase.TO_SHINKAICHI, t)
                    .copy(preRung = true, mainRung = true, minDist = dShinkaichi)
            }
        }
        val candidates = when (s.via) {
            StationId.TANIGAMI -> listOf(StationId.SANNOMIYA)
            StationId.SHINKAICHI -> listOf(StationId.SHINKAICHI)
            else -> listOf(StationId.SANNOMIYA, StationId.SHINKAICHI)
        }
        val next = candidates
            .filter { id -> val d = dist(id, here); d != null && d <= PRE_M && approaching(id, here, prev) }
            .minByOrNull { dist(it, here)!! }
            ?: return s
        val phase = if (next == StationId.SANNOMIYA) FreePhase.TO_SANNOMIYA else FreePhase.TO_SHINKAICHI
        return newLeg(s, next, phase, t)
    }

    /** 三宮から、谷上方面（新神戸へ）か新開地方面（元町へ）かを見る */
    private fun atSannomiya(s: FreeState, here: LatLon?, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        val boarded = s.boardedAt?.takeIf { t.minutes - it <= 5 }
        if (s.via == StationId.TANIGAMI) {
            return newLeg(s, StationId.TANIGAMI, FreePhase.TO_TANIGAMI, t)
                .copy(expectedArr = boarded?.let { it + SANNOMIYA_TO_TANIGAMI_MIN })
        }
        if (s.via == StationId.SHINKAICHI) {
            return newLeg(s, StationId.SHINKAICHI, FreePhase.TO_SHINKAICHI, t)
                .copy(viaSannomiya = true, expectedArr = boarded?.let { it + SANNOMIYA_TO_SHINKAICHI_MIN })
        }
        here ?: return s
        val nearShinKobe = (dist(StationId.SHIN_KOBE, here) ?: Double.MAX_VALUE) <= NEAR_SHIN_KOBE_M
        val towardTanigami = nearShinKobe ||
            closerThanStations(StationId.TANIGAMI, here, from = StationId.SANNOMIYA, by = 1000.0)
        val towardShinkaichi = closerThanStations(StationId.SHINKAICHI, here, from = StationId.SANNOMIYA, by = 800.0)
        return when {
            towardTanigami -> {
                acts += TripAction.Notify("谷上方面（地下鉄）に向かっています。谷上の手前で起こします")
                val expected = boarded?.let { it + SANNOMIYA_TO_TANIGAMI_MIN }
                    ?: if (nearShinKobe) t.minutes + SHIN_KOBE_TO_TANIGAMI_MIN else null
                newLeg(s, StationId.TANIGAMI, FreePhase.TO_TANIGAMI, t).copy(expectedArr = expected)
            }
            towardShinkaichi -> {
                acts += TripAction.Notify("新開地方面に向かっています。新開地の手前で起こします")
                newLeg(s, StationId.SHINKAICHI, FreePhase.TO_SHINKAICHI, t).copy(viaSannomiya = true)
            }
            else -> s
        }
    }

    /** 駅に向かっている区間：予告・本アラーム・起こし直し、そして駅を過ぎたあとの判定 */
    private fun leg(s0: FreeState, here: LatLon?, prev: LatLon?, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        val target = s0.target ?: return s0
        val (preM, mainM) = radii(target)
        var s = s0
        val d = here?.let { dist(target, it) }
        if (d != null) {
            s = s.copy(minDist = min(s.minDist ?: d, d))
            if (!s.mainRung && d <= mainM) {
                s = ringMain(s, target, t, acts)
            } else if (!s.preRung && d <= preM && approaching(target, here, prev)) {
                s = ringPre(s, target, t, d, acts)
            }
        } else if (s.expectedArr != null) {
            // 地下・トンネルで位置が取れない：着く見込みの時刻で起こす
            val left = s.expectedArr - t.minutes
            if (!s.mainRung && left <= TIME_MAIN_MIN) {
                s = ringMain(s, target, t, acts)
            } else if (!s.preRung && left <= TIME_PRE_MIN) {
                s = ringPre(s, target, t, null, acts)
            }
        }
        // 予告のあと「起きた」を押すまで、1分おきに起こし直す（本アラームまで）
        if (s.preRung && !s.mainRung && !awakeSincePre(s) && s.lastRingAt != null && s.lastRingAt < t.minutes) {
            val left = d?.let { "（あと約${km(it)}）" } ?: ""
            acts += TripAction.Ring(RingKind.WAKE, "起きて！まもなく${target.label}$left", preLevel(target))
            s = s.copy(lastRingAt = t.minutes)
        }
        if (here == null || d == null) return s
        return when (s.phase) {
            FreePhase.TO_SANNOMIYA -> {
                val passed = s.mainRung && d >= (s.minDist ?: d) + LEAVING_M
                if (d <= AT_M || passed) {
                    if (s.via == null) acts += TripAction.Notify("三宮です。谷上方面（地下鉄）か新開地方面かは、位置で判断します")
                    s.copy(phase = FreePhase.AT_SANNOMIYA, target = null)
                } else {
                    s
                }
            }
            FreePhase.TO_SHINKAICHI -> afterShinkaichi(s, here, d, t, acts)
            FreePhase.TO_TANIGAMI -> afterTanigami(s, here, d, t, acts)
            FreePhase.TO_KITA -> afterKita(s, here, d, t, acts)
            else -> s
        }
    }

    /** 新開地を過ぎた：神戸電鉄（湊川方面）に乗っていれば北鈴蘭台へ、それ以外（大開・高速神戸方面）は乗り過ごし */
    private fun afterShinkaichi(s: FreeState, here: LatLon, d: Double, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        val minDist = s.minDist ?: d
        val leaving = minDist <= MAIN_M && d > AT_M && d >= minDist + LEAVING_M
        // 北鈴蘭台まで6km以内で、三宮より新開地の方が近い（新神戸のあたりを除く）
        val towardKita = (dist(StationId.KITA_SUZURANDAI, here) ?: Double.MAX_VALUE) <= 6000.0 &&
            d < (dist(StationId.SANNOMIYA, here) ?: Double.MAX_VALUE)
        val onShintetsu = towardKita ||
            (leaving && nearestOf(here, StationId.MINATOGAWA, StationId.DAIKAI, StationId.KOSOKU_KOBE) == StationId.MINATOGAWA)
        if (onShintetsu) {
            acts += TripAction.StopRinging
            acts += TripAction.Notify("神戸電鉄に乗りました。北鈴蘭台の手前で起こします")
            return newLeg(s, StationId.KITA_SUZURANDAI, FreePhase.TO_KITA, t).copy(kitaFrom = StationId.SHINKAICHI)
        }
        return confirmBeyond(s, leaving && s.lastMotion != Motion.WALKING, t, acts)
    }

    /** 谷上（終点）を過ぎた：神戸電鉄で北鈴蘭台へ向かっていれば次へ、三宮方面に戻っていれば乗り過ごし */
    private fun afterTanigami(s: FreeState, here: LatLon, d: Double, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        val minDist = s.minDist ?: d
        // 神戸電鉄（有馬線）は谷上から西へ、地下鉄の折り返しは東（新神戸）へ向かう
        val westOfTanigami = stations[StationId.TANIGAMI]?.let { here.lon < it.lon } == true
        if (d >= LEAVING_M && westOfTanigami &&
            closerThanStations(StationId.KITA_SUZURANDAI, here, from = StationId.TANIGAMI, by = 500.0)
        ) {
            acts += TripAction.StopRinging
            acts += TripAction.Notify("神戸電鉄に乗りました。北鈴蘭台の手前で起こします")
            val boarded = s.boardedAt?.takeIf { t.minutes - it <= 5 }
            return newLeg(s, StationId.KITA_SUZURANDAI, FreePhase.TO_KITA, t).copy(
                kitaFrom = StationId.TANIGAMI,
                expectedArr = (boarded ?: (t.minutes - 1)) + TANIGAMI_TO_KITA_MIN,
            )
        }
        val back = minDist <= MAIN_M && d >= minDist + MAIN_M &&
            (dist(StationId.SHIN_KOBE, here) ?: Double.MAX_VALUE) < d && s.lastMotion != Motion.WALKING
        return confirmBeyond(s, back, t, acts)
    }

    /** 北鈴蘭台：降りたら終了。次の駅（谷上からなら鈴蘭台、新開地からなら山の街）の方が近ければ乗り過ごし */
    private fun afterKita(s: FreeState, here: LatLon, d: Double, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        if (d <= AT_M && offTrain(s, t)) return doneState(s, "おかえりなさい（北鈴蘭台で降りました）", acts)
        val next = if (s.kitaFrom == StationId.TANIGAMI) StationId.SUZURANDAI else StationId.YAMANOMACHI
        val beyond = (s.minDist ?: d) <= KITA_MAIN_M && d > 250.0 &&
            (dist(next, here) ?: Double.MAX_VALUE) < d && s.lastMotion != Motion.WALKING
        return confirmBeyond(s, beyond, t, acts)
    }

    /** 駅を過ぎた位置が2回続いたら乗り過ごし（地下で位置がずれる誤判定を避ける） */
    private fun confirmBeyond(s: FreeState, beyond: Boolean, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        if (!beyond) return s.copy(beyondCount = 0)
        val count = s.beyondCount + 1
        if (count < 2) return s.copy(beyondCount = count)
        return ringOvershoot(s.copy(phase = FreePhase.OVERSHOT, beyondCount = 0, rings = 0), t, acts)
    }

    private fun overshot(s: FreeState, here: LatLon?, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        val target = s.target ?: return s
        val d = here?.let { dist(target, it) }
        // 駅にいると分かった → 誤判定だったので戻す
        if (d != null && d <= AT_M) {
            acts += TripAction.StopRinging
            acts += TripAction.Notify("${target.label}にいるので、乗り過ごしではありませんでした")
            return s.copy(phase = legPhase(target), minDist = d, beyondCount = 0, rings = 0)
        }
        // 降りた（歩いた・同じ場所にとどまっている）→ 戻り方を案内して、戻る途中でまた起こす
        if (offTrain(s, t)) {
            acts += TripAction.StopRinging
            return when (target) {
                StationId.KITA_SUZURANDAI ->
                    doneState(s, "降りたのを確認しました。反対方向の電車で北鈴蘭台へ戻ってください", acts)
                else -> {
                    acts += TripAction.Notify(
                        if (target == StationId.TANIGAMI) "降りたのを確認しました。谷上行きに乗り直してください。谷上の手前でまた起こします"
                        else "降りたのを確認しました。反対方向の電車で新開地に戻ってください。新開地の手前でまた起こします",
                    )
                    newLeg(s, target, legPhase(target), t).copy(viaSannomiya = s.viaSannomiya)
                }
            }
        }
        if (s.lastRingAt != null && s.lastRingAt >= t.minutes) return s
        return ringOvershoot(s, t, acts)
    }

    // ---- 鳴らす ----

    private fun ringPre(s: FreeState, target: StationId, t: ServiceTime, d: Double?, acts: MutableList<TripAction>): FreeState {
        val left = d?.let { "（あと約${km(it)}）" } ?: ""
        val message = when (target) {
            StationId.SANNOMIYA -> "まもなく三宮$left。起きてください（谷上へは地下鉄に乗り換え）"
            StationId.SHINKAICHI -> "まもなく新開地$left。起きてください（神戸電鉄に乗り換え）"
            StationId.TANIGAMI -> "まもなく終点の谷上$left。起きてください（神戸電鉄に乗り換え）"
            else -> "まもなく北鈴蘭台$left。起きてください"
        }
        acts += TripAction.Ring(RingKind.WAKE, message, preLevel(target))
        return s.copy(preRung = true, preAt = t.minutes, lastRingAt = t.minutes)
    }

    private fun ringMain(s: FreeState, target: StationId, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        val message = when (target) {
            StationId.SANNOMIYA -> "三宮に着きます。谷上経由なら降りて地下鉄（谷上行き）へ。新開地経由ならそのまま"
            StationId.SHINKAICHI -> "新開地に着きます。降りて神戸電鉄（北鈴蘭台方面）へ"
            StationId.TANIGAMI -> "終点の谷上に着きます。降りて神戸電鉄（新開地方面）へ乗り換え"
            else -> "北鈴蘭台に着きます。降りてください"
        }
        // 本アラームは音あり（音量最大・強い振動）
        acts += TripAction.Ring(RingKind.WAKE, message, 1)
        return s.copy(preRung = true, mainRung = true, preAt = s.preAt ?: t.minutes, lastRingAt = t.minutes)
    }

    private fun ringOvershoot(s: FreeState, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        val rings = s.rings + 1
        val level = if (rings > SIREN_AFTER_RINGS) SIREN_LEVEL else 1
        val (kind, message) = when (s.target) {
            StationId.TANIGAMI -> RingKind.OVERSHOT_TANIGAMI to
                "谷上で降りていません！三宮方面に戻っています。次の駅で降りて、谷上行きに乗り直してください"
            StationId.KITA_SUZURANDAI -> RingKind.OVERSHOT_KITA to
                "北鈴蘭台を過ぎています！次の駅で降りて、反対方向の電車で戻ってください"
            else -> RingKind.OVERSHOT_SHINKAICHI to
                "新開地を過ぎています！次の駅で降りて、反対方向の電車で新開地へ戻ってください"
        }
        acts += TripAction.Ring(kind, message, level)
        return s.copy(rings = rings, lastRingAt = t.minutes)
    }

    /** 新開地に着くまで（三宮・新開地・谷上の予告）は振動だけ。北鈴蘭台は神戸電鉄に乗り換えたあとなので音あり */
    private fun preLevel(target: StationId) = if (target == StationId.KITA_SUZURANDAI) 1 else VIBRATE_ONLY

    // ---- 補助 ----

    private fun newLeg(s: FreeState, target: StationId, phase: FreePhase, t: ServiceTime) = s.copy(
        phase = phase, target = target,
        preRung = false, mainRung = false, preAt = null, minDist = null, beyondCount = 0,
        expectedArr = null, rings = 0, viaSannomiya = false,
    )

    private fun legPhase(target: StationId) = when (target) {
        StationId.SANNOMIYA -> FreePhase.TO_SANNOMIYA
        StationId.TANIGAMI -> FreePhase.TO_TANIGAMI
        StationId.KITA_SUZURANDAI -> FreePhase.TO_KITA
        else -> FreePhase.TO_SHINKAICHI
    }

    private fun radii(target: StationId) =
        if (target == StationId.KITA_SUZURANDAI) KITA_PRE_M to KITA_MAIN_M else PRE_M to MAIN_M

    private fun awakeSincePre(s: FreeState): Boolean {
        val awake = s.awakeAt ?: return false
        val pre = s.preAt ?: return false
        return awake >= pre
    }

    /** 降りているとみなせるか：2分以内に歩いた、または同じ場所に [AlarmSettings.offTrainStayMinutes] 分以上（乗り物でない） */
    private fun offTrain(s: FreeState, t: ServiceTime): Boolean {
        if (s.walkedAt != null && t.minutes - s.walkedAt <= 2 && s.lastMotion == Motion.WALKING) return true
        val since = s.stayAt ?: return false
        if (fresh(s, t) == null) return false
        return t.minutes - since >= settings.offTrainStayMinutes && s.lastMotion != Motion.IN_VEHICLE
    }

    private fun fresh(s: FreeState, t: ServiceTime): LatLon? {
        val at = s.lastLocAt ?: return null
        return if (t.minutes - at <= FRESH_MIN) s.lastLocation else null
    }

    private fun dist(id: StationId, here: LatLon): Double? = stations[id]?.distanceTo(here)

    private fun approaching(id: StationId, here: LatLon, prev: LatLon?): Boolean {
        prev ?: return false
        val now = dist(id, here) ?: return false
        val before = dist(id, prev) ?: return false
        return before - now >= APPROACH_M
    }

    /** [target] まで、駅 [from] からの距離より [by] m 以上近い（[from] から [target] の方へ進んでいる） */
    private fun closerThanStations(target: StationId, here: LatLon, from: StationId, by: Double): Boolean {
        val a = stations[from] ?: return false
        val b = stations[target] ?: return false
        return here.distanceTo(b) <= a.distanceTo(b) - by
    }

    private fun nearestOf(here: LatLon, vararg ids: StationId): StationId? =
        ids.filter { stations[it] != null }.minByOrNull { dist(it, here)!! }

    private fun km(m: Double) = if (m >= 1000) "%.1fkm".format(m / 1000) else "${(m / 100).toInt() * 100}m"

    private fun doneState(s: FreeState, message: String, acts: MutableList<TripAction>): FreeState {
        acts += TripAction.StopRinging
        acts += TripAction.Notify(message)
        acts += TripAction.End
        return s.copy(phase = FreePhase.DONE)
    }

    private fun done(s: FreeState, message: String): FreeTransition {
        val acts = mutableListOf<TripAction>()
        return FreeTransition(doneState(s, message, acts), acts)
    }
}
