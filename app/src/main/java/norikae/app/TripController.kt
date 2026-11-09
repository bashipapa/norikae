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
    fun start(ctx: Context, route: Route, fromForeground: Boolean = true) {
        val (date, _) = now()
        // 前の乗車が残っていれば片付ける
        if (isActive(ctx)) end(ctx)
        val transition = engine(ctx).start(route, date.toString())
        apply(ctx, transition)
        TripService.start(ctx, fromForeground)
    }

    /** 「今乗った」：実際の乗車時刻から経路を決めて開始 */
    fun startFromBoardingNow(
        ctx: Context,
        origin: Origin = Origin.ARAI,
        filter: (SanyoTrain) -> Boolean = { true },
        fromForeground: Boolean = true,
    ): Boolean {
        val (_, time) = now()
        val route = planner(ctx).routeFromBoarding(time, origin, filter) ?: return false
        start(ctx, route, fromForeground)
        return true
    }

    @Synchronized
    fun handle(ctx: Context, event: TripEvent) {
        val state = Store.trip(ctx) ?: return
        apply(ctx, engine(ctx).reduce(state, event))
    }

    /** 乗り物/歩行の判定が届いた。乗車中なら判定へ、そうでなければ自動開始を検討 */
    fun onMotion(ctx: Context, motion: Motion) {
        val (date, time) = now()
        if (isActive(ctx)) {
            handle(ctx, TripEvent.MotionChanged(motion, time))
            return
        }
        // 平日専用：土日と「今日は休み」の日は自動開始しない
        if (!Store.isServiceDay(ctx, date)) return
        // 受信時点では位置がないので、「乗る駅（荒井・山陽明石）の範囲を出た時刻」（GeofenceReceiver が保存）で判定する
        val left = Store.leftOrigin(ctx, date) ?: return
        val shouldStart = AutoStartRule().shouldStart(time, motion, nearArai = null, leftAraiAt = left.second)
        if (!shouldStart) return
        // 明石は特急・普通どちらに乗ったか分からないので、乗った時刻に一番近い列車にする
        if (!startFromBoardingNow(ctx, left.first, fromForeground = false)) {
            Notifier.info(ctx, "飲み会モード：乗車を検知しましたが、経路を決められません。アプリ右上の設定で「荒井→新開地の所要分」を設定してください")
        }
    }

    fun end(ctx: Context) {
        val state = Store.trip(ctx)
        if (state != null) apply(ctx, Transition(state, listOf(TripAction.StopRinging, TripAction.End)))
    }

    private fun apply(ctx: Context, transition: Transition) {
        val state: TripState = transition.state
        val ended = transition.actions.any { it is TripAction.End }
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
