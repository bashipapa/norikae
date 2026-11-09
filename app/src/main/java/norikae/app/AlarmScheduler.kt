package norikae.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import norikae.core.AlarmKind
import norikae.core.PlannedAlarm
import java.time.LocalDate
import java.time.ZoneId

/**
 * OS のアラーム機能（AlarmManager.setAlarmClock）で予約する。
 * setAlarmClock はスリープ中でも正確に動き、ステータスバーに目覚ましアイコンが出る。
 */
object AlarmScheduler {
    const val EXTRA_KIND = "kind"

    fun schedule(ctx: Context, alarm: PlannedAlarm, serviceDate: LocalDate) {
        val triggerAt = alarm.at.toLocalDateTime(serviceDate)
            .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (triggerAt < System.currentTimeMillis() - 30_000) return // 過ぎた時刻は予約しない

        val am = ctx.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Notifier.info(ctx, "正確なアラームの権限がありません。アプリの「権限」から許可してください")
            return
        }
        val showIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAt, showIntent), operation(ctx, alarm.kind))
    }

    /** アラームのテスト: [seconds] 秒後に、本番と同じ方法（setAlarmClock）で鳴らす */
    fun scheduleTest(ctx: Context, seconds: Int) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !am.canScheduleExactAlarms()) {
            Notifier.info(ctx, "正確なアラームの権限がありません。アプリの「権限」から許可してください")
            return
        }
        val showIntent = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val operation = PendingIntent.getBroadcast(
            ctx, 300,
            Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_TEST),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        am.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + seconds * 1000L, showIntent), operation)
    }

    fun cancel(ctx: Context, kind: AlarmKind) {
        ctx.getSystemService(AlarmManager::class.java).cancel(operation(ctx, kind))
    }

    /** 種類ごとに1つの予約（同じ種類を予約し直すと置き換わる） */
    private fun operation(ctx: Context, kind: AlarmKind): PendingIntent =
        PendingIntent.getBroadcast(
            ctx,
            100 + kind.ordinal,
            Intent(ctx, AlarmReceiver::class.java).putExtra(EXTRA_KIND, kind.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
}
