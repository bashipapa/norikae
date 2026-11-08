package norikae.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import norikae.core.RingKind
import norikae.core.TripState
import norikae.core.ServiceTime

/**
 * アラームを鳴らす。
 * 「アラーム音のチャンネル + FLAG_INSISTENT（止めるまで鳴り続ける）+ 全画面表示」で、
 * ロック中でも画面を点けて鳴らす。
 */
object Ringer {
    private const val CHANNEL_ALARM = "alarm"
    private const val NOTIFICATION_ID = 1

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL_ALARM) != null) return
        val channel = NotificationChannel(CHANNEL_ALARM, "乗り過ごしアラーム", NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build(),
            )
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 800, 400, 800, 400, 800)
            setBypassDnd(true)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        nm.createNotificationChannel(channel)
    }

    fun ring(ctx: Context, kind: RingKind, message: String) {
        ensureChannel(ctx)
        val title = when (kind) {
            RingKind.WAKE -> "起きて！"
            RingKind.OVERSHOT_SHINKAICHI -> "新開地を乗り過ごし！"
            RingKind.OVERSHOT_KITA -> "北鈴蘭台を乗り過ごし！"
            RingKind.WRONG_LINE -> "電車が違うかも！"
        }
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
        val notification = NotificationCompat.Builder(ctx, CHANNEL_ALARM)
            .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
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
            .setTimeoutAfter(5 * 60_000L)
            .build()
            .apply { flags = flags or Notification.FLAG_INSISTENT }
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification) }
    }

    fun stop(ctx: Context) {
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
            .setSmallIcon(android.R.drawable.ic_dialog_info)
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
            "${phaseLabel(it)}｜$shinkaichi${ServiceTime(it.shintetsuDep)} ${it.shintetsuDest}行き → 北鈴蘭台 ${ServiceTime(it.kitaArr)}着"
        } ?: "乗車中"
        return NotificationCompat.Builder(ctx, CHANNEL_INFO)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
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
