package norikae.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.google.android.gms.location.ActivityTransition
import com.google.android.gms.location.ActivityTransitionResult
import com.google.android.gms.location.DetectedActivity
import com.google.android.gms.location.Geofence
import com.google.android.gms.location.GeofencingEvent
import norikae.core.AlarmKind
import norikae.core.Motion
import norikae.core.Origin
import norikae.core.RingKind
import norikae.core.TripEvent

/** 予約したアラームの時刻になった */
class AlarmReceiver : BroadcastReceiver() {
    companion object {
        const val ACTION_STOP_RINGING = "norikae.app.STOP_RINGING"
        const val ACTION_TEST = "norikae.app.TEST_ALARM"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_RINGING -> {
                TripLog.add(context, TripLog.Category.USER, "通知の「止める」を押した")
                TripController.handle(context, TripEvent.UserAwake(TripController.now().second))
                Ringer.stop(context)
                return
            }
            ACTION_TEST -> {
                TripLog.add(context, TripLog.Category.RING, "アラームのテスト（1分後）を鳴らした")
                Ringer.ring(context, RingKind.WAKE, "アラームのテストです。「起きた（止める）」で止まります")
                return
            }
        }
        val kind = intent.getStringExtra(AlarmScheduler.EXTRA_KIND)?.let { AlarmKind.valueOf(it) } ?: return
        val (_, time) = TripController.now()
        TripController.handle(context, TripEvent.AlarmFired(kind, time))
        // 出発15分前から位置の取得を始める（「常に許可」が無ければ、アプリを開いたときに始まる）
        if (kind == AlarmKind.DEPART_REMINDER && TripController.isActive(context)) {
            TripService.start(context, fromForeground = false)
        }
    }
}

/** 乗り物/歩行/停止 の切り替わり（Activity Recognition Transition API） */
class MotionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!ActivityTransitionResult.hasResult(intent)) return
        val result = ActivityTransitionResult.extractResult(intent) ?: return
        result.transitionEvents
            .filter { it.transitionType == ActivityTransition.ACTIVITY_TRANSITION_ENTER }
            .mapNotNull {
                when (it.activityType) {
                    DetectedActivity.IN_VEHICLE -> Motion.IN_VEHICLE
                    DetectedActivity.WALKING, DetectedActivity.ON_FOOT -> Motion.WALKING
                    DetectedActivity.STILL -> Motion.STILL
                    else -> null
                }
            }
            .forEach { TripController.onMotion(context, it) }
    }
}

/** 乗る駅（荒井・山陽明石）の範囲を出た（自動開始用） */
class GeofenceReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val event = GeofencingEvent.fromIntent(intent) ?: return
        if (event.hasError()) return
        if (event.geofenceTransition != Geofence.GEOFENCE_TRANSITION_EXIT) return
        val origin = event.triggeringGeofences.orEmpty()
            .firstNotNullOfOrNull { g -> Origin.entries.firstOrNull { it.name == g.requestId } }
            ?: return
        val (date, time) = TripController.now()
        Store.saveLeftOrigin(context, date, origin, time)
        TripLog.add(context, TripLog.Category.PLACE, "${origin.label}駅の範囲（半径300m）を出た")
    }
}

/** 再起動・アプリ更新のあとに、判定の登録とアラームを戻す */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TripLog.add(context, TripLog.Category.SYSTEM, if (intent.action == Intent.ACTION_BOOT_COMPLETED) "スマホを再起動した" else "アプリを更新した")
        Sensing.registerAll(context)
        TripController.restore(context)
    }
}
