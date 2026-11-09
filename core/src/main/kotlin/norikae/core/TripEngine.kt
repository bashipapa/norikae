package norikae.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 移動の状態（スマホの動きから OS が判定するもの） */
enum class Motion { IN_VEHICLE, WALKING, STILL }

enum class Phase {
    /** 山陽に乗っている（新開地へ向かっている） */
    RIDING_SANYO,
    /** 新開地で降りた／乗り換え中 */
    TRANSFERRING,
    /** 神戸電鉄に乗っている（北鈴蘭台へ向かっている） */
    RIDING_SHINTETSU,
    /** 新開地を乗り過ごした */
    OVERSHOT_SHINKAICHI,
    /** 北鈴蘭台を乗り過ごした */
    OVERSHOT_KITA,
    /** 帰宅完了（または終了） */
    DONE,
}

enum class RingKind {
    /** 通常の起床アラーム（予告・本アラーム） */
    WAKE,
    OVERSHOT_SHINKAICHI,
    OVERSHOT_KITA,
    /** 粟生線・鈴蘭台止まりなど、北鈴蘭台に行かない電車に乗っている */
    WRONG_LINE,
}

/** 乗車中の状態。アプリのプロセスが落ちても戻せるよう JSON で保存できる形にしている */
@Serializable
data class TripState(
    val serviceDate: String,
    val phase: Phase,
    val shinkaichiArr: Int?,
    val umedaRisk: Boolean,
    val shintetsuDep: Int,
    val shintetsuDest: String,
    val kitaArr: Int,
    val transferAtSuzurandai: Boolean,
    val lastMotion: Motion? = null,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    val shinkaichiRings: Int = 0,
    val kitaRings: Int = 0,
    val wrongLineWarned: Boolean = false,
) {
    val lastLocation: LatLon? get() = if (lastLat != null && lastLon != null) LatLon(lastLat, lastLon) else null

    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        fun fromJson(text: String): TripState = json.decodeFromString(serializer(), text)
    }
}

sealed interface TripEvent {
    val at: ServiceTime

    data class MotionChanged(val motion: Motion, override val at: ServiceTime) : TripEvent
    data class LocationChanged(val location: LatLon, override val at: ServiceTime) : TripEvent
    data class AlarmFired(val kind: AlarmKind, override val at: ServiceTime) : TripEvent
    /** ユーザーが「降りた」「終了」を押した */
    data class UserFinished(override val at: ServiceTime) : TripEvent
}

sealed interface TripAction {
    /** これらのアラームを予約する（同じ種類の既存予約は置き換える） */
    data class Schedule(val alarms: List<PlannedAlarm>) : TripAction
    /** これらの種類のアラーム予約を取り消す */
    data class Cancel(val kinds: Set<AlarmKind>) : TripAction
    /** アラームを鳴らす */
    data class Ring(val kind: RingKind, val message: String) : TripAction
    /** 鳴っているアラームを止める */
    data object StopRinging : TripAction
    /** 通知だけ出す（鳴らさない） */
    data class Notify(val message: String) : TripAction
    /** 乗車終了。すべてのアラームと位置取得を止める */
    data object End : TripAction
}

data class Transition(val state: TripState, val actions: List<TripAction>)

/**
 * 乗車中の判定ロジック本体。入力（イベント）から次の状態と、やること（アクション）を返すだけの純粋な処理。
 */
class TripEngine(
    private val planner: RoutePlanner,
    private val stations: StationLocations = StationLocations(),
    private val settings: AlarmSettings = AlarmSettings(),
) {
    private companion object {
        val SHINKAICHI_KINDS = setOf(AlarmKind.SHINKAICHI_PRE, AlarmKind.SHINKAICHI_MAIN, AlarmKind.SHINKAICHI_CHECK)
        val KITA_KINDS = setOf(AlarmKind.KITA_PRE, AlarmKind.KITA_MAIN, AlarmKind.KITA_CHECK)

        const val SHINKAICHI_RADIUS_M = 700.0
        const val KITA_RADIUS_M = 500.0
        const val WRONG_LINE_RADIUS_M = 400.0
        const val YAMANOMACHI_RADIUS_M = 400.0
        /** 乗り過ごして山の街で降り、反対ホームに移るまでの余裕 */
        const val RETURN_MARGIN_MIN = 5
    }

    /** 乗車開始 */
    fun start(route: Route, serviceDate: String): Transition {
        val state = TripState(
            serviceDate = serviceDate,
            phase = Phase.RIDING_SANYO,
            shinkaichiArr = route.shinkaichiArr?.minutes,
            umedaRisk = route.umedaRisk,
            shintetsuDep = route.shintetsu.dep.minutes,
            shintetsuDest = route.shintetsu.dest,
            kitaArr = route.kitaArr.minutes,
            transferAtSuzurandai = route.shintetsu.transferAtSuzurandai,
        )
        val summary = buildString {
            append("アラームON：")
            route.shinkaichiArr?.let { append("新開地 ${it}着 → ") }
            append("神戸電鉄 ${route.shintetsu.dep} ${route.shintetsu.dest}行き → 北鈴蘭台 ${route.kitaArr}着")
            if (route.umedaRisk) append("（梅田行きに注意）")
        }
        return Transition(state, listOf(TripAction.Schedule(AlarmPlanner.plan(route, settings)), TripAction.Notify(summary)))
    }

    fun reduce(state: TripState, event: TripEvent): Transition {
        if (state.phase == Phase.DONE) return Transition(state, emptyList())
        return when (event) {
            is TripEvent.MotionChanged -> onMotion(state.copy(lastMotion = event.motion), event)
            is TripEvent.LocationChanged -> onLocation(
                state.copy(lastLat = event.location.lat, lastLon = event.location.lon), event,
            )
            is TripEvent.AlarmFired -> onAlarm(state, event)
            is TripEvent.UserFinished -> done(state, "終了しました")
        }
    }

    // ---- 移動の状態が変わった ----

    private fun onMotion(state: TripState, e: TripEvent.MotionChanged): Transition = when (e.motion) {
        Motion.WALKING -> onWalking(state, e.at)
        Motion.IN_VEHICLE -> onInVehicle(state, e.at)
        Motion.STILL -> Transition(state, emptyList())
    }

    private fun onWalking(state: TripState, t: ServiceTime): Transition {
        val loc = state.lastLocation
        return when (state.phase) {
            Phase.RIDING_SANYO -> {
                val timeOk = state.shinkaichiArr == null || t.minutes >= state.shinkaichiArr - 5
                val nearOk = stations.isNear(StationId.SHINKAICHI, loc, SHINKAICHI_RADIUS_M) != false
                if (timeOk && nearOk) {
                    Transition(
                        state.copy(phase = Phase.TRANSFERRING),
                        listOf(
                            TripAction.StopRinging,
                            TripAction.Cancel(SHINKAICHI_KINDS),
                            TripAction.Notify("乗り換え：神戸電鉄 ${ServiceTime(state.shintetsuDep)} ${state.shintetsuDest}行き"),
                        ),
                    )
                } else {
                    Transition(state, emptyList())
                }
            }

            Phase.OVERSHOT_SHINKAICHI -> {
                // 乗り過ごした先で降りた。戻ってくる前提で、乗れそうな電車で北鈴蘭台アラームを仮に入れ直す
                val fallback = planner.connectAtShinkaichi(t + 10)
                val actions = mutableListOf<TripAction>(
                    TripAction.StopRinging,
                    TripAction.Cancel(SHINKAICHI_KINDS),
                    TripAction.Notify("反対方向（姫路方面）の電車で新開地に戻り、神戸電鉄に乗り換えてください"),
                )
                var next = state.copy(phase = Phase.TRANSFERRING)
                if (fallback != null) {
                    next = next.withShintetsu(fallback)
                    actions += TripAction.Schedule(AlarmPlanner.kitaAlarms(fallback, settings))
                } else {
                    actions += TripAction.Notify("北鈴蘭台へ行く電車はもうありません")
                }
                Transition(next, actions)
            }

            Phase.RIDING_SHINTETSU -> {
                val timeOk = t.minutes >= state.kitaArr - 3
                val nearOk = stations.isNear(StationId.KITA_SUZURANDAI, loc, KITA_RADIUS_M) != false
                if (timeOk && nearOk) done(state, "おかえりなさい（北鈴蘭台で降りました）") else Transition(state, emptyList())
            }

            Phase.OVERSHOT_KITA -> done(state, "降りたのを確認しました。${returnGuidance(state, t)}")

            Phase.TRANSFERRING, Phase.DONE -> Transition(state, emptyList())
        }
    }

    private fun onInVehicle(state: TripState, t: ServiceTime): Transition {
        if (state.phase != Phase.TRANSFERRING) return Transition(state, emptyList())
        val next = state.copy(phase = Phase.RIDING_SHINTETSU)
        val train = planner.shintetsuFromBoarding(t) ?: return Transition(next, emptyList())
        if (!train.reachesKitaSuzurandai) {
            return Transition(
                next.copy(wrongLineWarned = true),
                listOf(TripAction.Ring(RingKind.WRONG_LINE, "${train.dep} ${train.dest}行きに乗っている可能性があります。この電車は北鈴蘭台に止まりません")),
            )
        }
        if (train.dep.minutes == state.shintetsuDep) return Transition(next, emptyList())
        // 予定と違う電車に乗った → 北鈴蘭台アラームを実際の電車に合わせ直す
        return Transition(
            next.withShintetsu(train),
            listOf(
                TripAction.Schedule(AlarmPlanner.kitaAlarms(train, settings)),
                TripAction.Notify("神戸電鉄 ${train.dep} ${train.dest}行きに合わせました（北鈴蘭台 ${train.kitaSuzurandaiArr}着）"),
            ),
        )
    }

    // ---- 位置が変わった ----

    private fun onLocation(state: TripState, e: TripEvent.LocationChanged): Transition {
        if (state.phase != Phase.RIDING_SHINTETSU) return Transition(state, emptyList())
        if (!state.wrongLineWarned &&
            stations.isNear(StationId.SUZURANDAI_NISHIGUCHI, e.location, WRONG_LINE_RADIUS_M) == true
        ) {
            return Transition(
                state.copy(wrongLineWarned = true),
                listOf(TripAction.Ring(RingKind.WRONG_LINE, "粟生線（鈴蘭台西口方面）を走っています。北鈴蘭台に行きません。鈴蘭台に戻ってください")),
            )
        }
        if (stations.isNear(StationId.YAMANOMACHI, e.location, YAMANOMACHI_RADIUS_M) == true) {
            return overshootKita(state, e.at)
        }
        return Transition(state, emptyList())
    }

    // ---- 予約したアラームの時刻になった ----

    private fun onAlarm(state: TripState, e: TripEvent.AlarmFired): Transition = when (e.kind) {
        AlarmKind.SHINKAICHI_PRE, AlarmKind.SHINKAICHI_MAIN ->
            if (state.phase == Phase.RIDING_SANYO) ring(state, e.kind) else Transition(state, emptyList())

        AlarmKind.KITA_PRE, AlarmKind.KITA_MAIN ->
            if (state.phase == Phase.RIDING_SHINTETSU || state.phase == Phase.TRANSFERRING) {
                ring(state, e.kind)
            } else {
                Transition(state, emptyList())
            }

        AlarmKind.SHINKAICHI_CHECK -> checkShinkaichi(state, e.at)
        AlarmKind.KITA_CHECK -> checkKita(state, e.at)
    }

    private fun ring(state: TripState, kind: AlarmKind): Transition {
        val message = when (kind) {
            AlarmKind.SHINKAICHI_PRE ->
                "まもなく新開地。起きてください" + if (state.umedaRisk) "（乗り過ごすと梅田まで行きます）" else ""
            AlarmKind.SHINKAICHI_MAIN -> "次は新開地。降りて神戸電鉄 ${ServiceTime(state.shintetsuDep)} ${state.shintetsuDest}行きへ"
            AlarmKind.KITA_PRE -> (if (state.transferAtSuzurandai) "鈴蘭台で三田行きに乗り換え。" else "") + "まもなく北鈴蘭台。起きてください"
            AlarmKind.KITA_MAIN -> "次は北鈴蘭台。降りてください"
            else -> ""
        }
        return Transition(state, listOf(TripAction.Ring(RingKind.WAKE, message)))
    }

    private fun checkShinkaichi(state: TripState, t: ServiceTime): Transition {
        val stillRiding = state.phase == Phase.RIDING_SANYO ||
            (state.phase == Phase.OVERSHOT_SHINKAICHI && state.lastMotion != Motion.WALKING)
        if (!stillRiding) return Transition(state, emptyList())
        if (state.shinkaichiRings >= settings.maxOvershootRings) return Transition(state, emptyList())

        val rings = state.shinkaichiRings + 1
        val actions = mutableListOf<TripAction>(
            TripAction.Ring(
                RingKind.OVERSHOT_SHINKAICHI,
                "新開地を過ぎています！次の駅で降りて、反対方向の電車で新開地へ戻ってください" +
                    if (state.umedaRisk) "（このままだと梅田まで行きます）" else "",
            ),
        )
        if (rings < settings.maxOvershootRings) {
            actions += TripAction.Schedule(listOf(PlannedAlarm(AlarmKind.SHINKAICHI_CHECK, t + 1, "")))
        }
        return Transition(state.copy(phase = Phase.OVERSHOT_SHINKAICHI, shinkaichiRings = rings), actions)
    }

    private fun checkKita(state: TripState, t: ServiceTime): Transition {
        val loc = state.lastLocation
        when (state.phase) {
            Phase.RIDING_SHINTETSU, Phase.OVERSHOT_KITA -> Unit
            Phase.TRANSFERRING -> {
                // 神戸電鉄に乗ったのを検知できていない。新開地にいるなら乗り遅れの可能性
                if (stations.isNear(StationId.SHINKAICHI, loc, SHINKAICHI_RADIUS_M) == true) {
                    return Transition(state, listOf(TripAction.Notify("まだ新開地にいるようです。電車に乗れましたか？")))
                }
            }
            else -> return Transition(state, emptyList())
        }
        val near = stations.isNear(StationId.KITA_SUZURANDAI, loc, KITA_RADIUS_M)
        if (near == true && state.lastMotion != Motion.IN_VEHICLE) {
            return done(state, "おかえりなさい（北鈴蘭台にいます）")
        }
        return overshootKita(state, t)
    }

    private fun overshootKita(state: TripState, t: ServiceTime): Transition {
        if (state.kitaRings >= settings.maxOvershootRings) return Transition(state, emptyList())
        val rings = state.kitaRings + 1
        val actions = mutableListOf<TripAction>(
            TripAction.Ring(RingKind.OVERSHOT_KITA, "北鈴蘭台を過ぎた可能性があります！次の駅で降りてください。${returnGuidance(state, t)}"),
        )
        if (rings < settings.maxOvershootRings) {
            actions += TripAction.Schedule(listOf(PlannedAlarm(AlarmKind.KITA_CHECK, t + 1, "")))
        }
        return Transition(state.copy(phase = Phase.OVERSHOT_KITA, kitaRings = rings), actions)
    }

    private fun returnGuidance(state: TripState, t: ServiceTime): String {
        val back = planner.returnTrain(t + RETURN_MARGIN_MIN)
            ?: return "北鈴蘭台に戻る電車はもうありません。タクシーを検討してください"
        return "戻る電車の目安：北鈴蘭台 ${back.dep}着（新開地方面・${back.dest}行き）"
    }

    private fun done(state: TripState, message: String) = Transition(
        state.copy(phase = Phase.DONE),
        listOf(TripAction.StopRinging, TripAction.Notify(message), TripAction.End),
    )

    private fun TripState.withShintetsu(train: ShintetsuTrain) = copy(
        shintetsuDep = train.dep.minutes,
        shintetsuDest = train.dest,
        kitaArr = train.kitaSuzurandaiArr!!.minutes,
        transferAtSuzurandai = train.transferAtSuzurandai,
    )
}

/**
 * 自動開始の判定: 21時以降に、荒井の近くで（または荒井を出て15分以内に）乗り物での移動が始まったら開始する。
 */
data class AutoStartRule(val drinking: DrinkingModeRule = DrinkingModeRule(), val withinMinutesAfterLeavingArai: Int = 15) {
    fun shouldStart(at: ServiceTime, motion: Motion, nearArai: Boolean?, leftAraiAt: ServiceTime?): Boolean {
        if (!drinking.isDrinkingTime(at) || motion != Motion.IN_VEHICLE) return false
        if (nearArai == true) return true
        return leftAraiAt != null && at - leftAraiAt in 0..withinMinutesAfterLeavingArai
    }
}
