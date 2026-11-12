package norikae.app

import android.content.Context
import norikae.core.AlarmKind
import norikae.core.AlarmPlanner
import norikae.core.AlarmSettings
import norikae.core.AutoStartRule
import norikae.core.Motion
import norikae.core.Origin
import norikae.core.Phase
import norikae.core.Route
import norikae.core.SanyoTrain
import norikae.core.ServiceTime
import norikae.core.RoutePlanner
import norikae.core.Transition
import norikae.core.TripAction
import norikae.core.TripEngine
import norikae.core.TripEvent
import norikae.core.TripState
import norikae.core.toService
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * アプリの中心。イベントを TripEngine（core）に渡し、返ってきたアクションを Android の機能で実行する。
 */
object TripController {

    private val settings = AlarmSettings()

    fun planner(ctx: Context) = RoutePlanner(Store.timetable(ctx))

    private fun engine(ctx: Context) = TripEngine(planner(ctx), Store.stations(ctx), settings)

    fun now(): Pair<LocalDate, norikae.core.ServiceTime> = LocalDateTime.now().toService()

    fun isActive(ctx: Context): Boolean = Store.trip(ctx) != null

    /** 経路を選んで開始（画面から） */
    @Synchronized
    fun start(ctx: Context, route: Route, fromForeground: Boolean = true, how: String = "一覧から選択") {
        val (date, _) = now()
        // 前の乗車が残っていれば片付ける
        if (isActive(ctx)) {
            TripLog.add(ctx, TripLog.Category.START, "前の乗車が残っていたので終了してから開始")
            end(ctx)
        }
        TripLog.add(ctx, TripLog.Category.START, "開始（$how）：${describe(route)}")
        val transition = engine(ctx).start(route, date.toString())
        apply(ctx, transition, before = null)
        TripService.start(ctx, fromForeground)
        FitbitHelper.notify(ctx, route)
    }

    private fun describe(route: Route): String = buildString {
        route.sanyo?.let { append("${route.origin.label} ${it.dep}発 ${it.type.label} ${it.dest}行き → ") }
        route.sanyo?.transferAt?.let { append("${it}で乗換 → ") }
        route.shinkaichiArr?.let { append("新開地 ${it}着${if (route.shinkaichiEstimated) "(推定)" else ""} → ") }
        append("神鉄 ${route.shintetsu.dep}発 ${route.shintetsu.dest}行き → ${route.alightStation} ${route.kitaArr}着")
        if (route.taxiFromSuzurandai) append("（タクシー）")
    }

    /** 「今乗った」：実際の乗車時刻から経路を決めて開始 */
    fun startFromBoardingNow(
        ctx: Context,
        origin: Origin = Origin.ARAI,
        filter: (SanyoTrain) -> Boolean = { true },
        fromForeground: Boolean = true,
    ): Boolean {
        val (_, time) = now()
        val route = planner(ctx).routeFromBoarding(time, origin, filter)
        if (route == null) {
            TripLog.add(ctx, TripLog.Category.START, "${origin.label} ${time}乗車で経路を決められなかった（終電後など）")
            return false
        }
        start(ctx, route, fromForeground, how = if (fromForeground) "${origin.label}で今乗った" else "自動開始・${origin.label}")
        return true
    }

    @Synchronized
    fun handle(ctx: Context, event: TripEvent) {
        val state = Store.trip(ctx) ?: return
        logEvent(ctx, state, event)
        apply(ctx, engine(ctx).reduce(state, event), before = state)
    }

    private var lastLocationLogAt = 0L

    private fun logEvent(ctx: Context, state: TripState, event: TripEvent) {
        when (event) {
            is TripEvent.MotionChanged -> Unit // onMotion で記録済み
            is TripEvent.AlarmFired -> TripLog.add(ctx, TripLog.Category.ALARM, "${alarmLabel(event.kind)}の時刻（${event.at}）：${Notifier.phaseLabel(state)}")
            is TripEvent.UserFinished -> TripLog.add(ctx, TripLog.Category.USER, "「降りた／終了」を押した")
            is TripEvent.UserTransferring -> TripLog.add(ctx, TripLog.Category.USER, "「新開地で乗り換え中」を押した")
            is TripEvent.LocationChanged -> {
                // 位置は20秒ごとに届くので、2分に1回だけ記録する
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastLocationLogAt < 120_000) return
                lastLocationLogAt = nowMs
                val nearest = Store.stations(ctx).map.minByOrNull { it.value.distanceTo(event.location) }
                val where = nearest?.let { "${it.key.label}まで約${it.value.distanceTo(event.location).toInt()}m" } ?: ""
                TripLog.add(ctx, TripLog.Category.PLACE, "%.5f, %.5f（%s）".format(event.location.lat, event.location.lon, where))
            }
        }
    }

    private fun alarmLabel(kind: AlarmKind) = when (kind) {
        AlarmKind.SHINKAICHI_PRE -> "新開地の予告"
        AlarmKind.SHINKAICHI_MAIN -> "新開地の本アラーム"
        AlarmKind.SHINKAICHI_CHECK -> "新開地で降りたかの確認"
        AlarmKind.KITA_PRE -> "北鈴蘭台の予告"
        AlarmKind.KITA_MAIN -> "北鈴蘭台の本アラーム"
        AlarmKind.KITA_CHECK -> "北鈴蘭台で降りたかの確認"
    }

    private fun ringLabel(k: norikae.core.RingKind) = when (k) {
        norikae.core.RingKind.WAKE -> "起こす"
        norikae.core.RingKind.OVERSHOT_SHINKAICHI -> "新開地の乗り過ごし"
        norikae.core.RingKind.OVERSHOT_KITA -> "北鈴蘭台の乗り過ごし"
        norikae.core.RingKind.WRONG_LINE -> "電車違い"
    }

    private fun motionLabel(m: Motion) = when (m) {
        Motion.IN_VEHICLE -> "乗り物"
        Motion.WALKING -> "歩行"
        Motion.STILL -> "停止"
    }

    /** 乗り物/歩行の判定が届いた。乗車中なら判定へ、そうでなければ自動開始を検討 */
    fun onMotion(ctx: Context, motion: Motion) {
        val (date, time) = now()
        if (isActive(ctx)) {
            TripLog.add(ctx, TripLog.Category.MOTION, "${motionLabel(motion)}を検知（乗車中）")
            handle(ctx, TripEvent.MotionChanged(motion, time))
            return
        }
        // 乗車中でないときは、21時以降の「乗り物」だけ記録する（自動開始しなかった理由を残す）
        val rule = AutoStartRule()
        val log = motion == Motion.IN_VEHICLE && rule.drinking.isDrinkingTime(time)
        // 平日専用：土日と「今日は休み」の日は自動開始しない
        if (!Store.isServiceDay(ctx, date)) {
            if (log) TripLog.add(ctx, TripLog.Category.AUTO, "乗り物を検知。自動開始しない：土日または「今日は休み」")
            return
        }
        // 受信時点では位置がないので、「乗る駅（荒井・山陽明石）の範囲を出た時刻」（GeofenceReceiver が保存）で判定する
        val left = Store.leftOrigin(ctx, date)
        if (left == null) {
            if (log) TripLog.add(ctx, TripLog.Category.AUTO, "乗り物を検知。自動開始しない：今日は荒井・山陽明石の範囲を出た記録がない（位置情報「常に許可」が必要）")
            return
        }
        val shouldStart = rule.shouldStart(time, motion, nearArai = null, leftAraiAt = left.second)
        if (!shouldStart) {
            if (log) TripLog.add(ctx, TripLog.Category.AUTO, "乗り物を検知。自動開始しない：${left.first.label}を出たのが ${left.second}（${rule.withinMinutesAfterLeavingArai}分以上前）")
            return
        }
        // 明石は特急・普通どちらに乗ったか分からないので、乗った時刻に一番近い列車にする
        if (!startFromBoardingNow(ctx, left.first, fromForeground = false)) {
            Notifier.info(ctx, "飲み会モード：乗車を検知しましたが、経路を決められません。アプリ右上の設定で「荒井→新開地の所要分」を設定してください")
        }
    }

    fun end(ctx: Context) {
        val state = Store.trip(ctx)
        if (state != null) {
            TripLog.add(ctx, TripLog.Category.USER, "乗車を終了（中止）")
            apply(ctx, Transition(state, listOf(TripAction.StopRinging, TripAction.End)), before = state)
        }
    }

    private fun apply(ctx: Context, transition: Transition, before: TripState?) {
        val state: TripState = transition.state
        val ended = transition.actions.any { it is TripAction.End }
        if (before != null && before.phase != state.phase) {
            TripLog.add(ctx, TripLog.Category.STATE, "${Notifier.phaseLabel(before)} → ${Notifier.phaseLabel(state)}")
        }
        if (before != null && before.shintetsuDep != state.shintetsuDep) {
            TripLog.add(ctx, TripLog.Category.STATE, "神鉄を ${ServiceTime(state.shintetsuDep)}発 ${state.shintetsuDest}行きに変更（${if (state.taxi) "鈴蘭台" else "北鈴蘭台"} ${ServiceTime(state.kitaArr)}着）")
        }
        for (action in transition.actions) {
            when (action) {
                is TripAction.Schedule -> TripLog.add(ctx, TripLog.Category.ALARM, "予約：" + action.alarms.joinToString("、") { "${alarmLabel(it.kind)} ${it.at}" })
                is TripAction.Ring -> TripLog.add(ctx, TripLog.Category.RING, "${ringLabel(action.kind)}：${action.message}")
                is TripAction.Notify -> TripLog.add(ctx, TripLog.Category.NOTICE, action.message)
                TripAction.End -> TripLog.add(ctx, TripLog.Category.STATE, "乗車終了")
                else -> Unit
            }
        }
        Store.saveTrip(ctx, if (ended) null else state)
        val serviceDate = LocalDate.parse(state.serviceDate)
        for (action in transition.actions) {
            when (action) {
                is TripAction.Schedule -> action.alarms.forEach { AlarmScheduler.schedule(ctx, it, serviceDate) }
                is TripAction.Cancel -> action.kinds.forEach { AlarmScheduler.cancel(ctx, it) }
                is TripAction.Ring -> Ringer.ring(ctx, action.kind, action.message)
                TripAction.StopRinging -> Ringer.stop(ctx)
                is TripAction.Notify -> Notifier.info(ctx, action.message)
                TripAction.End -> {
                    AlarmKind.entries.forEach { AlarmScheduler.cancel(ctx, it) }
                    TripService.stop(ctx)
                }
            }
        }
        Notifier.updateOngoing(ctx, if (ended) null else state)
    }

    /** 再起動後にアラームを入れ直す */
    fun restore(ctx: Context) {
        val state = Store.trip(ctx) ?: return
        TripLog.add(ctx, TripLog.Category.SYSTEM, "再起動・更新のあと、乗車中の状態を復元（${Notifier.phaseLabel(state)}）")
        val serviceDate = LocalDate.parse(state.serviceDate)
        val (today, time) = now()
        // 営業日が変わっていたら古い乗車は捨てる
        if (today != serviceDate || time.minutes > state.kitaArr + 60) {
            Store.saveTrip(ctx, null)
            return
        }
        val p = planner(ctx)
        val train = p.shintetsuFromBoarding(ServiceTime(state.shintetsuDep))
        if (train != null) {
            val shinkaichiArr = state.shinkaichiArr
            val alarms = if (state.phase == Phase.RIDING_SANYO && shinkaichiArr != null) {
                val route = Route(null, ServiceTime(shinkaichiArr), true, train, train.kitaSuzurandaiArr ?: ServiceTime(state.kitaArr))
                AlarmPlanner.plan(route, settings)
            } else {
                AlarmPlanner.kitaAlarms(train, settings)
            }
            // AlarmScheduler は過ぎた時刻を予約しない
            alarms.forEach { AlarmScheduler.schedule(ctx, it, serviceDate) }
        }
        TripService.start(ctx, fromForeground = false)
    }
}
