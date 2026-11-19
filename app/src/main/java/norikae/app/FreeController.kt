package norikae.app

import android.content.Context
import norikae.core.AlarmKind
import norikae.core.AlarmSettings
import norikae.core.FreePhase
import norikae.core.FreeState
import norikae.core.FreeTransition
import norikae.core.FreeTripEngine
import norikae.core.Motion
import norikae.core.StationId
import norikae.core.TripAction
import norikae.core.TripEvent
import java.time.LocalDate

/**
 * どこからでもモード（大阪などから帰るとき）。時刻表は使わず、位置で 三宮/新開地 → 谷上 → 北鈴蘭台 を起こす。
 * 判定は FreeTripEngine（core）。ここは Android の機能で実行するだけ。
 */
object FreeController {
    private val settings = AlarmSettings()

    private fun engine(ctx: Context) = FreeTripEngine(Store.stations(ctx), settings)

    fun isActive(ctx: Context): Boolean = Store.freeTrip(ctx) != null

    @Synchronized
    fun start(ctx: Context, via: StationId?) {
        if (TripController.isActive(ctx)) {
            TripLog.add(ctx, TripLog.Category.START, "時刻表の乗車を終了してから、どこからでもモードを開始")
            TripController.end(ctx)
        }
        if (isActive(ctx)) end(ctx)
        val (date, time) = TripController.now()
        val e = engine(ctx)
        TripLog.add(ctx, TripLog.Category.START, "どこからでもモードを開始：${e.viaLabel(via)}")
        apply(ctx, e.start(date.toString(), time, via), before = null)
        TripService.start(ctx, fromForeground = true)
    }

    @Synchronized
    fun handle(ctx: Context, event: TripEvent) {
        val state = Store.freeTrip(ctx) ?: return
        logEvent(ctx, state, event)
        apply(ctx, engine(ctx).reduce(state, event), before = state)
    }

    fun onMotion(ctx: Context, motion: Motion) {
        TripLog.add(ctx, TripLog.Category.MOTION, "${TripController.motionLabel(motion)}を検知（どこからでもモード）")
        handle(ctx, TripEvent.MotionChanged(motion, TripController.now().second))
    }

    fun end(ctx: Context) {
        val state = Store.freeTrip(ctx) ?: return
        TripLog.add(ctx, TripLog.Category.USER, "どこからでもモードを終了（中止）")
        apply(ctx, FreeTransition(state, listOf(TripAction.StopRinging, TripAction.End)), before = state)
    }

    private var lastLocationLogAt = 0L

    private fun logEvent(ctx: Context, state: FreeState, event: TripEvent) {
        when (event) {
            is TripEvent.UserFinished -> TripLog.add(ctx, TripLog.Category.USER, "「北鈴蘭台で降りた（終了）」を押した")
            is TripEvent.UserChoseVia -> TripLog.add(ctx, TripLog.Category.USER, "乗り換えを選んだ：${engine(ctx).viaLabel(event.via)}")
            is TripEvent.LocationChanged -> {
                // 位置は20秒ごとに届くので、2分に1回だけ記録する
                val nowMs = System.currentTimeMillis()
                if (nowMs - lastLocationLogAt < 120_000) return
                lastLocationLogAt = nowMs
                val target = state.target
                val where = target?.let { id ->
                    Store.stations(ctx)[id]?.let { "${id.label}まで約${it.distanceTo(event.location).toInt()}m" }
                } ?: ""
                TripLog.add(ctx, TripLog.Category.PLACE, "%.5f, %.5f（%s）".format(event.location.lat, event.location.lon, where))
            }
            else -> Unit
        }
    }

    fun phaseLabel(state: FreeState): String = when (state.phase) {
        FreePhase.HEADING -> when (state.via) {
            StationId.TANIGAMI -> "三宮に向かっています"
            StationId.SHINKAICHI -> "新開地に向かっています"
            else -> "三宮・新開地に向かっています"
        }
        FreePhase.TO_SANNOMIYA -> "三宮に近づいています"
        FreePhase.AT_SANNOMIYA -> "三宮（谷上方面か新開地方面かを判断中）"
        FreePhase.TO_SHINKAICHI -> "新開地に向かっています"
        FreePhase.TO_TANIGAMI -> "谷上に向かっています"
        FreePhase.TO_KITA -> "北鈴蘭台に向かっています"
        FreePhase.OVERSHOT -> "${state.target?.label ?: ""}を乗り過ごし"
        FreePhase.DONE -> "終了"
    }

    /** 画面・常駐通知に出す説明 */
    fun describe(ctx: Context, state: FreeState): String {
        val (_, time) = TripController.now()
        val d = engine(ctx).distanceToTarget(state, time)
        val dist = d?.let { "（${state.target?.label}まで約" + (if (it >= 1000) "%.1fkm".format(it / 1000) else "${it.toInt()}m") + "）" } ?: ""
        return phaseLabel(state) + dist
    }

    /** 乗り換えの選び直しができる段階か（三宮に着くまで） */
    fun canChooseVia(state: FreeState) =
        state.phase == FreePhase.HEADING || state.phase == FreePhase.TO_SANNOMIYA || state.phase == FreePhase.AT_SANNOMIYA

    private fun apply(ctx: Context, transition: FreeTransition, before: FreeState?) {
        val state = transition.state
        val ended = transition.actions.any { it is TripAction.End }
        if (before != null && (before.phase != state.phase || before.target != state.target)) {
            TripLog.add(ctx, TripLog.Category.STATE, "${phaseLabel(before)} → ${phaseLabel(state)}")
        }
        for (action in transition.actions) {
            when (action) {
                is TripAction.Ring -> TripLog.add(
                    ctx, TripLog.Category.RING,
                    "${TripController.ringLabel(action.kind)}（${when { action.level >= 2 -> "サイレン・音量最大"; action.level >= 1 -> "アラーム音・音量最大"; else -> "振動だけ" }}）：${action.message}",
                )
                is TripAction.Notify -> TripLog.add(ctx, TripLog.Category.NOTICE, action.message)
                TripAction.End -> TripLog.add(ctx, TripLog.Category.STATE, "どこからでもモード終了")
                else -> Unit
            }
        }
        Store.saveFreeTrip(ctx, if (ended) null else state)
        val serviceDate = LocalDate.parse(state.serviceDate)
        for (action in transition.actions) {
            when (action) {
                is TripAction.Schedule -> action.alarms.forEach { AlarmScheduler.schedule(ctx, it, serviceDate) }
                is TripAction.Cancel -> action.kinds.forEach { AlarmScheduler.cancel(ctx, it) }
                is TripAction.Ring -> Ringer.ring(ctx, action.kind, action.message, action.level)
                TripAction.StopRinging -> Ringer.stop(ctx)
                is TripAction.Notify -> Notifier.info(ctx, action.message)
                TripAction.End -> {
                    AlarmScheduler.cancel(ctx, AlarmKind.FREE_TICK)
                    if (!TripController.isActive(ctx)) TripService.stop(ctx)
                }
            }
        }
        if (!TripController.isActive(ctx)) Notifier.updateOngoingFree(ctx, if (ended) null else describe(ctx, state))
    }

    /** 再起動・アプリ更新のあとに、位置の取得と見直しを戻す */
    fun restore(ctx: Context) {
        val state = Store.freeTrip(ctx) ?: return
        val (today, time) = TripController.now()
        if (today.toString() != state.serviceDate || time.minutes - state.startedAt > FreeTripEngine.MAX_TRIP_MIN) {
            Store.saveFreeTrip(ctx, null)
            return
        }
        TripLog.add(ctx, TripLog.Category.SYSTEM, "再起動・更新のあと、どこからでもモードを復元（${phaseLabel(state)}）")
        TripService.start(ctx, fromForeground = false)
        handle(ctx, TripEvent.AlarmFired(AlarmKind.FREE_TICK, time))
    }
}
