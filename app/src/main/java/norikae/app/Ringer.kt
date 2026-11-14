package norikae.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import norikae.core.RingKind
import norikae.core.TripState
import norikae.core.ServiceTime

/**
 * アラームを鳴らす。
 * 音と振動は通知ではなくアプリ自身が出す（AlarmPlayer）。通知のクールダウンや通知の音の設定に左右されないようにするため。
 * 通知は、ロック中でも画面を点けて鳴動画面を出す（全画面表示）ためだけに使う（通知自体は音も振動も出さない）。
 * 鳴らしている間は AlarmSoundService（常駐処理）が動き、アプリが止められないようにする。
 */
object Ringer {
    /** 音・振動なしの表示専用チャンネル（以前の "alarm" チャンネルは音付きだったので作り直す） */
    private const val CHANNEL_ALARM = "alarm_display"
    private const val OLD_CHANNEL_ALARM = "alarm"
    const val NOTIFICATION_ID = 1

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(OLD_CHANNEL_ALARM) != null) nm.deleteNotificationChannel(OLD_CHANNEL_ALARM)
        if (nm.getNotificationChannel(CHANNEL_ALARM) != null) return
        val channel = NotificationChannel(CHANNEL_ALARM, "乗り過ごしアラーム（画面表示）", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(null, null)
            enableVibration(false)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    fun title(kind: RingKind) = when (kind) {
        RingKind.WAKE -> "起きて！"
        RingKind.DEPART -> "帰る時間です"
        RingKind.OVERSHOT_SHINKAICHI -> "新開地を乗り過ごし！"
        RingKind.OVERSHOT_KITA -> "北鈴蘭台を乗り過ごし！"
        RingKind.WRONG_LINE -> "電車が違うかも！"
    }

    /** 全画面の鳴動画面を出す通知（音・振動なし） */
    fun buildNotification(ctx: Context, kind: RingKind, message: String): Notification {
        ensureChannel(ctx)
        val title = title(kind)
        val open = Intent(ctx, RingingActivity::class.java)
            .putExtra(RingingActivity.EXTRA_TITLE, title)
            .putExtra(RingingActivity.EXTRA_MESSAGE, message)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val fullScreen = PendingIntent.getActivity(
            ctx, 1, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getBroadcast(
            ctx, 2,
            Intent(ctx, AlarmReceiver::class.java).setAction(AlarmReceiver.ACTION_STOP_RINGING),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(ctx, CHANNEL_ALARM)
            .setSmallIcon(R.drawable.ic_stat_norikae)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setFullScreenIntent(fullScreen, true)
            .setContentIntent(fullScreen)
            .addAction(0, "止める", stop)
            .setAutoCancel(false)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * 鳴らす。[level] 1=アラーム音、2以上=サイレン。どれもアラーム音量を最大にし、強い振動を続ける。
     */
    fun ring(ctx: Context, kind: RingKind, message: String, level: Int = 1) {
        val intent = Intent(ctx, AlarmSoundService::class.java)
            .putExtra(AlarmSoundService.EXTRA_KIND, kind.name)
            .putExtra(AlarmSoundService.EXTRA_MESSAGE, message)
            .putExtra(AlarmSoundService.EXTRA_LEVEL, level)
        val started = runCatching { ContextCompat.startForegroundService(ctx, intent) }
        if (started.isFailure) {
            // 常駐処理を始められないとき（裏からの起動制限など）は、その場で鳴らす
            TripLog.add(ctx, TripLog.Category.SYSTEM, "鳴動用の常駐処理を開始できず、直接鳴らした：${started.exceptionOrNull()?.javaClass?.simpleName}")
            runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification(ctx, kind, message)) }
            AlarmPlayer.start(ctx, level)
        }
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, AlarmSoundService::class.java))
        AlarmPlayer.stop(ctx)
        ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }
}

/** 鳴らさない通知（案内・乗車中の常駐表示） */
object Notifier {
    const val CHANNEL_INFO = "info"
    const val ONGOING_ID = 10
    private const val INFO_ID = 11

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_INFO) != null) return
        nm.createNotificationChannel(NotificationChannel(CHANNEL_INFO, "案内", NotificationManager.IMPORTANCE_DEFAULT))
    }

    fun info(ctx: Context, message: String) {
        ensureChannel(ctx)
        val n = NotificationCompat.Builder(ctx, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_stat_norikae)
            .setContentTitle("のりかえアラーム")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openApp(ctx))
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(INFO_ID, n) }
    }

    fun ongoing(ctx: Context, state: TripState?): Notification {
        ensureChannel(ctx)
        val text = state?.let {
            val shinkaichi = it.shinkaichiArr?.let { m -> "新開地 ${ServiceTime(m)}着 → " } ?: ""
            "${phaseLabel(it)}｜$shinkaichi${ServiceTime(it.shintetsuDep)} ${it.shintetsuDest}行き → ${if (it.taxi) "鈴蘭台" else "北鈴蘭台"} ${ServiceTime(it.kitaArr)}着" + if (it.taxi) "（タクシー）" else ""
        } ?: "乗車中"
        return NotificationCompat.Builder(ctx, CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_stat_norikae)
            .setContentTitle("飲み会モード（乗車中）")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openApp(ctx))
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    fun updateOngoing(ctx: Context, state: TripState?) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (state == null) nm.cancel(ONGOING_ID) else runCatching { nm.notify(ONGOING_ID, ongoing(ctx, state)) }
    }

    fun phaseLabel(state: TripState): String = when (state.phase) {
        norikae.core.Phase.PLANNED -> "乗る前（予定）"
        norikae.core.Phase.RIDING_SANYO -> "山陽に乗車中"
        norikae.core.Phase.TRANSFERRING -> "新開地で乗り換え中"
        norikae.core.Phase.RIDING_SHINTETSU -> "神戸電鉄に乗車中"
        norikae.core.Phase.OVERSHOT_SHINKAICHI -> "新開地を乗り過ごし"
        norikae.core.Phase.OVERSHOT_KITA -> "北鈴蘭台を乗り過ごし"
        norikae.core.Phase.DONE -> "終了"
    }

    private fun openApp(ctx: Context) = PendingIntent.getActivity(
        ctx, 0, Intent(ctx, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
