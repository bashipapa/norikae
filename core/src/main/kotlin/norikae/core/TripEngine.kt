package norikae.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** 移動の状態（スマホの動きから OS が判定するもの） */
enum class Motion { IN_VEHICLE, WALKING, STILL }

enum class Phase {
    /** 乗る前（事前に予定を入れた）。出発時刻になったら自動で RIDING_SANYO になる */
    PLANNED,
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
    /** 出発の15分前「帰る時間です」 */
    DEPART,
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
    /** 最後に電車を降りる時刻（北鈴蘭台着。鈴蘭台止まりなら鈴蘭台着） */
    val kitaArr: Int,
    val transferAtSuzurandai: Boolean,
    /** 鈴蘭台止まりに乗り、鈴蘭台からタクシーで帰る */
    val taxi: Boolean = false,
    /** 山陽の出発時刻（営業日基準の分）。予定（PLANNED）から乗車中に切り替える時刻 */
    val sanyoDep: Int? = null,
    /** 帰り道の説明（出発前のお知らせに出す） */
    val plan: String? = null,
    /** 最後に「起きた（止める）」を押した時刻（営業日基準の分）。予告のあとの起こし直しを止めるのに使う */
    val awakeAt: Int? = null,
    val lastMotion: Motion? = null,
    val lastLat: Double? = null,
    val lastLon: Double? = null,
    /** 最後に位置を取った時刻（営業日基準の分）。古い位置は判定に使わない */
    val lastLocAt: Int? = null,
    /** とどまっている場所と、そこに来た時刻（150m以上動いたら更新）。乗り過ごしのあと降りたかの判定に使う */
    val stayLat: Double? = null,
    val stayLon: Double? = null,
    val stayAt: Int? = null,
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
    /** ユーザーが「起きた（止める）」を押した */
    data class UserAwake(override val at: ServiceTime) : TripEvent
    /** ユーザーが「新開地で乗り換え中」を押した（乗り過ごしの誤判定を直す） */
    data class UserTransferring(override val at: ServiceTime) : TripEvent
    /** ユーザーが「降りた」「終了」を押した */
    data class UserFinished(override val at: ServiceTime) : TripEvent
}

sealed interface TripAction {
    /** これらのアラームを予約する（同じ種類の既存予約は置き換える） */
    data class Schedule(val alarms: List<PlannedAlarm>) : TripAction
    /** これらの種類のアラーム予約を取り消す */
    data class Cancel(val kinds: Set<AlarmKind>) : TripAction
    /** アラームを鳴らす */
    /**
     * アラームを鳴らす。[level] 1=通常のアラーム音、2以上=サイレン（音量はいつも最大・強い振動）
     */
    data class Ring(val kind: RingKind, val message: String, val level: Int = 1) : TripAction
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
        /** この半径以内なら「その駅にいる」 */
        const val AT_STATION_RADIUS_M = 250.0
        /** この分数より古い位置は判定に使わない */
        const val LOCATION_FRESH_MIN = 3
        /** 到着のこの分数前より後に「起きた」を押していれば、その駅の起こし直しは止める */
        const val WAKE_WINDOW_MIN = 10
        /** 乗り過ごし・電車違いの警報の強さ（サイレン） */
        const val ALERT_LEVEL = 3

        /** この距離以内なら「同じ場所にとどまっている」 */
        const val STAY_RADIUS_M = 150.0

        // 北鈴蘭台〜山の街は約800m、鈴蘭台〜鈴蘭台西口は約560m。範囲が重ならない大きさにしている
        const val KITA_RADIUS_M = 400.0
        const val SUZURANDAI_RADIUS_M = 400.0
        const val WRONG_LINE_RADIUS_M = 300.0
        const val YAMANOMACHI_RADIUS_M = 300.0
        /** 乗り過ごして山の街で降り、反対ホームに移るまでの余裕 */
        const val RETURN_MARGIN_MIN = 5
    }

    /** 乗車開始 */
    /**
     * アラームを入れる。[planned] が true なら「予定」として入れ、出発の [AlarmSettings.departReminderMinutes] 分前に知らせる。
     * 出発時刻になったら自動で乗車中（RIDING_SANYO）に切り替わる。
     */
    fun start(route: Route, serviceDate: String, planned: Boolean = false): Transition {
        val dep = route.sanyo?.dep
        val isPlanned = planned && dep != null
        val state = TripState(
            serviceDate = serviceDate,
            phase = if (isPlanned) Phase.PLANNED else Phase.RIDING_SANYO,
            sanyoDep = dep?.minutes,
            plan = describePlan(route),
            shinkaichiArr = route.shinkaichiArr?.minutes,
            umedaRisk = route.umedaRisk,
            shintetsuDep = route.shintetsu.dep.minutes,
            shintetsuDest = route.shintetsu.dest,
            kitaArr = route.kitaArr.minutes,
            transferAtSuzurandai = route.shintetsu.transferAtSuzurandai,
            taxi = route.taxiFromSuzurandai,
        )
        val summary = buildString {
            append("アラームON：")
            route.shinkaichiArr?.let { append("新開地 ${it}着 → ") }
            append("神戸電鉄 ${route.shintetsu.dep} ${route.shintetsu.dest}行き → ${route.alightStation} ${route.kitaArr}着")
            if (route.taxiFromSuzurandai) append("（鈴蘭台からタクシー）")
            if (route.umedaRisk) append("（梅田行きに注意）")
        }
        val alarms = AlarmPlanner.plan(route, settings).toMutableList()
        if (isPlanned) {
            alarms += PlannedAlarm(AlarmKind.DEPART_REMINDER, dep!! - settings.departReminderMinutes, "")
            return Transition(
                state,
                listOf(
                    TripAction.Schedule(alarms.sortedBy { it.at }),
                    TripAction.Notify("予定を入れました：${dep - settings.departReminderMinutes} に「帰る時間」をお知らせします。${route.origin.label} ${dep}発"),
                ),
            )
        }
        return Transition(state, listOf(TripAction.Schedule(alarms), TripAction.Notify(summary)))
    }

    /** 帰り道の段取り（出発前のお知らせに出す） */
    private fun describePlan(route: Route): String = buildString {
        route.sanyo?.let { s ->
            append("${route.origin.label} ${s.dep}発 ${s.type.label} ${s.dest}行き")
            s.transferAt?.let { append(" → ${it}で${s.transferTrainType?.label ?: ""}に乗換") }
            route.shinkaichiArr?.let { append(" → 新開地 ${it}着") }
            route.waitAtShinkaichi?.let { append("（乗換${it}分${if (route.tightTransfer) "・走る" else ""}）") }
            append(" → ")
        }
        append("神鉄 ${route.shintetsu.dep}発 ${route.shintetsu.dest}行き → ${route.alightStation} ${route.kitaArr}着")
        if (route.taxiFromSuzurandai) append("（鈴蘭台からタクシー）")
        route.totalMinutes?.let { append("。所要 ${formatMinutes(it)}") }
    }

    fun reduce(original: TripState, event: TripEvent): Transition {
        if (original.phase == Phase.DONE) return Transition(original, emptyList())
        if (event is TripEvent.UserAwake) return Transition(original.copy(awakeAt = event.at.minutes), emptyList())
        // 予定（乗る前）は、出発の1分前を過ぎたら自動で乗車中にする
        val state = if (original.phase == Phase.PLANNED &&
            (original.sanyoDep == null || event.at.minutes >= original.sanyoDep - 1)
        ) {
            original.copy(phase = Phase.RIDING_SANYO)
        } else {
            original
        }
        if (state.phase == Phase.PLANNED) {
            // 乗る前は、出発前のお知らせ・ボタン以外は何もしない（駅までの移動を誤判定しないため）
            return when (event) {
                is TripEvent.AlarmFired ->
                    if (event.kind == AlarmKind.DEPART_REMINDER) departReminder(state) else Transition(state, emptyList())
                is TripEvent.UserFinished -> done(state, "終了しました")
                else -> Transition(state, emptyList())
            }
        }
        return when (event) {
            is TripEvent.MotionChanged -> onMotion(state.copy(lastMotion = event.motion), event)
            is TripEvent.LocationChanged -> onLocation(withStay(state, event).copy(
                lastLat = event.location.lat, lastLon = event.location.lon, lastLocAt = event.at.minutes,
            ), event)
            is TripEvent.AlarmFired -> onAlarm(state, event)
            is TripEvent.UserFinished -> done(state, "終了しました")
            is TripEvent.UserTransferring -> toTransferring(state, "乗り換え中にしました")
            is TripEvent.UserAwake -> Transition(state, emptyList())
        }
    }

    // ---- 移動の状態が変わった ----

    private fun onMotion(state: TripState, e: TripEvent.MotionChanged): Transition = when (e.motion) {
        Motion.WALKING -> onWalking(state, e.at)
        Motion.IN_VEHICLE -> onInVehicle(state, e.at)
        Motion.STILL -> Transition(state, emptyList())
    }

    private fun onWalking(state: TripState, t: ServiceTime): Transition {
        val loc = freshLocation(state, t)
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
                val fallback = planner.connectOrTaxi(t + 10)
                val actions = mutableListOf<TripAction>(
                    TripAction.StopRinging,
                    TripAction.Cancel(SHINKAICHI_KINDS),
                    TripAction.Notify("反対方向（姫路方面）の電車で新開地に戻り、神戸電鉄に乗り換えてください"),
                )
                var next = state.copy(phase = Phase.TRANSFERRING)
                if (fallback != null) {
                    next = next.withShintetsu(fallback)
                    actions += TripAction.Schedule(AlarmPlanner.kitaAlarms(fallback, settings))
                    if (fallback.endsAtSuzurandai) actions += TripAction.Notify("北鈴蘭台行きはもうありません。${fallback.dep} 鈴蘭台行きに乗り、鈴蘭台からタクシーで帰ってください")
                } else {
                    actions += TripAction.Notify("北鈴蘭台へ行く電車はもうありません")
                }
                Transition(next, actions)
            }

            Phase.RIDING_SHINTETSU -> {
                val timeOk = t.minutes >= state.kitaArr - 3
                val nearOk = if (state.taxi) {
                    stations.isNear(StationId.SUZURANDAI, loc, SUZURANDAI_RADIUS_M) != false
                } else {
                    stations.isNear(StationId.KITA_SUZURANDAI, loc, KITA_RADIUS_M) != false
                }
                when {
                    !(timeOk && nearOk) -> Transition(state, emptyList())
                    state.taxi -> done(state, "鈴蘭台で降りました。タクシーで北鈴蘭台へ帰ってください")
                    else -> done(state, "おかえりなさい（北鈴蘭台で降りました）")
                }
            }

            Phase.OVERSHOT_KITA -> done(state, "降りたのを確認しました。${returnGuidance(state, t)}")

            Phase.PLANNED, Phase.TRANSFERRING, Phase.DONE -> Transition(state, emptyList())
        }
    }

    private fun onInVehicle(state: TripState, t: ServiceTime): Transition {
        if (state.phase != Phase.TRANSFERRING) return Transition(state, emptyList())
        val next = state.copy(phase = Phase.RIDING_SHINTETSU)
        val train = planner.shintetsuFromBoarding(t) ?: return Transition(next, emptyList())
        if (train.endsAtSuzurandai) {
            // 鈴蘭台止まり（終電後）。路線は合っているので警告せず、鈴蘭台で起こす
            if (train.dep.minutes == state.shintetsuDep) return Transition(next, emptyList())
            return Transition(
                next.withShintetsu(train),
                listOf(
                    TripAction.Schedule(AlarmPlanner.kitaAlarms(train, settings)),
                    TripAction.Notify("鈴蘭台止まりの終電です（鈴蘭台 ${train.suzurandaiArr}着）。鈴蘭台からタクシーで帰ってください"),
                ),
            )
        }
        if (!train.reachesKitaSuzurandai) {
            return Transition(
                next.copy(wrongLineWarned = true),
                listOf(TripAction.Ring(RingKind.WRONG_LINE, "${train.dep} ${train.dest}行きに乗っている可能性があります。この電車は北鈴蘭台に止まりません", ALERT_LEVEL)),
            )
        }
        if (train.dep.minutes == state.shintetsuDep) return Transition(next, emptyList())
        // 予定と違う電車に乗った → 北鈴蘭台アラームを実際の電車に合わせ直す
        return Transition(
            next.withShintetsu(train),
            listOf(
                TripAction.Schedule(AlarmPlanner.kitaAlarms(train, settings)),
                TripAction.Notify("神戸電鉄 ${train.dep} ${train.dest}行きに合わせました（北鈴蘭台 ${train.finalArr}着）"),
            ),
        )
    }

    // ---- 位置が変わった ----

    private fun onLocation(state: TripState, e: TripEvent.LocationChanged): Transition {
        val arr = state.shinkaichiArr
        when (state.phase) {
            Phase.RIDING_SANYO -> {
                // 新開地着の2分前以降に、高速神戸側まで行っている → 乗り過ごし（次の駅に近づいた）
                if (arr != null && e.at.minutes >= arr - 2 &&
                    whereAround(StationId.SHINKAICHI, StationId.KOSOKU_KOBE, e.location) == Where.BEYOND
                ) {
                    return overshootShinkaichi(state, e.at)
                }
                return Transition(state, emptyList())
            }
            Phase.OVERSHOT_SHINKAICHI -> {
                // 乗り過ごし判定のあとで新開地にいると分かった → 誤判定だったので乗り換え中に戻す
                if (whereAround(StationId.SHINKAICHI, StationId.KOSOKU_KOBE, e.location) == Where.AT) {
                    return toTransferring(state, "新開地にいるので、乗り換え中に戻しました")
                }
                // 同じ場所にしばらくとどまっている → 次の駅などで降りたとみなす（歩行と同じ扱い）
                if (isOffTrain(state, e.at)) return onWalking(state, e.at)
                return Transition(state, emptyList())
            }
            Phase.OVERSHOT_KITA -> {
                if (isOffTrain(state, e.at)) return onWalking(state, e.at)
                return Transition(state, emptyList())
            }
            Phase.RIDING_SHINTETSU -> Unit
            else -> return Transition(state, emptyList())
        }
        if (!state.wrongLineWarned &&
            stations.isNear(StationId.SUZURANDAI_NISHIGUCHI, e.location, WRONG_LINE_RADIUS_M) == true
        ) {
            return Transition(
                state.copy(wrongLineWarned = true),
                listOf(TripAction.Ring(RingKind.WRONG_LINE, "粟生線（鈴蘭台西口方面）を走っています。北鈴蘭台に行きません。鈴蘭台に戻ってください", ALERT_LEVEL)),
            )
        }
        if (!state.taxi && stations.isNear(StationId.YAMANOMACHI, e.location, YAMANOMACHI_RADIUS_M) == true) {
            return overshootKita(state, e.at)
        }
        return Transition(state, emptyList())
    }

    // ---- 予約したアラームの時刻になった ----

    private fun onAlarm(state: TripState, e: TripEvent.AlarmFired): Transition = when (e.kind) {
        AlarmKind.SHINKAICHI_PRE, AlarmKind.SHINKAICHI_MAIN, AlarmKind.WAKE_REPEAT_SHINKAICHI ->
            if (state.phase == Phase.RIDING_SANYO) ring(state, e.kind, e.at) else Transition(state, emptyList())

        AlarmKind.KITA_PRE, AlarmKind.KITA_MAIN, AlarmKind.WAKE_REPEAT_KITA ->
            if (state.phase == Phase.RIDING_SHINTETSU || state.phase == Phase.TRANSFERRING ||
                state.phase == Phase.OVERSHOT_SHINKAICHI
            ) {
                ring(state, e.kind, e.at)
            } else {
                Transition(state, emptyList())
            }

        AlarmKind.DEPART_REMINDER -> Transition(state, emptyList()) // 出発後に届いたものは無視
        AlarmKind.SHINKAICHI_CHECK -> checkShinkaichi(state, e.at)
        AlarmKind.KITA_CHECK -> checkKita(state, e.at)
    }

    /** 出発の15分前のお知らせ（帰る段取り） */
    private fun departReminder(state: TripState): Transition {
        val dep = state.sanyoDep?.let { ServiceTime(it) }
        val message = buildString {
            append("あと${settings.departReminderMinutes}分で出発（${dep}発）。帰る準備をしましょう：会計・忘れ物・駅へ移動。")
            state.plan?.let { append("\n$it") }
        }
        return Transition(state, listOf(TripAction.Ring(RingKind.DEPART, message)))
    }

    /** 到着 [arr] の前の起こしで、もう「起きた」を押しているか（予告より前に押したものは数えない） */
    private fun awakeFor(state: TripState, arr: Int?): Boolean {
        val awake = state.awakeAt ?: return false
        return arr != null && awake >= arr - WAKE_WINDOW_MIN
    }

    private fun ring(state: TripState, kind: AlarmKind, at: ServiceTime): Transition {
        val shinkaichi = kind == AlarmKind.SHINKAICHI_PRE || kind == AlarmKind.SHINKAICHI_MAIN ||
            kind == AlarmKind.WAKE_REPEAT_SHINKAICHI
        val arr = if (shinkaichi) state.shinkaichiArr else state.kitaArr
        val awake = awakeFor(state, arr)
        val isRepeat = kind == AlarmKind.WAKE_REPEAT_SHINKAICHI || kind == AlarmKind.WAKE_REPEAT_KITA
        // 起こし直しは「起きた」を押していたら鳴らさない
        if (isRepeat && awake) return Transition(state, emptyList())
        val left = arr?.let { it - at.minutes }
        val message = when (kind) {
            AlarmKind.WAKE_REPEAT_SHINKAICHI ->
                "起きて！あと${left}分で新開地" + if (state.umedaRisk) "（乗り過ごすと梅田まで行きます）" else ""
            AlarmKind.WAKE_REPEAT_KITA ->
                "起きて！あと${left}分で" + if (state.taxi) "終点の鈴蘭台" else "北鈴蘭台"
            AlarmKind.SHINKAICHI_PRE ->
                "まもなく新開地。起きてください" + if (state.umedaRisk) "（乗り過ごすと梅田まで行きます）" else ""
            AlarmKind.SHINKAICHI_MAIN -> {
                val wait = state.shinkaichiArr?.let { state.shintetsuDep - it }
                val hurry = if (wait != null && wait <= Route.TIGHT_TRANSFER_MINUTES) "乗り換え${wait}分、走って！" else ""
                "次は新開地。${hurry}降りて神戸電鉄 ${ServiceTime(state.shintetsuDep)} ${state.shintetsuDest}行きへ"
            }
            AlarmKind.KITA_PRE -> when {
                state.taxi -> "まもなく終点の鈴蘭台。起きてください（鈴蘭台からタクシー）"
                state.transferAtSuzurandai -> "鈴蘭台で三田行きに乗り換え。まもなく北鈴蘭台。起きてください"
                else -> "まもなく北鈴蘭台。起きてください"
            }
            AlarmKind.KITA_MAIN -> if (state.taxi) "次は終点の鈴蘭台。降りてください" else "次は北鈴蘭台。降りてください"
            else -> ""
        }
        // だんだん強く：予告は通常の音、起こし直しと本アラームは（起きていなければ）サイレン
        val level = when {
            kind == AlarmKind.SHINKAICHI_PRE || kind == AlarmKind.KITA_PRE -> 1
            awake -> 1
            else -> 2
        }
        val actions = mutableListOf<TripAction>(TripAction.Ring(RingKind.WAKE, message, level))
        // 予告・起こし直しのあと、本アラームの1分前まで1分おきに起こし直す（「起きた」を押せば止まる）
        val repeatKind = if (shinkaichi) AlarmKind.WAKE_REPEAT_SHINKAICHI else AlarmKind.WAKE_REPEAT_KITA
        val next = at + 1
        if ((kind == AlarmKind.SHINKAICHI_PRE || kind == AlarmKind.KITA_PRE || isRepeat) &&
            arr != null && next.minutes < arr - settings.mainMinutes
        ) {
            actions += TripAction.Schedule(listOf(PlannedAlarm(repeatKind, next, "")))
        }
        return Transition(state, actions)
    }

    private fun checkShinkaichi(state: TripState, t: ServiceTime): Transition {
        val stillRiding = state.phase == Phase.RIDING_SANYO ||
            (state.phase == Phase.OVERSHOT_SHINKAICHI && state.lastMotion != Motion.WALKING)
        if (!stillRiding) return Transition(state, emptyList())
        if (state.phase == Phase.OVERSHOT_SHINKAICHI && isOffTrain(state, t)) return onWalking(state, t)
        when (whereAround(StationId.SHINKAICHI, StationId.KOSOKU_KOBE, freshLocation(state, t))) {
            // 新開地にいる：歩行を検知できなくても、降りて乗り換え中とみなす
            Where.AT -> return toTransferring(state, "新開地にいるので、乗り換え中とみなしました")
            // まだ新開地の手前（遅れ）：鳴らさず1分後にもう一度確認
            Where.BEFORE -> return Transition(
                state,
                listOf(TripAction.Schedule(listOf(PlannedAlarm(AlarmKind.SHINKAICHI_CHECK, t + 1, "")))),
            )
            // 高速神戸側まで行っている、または位置が分からない：乗り過ごし（位置が無いときは安全のため鳴らす）
            Where.BEYOND, Where.UNKNOWN -> return overshootShinkaichi(state, t)
        }
    }

    private fun overshootShinkaichi(state: TripState, t: ServiceTime): Transition {
        if (state.shinkaichiRings >= settings.maxOvershootRings) return Transition(state, emptyList())

        val rings = state.shinkaichiRings + 1
        val actions = mutableListOf<TripAction>(
            TripAction.Ring(
                RingKind.OVERSHOT_SHINKAICHI,
                "新開地を過ぎています！次の駅で降りて、反対方向の電車で新開地へ戻ってください" +
                    if (state.umedaRisk) "（このままだと梅田まで行きます）" else "",
                ALERT_LEVEL,
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
            Phase.RIDING_SHINTETSU -> Unit
            Phase.OVERSHOT_KITA -> if (isOffTrain(state, t)) return onWalking(state, t)
            Phase.TRANSFERRING -> {
                // 神戸電鉄に乗ったのを検知できていない。新開地にいるなら乗り遅れの可能性
                if (stations.isNear(StationId.SHINKAICHI, loc, SHINKAICHI_RADIUS_M) == true) {
                    return Transition(state, listOf(TripAction.Notify("まだ新開地にいるようです。電車に乗れましたか？")))
                }
            }
            else -> return Transition(state, emptyList())
        }
        if (state.taxi) return checkSuzurandaiTerminal(state, t)
        when (whereAround(StationId.KITA_SUZURANDAI, StationId.YAMANOMACHI, freshLocation(state, t))) {
            Where.AT -> return done(state, "おかえりなさい（北鈴蘭台にいます）")
            // まだ北鈴蘭台の手前（遅れ）：鳴らさず1分後にもう一度確認
            Where.BEFORE -> return Transition(
                state,
                listOf(TripAction.Schedule(listOf(PlannedAlarm(AlarmKind.KITA_CHECK, t + 1, "")))),
            )
            Where.BEYOND, Where.UNKNOWN -> Unit
        }
        val near = stations.isNear(StationId.KITA_SUZURANDAI, loc, KITA_RADIUS_M)
        if (near == true && state.lastMotion != Motion.IN_VEHICLE) {
            return done(state, "おかえりなさい（北鈴蘭台にいます）")
        }
        return overshootKita(state, t)
    }

    /** 鈴蘭台止まり：終点なので乗り過ごしは無い。まだ乗っていそうなら起きるまで鳴らす */
    private fun checkSuzurandaiTerminal(state: TripState, t: ServiceTime): Transition {
        val near = stations.isNear(StationId.SUZURANDAI, state.lastLocation, SUZURANDAI_RADIUS_M)
        if (near == true && state.lastMotion != Motion.IN_VEHICLE) {
            return done(state, "鈴蘭台に着きました。タクシーで北鈴蘭台へ帰ってください")
        }
        if (state.kitaRings >= settings.maxOvershootRings) return Transition(state, emptyList())
        val rings = state.kitaRings + 1
        val actions = mutableListOf<TripAction>(
            TripAction.Ring(RingKind.WAKE, "終点の鈴蘭台です！起きて降りてください（鈴蘭台からタクシー）", ALERT_LEVEL),
        )
        if (rings < settings.maxOvershootRings) {
            actions += TripAction.Schedule(listOf(PlannedAlarm(AlarmKind.KITA_CHECK, t + 1, "")))
        }
        return Transition(state.copy(kitaRings = rings), actions)
    }

    private fun overshootKita(state: TripState, t: ServiceTime): Transition {
        if (state.kitaRings >= settings.maxOvershootRings) return Transition(state, emptyList())
        val rings = state.kitaRings + 1
        val actions = mutableListOf<TripAction>(
            TripAction.Ring(RingKind.OVERSHOT_KITA, "北鈴蘭台を過ぎた可能性があります！次の駅で降りてください。${returnGuidance(state, t)}", ALERT_LEVEL),
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

    /** とどまっている場所を更新する（前の場所から [STAY_RADIUS_M] 以上動いたら、今の場所・時刻に置き直す） */
    private fun withStay(state: TripState, e: TripEvent.LocationChanged): TripState {
        val stay = if (state.stayLat != null && state.stayLon != null) LatLon(state.stayLat, state.stayLon) else null
        return if (stay == null || state.stayAt == null || stay.distanceTo(e.location) > STAY_RADIUS_M) {
            state.copy(stayLat = e.location.lat, stayLon = e.location.lon, stayAt = e.at.minutes)
        } else {
            state
        }
    }

    /** 電車を降りているとみなせるか：新しい位置が取れていて、同じ場所に [AlarmSettings.offTrainStayMinutes] 分以上、乗り物ではない */
    private fun isOffTrain(state: TripState, t: ServiceTime): Boolean {
        val since = state.stayAt ?: return false
        if (freshLocation(state, t) == null) return false
        return t.minutes - since >= settings.offTrainStayMinutes && state.lastMotion != Motion.IN_VEHICLE
    }

    /** 取ってから [LOCATION_FRESH_MIN] 分以内の位置だけ使う */
    private fun freshLocation(state: TripState, t: ServiceTime): LatLon? {
        val at = state.lastLocAt ?: return null
        return if (t.minutes - at <= LOCATION_FRESH_MIN) state.lastLocation else null
    }

    private enum class Where { AT, BEFORE, BEYOND, UNKNOWN }

    /**
     * 駅 [station] に対して今どこにいるか。
     * 半径 [AT_STATION_RADIUS_M] 以内なら AT、次の駅 [next] の方が近ければ BEYOND（通り過ぎた）、それ以外は BEFORE（まだ手前）。
     */
    private fun whereAround(station: StationId, next: StationId, loc: LatLon?): Where {
        val s = stations[station] ?: return Where.UNKNOWN
        val here = loc ?: return Where.UNKNOWN
        val ds = here.distanceTo(s)
        if (ds <= AT_STATION_RADIUS_M) return Where.AT
        val n = stations[next] ?: return Where.UNKNOWN
        return if (here.distanceTo(n) < ds) Where.BEYOND else Where.BEFORE
    }

    /** 新開地で降りて乗り換え中にする（乗り過ごしの誤判定を直すときも） */
    private fun toTransferring(state: TripState, message: String): Transition {
        if (state.phase != Phase.RIDING_SANYO && state.phase != Phase.OVERSHOT_SHINKAICHI) {
            return Transition(state, emptyList())
        }
        return Transition(
            state.copy(phase = Phase.TRANSFERRING, shinkaichiRings = 0),
            listOf(
                TripAction.StopRinging,
                TripAction.Cancel(SHINKAICHI_KINDS),
                TripAction.Notify("$message：神戸電鉄 ${ServiceTime(state.shintetsuDep)} ${state.shintetsuDest}行き"),
            ),
        )
    }

    private fun done(state: TripState, message: String) = Transition(
        state.copy(phase = Phase.DONE),
        listOf(TripAction.StopRinging, TripAction.Notify(message), TripAction.End),
    )

    private fun TripState.withShintetsu(train: ShintetsuTrain) = copy(
        shintetsuDep = train.dep.minutes,
        shintetsuDest = train.dest,
        kitaArr = train.finalArr!!.minutes,
        transferAtSuzurandai = train.transferAtSuzurandai,
        taxi = train.endsAtSuzurandai,
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
