package norikae.app

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import norikae.core.AlarmKind
import norikae.core.AlarmPlanner
import norikae.core.AlarmSettings
import norikae.core.Route
import norikae.core.ServiceTime
import norikae.core.TripState

/**
 * Fitbit Air の補助（手動）。
 * Fitbit Air はスマホの通知を転送できず、ほかのアプリから振動アラームを入れる公式の方法も無いため、
 * 「Google Health アプリで入れる時刻」を通知で知らせ、Google Health を開くボタンを付ける。
 */
object FitbitHelper {
    private const val NOTIFICATION_ID = 20

    /** Google Health（旧 Fitbit アプリ）の候補。Fitbit アプリを更新する形で改名されたため、旧パッケージ名を先に試す */
    val GOOGLE_HEALTH_PACKAGES = listOf("com.fitbit.FitbitMobile")

    data class AlarmTime(val label: String, val at: ServiceTime)

    /** Fitbit Air に入れる時刻：新開地の予告と、北鈴蘭台（鈴蘭台止まりなら鈴蘭台）の予告 */
    fun times(route: Route, settings: AlarmSettings = AlarmSettings()): List<AlarmTime> =
        AlarmPlanner.plan(route, settings).mapNotNull {
            when (it.kind) {
                AlarmKind.SHINKAICHI_PRE -> AlarmTime("新開地の前", it.at)
                AlarmKind.KITA_PRE -> AlarmTime("${route.alightStation}の前", it.at)
                else -> null
            }
        }

    /** 乗車中の状態から（予定と違う電車に乗ったあとも、今の予定で出す） */
    fun times(state: TripState, settings: AlarmSettings = AlarmSettings()): List<AlarmTime> = buildList {
        state.shinkaichiArr?.let {
            val pre = if (state.umedaRisk) settings.shinkaichiPreMinutesUmedaRisk else settings.shinkaichiPreMinutes
            add(AlarmTime("新開地の前", ServiceTime(it - pre)))
        }
        add(AlarmTime(if (state.taxi) "鈴蘭台の前" else "北鈴蘭台の前", ServiceTime(state.kitaArr - settings.kitaPreMinutes)))
    }

    fun describe(times: List<AlarmTime>) = times.joinToString("、") { "${it.at.toClock()}（${it.label}）" }

    /** アラームを設定したときに出す通知 */
    fun notify(ctx: Context, route: Route) {
        if (!Store.fitbitHelperEnabled(ctx)) return
        val times = times(route)
        if (times.isEmpty()) return
        Notifier.ensureChannel(ctx)
        val text = "Google Health アプリで Fitbit Air に1回だけの振動アラームを入れてください：${describe(times)}"
        val n = NotificationCompat.Builder(ctx, Notifier.CHANNEL_INFO)
            .setSmallIcon(R.drawable.ic_stat_norikae)
            .setContentTitle("Fitbit Air にもアラームを")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openGoogleHealthIntent(ctx))
            .addAction(0, "Google Health を開く", openGoogleHealthIntent(ctx))
            .setAutoCancel(true)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n) }
    }

    /** Google Health を開く Intent。入っていなければ Play ストアで探す */
    fun openGoogleHealth(ctx: Context): Intent =
        GOOGLE_HEALTH_PACKAGES.firstNotNullOfOrNull { ctx.packageManager.getLaunchIntentForPackage(it) }
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ?: Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=Google%20Health%20Fitbit"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun openGoogleHealthIntent(ctx: Context) = PendingIntent.getActivity(
        ctx, 30, openGoogleHealth(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** 24:15 → 0:15 のように、時計の表示に直す（Fitbit のアラームは普通の時計の時刻で入れるため） */
fun ServiceTime.toClock(): String = "%d:%02d".format((minutes / 60) % 24, minutes % 60)
