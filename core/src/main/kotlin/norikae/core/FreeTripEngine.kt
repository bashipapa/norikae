package norikae.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.ceil
import kotlin.math.min

/**
 * どこからでもモード（大阪などから帰るとき）。時刻表は使わず、位置と「歩いた／乗った」の動きで起こす。
 *
 * 帰り道は2通り：
 *  - 三宮で地下鉄に乗り換え → 谷上（終点）で神戸電鉄に乗り換え → 北鈴蘭台
 *  - 新開地で神戸電鉄に乗り換え → 北鈴蘭台
 *
 * 基本の考え方（寝過ごさないために「鳴らない」状況を作らない）：
 *  1. 位置が取れているときは距離で起こす（予告 → 本アラーム）。
 *  2. 位置が取れない間（地下・トンネル）は、最後の位置から見込んだ到着時刻で起こす。
 *  3. 着いたはずの時刻から [CHECK_AFTER_MIN] 分たっても「歩いた」が検知できなければ、
 *     降りたと確認できるまで毎分鳴らす（[SIREN_AFTER_RINGS] 回を超えたらサイレン）。
 *     「○○で降りた」ボタン・歩行の検知・正しい次の路線にいる位置、のどれかで止まる。
 */
enum class FreePhase {
    /** 三宮か新開地に向かっている（まだ区間に入っていない） */
    HEADING,
    /** 三宮に近づいている */
    TO_SANNOMIYA,
    /** 三宮に着いた。降りて地下鉄に乗ったか、そのまま新開地へ向かっているかを見ている */
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
    /** 三宮から新開地に向かっている */
    val viaSannomiya: Boolean = false,
    val preRung: Boolean = false,
    val mainRung: Boolean = false,
    /** 予告・本アラームを鳴らした時刻 */
    val preAt: Int? = null,
    val mainAt: Int? = null,
    /** 向かっている駅に一番近づいた距離（m） */
    val minDist: Double? = null,
    /** 駅を過ぎた（離れていく）位置が続いた回数。誤判定を避けるため2回続いたら乗り過ごしにする */
    val beyondCount: Int = 0,
    /** 着く見込みの時刻。位置が取れないときは、この時刻で起こす。位置が来るたびに見直す */
    val expectedArr: Int? = null,
    /** 向かっている駅に着いた（とみなした）時刻 */
    val arrivedAt: Int? = null,
    /** 着いたあと、降りたと確認できた時刻（歩行の検知・ボタン） */
    val offConfirmedAt: Int? = null,
    /** 三宮で降りた（歩いた・ボタン） */
    val offAtSannomiya: Boolean = false,
    /** 乗り換えで歩いたあと、乗り物に乗った時刻 */
    val boardedAt: Int? = null,
    /** 乗り過ごしのあと降りて戻るとき、降りた場所から駅までの距離。戻りの本アラームはこれより近づいてから */
    val returnFromM: Double? = null,
    val awakeAt: Int? = null,
    val lastRingAt: Int? = null,
    /** 乗り過ごし警報・降りたかの確認を鳴らした回数（区間ごと）。超えたらサイレン */
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
        /** 駅に向かう区間に入る距離。ここから到着見込みの時刻を出し始める */
        const val LEG_ENTER_M = 10_000.0
        /** 一番近づいた距離からこれだけ離れたら「駅を離れた」 */
        const val LEAVING_M = 500.0
        /** 着いたはずのあと、これだけ離れた位置なら乗り過ごし */
        const val GONE_M = 1500.0
        /** 着く前に、一番近づいた距離からこれだけ離れていったら「反対方向かも」 */
        const val RECEDE_M = 1000.0
        const val NEAR_SHIN_KOBE_M = 600.0
        /** これより古い位置は使わない（分） */
        const val FRESH_MIN = 2
        /** 開始から4時間で自動終了（止め忘れ対策） */
        const val MAX_TRIP_MIN = 240
        /** 乗り換えで歩いてからこの分数以内に乗り物に乗ったら、乗り換えたとみなす */
        const val BOARD_AFTER_WALK_MIN = 20

        /** 到着見込みに使う速さ（m/分）。阪神・阪急・JR・地下鉄は36km/h、神戸電鉄は坂で遅いので24km/h */
        const val SPEED_M_PER_MIN = 600.0
        const val SHINTETSU_SPEED_M_PER_MIN = 400.0

        // 所要時間（Yahoo!路線情報で確認、2026-11-19）
        const val SANNOMIYA_TO_TANIGAMI_MIN = 10
        const val SHIN_KOBE_TO_TANIGAMI_MIN = 7
        /** 三宮に着いてから新開地に着くまで（停車1分 + 7分） */
        const val SANNOMIYA_TO_SHINKAICHI_MIN = 8
        const val TANIGAMI_TO_KITA_MIN = 8
        /** 新開地で神戸電鉄に乗ってから北鈴蘭台まで（時刻表では約19分） */
        const val SHINKAICHI_TO_KITA_MIN = 19

        /** 時刻での起こし：着く見込みの何分前に予告・本アラーム */
        const val TIME_PRE_MIN = 3
        const val TIME_MAIN_MIN = 1
        /** 着いたはずのあと、この分数たっても「歩いた」が無ければ、降りたか確認のアラームを鳴らし始める */
        const val CHECK_AFTER_MIN = 3
        /** おまかせのとき：三宮に着いて何分たっても降りた動きが無ければ、そのまま新開地へ向かっているとみなす */
        const val ASSUME_RIDING_ON_MIN = 3
        /** 三宮経由のとき：三宮に着いて何分たっても降りた動きが無ければ、新開地での起こしも入れる（確認アラームは続ける） */
        const val SANNOMIYA_GIVE_UP_MIN = 6
        /** 三宮で歩いたあと乗った動きが取れないとき、歩いてからこの分数で地下鉄に乗ったとみなす */
        const val WALK_TO_BOARD_FALLBACK_MIN = 5

        const val VIBRATE_ONLY = 0
        const val SIREN_AFTER_RINGS = 10
        const val SIREN_LEVEL = 3
        const val STAY_RADIUS_M = 150.0

        private val WAKE_TARGETS = setOf(StationId.SANNOMIYA, StationId.SHINKAICHI, StationId.TANIGAMI, StationId.KITA_SUZURANDAI)
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

    /** 「降りた」ボタンの文言（今の区間で降りる駅） */
    fun offButtonLabel(state: FreeState): String? = when (state.phase) {
        FreePhase.TO_SANNOMIYA, FreePhase.AT_SANNOMIYA -> if (state.offAtSannomiya) null else "三宮で降りた（地下鉄で谷上へ）"
        FreePhase.TO_SHINKAICHI -> "新開地で降りた（神戸電鉄へ）"
        FreePhase.TO_TANIGAMI -> "谷上で降りた（神戸電鉄へ）"
        FreePhase.TO_KITA -> "北鈴蘭台で降りた（終了）"
        FreePhase.OVERSHOT -> state.target?.let { "${it.label}に戻って降りた" }
        FreePhase.HEADING, FreePhase.DONE -> null
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
            is TripEvent.UserTransferring -> userGotOff(state, t)
            is TripEvent.UserReturning -> FreeTransition(state, emptyList())
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
                if (via == StationId.SHINKAICHI) s = newLeg(s, StationId.SHINKAICHI, FreePhase.TO_SHINKAICHI)
            FreePhase.AT_SANNOMIYA -> Unit // evaluate で谷上・新開地へ進める
            else -> return FreeTransition(state, emptyList())
        }
        acts += TripAction.Notify("乗り換え：${viaLabel(via)}")
        val next = evaluate(s, t, newFix = false)
        return FreeTransition(next.state, acts + next.actions)
    }

    /** 「○○で降りた」ボタン：今の駅で降りたことにして、次の区間へ */
    private fun userGotOff(state: FreeState, t: ServiceTime): FreeTransition {
        val acts = mutableListOf<TripAction>(TripAction.StopRinging)
        val s = when (state.phase) {
            FreePhase.TO_SANNOMIYA, FreePhase.AT_SANNOMIYA -> state.copy(
                phase = FreePhase.AT_SANNOMIYA, target = StationId.SANNOMIYA,
                arrivedAt = state.arrivedAt ?: t.minutes, offConfirmedAt = t.minutes, offAtSannomiya = true,
                via = if (state.via == null) StationId.TANIGAMI else state.via, rings = 0, lastRingAt = null,
            )
            FreePhase.TO_SHINKAICHI, FreePhase.TO_TANIGAMI, FreePhase.OVERSHOT ->
                when (state.target) {
                    StationId.SHINKAICHI -> toKita(state, StationId.SHINKAICHI, t.minutes + SHINKAICHI_TO_KITA_MIN, acts)
                    StationId.TANIGAMI -> toKita(state, StationId.TANIGAMI, t.minutes + TANIGAMI_TO_KITA_MIN + 2, acts)
                    StationId.KITA_SUZURANDAI -> return done(state, "おかえりなさい")
                    else -> state.copy(
                        phase = FreePhase.AT_SANNOMIYA, target = StationId.SANNOMIYA, arrivedAt = state.arrivedAt ?: t.minutes,
                        offConfirmedAt = t.minutes, offAtSannomiya = true, rings = 0, lastRingAt = null,
                    )
                }
            FreePhase.TO_KITA -> return done(state, "おかえりなさい")
            FreePhase.HEADING, FreePhase.DONE -> return FreeTransition(state, emptyList())
        }
        val next = evaluate(s, t, newFix = false)
        return FreeTransition(next.state, acts + next.actions)
    }

    private fun onMotion(state: FreeState, motion: Motion, t: ServiceTime): FreeTransition {
        var s = state.copy(lastMotion = motion, walkedAt = if (motion == Motion.WALKING) t.minutes else state.walkedAt)
        // 乗り換えで歩いたあとに乗り物に乗った
        if (motion == Motion.IN_VEHICLE && state.walkedAt != null && t.minutes - state.walkedAt <= BOARD_AFTER_WALK_MIN) {
            s = s.copy(boardedAt = t.minutes)
        }
        return evaluate(s, t, newFix = false)
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
            val before = Triple(s.phase, s.target, s.arrivedAt)
            s = step(s, here, prev, t, firstFix && it == 0, newFix && it == 0, acts)
            if (s.phase == FreePhase.DONE || Triple(s.phase, s.target, s.arrivedAt) == before) return finish(s, t, acts)
        }
        return finish(s, t, acts)
    }

    private fun finish(s: FreeState, t: ServiceTime, acts: MutableList<TripAction>): FreeTransition {
        if (s.phase == FreePhase.DONE) return FreeTransition(s, acts)
        // 区間に入ったら、位置が来なくても1分おきに見直す（時刻での起こし・起こし直し・確認・乗り過ごし警報のため）
        if (s.phase != FreePhase.HEADING) {
            acts += TripAction.Schedule(listOf(PlannedAlarm(AlarmKind.FREE_TICK, t + 1, "")))
        }
        return FreeTransition(s, acts)
    }

    private fun step(
        s: FreeState, here: LatLon?, prev: LatLon?, t: ServiceTime, firstFix: Boolean, newFix: Boolean, acts: MutableList<TripAction>,
    ): FreeState = when (s.phase) {
        FreePhase.HEADING -> heading(s, here, prev, t, firstFix, acts)
        FreePhase.AT_SANNOMIYA -> atSannomiya(s, here, t, acts)
        FreePhase.TO_SANNOMIYA, FreePhase.TO_SHINKAICHI, FreePhase.TO_TANIGAMI, FreePhase.TO_KITA ->
            leg(s, here, prev, t, newFix, acts)
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
                acts += TripAction.Notify("三宮の近くです。降りて地下鉄に乗るか、そのまま新開地へ向かうかを見ています")
                return s.copy(phase = FreePhase.AT_SANNOMIYA, target = StationId.SANNOMIYA, arrivedAt = t.minutes)
            }
            if (dShinkaichi != null && dShinkaichi <= START_NEAR_M) {
                return newLeg(s, StationId.SHINKAICHI, FreePhase.TO_SHINKAICHI)
                    .copy(preRung = true, mainRung = true, mainAt = t.minutes, minDist = dShinkaichi, arrivedAt = t.minutes)
            }
        }
        val candidates = when (s.via) {
            StationId.TANIGAMI -> listOf(StationId.SANNOMIYA)
            StationId.SHINKAICHI -> listOf(StationId.SHINKAICHI)
            else -> listOf(StationId.SANNOMIYA, StationId.SHINKAICHI)
        }
        // 10km以内で近づいている（または1km以内）駅に向かっているとみなす。ここから到着見込みを出し始める
        val next = candidates
            .filter { id ->
                val d = dist(id, here)
                d != null && d <= LEG_ENTER_M && (approaching(id, here, prev) || d <= MAIN_M)
            }
            .minByOrNull { dist(it, here)!! }
            ?: return s
        val phase = if (next == StationId.SANNOMIYA) FreePhase.TO_SANNOMIYA else FreePhase.TO_SHINKAICHI
        return newLeg(s, next, phase)
    }

    /**
     * 三宮に着いたあと。谷上方面（地下鉄）か新開地方面かを、位置と「歩いた／乗った」で見る。
     * 位置が無く動きも無いまま時間がたったら、乗ったまま新開地へ向かっているとみなして起こす（寝ている可能性が高い）。
     */
    private fun atSannomiya(s0: FreeState, here: LatLon?, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        var s = s0
        val arrivedAt = s.arrivedAt ?: t.minutes
        if (s.arrivedAt == null) s = s.copy(arrivedAt = arrivedAt)
        // 新開地経由に変えた（三宮では降りない）→ すぐ新開地の区間へ
        if (s.via == StationId.SHINKAICHI) return towardShinkaichiFromSannomiya(s, t, acts, arrivedAt, byLocation = false)
        // 歩いた → 三宮で降りた
        if (!s.offAtSannomiya && s.walkedAt != null && s.walkedAt >= arrivedAt - 1) {
            s = s.copy(offAtSannomiya = true, offConfirmedAt = s.walkedAt, rings = 0)
            acts += TripAction.StopRinging
            if (s.via == null) acts += TripAction.Notify("三宮で降りました。地下鉄で谷上へ向かうとみなします（谷上の手前で起こします）")
        }
        if (here != null) {
            val nearShinKobe = (dist(StationId.SHIN_KOBE, here) ?: Double.MAX_VALUE) <= NEAR_SHIN_KOBE_M
            val towardTanigami = nearShinKobe ||
                closerThanStations(StationId.TANIGAMI, here, from = StationId.SANNOMIYA, by = 1000.0)
            val towardShinkaichi = closerThanStations(StationId.SHINKAICHI, here, from = StationId.SANNOMIYA, by = 800.0)
            if (towardTanigami) {
                acts += TripAction.StopRinging
                acts += TripAction.Notify("谷上方面（地下鉄）に向かっています。谷上の手前で起こします")
                val expected = s.boardedAt?.takeIf { t.minutes - it <= 5 }?.let { it + SANNOMIYA_TO_TANIGAMI_MIN }
                    ?: if (nearShinKobe) t.minutes + SHIN_KOBE_TO_TANIGAMI_MIN else t.minutes + SANNOMIYA_TO_TANIGAMI_MIN - 2
                return newLeg(s, StationId.TANIGAMI, FreePhase.TO_TANIGAMI).copy(expectedArr = expected)
            }
            if (towardShinkaichi && s.lastMotion != Motion.WALKING) {
                return towardShinkaichiFromSannomiya(s, t, acts, arrivedAt, byLocation = true)
            }
        }
        if (s.offAtSannomiya) {
            // 降りて地下鉄に乗った（乗ったのを検知、または歩いてから時間がたった）
            val walked = s.offConfirmedAt ?: s.walkedAt ?: arrivedAt
            val boarded = s.boardedAt?.takeIf { it >= walked }
                ?: (walked + WALK_TO_BOARD_FALLBACK_MIN).takeIf { t.minutes >= it }
                ?: return s
            acts += TripAction.Notify(
                if (s.boardedAt != null && s.boardedAt >= walked) "地下鉄に乗りました。谷上の手前で起こします（位置が取れない間は時刻で推定）"
                else "地下鉄に乗ったとみなします。谷上の手前で起こします（時刻で推定）",
            )
            return newLeg(s, StationId.TANIGAMI, FreePhase.TO_TANIGAMI).copy(expectedArr = boarded + SANNOMIYA_TO_TANIGAMI_MIN)
        }
        // 降りた動きが無い
        val waited = t.minutes - arrivedAt
        if (s.via == null && waited >= ASSUME_RIDING_ON_MIN) {
            acts += TripAction.Notify("三宮で降りた動きが無いので、そのまま新開地へ向かっているとみなします。新開地の手前で起こします")
            return towardShinkaichiFromSannomiya(s, t, acts, arrivedAt, byLocation = false)
        }
        if (s.via == StationId.TANIGAMI && waited >= CHECK_AFTER_MIN) {
            // 三宮経由のはずなのに降りた動きが無い → 降りたと確認できるまで毎分鳴らす
            s = ringCheck(s, "三宮で降りましたか？降りていないなら次の駅で降りて戻るか、新開地で神戸電鉄に乗り換えてください。降りていれば「三宮で降りた」を押してください", t, acts)
            if (waited >= SANNOMIYA_GIVE_UP_MIN) {
                acts += TripAction.Notify("三宮で降りた動きが無いので、新開地でも起こします")
                return towardShinkaichiFromSannomiya(s, t, acts, arrivedAt, byLocation = false).copy(rings = s.rings)
            }
        }
        return s
    }

    /** 三宮を過ぎて新開地へ向かっている。三宮経由のつもりだったなら乗り過ごしとして鳴らし、新開地で起こす */
    private fun towardShinkaichiFromSannomiya(
        s: FreeState, t: ServiceTime, acts: MutableList<TripAction>, arrivedAt: Int, byLocation: Boolean,
    ): FreeState {
        if (s.via == StationId.TANIGAMI && !s.offAtSannomiya && byLocation) {
            acts += TripAction.Ring(
                RingKind.OVERSHOT_SANNOMIYA,
                "三宮で降りていません！そのまま新開地で降りて、神戸電鉄に乗り換えてください（JRなら神戸駅で降りて高速神戸・新開地へ）",
                1,
            )
        } else if (byLocation) {
            acts += TripAction.Notify("新開地方面に向かっています。新開地の手前で起こします")
        }
        return newLeg(s, StationId.SHINKAICHI, FreePhase.TO_SHINKAICHI).copy(
            via = StationId.SHINKAICHI, viaSannomiya = true,
            // 三宮に着いてから8分。遅れて切り替えたときも、最低2分は先にする
            expectedArr = maxOf(arrivedAt + SANNOMIYA_TO_SHINKAICHI_MIN, t.minutes + 2),
            lastRingAt = if (byLocation && s.via == StationId.TANIGAMI) t.minutes else s.lastRingAt,
        )
    }

    /** 駅に向かっている区間：到着見込み・予告・本アラーム・起こし直し、着いたあとの判定 */
    private fun leg(
        s0: FreeState, here: LatLon?, prev: LatLon?, t: ServiceTime, newFix: Boolean, acts: MutableList<TripAction>,
    ): FreeState {
        val target = s0.target ?: return s0
        val (preM, mainM) = radii(target)
        var s = s0
        val d = here?.let { dist(target, it) }
        if (d != null) {
            s = s.copy(minDist = min(s.minDist ?: d, d))
            // 新しい位置が来るたびに到着見込みを見直す（位置が途切れたらこの時刻で起こす）。
            // 1分おきの見直しでは見直さない（同じ位置で見込みが後ろにずれていくのを防ぐ）
            if (newFix && s.arrivedAt == null && d <= LEG_ENTER_M) s = s.copy(expectedArr = t.minutes + etaMinutes(target, here, d))
            val mainLimit = s.returnFromM?.let { min(mainM, it - 300.0) } ?: mainM
            if (!s.mainRung && d <= mainLimit) {
                s = ringMain(s, target, t, acts, estimated = false)
            } else if (!s.preRung && d <= preM && approaching(target, here, prev)) {
                s = ringPre(s, target, t, d, acts, estimated = false)
            }
        } else if (s.expectedArr != null && s.arrivedAt == null) {
            // 位置が取れない：着く見込みの時刻で起こす
            val left = s.expectedArr - t.minutes
            if (!s.mainRung && left <= TIME_MAIN_MIN) {
                s = ringMain(s, target, t, acts, estimated = true)
            } else if (!s.preRung && left <= TIME_PRE_MIN) {
                s = ringPre(s, target, t, null, acts, estimated = true)
            }
        }
        // 予告のあと「起きた」を押すまで、1分おきに起こし直す（本アラームまで）。戻りの区間（予告なし）では起こし直さない
        if (s.preRung && s.preAt != null && !s.mainRung && !awakeSincePre(s) && s.lastRingAt != null && s.lastRingAt < t.minutes) {
            val left = d?.let { "（あと約${km(it)}）" } ?: ""
            acts += TripAction.Ring(RingKind.WAKE, "起きて！まもなく${target.label}$left", preLevel(target))
            s = s.copy(lastRingAt = t.minutes)
        }
        // 着いた：駅の近くにいる、または位置が無いまま見込みの時刻になった
        if (s.arrivedAt == null) {
            val arrived = (d != null && d <= AT_M) ||
                (s.expectedArr != null && t.minutes >= s.expectedArr && (d == null || d <= GONE_M))
            if (arrived) s = s.copy(arrivedAt = t.minutes)
        }
        // 着く前に、一番近づいた距離から離れていっている → 反対方向の電車かもしれない
        if (s.arrivedAt == null && d != null && prev != null && s.minDist != null && d >= s.minDist + RECEDE_M &&
            receding(target, here, prev) && s.lastMotion != Motion.WALKING && (s.lastRingAt ?: -1) < t.minutes
        ) {
            acts += TripAction.Ring(RingKind.WRONG_LINE, "${target.label}から離れていっています（あと約${km(d)}）。反対方向の電車に乗っていませんか？", 1)
            s = s.copy(lastRingAt = t.minutes)
        }
        return when (s.phase) {
            FreePhase.TO_SANNOMIYA -> {
                val passed = d != null && s.mainRung && d >= (s.minDist ?: d) + LEAVING_M
                if (s.arrivedAt != null || passed) {
                    s.copy(phase = FreePhase.AT_SANNOMIYA, arrivedAt = s.arrivedAt ?: t.minutes)
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

    /** 着いたあと「降りた」と確認できた時刻：着いたころ以降に歩いた、またはボタン。確認できていなければ null */
    private fun offConfirmedAt(s: FreeState): Int? {
        s.offConfirmedAt?.let { return it }
        val arrived = s.arrivedAt ?: return null
        return s.walkedAt?.takeIf { it >= arrived - 1 }
    }

    private fun offConfirmed(s: FreeState): Boolean = offConfirmedAt(s) != null

    /** この駅で降りたと確認できたあとに乗り物に乗った（次の電車に乗った）時刻 */
    private fun boardedAfterOff(s: FreeState): Int? {
        val off = offConfirmedAt(s) ?: return null
        return s.boardedAt?.takeIf { it >= off }
    }

    /**
     * 着いたはずなのに降りたと確認できない → 確認のアラーム（音）を毎分。
     * 位置が無い地下では、これが寝過ごしを止める最後の手段になる。
     */
    private fun checkIfNotOff(s: FreeState, t: ServiceTime, acts: MutableList<TripAction>, message: String): FreeState {
        val arrived = s.arrivedAt ?: return s
        if (offConfirmed(s) || t.minutes - arrived < CHECK_AFTER_MIN) return s
        return ringCheck(s, message, t, acts)
    }

    /** 新開地：神戸電鉄（湊川方面）に乗っていれば北鈴蘭台へ。大開・高速神戸方面に離れていけば乗り過ごし */
    private fun afterShinkaichi(s0: FreeState, here: LatLon?, d: Double?, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        var s = s0
        if (here != null && d != null) {
            val minDist = s.minDist ?: d
            val leaving = minDist <= MAIN_M && d > AT_M && d >= minDist + LEAVING_M
            // 北鈴蘭台まで6km以内で、三宮より新開地の方が近い（新神戸のあたりを除く）
            val towardKita = (dist(StationId.KITA_SUZURANDAI, here) ?: Double.MAX_VALUE) <= 6000.0 &&
                d < (dist(StationId.SANNOMIYA, here) ?: Double.MAX_VALUE)
            val onShintetsu = towardKita ||
                (leaving && nearestOf(here, StationId.MINATOGAWA, StationId.DAIKAI, StationId.KOSOKU_KOBE) == StationId.MINATOGAWA)
            if (onShintetsu) {
                acts += TripAction.Notify("神戸電鉄に乗りました。北鈴蘭台の手前で起こします")
                return toKita(s, StationId.SHINKAICHI, t.minutes + SHINKAICHI_TO_KITA_MIN - 2, acts)
            }
            // 着いたはずのあと、1.5km以上離れた位置（新開地の反対側へ）→ 乗り過ごし
            val gone = s.arrivedAt != null && !offConfirmed(s) && d >= GONE_M
            val beyond = (leaving || gone) && s.lastMotion != Motion.WALKING
            s = confirmBeyond(s, beyond, t, acts)
            if (s.phase == FreePhase.OVERSHOT) return s
        }
        // 降りて神戸電鉄に乗った（位置が取れなくても、歩いた→乗った で進める）
        boardedAfterOff(s)?.let { boarded ->
            acts += TripAction.Notify("神戸電鉄に乗りました。北鈴蘭台の手前で起こします")
            return toKita(s, StationId.SHINKAICHI, boarded + SHINKAICHI_TO_KITA_MIN, acts)
        }
        return checkIfNotOff(s, t, acts, "新開地に着いているはずです。降りましたか？降りていなければ次の駅で降りて戻ってください。降りていれば「新開地で降りた」を押してください")
    }

    /** 谷上（終点）：神戸電鉄で北鈴蘭台へ向かっていれば次へ。三宮方面に戻っていれば乗り過ごし */
    private fun afterTanigami(s0: FreeState, here: LatLon?, d: Double?, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        var s = s0
        if (here != null && d != null) {
            val minDist = s.minDist ?: d
            // 神戸電鉄（有馬線）は谷上から西へ、地下鉄の折り返しは東（新神戸）へ向かう
            val westOfTanigami = stations[StationId.TANIGAMI]?.let { here.lon < it.lon } == true
            if (d >= LEAVING_M && westOfTanigami &&
                closerThanStations(StationId.KITA_SUZURANDAI, here, from = StationId.TANIGAMI, by = 500.0)
            ) {
                acts += TripAction.Notify("神戸電鉄に乗りました。北鈴蘭台の手前で起こします")
                val boarded = boardedAfterOff(s) ?: (t.minutes - 1)
                return toKita(s, StationId.TANIGAMI, boarded + TANIGAMI_TO_KITA_MIN, acts)
            }
            val back = (minDist <= MAIN_M || s.arrivedAt != null) && d >= min(minDist, AT_M) + MAIN_M &&
                (dist(StationId.SHIN_KOBE, here) ?: Double.MAX_VALUE) < d && s.lastMotion != Motion.WALKING
            s = confirmBeyond(s, back, t, acts)
            if (s.phase == FreePhase.OVERSHOT) return s
        }
        boardedAfterOff(s)?.let { boarded ->
            acts += TripAction.Notify("神戸電鉄に乗りました。北鈴蘭台の手前で起こします")
            return toKita(s, StationId.TANIGAMI, boarded + TANIGAMI_TO_KITA_MIN, acts)
        }
        return checkIfNotOff(s, t, acts, "終点の谷上に着いているはずです。降りましたか？このまま乗っていると三宮に戻ります。降りていれば「谷上で降りた」を押してください")
    }

    /** 北鈴蘭台：降りたら終了。次の駅（谷上からなら鈴蘭台、新開地からなら山の街）の方が近ければ乗り過ごし */
    private fun afterKita(s0: FreeState, here: LatLon?, d: Double?, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        var s = s0
        if (here != null && d != null) {
            if (d <= AT_M && offTrain(s, t)) return doneState(s, "おかえりなさい（北鈴蘭台で降りました）", acts)
            val next = if (s.kitaFrom == StationId.TANIGAMI) StationId.SUZURANDAI else StationId.YAMANOMACHI
            val nextCloser = (dist(next, here) ?: Double.MAX_VALUE) < d
            val beyond = ((s.minDist ?: d) <= KITA_MAIN_M && d > 250.0 && nextCloser ||
                s.arrivedAt != null && !offConfirmed(s) && d >= GONE_M) && s.lastMotion != Motion.WALKING
            s = confirmBeyond(s, beyond, t, acts)
            if (s.phase == FreePhase.OVERSHOT) return s
        } else if (offConfirmed(s) && s.arrivedAt != null && t.minutes - s.arrivedAt >= 2) {
            // 位置は無いが、着いたあと歩いた → 降りた
            return doneState(s, "おかえりなさい（北鈴蘭台で降りたとみなしました）", acts)
        }
        if (offConfirmed(s) && s.arrivedAt != null && here != null && d != null && d <= GONE_M) {
            return doneState(s, "おかえりなさい（北鈴蘭台で降りました）", acts)
        }
        return checkIfNotOff(s, t, acts, "北鈴蘭台に着いているはずです。降りましたか？降りていなければ次の駅で降りて戻ってください")
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
            return s.copy(phase = legPhase(target), minDist = d, beyondCount = 0, rings = 0, arrivedAt = s.arrivedAt ?: t.minutes)
        }
        // 降りた（歩いた・同じ場所にとどまっている）→ 戻り方を案内して、戻る途中でまた起こす
        if (offTrain(s, t)) {
            acts += TripAction.StopRinging
            return when (target) {
                StationId.KITA_SUZURANDAI ->
                    doneState(s, "降りたのを確認しました。反対方向の電車で北鈴蘭台へ戻ってください", acts)
                else -> {
                    acts += TripAction.Notify(
                        when (target) {
                            StationId.TANIGAMI -> "降りたのを確認しました。谷上行きに乗り直してください。谷上の手前でまた起こします"
                            StationId.SANNOMIYA -> "降りたのを確認しました。三宮に戻ってください。三宮の手前でまた起こします"
                            else -> "降りたのを確認しました。反対方向の電車で新開地に戻ってください。新開地の手前でまた起こします"
                        },
                    )
                    newLeg(s, target, legPhase(target)).copy(viaSannomiya = s.viaSannomiya, preRung = true, returnFromM = d)
                }
            }
        }
        if (s.lastRingAt != null && s.lastRingAt >= t.minutes) return s
        return ringOvershoot(s, t, acts)
    }

    // ---- 鳴らす ----

    private fun ringPre(
        s: FreeState, target: StationId, t: ServiceTime, d: Double?, acts: MutableList<TripAction>, estimated: Boolean,
    ): FreeState {
        val left = d?.let { "（あと約${km(it)}）" } ?: if (estimated) "（位置が取れないため時刻で推定）" else ""
        val message = when (target) {
            StationId.SANNOMIYA -> "まもなく三宮$left。起きてください（谷上へは地下鉄に乗り換え）"
            StationId.SHINKAICHI -> "まもなく新開地$left。起きてください（神戸電鉄に乗り換え）"
            StationId.TANIGAMI -> "まもなく終点の谷上$left。起きてください（神戸電鉄に乗り換え）"
            else -> "まもなく北鈴蘭台$left。起きてください"
        }
        acts += TripAction.Ring(RingKind.WAKE, message, preLevel(target))
        return s.copy(preRung = true, preAt = t.minutes, lastRingAt = t.minutes)
    }

    private fun ringMain(s: FreeState, target: StationId, t: ServiceTime, acts: MutableList<TripAction>, estimated: Boolean): FreeState {
        val note = if (estimated) "（位置が取れないため時刻で推定）" else ""
        val message = when (target) {
            StationId.SANNOMIYA -> "三宮に着きます$note。谷上経由なら降りて地下鉄（谷上行き）へ。新開地経由ならそのまま"
            StationId.SHINKAICHI -> "新開地に着きます$note。降りて神戸電鉄（北鈴蘭台方面）へ"
            StationId.TANIGAMI -> "終点の谷上に着きます$note。降りて神戸電鉄（新開地方面）へ乗り換え"
            else -> "北鈴蘭台に着きます$note。降りてください"
        }
        // 本アラームは音あり（音量最大・強い振動）
        acts += TripAction.Ring(RingKind.WAKE, message, 1)
        return s.copy(preRung = true, mainRung = true, preAt = s.preAt ?: t.minutes, mainAt = t.minutes, lastRingAt = t.minutes)
    }

    /** 降りたかの確認（音）。毎分、確認できるまで。回数を超えたらサイレン */
    private fun ringCheck(s: FreeState, message: String, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        if (s.lastRingAt != null && s.lastRingAt >= t.minutes) return s
        val rings = s.rings + 1
        acts += TripAction.Ring(RingKind.CHECK, message, if (rings > SIREN_AFTER_RINGS) SIREN_LEVEL else 1)
        return s.copy(rings = rings, lastRingAt = t.minutes)
    }

    private fun ringOvershoot(s: FreeState, t: ServiceTime, acts: MutableList<TripAction>): FreeState {
        val rings = s.rings + 1
        val level = if (rings > SIREN_AFTER_RINGS) SIREN_LEVEL else 1
        val (kind, message) = when (s.target) {
            StationId.TANIGAMI -> RingKind.OVERSHOT_TANIGAMI to
                "谷上で降りていません！三宮方面に戻っています。次の駅で降りて、谷上行きに乗り直してください"
            StationId.KITA_SUZURANDAI -> RingKind.OVERSHOT_KITA to
                "北鈴蘭台を過ぎています！次の駅で降りて、反対方向の電車で戻ってください"
            StationId.SANNOMIYA -> RingKind.OVERSHOT_SANNOMIYA to
                "三宮を過ぎています！次の駅で降りて戻ってください"
            else -> RingKind.OVERSHOT_SHINKAICHI to
                "新開地を過ぎています！次の駅で降りて、反対方向の電車で新開地へ戻ってください"
        }
        acts += TripAction.Ring(kind, message, level)
        return s.copy(rings = rings, lastRingAt = t.minutes)
    }

    /** 新開地に着くまで（三宮・新開地・谷上の予告）は振動だけ。北鈴蘭台は神戸電鉄に乗り換えたあとなので音あり */
    private fun preLevel(target: StationId) = if (target == StationId.KITA_SUZURANDAI) 1 else VIBRATE_ONLY

    // ---- 補助 ----

    /** 新しい区間を始める（区間ごとの判定の記録を消す） */
    private fun newLeg(s: FreeState, target: StationId, phase: FreePhase) = s.copy(
        phase = phase, target = target,
        preRung = false, mainRung = false, preAt = null, mainAt = null, minDist = null, beyondCount = 0,
        expectedArr = null, arrivedAt = null, offConfirmedAt = null, returnFromM = null, rings = 0, viaSannomiya = false,
    )

    private fun toKita(s: FreeState, from: StationId, expectedArr: Int, acts: MutableList<TripAction>): FreeState {
        acts += TripAction.StopRinging
        return newLeg(s, StationId.KITA_SUZURANDAI, FreePhase.TO_KITA).copy(kitaFrom = from, expectedArr = expectedArr)
    }

    private fun legPhase(target: StationId) = when (target) {
        StationId.SANNOMIYA -> FreePhase.TO_SANNOMIYA
        StationId.TANIGAMI -> FreePhase.TO_TANIGAMI
        StationId.KITA_SUZURANDAI -> FreePhase.TO_KITA
        else -> FreePhase.TO_SHINKAICHI
    }

    private fun radii(target: StationId) =
        if (target == StationId.KITA_SUZURANDAI) KITA_PRE_M to KITA_MAIN_M else PRE_M to MAIN_M

    /**
     * 今の位置から駅 [target] に着くまでの見込み（分）。
     * 途中に必ず通る駅（三宮・新神戸・谷上）の近くなら、確認した所要時間を使う。それ以外は距離÷速さ。
     */
    private fun etaMinutes(target: StationId, here: LatLon, d: Double): Int {
        val bySpeed = { m: Double, speed: Double -> maxOf(1, ceil(m / speed).toInt()) }
        val dSannomiya = dist(StationId.SANNOMIYA, here)
        return when (target) {
            StationId.SHINKAICHI -> {
                // 三宮の東側（大阪方面）から来ている：三宮まで ＋ 三宮→新開地
                val sannomiya = stations[StationId.SANNOMIYA]
                if (dSannomiya != null && sannomiya != null && dSannomiya < d && here.lon > sannomiya.lon - 0.003) {
                    (if (dSannomiya <= AT_M) 0 else bySpeed(dSannomiya, SPEED_M_PER_MIN)) + SANNOMIYA_TO_SHINKAICHI_MIN
                } else {
                    bySpeed(d, SPEED_M_PER_MIN)
                }
            }
            StationId.TANIGAMI -> when {
                dSannomiya != null && dSannomiya <= MAIN_M -> SANNOMIYA_TO_TANIGAMI_MIN
                (dist(StationId.SHIN_KOBE, here) ?: Double.MAX_VALUE) <= 800.0 -> SHIN_KOBE_TO_TANIGAMI_MIN
                else -> bySpeed(d, SPEED_M_PER_MIN)
            }
            StationId.KITA_SUZURANDAI ->
                if ((dist(StationId.TANIGAMI, here) ?: Double.MAX_VALUE) <= 800.0) TANIGAMI_TO_KITA_MIN
                else bySpeed(d, SHINTETSU_SPEED_M_PER_MIN)
            else -> bySpeed(d, SPEED_M_PER_MIN)
        }
    }

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

    private fun receding(id: StationId, here: LatLon, prev: LatLon): Boolean {
        val now = dist(id, here) ?: return false
        val before = dist(id, prev) ?: return false
        return now - before >= APPROACH_M
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
